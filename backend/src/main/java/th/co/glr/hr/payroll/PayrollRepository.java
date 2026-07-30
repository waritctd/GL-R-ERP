package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.payroll.export.PayrollExportRow;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest;

@Repository
public class PayrollRepository {
    // Advisory-lock namespace ("PAYR" as int4) keeps this lock from colliding with any other
    // pg_advisory lock added elsewhere in the app.
    private static final int PAYROLL_PROCESS_LOCK_NAMESPACE = 0x50415952;

    private final NamedParameterJdbcTemplate jdbc;

    public PayrollRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PayrollEmployeeSnapshot> findActiveEmployees() {
        return jdbc.query("""
            SELECT e.employee_id,
                   e.employee_code,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
                   dep.name_th AS department_name,
                   bank.name_th AS bank_name,
                   ba.account_no AS bank_account,
                   COALESCE(e.current_salary, 0) AS base_salary,
                   COALESCE(e.director_remuneration, 0) AS director_remuneration,
                   e.withholding_tax_override AS withholding_tax_override,
                   e.date_of_birth AS date_of_birth
              FROM hr.employee e
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
              LEFT JOIN hr.employee_bank_account ba ON ba.employee_id = e.employee_id
              LEFT JOIN hr.bank bank ON bank.bank_id = ba.bank_id
             WHERE e.is_active = TRUE
               AND (COALESCE(e.current_salary, 0) > 0 OR COALESCE(e.director_remuneration, 0) > 0)
             ORDER BY e.employee_code
            """, Map.of(), (rs, rowNum) -> new PayrollEmployeeSnapshot(
                rs.getLong("employee_id"),
                rs.getString("employee_code"),
                rs.getString("employee_name"),
                rs.getString("department_name"),
                rs.getString("bank_name"),
                rs.getString("bank_account"),
                rs.getBigDecimal("base_salary"),
                rs.getBigDecimal("director_remuneration"),
                // NULLABLE standing override -- read raw so SQL NULL stays null (no COALESCE):
                // null = no standing override, distinct from a 0 override.
                rs.getBigDecimal("withholding_tax_override"),
                // Drives the ยกเว้นเงินได้ 190,000 for taxpayers aged 65+ (V93). Nullable: a missing
                // date of birth means the exemption is not granted on an assumption.
                rs.getObject("date_of_birth", LocalDate.class)
            ));
    }

    public Map<Long, BigDecimal> findApprovedOvertimePayByEmployee(LocalDate payrollMonth) {
        return jdbc.query("""
            SELECT ot.employee_id,
                   -- ot.salary_basis is the authority: it was frozen at manager approval, resolved
                   -- as of the work date, so a later salary change never re-prices approved work.
                   -- The employee join/COALESCE is only a safety net for rows approved before that
                   -- column existed (or any NULL that slips through).
                   COALESCE(SUM((ot.payable_minutes::numeric / 60)
                       * ((COALESCE(ot.salary_basis, e.current_salary, 0) / 30 / 8) * ot.pay_rate_multiplier)), 0) AS overtime_pay
              FROM hr.overtime_request ot
              JOIN hr.employee e ON e.employee_id = ot.employee_id
             WHERE ot.status = 'APPROVED'
               AND ot.payroll_month = :payrollMonth
             GROUP BY ot.employee_id
            """,
            Map.of("payrollMonth", payrollMonth),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), money(rs.getBigDecimal("overtime_pay"))))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Year-to-date figures used to project annual income (C2). Combines actual processed
     * {@code payroll_line} rows for months already run this year with
     * {@code hr.payroll_year_to_date_seed} -- the pre-system history back-loaded at go-live. An
     * employee may have rows in either table, both, or neither; the UNION ALL + GROUP BY sums whatever
     * exists per employee, equivalent to a FULL OUTER JOIN without the NULL-handling ceremony.
     */
    public Map<Long, PayrollYearToDate> findYearToDateByEmployee(LocalDate payrollMonth) {
        // ป.96/2543: the two limbs are summed SEPARATELY. Collapsing them here would destroy the
        // variable-limb withholding figure that next period's ข้อ 2.5 difference must net against.
        return jdbc.query("""
            SELECT employee_id,
                   COALESCE(SUM(regular_income), 0) AS regular_income,
                   COALESCE(SUM(variable_income), 0) AS variable_income,
                   COALESCE(SUM(social_security), 0) AS social_security,
                   COALESCE(SUM(regular_withholding_tax), 0) AS regular_withholding_tax,
                   COALESCE(SUM(variable_withholding_tax), 0) AS variable_withholding_tax,
                   COALESCE(SUM(withholding_tax), 0) AS withholding_tax,
                   COALESCE(SUM(regular_limb_taxable_income), 0) AS regular_limb_taxable_income,
                   COALESCE(SUM(regular_limb_withholding_tax), 0) AS regular_limb_withholding_tax,
                   COALESCE(SUM(cumulative_limb_taxable_income), 0) AS cumulative_limb_taxable_income,
                   COALESCE(SUM(cumulative_limb_withholding_tax), 0) AS cumulative_limb_withholding_tax,
                   -- F1 fix (Opus review, 2026-07-30): the running total of every period's OWN
                   -- taxable_income_known_limb this tax year -- see PayrollYearToDate's field javadoc
                   -- for why the calculator needs this to layer the cumulative limb on top of income
                   -- that includes a settled known-frequency payment, in every period after the one
                   -- that paid it.
                   COALESCE(SUM(known_limb_taxable_income), 0) AS known_limb_taxable_income
              FROM (
                  -- V92's regular/variable columns feed the legacy calculate() 2-limb model;
                  -- V96's *_limb columns feed calculateClassified()'s 3-limb model. calculateLine now
                  -- writes only the V96 columns (see PayrollService#calculateLine /
                  -- PayrollLineDto), so V92's columns read back as their DB default (0) for every line
                  -- processed from this rebase forward -- harmless, because calculate() is reachable
                  -- only from PayrollExcelReconciliationTest's frozen regression suite.
                  SELECT pl.employee_id,
                         pl.regular_taxable_income AS regular_income,
                         pl.variable_taxable_income AS variable_income,
                         pl.social_security AS social_security,
                         pl.regular_withholding_tax AS regular_withholding_tax,
                         pl.variable_withholding_tax AS variable_withholding_tax,
                         pl.withholding_tax AS withholding_tax,
                         pl.taxable_income_regular_limb AS regular_limb_taxable_income,
                         pl.withholding_tax_regular_limb AS regular_limb_withholding_tax,
                         pl.taxable_income_cumulative_limb AS cumulative_limb_taxable_income,
                         pl.withholding_tax_cumulative_limb AS cumulative_limb_withholding_tax,
                         pl.taxable_income_known_limb AS known_limb_taxable_income
                    FROM hr.payroll_line pl
                    JOIN hr.payroll_period pp ON pp.period_id = pl.period_id
                   WHERE pp.payroll_month >= date_trunc('year', :payrollMonth::date)::date
                     AND pp.payroll_month < :payrollMonth
                     AND pp.status <> 'VOID'
                  UNION ALL
                  -- The go-live seed carries NO limb split and never will: it is pre-system history
                  -- from the accountant's records, produced by the single-limb method, in which every
                  -- baht WAS annualised as regular pay. Mapping it to the regular limb (both the V92
                  -- two-limb sense and the V96 three-limb sense) is therefore accurate rather than a
                  -- fallback. See the V92 and V96 migration comments.
                  SELECT s.employee_id,
                         s.taxable_income,
                         0::numeric,
                         s.social_security,
                         s.withholding_tax,
                         0::numeric,
                         s.withholding_tax,
                         s.taxable_income,
                         s.withholding_tax,
                         s.cumulative_limb_taxable_income, s.cumulative_limb_withholding_tax,
                         -- The seed predates the three-limb model entirely (produced by the old
                         -- single-limb engine, back-loaded pre-system history) -- there is no
                         -- known-limb figure to carry, same rationale as the cumulative limb columns
                         -- added by V96 for this table having no known-limb counterpart either.
                         0::numeric AS known_limb_taxable_income
                    FROM hr.payroll_year_to_date_seed s
                   WHERE s.tax_year = EXTRACT(YEAR FROM :payrollMonth::date)::int
              ) combined
             GROUP BY employee_id
            """,
            Map.of("payrollMonth", payrollMonth),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), new PayrollYearToDate(
                money(rs.getBigDecimal("regular_income")),
                money(rs.getBigDecimal("variable_income")),
                money(rs.getBigDecimal("social_security")),
                money(rs.getBigDecimal("regular_withholding_tax")),
                money(rs.getBigDecimal("variable_withholding_tax")),
                money(rs.getBigDecimal("withholding_tax")),
                money(rs.getBigDecimal("regular_limb_taxable_income")),
                money(rs.getBigDecimal("regular_limb_withholding_tax")),
                money(rs.getBigDecimal("cumulative_limb_taxable_income")),
                money(rs.getBigDecimal("cumulative_limb_withholding_tax")),
                money(rs.getBigDecimal("known_limb_taxable_income"))
            )))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Special-pay carry-forward (2026-07-23, extended to all nine พิเศษ slots + meal allowance,
     * Opus review 2026-07-29): per active employee, the carried recurring fields (special_pay_1..9,
     * meal_allowance, non_taxable_income, student_loan_deduction, legal_execution_deduction) from
     * that employee's most-recent PRIOR processed {@code payroll_line} — the latest period strictly
     * before {@code payrollMonth} with {@code status <> 'VOID'}. {@code DISTINCT ON} picks exactly one
     * row per employee: the latest {@code payroll_month}. An employee with no qualifying prior line is
     * simply absent from the result (no fabricated zero row) — the caller treats "no suggestion" as
     * "nothing to pre-fill." Restricted to currently active employees so a terminated employee's old
     * figures never leak into a suggestion for a payroll run they are no longer part of.
     *
     * <p>Defect fix (Opus review, 2026-07-29): the original version joined only {@code cf1}-{@code
     * cf5} (special_pay_1..5) and the DTO carried only those five slots, even though V98 also seeds
     * carry-forward flags for {@code SPECIAL_PAY_6}, {@code SPECIAL_PAY_9} and {@code
     * MEAL_ALLOWANCE} — the very components V98's own rationale names as recurring (ค่า GPRS(เพิ่ม),
     * เงินรางวัล). Those flags were dead: stored, never read. Extended to join and carry all nine
     * พิเศษ slots plus meal allowance, following the exact same {@code CASE WHEN cfN.carry_forward
     * THEN pl.special_pay_N ELSE 0 END} shape as the original five.
     *
     * <p>Second defect fix, same review: each {@code cfN} join used to match on {@code cfN.tax_year =
     * EXTRACT(YEAR FROM pp.payroll_month)} — the SOURCE row's own year, with no fallback. V98 seeded
     * carry-forward flags for 2026 only, so a source row from January 2027 onward would find no
     * matching flag row at all and every slot would silently stop carrying the instant the calendar
     * rolled over — carry-forward "surviving" through the end of 2026 and then stopping dead in
     * February 2027, exactly the per-employee/per-year cliff {@link #findComponentTaxTreatmentsByEmployee}
     * had for the same underlying reason. Fixed with a {@code LATERAL} join per slot that picks, for
     * that employee and component, the closest {@code tax_year <= EXTRACT(YEAR FROM pp.payroll_month)}
     * — the same "roll forward to the most recent year at or before" resolution, done per row because
     * (unlike the tax-treatment/SSO-inclusion reads) the source period differs per employee here.
     */
    public List<PayrollCarryForwardDtos.SuggestedInputRow> findCarryForwardSuggestions(LocalDate payrollMonth) {
        return jdbc.query("""
            SELECT DISTINCT ON (pl.employee_id)
                   pl.employee_id,
                   -- Each slot is carried ONLY where that employee's own carry-forward flag says so
                   -- (V98, seeded from the accountant's ledger at the owner's 70%-same-value rule).
                   -- This replaced a hardcoded special_pay_1..5, which the V95/V97 renumbering had
                   -- silently repointed at different money: the set gained ค่าเช่าบ้าน, which varies
                   -- per employee, and lost ค่า GPRS, which does not.
                   CASE WHEN cf1.carry_forward THEN pl.special_pay_1 ELSE 0 END AS special_pay_1,
                   CASE WHEN cf2.carry_forward THEN pl.special_pay_2 ELSE 0 END AS special_pay_2,
                   CASE WHEN cf3.carry_forward THEN pl.special_pay_3 ELSE 0 END AS special_pay_3,
                   CASE WHEN cf4.carry_forward THEN pl.special_pay_4 ELSE 0 END AS special_pay_4,
                   CASE WHEN cf5.carry_forward THEN pl.special_pay_5 ELSE 0 END AS special_pay_5,
                   CASE WHEN cf6.carry_forward THEN pl.special_pay_6 ELSE 0 END AS special_pay_6,
                   CASE WHEN cf7.carry_forward THEN pl.special_pay_7 ELSE 0 END AS special_pay_7,
                   CASE WHEN cf8.carry_forward THEN pl.special_pay_8 ELSE 0 END AS special_pay_8,
                   CASE WHEN cf9.carry_forward THEN pl.special_pay_9 ELSE 0 END AS special_pay_9,
                   CASE WHEN cfMeal.carry_forward THEN pl.meal_allowance ELSE 0 END AS meal_allowance,
                   pl.non_taxable_income, pl.student_loan_deduction, pl.legal_execution_deduction,
                   pl.withholding_tax_override
              FROM hr.payroll_line pl
              JOIN hr.payroll_period pp ON pp.period_id = pl.period_id
              JOIN hr.employee e ON e.employee_id = pl.employee_id AND e.is_active = TRUE
              -- LEFT JOIN LATERAL, one per component: an employee/component with no flag row at or
              -- before the source period's year carries NOTHING, which is the safe default. Picking
              -- the closest tax_year <= the source row's year (rather than an exact-year match) is
              -- what keeps carry-forward alive past a calendar-year rollover -- see this method's
              -- javadoc. Five workbook names could not be resolved to an employee and are deliberately
              -- unseeded (see V98) -- they must pre-fill zero, not last month's figure.
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_1'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf1 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_2'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf2 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_3'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf3 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_4'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf4 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_5'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf5 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_6'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf6 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_7'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf7 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_8'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf8 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'SPECIAL_PAY_9'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cf9 ON TRUE
              LEFT JOIN LATERAL (
                  SELECT carry_forward FROM hr.payroll_component_carry_forward cf
                   WHERE cf.employee_id = pl.employee_id AND cf.component = 'MEAL_ALLOWANCE'
                     AND cf.tax_year <= EXTRACT(YEAR FROM pp.payroll_month)::smallint
                   ORDER BY cf.tax_year DESC LIMIT 1
              ) cfMeal ON TRUE
             WHERE pp.payroll_month < :payrollMonth
               AND pp.status <> 'VOID'
             ORDER BY pl.employee_id, pp.payroll_month DESC
            """,
            Map.of("payrollMonth", payrollMonth),
            (rs, rowNum) -> new PayrollCarryForwardDtos.SuggestedInputRow(
                rs.getLong("employee_id"),
                money(rs.getBigDecimal("special_pay_1")),
                money(rs.getBigDecimal("special_pay_2")),
                money(rs.getBigDecimal("special_pay_3")),
                money(rs.getBigDecimal("special_pay_4")),
                money(rs.getBigDecimal("special_pay_5")),
                money(rs.getBigDecimal("special_pay_6")),
                money(rs.getBigDecimal("special_pay_7")),
                money(rs.getBigDecimal("special_pay_8")),
                money(rs.getBigDecimal("special_pay_9")),
                money(rs.getBigDecimal("meal_allowance")),
                money(rs.getBigDecimal("non_taxable_income")),
                money(rs.getBigDecimal("student_loan_deduction")),
                money(rs.getBigDecimal("legal_execution_deduction")),
                // Leave-derived unpaidLeaveDays/pendingUnpaidLeaveCorrectionDays are not this query's
                // concern (it only knows the prior payroll_line) -- PayrollService#suggestedInputs
                // overlays the real leave-derived figures onto every row after this method returns.
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                // PER-RUN withholding override typed last month, so HR sees it pre-filled again. Read
                // raw (nullable): null = none typed -> field starts blank and the standing employee
                // override (if any) re-applies on its own.
                rs.getBigDecimal("withholding_tax_override")
            ));
    }

    /**
     * C1 + ล.ย.01 effective dating (V93): the declaration IN FORCE for a given payroll month.
     *
     * <p>คำชี้แจง ภ.ง.ด.1 ข้อ 2.2: the employer computes ค่าลดหย่อน from what the employee declared on
     * แบบ ล.ย.01, and where the employee notifies a change during the year the NEW declaration applies
     * from the period it was notified. Declarations are therefore dated rows, and this resolves the
     * latest one on or before the payroll month — never a later one, so re-running an earlier month
     * reproduces the tax that month was actually filed on.
     */
    public Map<Long, PayrollTaxAllowanceInput> findTaxAllowancesByEmployee(LocalDate payrollMonth) {
        return jdbc.query("""
            SELECT DISTINCT ON (employee_id)
                   employee_id, spouse_allowance, child_allowance, parent_care_allowance,
                   disabled_care_allowance, maternity_allowance, life_insurance_allowance,
                   health_insurance_allowance, parent_health_insurance_allowance, rmf_allowance,
                   ssf_allowance, pension_insurance_allowance, thai_esg_allowance,
                   home_loan_interest_allowance, education_donation, general_donation, political_donation,
                   child_count, child_count_double, disabled_care_count,
                   disability_card_holder, parent_care_count
              FROM hr.employee_tax_allowance
             WHERE tax_year = :taxYear
               AND effective_month <= :payrollMonthValue
               -- Declaration verification gating (handoff section 3, task 2): an EXPIRED_UNVERIFIED
               -- declaration stops applying to withholding from the next payroll onward. Excluding it
               -- here means PayrollService#mergeAllowances' stored=null fallback (PayrollTaxAllowanceInput
               -- .empty()) applies automatically -- VERIFIED and GRANDFATHERED_UNVERIFIED both still
               -- apply, matching "GRANDFATHERED_UNVERIFIED -> applied, HR + employee warned".
               AND verification_status <> 'EXPIRED_UNVERIFIED'
             ORDER BY employee_id, effective_month DESC
            """,
            Map.of("taxYear", payrollMonth.getYear(), "payrollMonthValue", payrollMonth.getMonthValue()),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), new PayrollTaxAllowanceInput(
                money(rs.getBigDecimal("spouse_allowance")),
                money(rs.getBigDecimal("child_allowance")),
                money(rs.getBigDecimal("parent_care_allowance")),
                money(rs.getBigDecimal("disabled_care_allowance")),
                money(rs.getBigDecimal("maternity_allowance")),
                money(rs.getBigDecimal("life_insurance_allowance")),
                money(rs.getBigDecimal("health_insurance_allowance")),
                money(rs.getBigDecimal("parent_health_insurance_allowance")),
                money(rs.getBigDecimal("rmf_allowance")),
                money(rs.getBigDecimal("ssf_allowance")),
                money(rs.getBigDecimal("pension_insurance_allowance")),
                money(rs.getBigDecimal("thai_esg_allowance")),
                money(rs.getBigDecimal("home_loan_interest_allowance")),
                money(rs.getBigDecimal("education_donation")),
                money(rs.getBigDecimal("general_donation")),
                money(rs.getBigDecimal("political_donation")),
                // ล.ย.01 completeness (V93).
                rs.getInt("child_count"),
                rs.getInt("child_count_double"),
                rs.getInt("disabled_care_count"),
                rs.getBoolean("disability_card_holder"),
                rs.getInt("parent_care_count")
            )))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** C1: rows for the GET /api/payroll/tax-allowances listing, joined to employee for display. */
    public List<EmployeeTaxAllowanceDto> findTaxAllowanceRows(int taxYear) {
        return jdbc.query("""
            SELECT e.employee_id, e.employee_code,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
                   eta.spouse_allowance, eta.child_allowance, eta.parent_care_allowance,
                   eta.disabled_care_allowance, eta.maternity_allowance, eta.life_insurance_allowance,
                   eta.health_insurance_allowance, eta.parent_health_insurance_allowance, eta.rmf_allowance,
                   eta.ssf_allowance, eta.pension_insurance_allowance, eta.thai_esg_allowance,
                   eta.home_loan_interest_allowance, eta.education_donation, eta.general_donation,
                   eta.political_donation, eta.child_count,
                   eta.child_count_double, eta.disabled_care_count, eta.disability_card_holder,
                   eta.parent_care_count, eta.effective_month, eta.document_reference, eta.updated_at,
                   eta.verification_status, eta.verified_by_id, eta.verified_at, eta.verification_deadline
              FROM hr.employee e
              JOIN hr.employee_tax_allowance eta ON eta.employee_id = e.employee_id AND eta.tax_year = :taxYear
             ORDER BY e.employee_code, eta.effective_month
            """,
            Map.of("taxYear", taxYear),
            (rs, rowNum) -> new EmployeeTaxAllowanceDto(
                rs.getLong("employee_id"),
                rs.getString("employee_code"),
                rs.getString("employee_name"),
                new PayrollTaxAllowanceInput(
                    money(rs.getBigDecimal("spouse_allowance")),
                    money(rs.getBigDecimal("child_allowance")),
                    money(rs.getBigDecimal("parent_care_allowance")),
                    money(rs.getBigDecimal("disabled_care_allowance")),
                    money(rs.getBigDecimal("maternity_allowance")),
                    money(rs.getBigDecimal("life_insurance_allowance")),
                    money(rs.getBigDecimal("health_insurance_allowance")),
                    money(rs.getBigDecimal("parent_health_insurance_allowance")),
                    money(rs.getBigDecimal("rmf_allowance")),
                    money(rs.getBigDecimal("ssf_allowance")),
                    money(rs.getBigDecimal("pension_insurance_allowance")),
                    money(rs.getBigDecimal("thai_esg_allowance")),
                    money(rs.getBigDecimal("home_loan_interest_allowance")),
                    money(rs.getBigDecimal("education_donation")),
                    money(rs.getBigDecimal("general_donation")),
                    money(rs.getBigDecimal("political_donation")),
                    rs.getInt("child_count"),
                    rs.getInt("child_count_double"),
                    rs.getInt("disabled_care_count"),
                    rs.getBoolean("disability_card_holder"),
                    rs.getInt("parent_care_count")
                ),
                rs.getInt("effective_month"),
                rs.getString("document_reference"),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("verification_status"),
                nullableLong(rs, "verified_by_id"),
                rs.getObject("verified_at", OffsetDateTime.class),
                rs.getObject("verification_deadline", LocalDate.class)
            ));
    }

    /** C1: bulk upsert of the standing tax-allowance declaration. */
    public void upsertTaxAllowances(int taxYear, List<EmployeeTaxAllowanceUpsertRequest> items, Long updatedById) {
        for (EmployeeTaxAllowanceUpsertRequest item : items) {
            jdbc.update("""
                INSERT INTO hr.employee_tax_allowance (
                    employee_id, tax_year, spouse_allowance, child_allowance, parent_care_allowance,
                    disabled_care_allowance, maternity_allowance, life_insurance_allowance,
                    health_insurance_allowance, parent_health_insurance_allowance, rmf_allowance,
                    ssf_allowance, pension_insurance_allowance, thai_esg_allowance,
                    home_loan_interest_allowance, education_donation, general_donation,
                    political_donation, child_count, child_count_double,
                    disabled_care_count, disability_card_holder, parent_care_count, effective_month,
                    document_reference, updated_by_id, updated_at
                ) VALUES (
                    :employeeId, :taxYear, :spouseAllowance, :childAllowance, :parentCareAllowance,
                    :disabledCareAllowance, :maternityAllowance, :lifeInsuranceAllowance,
                    :healthInsuranceAllowance, :parentHealthInsuranceAllowance, :rmfAllowance,
                    :ssfAllowance, :pensionInsuranceAllowance, :thaiEsgAllowance,
                    :homeLoanInterestAllowance, :educationDonation, :generalDonation,
                    :politicalDonation, :childCount, :childCountDouble,
                    :disabledCareCount, :disabilityCardHolder, :parentCareCount, :effectiveMonth,
                    :documentReference, :updatedById, now()
                )
                ON CONFLICT (employee_id, tax_year, effective_month) DO UPDATE SET
                    spouse_allowance = EXCLUDED.spouse_allowance,
                    child_allowance = EXCLUDED.child_allowance,
                    parent_care_allowance = EXCLUDED.parent_care_allowance,
                    disabled_care_allowance = EXCLUDED.disabled_care_allowance,
                    maternity_allowance = EXCLUDED.maternity_allowance,
                    life_insurance_allowance = EXCLUDED.life_insurance_allowance,
                    health_insurance_allowance = EXCLUDED.health_insurance_allowance,
                    parent_health_insurance_allowance = EXCLUDED.parent_health_insurance_allowance,
                    rmf_allowance = EXCLUDED.rmf_allowance,
                    ssf_allowance = EXCLUDED.ssf_allowance,
                    pension_insurance_allowance = EXCLUDED.pension_insurance_allowance,
                    thai_esg_allowance = EXCLUDED.thai_esg_allowance,
                    home_loan_interest_allowance = EXCLUDED.home_loan_interest_allowance,
                    education_donation = EXCLUDED.education_donation,
                    general_donation = EXCLUDED.general_donation,
                    political_donation = EXCLUDED.political_donation,
                    child_count = EXCLUDED.child_count,
                    child_count_double = EXCLUDED.child_count_double,
                    disabled_care_count = EXCLUDED.disabled_care_count,
                    disability_card_holder = EXCLUDED.disability_card_holder,
                    parent_care_count = EXCLUDED.parent_care_count,
                    document_reference = EXCLUDED.document_reference,
                    updated_by_id = EXCLUDED.updated_by_id,
                    updated_at = now()
                """,
                new MapSqlParameterSource()
                    .addValue("employeeId", item.employeeId())
                    .addValue("taxYear", taxYear)
                    .addValue("spouseAllowance", safe(item.spouseAllowance()))
                    .addValue("childAllowance", safe(item.childAllowance()))
                    .addValue("parentCareAllowance", safe(item.parentCareAllowance()))
                    .addValue("disabledCareAllowance", safe(item.disabledCareAllowance()))
                    .addValue("maternityAllowance", safe(item.maternityAllowance()))
                    .addValue("lifeInsuranceAllowance", safe(item.lifeInsuranceAllowance()))
                    .addValue("healthInsuranceAllowance", safe(item.healthInsuranceAllowance()))
                    .addValue("parentHealthInsuranceAllowance", safe(item.parentHealthInsuranceAllowance()))
                    .addValue("rmfAllowance", safe(item.rmfAllowance()))
                    .addValue("ssfAllowance", safe(item.ssfAllowance()))
                    .addValue("pensionInsuranceAllowance", safe(item.pensionInsuranceAllowance()))
                    .addValue("thaiEsgAllowance", safe(item.thaiEsgAllowance()))
                    .addValue("homeLoanInterestAllowance", safe(item.homeLoanInterestAllowance()))
                    .addValue("educationDonation", safe(item.educationDonation()))
                    .addValue("generalDonation", safe(item.generalDonation()))
                    .addValue("politicalDonation", safe(item.politicalDonation()))
                    .addValue("childCount", item.childCount() == null ? 0 : item.childCount())
                    .addValue("childCountDouble", item.childCountDouble() == null ? 0 : item.childCountDouble())
                    .addValue("disabledCareCount", item.disabledCareCount() == null ? 0 : item.disabledCareCount())
                    .addValue("disabilityCardHolder", Boolean.TRUE.equals(item.disabilityCardHolder()))
                    .addValue("parentCareCount", item.parentCareCount() == null ? 0 : item.parentCareCount())
                    // ล.ย.01 ข้อ 2.2: a declaration with no stated month is the one in force from
                    // January, which is how every pre-V93 row was already applied.
                    .addValue("effectiveMonth", item.effectiveMonth() == null ? 1 : item.effectiveMonth())
                    .addValue("documentReference", item.documentReference())
                    .addValue("updatedById", updatedById));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Declaration verification + grandfathering (V95, 2026-07-29). Deliberately separate from
    // upsertTaxAllowances above: re-typing declared amounts does not, by itself, change
    // verification state -- that state machine's transition rules are the next task's service-
    // layer work. These three methods only persist a transition already decided by the caller.
    // ------------------------------------------------------------------------------------------

    /**
     * Persists the verification deadline for a stored declaration -- "60 days or two payroll
     * cut-offs after launch, whichever is later" (handoff section 3), computed by the service
     * layer. This method only stores whatever deadline the caller passes.
     */
    public void setTaxAllowanceVerificationDeadline(long employeeId, int taxYear, LocalDate deadline) {
        jdbc.update("""
            UPDATE hr.employee_tax_allowance
               SET verification_deadline = :deadline
             WHERE employee_id = :employeeId AND tax_year = :taxYear
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("taxYear", taxYear)
                .addValue("deadline", deadline));
    }

    /** HR verifies a declaration against supporting documents: VERIFIED, verifier + timestamp recorded. */
    public void markTaxAllowanceVerified(long employeeId, int taxYear, long verifiedById) {
        jdbc.update("""
            UPDATE hr.employee_tax_allowance
               SET verification_status = 'VERIFIED',
                   verified_by_id = :verifiedById,
                   verified_at = now()
             WHERE employee_id = :employeeId AND tax_year = :taxYear
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("taxYear", taxYear)
                .addValue("verifiedById", verifiedById));
    }

    /**
     * The verification deadline lapsed unverified: EXPIRED_UNVERIFIED. From the next payroll the
     * declared amount stops applying (service-layer concern -- this only flips the stored status;
     * it never retro-alters an already-filed month).
     */
    public void expireTaxAllowanceVerification(long employeeId, int taxYear) {
        jdbc.update("""
            UPDATE hr.employee_tax_allowance
               SET verification_status = 'EXPIRED_UNVERIFIED'
             WHERE employee_id = :employeeId AND tax_year = :taxYear
            """,
            Map.of("employeeId", employeeId, "taxYear", taxYear));
    }

    /** C2: rows for the GET /api/payroll/ytd-seed listing, joined to employee for display. */
    public List<YtdSeedDto> findYtdSeedRows(int taxYear) {
        return jdbc.query("""
            SELECT e.employee_id, e.employee_code,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
                   s.taxable_income, s.social_security, s.withholding_tax, s.source_note, s.updated_at
              FROM hr.employee e
              JOIN hr.payroll_year_to_date_seed s ON s.employee_id = e.employee_id AND s.tax_year = :taxYear
             ORDER BY e.employee_code
            """,
            Map.of("taxYear", taxYear),
            (rs, rowNum) -> new YtdSeedDto(
                rs.getLong("employee_id"),
                rs.getString("employee_code"),
                rs.getString("employee_name"),
                money(rs.getBigDecimal("taxable_income")),
                money(rs.getBigDecimal("social_security")),
                money(rs.getBigDecimal("withholding_tax")),
                rs.getString("source_note"),
                rs.getObject("updated_at", OffsetDateTime.class)
            ));
    }

    /** C2: bulk upsert of the year-to-date backfill used at mid-year go-live. */
    public void upsertYtdSeed(int taxYear, List<YtdSeedUpsertRequest> items, Long updatedById) {
        for (YtdSeedUpsertRequest item : items) {
            jdbc.update("""
                INSERT INTO hr.payroll_year_to_date_seed (
                    employee_id, tax_year, taxable_income, social_security, withholding_tax,
                    source_note, updated_by_id, updated_at
                ) VALUES (
                    :employeeId, :taxYear, :taxableIncome, :socialSecurity, :withholdingTax,
                    :sourceNote, :updatedById, now()
                )
                ON CONFLICT (employee_id, tax_year) DO UPDATE SET
                    taxable_income = EXCLUDED.taxable_income,
                    social_security = EXCLUDED.social_security,
                    withholding_tax = EXCLUDED.withholding_tax,
                    source_note = EXCLUDED.source_note,
                    updated_by_id = EXCLUDED.updated_by_id,
                    updated_at = now()
                """,
                new MapSqlParameterSource()
                    .addValue("employeeId", item.employeeId())
                    .addValue("taxYear", taxYear)
                    .addValue("taxableIncome", safe(item.taxableIncome()))
                    .addValue("socialSecurity", safe(item.socialSecurity()))
                    .addValue("withholdingTax", safe(item.withholdingTax()))
                    .addValue("sourceNote", item.sourceNote())
                    .addValue("updatedById", updatedById));
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ------------------------------------------------------------------------------------------
    // Payroll withholding classification + SSO inclusion matrices (V95, 2026-07-29), consulted by
    // PayrollCalculator#calculateClassified since task 2. See
    // docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md.
    // ------------------------------------------------------------------------------------------

    /**
     * Per-employee, per-component withholding-tax treatment (ป.96/2543 ข้อ 1(4)/1(5)/1(6)) for a
     * tax year. A component absent from an employee's inner map has no stored row at all; a
     * component present with a {@code null} value has a row but no treatment set yet ("not yet
     * classified"). Both read as "unclassified" to a caller, but they are NOT the same thing at
     * the SQL level and the distinction matters for {@link #upsertComponentTaxTreatment} callers
     * that need to know whether a row exists to update.
     *
     * <p>Defect fix (Opus review, 2026-07-29): the effective tax year used to be resolved PER
     * EMPLOYEE (each employee rolling forward from their own most recent {@code tax_year <=
     * taxYear}), not once for the whole table. The prior-prior implementation took a single
     * table-wide {@code MAX(tax_year)}: the first employee hired in a new tax year (seeded at their
     * hire year by {@code EmployeeService#create}) flipped that single MAX forward for EVERY
     * employee, so every pre-existing employee's classification map read back completely empty on
     * the very next payroll run.
     *
     * <p><b>D2 fix (fourth reachability audit, 2026-07-30): per-employee was still not narrow
     * enough -- resolution is now PER (EMPLOYEE, COMPONENT).</b> The per-employee {@code
     * employee_years} CTE resolved ONE effective year for an employee's ENTIRE row set: the instant
     * a single component gained a row in a new tax year (e.g. HR editing ONE dropdown on {@code
     * TaxTreatmentMatrixSection} in January of a new year, which saves only the edited cell --
     * {@code PayrollPage.jsx}'s {@code save()} sends {@code changes}, the diff, not the full
     * resolved matrix), that employee's effective year flipped to the new year for EVERY component,
     * and the {@code JOIN ... ON t.tax_year = ey.effective_tax_year} then excluded every OTHER
     * component's still-valid prior-year row. The other 15+ components read back as "not yet
     * classified" in the very API response the edit's own save renders, and {@link
     * PayrollCalculator#calculateClassified}'s classification gate 409s the whole 2027 run for any
     * of them with a non-zero amount -- a single dropdown edit stranding every other component HR
     * never touched.
     *
     * <p>Fixed by resolving the roll-forward independently per (employee, component) pair with
     * {@code DISTINCT ON}, rather than fixing the write path to materialise a full-year snapshot or
     * changing the frontend to submit the whole matrix every save. This is the more robust of the
     * options: it makes ANY partial write pattern -- today's single-cell save, a future bulk import,
     * a future screen -- safe by construction, instead of relying on every writer, present and
     * future, to remember to write a complete per-year snapshot. A lone new hire (or a lone edited
     * component) in 2027 reads their own 2027 row for that one component while every other
     * component, for that same employee, keeps independently rolling forward from its own most
     * recent {@code tax_year <= taxYear} -- exactly like {@link #findCarryForwardSuggestions} must
     * for the same reason (see that method's LATERAL joins, which resolve per employee+component for
     * an identical reason).
     */
    public Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> findComponentTaxTreatmentsByEmployee(int taxYear) {
        record Row(long employeeId, PayrollComponent component, PayrollTaxTreatment taxTreatment) {}
        List<Row> rows = jdbc.query("""
            SELECT DISTINCT ON (t.employee_id, t.component)
                   t.employee_id, t.component, t.tax_treatment
              FROM hr.payroll_component_tax_treatment t
             WHERE t.tax_year <= :taxYear
             ORDER BY t.employee_id, t.component, t.tax_year DESC
            """,
            Map.of("taxYear", taxYear),
            (rs, rowNum) -> new Row(
                rs.getLong("employee_id"),
                PayrollComponent.valueOf(rs.getString("component")),
                // Read raw (nullable): a stored NULL means "row exists, not yet classified" and
                // must stay distinct from any default treatment.
                rs.getString("tax_treatment") == null ? null : PayrollTaxTreatment.valueOf(rs.getString("tax_treatment"))
            ));
        // Accumulated by hand into a HashMap, NOT via Collectors.toMap. toMap (and groupingBy's
        // downstream toMap) route through Map.merge, which throws NullPointerException on a null
        // VALUE -- so the standard collector cannot represent the one state this map exists to carry:
        // a component row that exists but is not yet classified. That state is what blocks a payroll
        // run, so losing it would silently let an unclassified line through.
        //
        // A key that is ABSENT and a key present with a NULL value mean different things here:
        // absent = HR has never touched this component for this employee; present-and-null = a row
        // was created and deliberately left unclassified. Both must survive the round trip.
        Map<Long, Map<PayrollComponent, PayrollTaxTreatment>> byEmployee = new LinkedHashMap<>();
        for (Row row : rows) {
            byEmployee.computeIfAbsent(row.employeeId(), id -> new LinkedHashMap<>())
                .put(row.component(), row.taxTreatment());
        }
        return byEmployee;
    }

    /** Bulk upsert of per-employee, per-component tax-treatment classifications for a tax year. */
    public void upsertComponentTaxTreatment(int taxYear, List<ComponentTaxTreatmentUpsertRequest> items, Long updatedById) {
        for (ComponentTaxTreatmentUpsertRequest item : items) {
            jdbc.update("""
                INSERT INTO hr.payroll_component_tax_treatment (
                    employee_id, tax_year, component, tax_treatment, updated_by_id, updated_at
                ) VALUES (
                    :employeeId, :taxYear, :component, :taxTreatment, :updatedById, now()
                )
                ON CONFLICT (employee_id, tax_year, component) DO UPDATE SET
                    tax_treatment = EXCLUDED.tax_treatment,
                    updated_by_id = EXCLUDED.updated_by_id,
                    updated_at = now()
                """,
                new MapSqlParameterSource()
                    .addValue("employeeId", item.employeeId())
                    .addValue("taxYear", taxYear)
                    .addValue("component", item.component().name())
                    // Nullable and meaningful: null explicitly resets to "not yet classified"
                    // rather than being coerced to a default treatment.
                    .addValue("taxTreatment", item.taxTreatment() == null ? null : item.taxTreatment().name())
                    .addValue("updatedById", updatedById));
        }
    }

    /**
     * P0 fix (Opus review, 2026-07-30), layer 1b: seeds a SAFE DEFAULT classification for every
     * classification-eligible {@link PayrollComponent} for one employee/tax year, mirroring {@link
     * #seedSsoInclusionDefaults}'s own shape exactly. {@code ON CONFLICT DO NOTHING} -- this only
     * fills components that have no row yet, so it never clobbers an HR classification made through
     * the matrix screen, and re-running it is always safe.
     *
     * <p>Why this exists ALONGSIDE {@code V100}'s migration backfill rather than instead of it:
     * {@code V100} only reaches employees that existed in {@code hr.employee} at the moment that
     * migration ran. A migration is a one-time event; a NEW employee hired the day after deploy would
     * still have zero classification rows and hit exactly the same 409 the migration was written to
     * fix. This method is the creation-time counterpart -- wired into {@code EmployeeService#create}
     * next to {@link #seedSsoInclusionDefaults}, the exact call site {@link
     * PayrollClassificationReachabilityIntegrationTest} reproduces to prove the fix is reachable
     * through the real employee-creation path, not just a one-off SQL script.
     *
     * <p>This is NOT the "silent default" handoff section 1 forbids: that section is about {@link
     * PayrollCalculator#calculateClassified} never inventing a treatment for a row it cannot see. A
     * stored, {@code updated_by_id = NULL}-tagged default row that HR can see and override on the
     * matrix screen before it ever affects a real run is the same mechanism {@code
     * seedSsoInclusionDefaults} already uses for SSO inclusion, applied to the sibling matrix.
     *
     * <p>Defaults mirror {@code V100}'s own reasoning (kept in sync there via {@link
     * #defaultTaxTreatment}, not re-derived): {@link PayrollComponent#BONUS_PAY}/{@link
     * PayrollComponent#OTHER_ONE_OFF_PAY} -&gt; {@link PayrollTaxTreatment#EXTRA_KNOWN_FREQUENCY}
     * (handoff-explicit); {@link PayrollComponent#DIRECTOR_REMUNERATION} -&gt; {@link
     * PayrollTaxTreatment#REGULAR_REPROJECT} (owner-confirmed: paid every คราว, ป.96/2543 ข้อ 2.1/2.4
     * -- see {@link #defaultTaxTreatment}'s own javadoc for the correction history); every other
     * classification-eligible component -&gt; {@link PayrollTaxTreatment#EXTRA_CUMULATIVE_ACTUAL}, the
     * safe choice for a component this method has zero history to evidence recurrence for (see {@code
     * V100}'s migration comment for the full worked argument on why cumulative-actual, not
     * regular-reproject or known-frequency, is the safe unknown-recurrence default for THOSE
     * components).
     */
    public void seedComponentTaxTreatmentDefaults(long employeeId, int taxYear, Long updatedById) {
        for (PayrollComponent component : PayrollComponent.values()) {
            if (component == PayrollComponent.SALARY || component == PayrollComponent.NON_TAXABLE_INCOME) {
                continue; // SALARY is locked without ever needing a row; NON_TAXABLE_INCOME is out of scope.
            }
            jdbc.update("""
                INSERT INTO hr.payroll_component_tax_treatment (
                    employee_id, tax_year, component, tax_treatment, updated_by_id, updated_at
                ) VALUES (
                    :employeeId, :taxYear, :component, :taxTreatment, :updatedById, now()
                )
                ON CONFLICT (employee_id, tax_year, component) DO NOTHING
                """,
                new MapSqlParameterSource()
                    .addValue("employeeId", employeeId)
                    .addValue("taxYear", taxYear)
                    .addValue("component", component.name())
                    .addValue("taxTreatment", defaultTaxTreatment(component).name())
                    .addValue("updatedById", updatedById));
        }
    }

    /**
     * The safe company-wide default treatment for a component with no per-employee evidence.
     *
     * <p>Correction (owner, 2026-07-29, caught in review before this branch merged): {@code
     * DIRECTOR_REMUNERATION} does NOT fall to the generic unknown-recurrence default below. The owner
     * stated directly: "director remuneration is every month but the pay may change once in a while."
     * Paid every คราว makes it เงินได้ที่จ่ายตามปกติ under ป.96/2543 ข้อ 2.1, not a เงินพิเศษ under
     * ข้อ 2.5 -- the amount varying occasionally is not an obstacle, because ข้อ 2.4 requires
     * recomputing the withholding every คราว from the actual year-to-date figure, which is exactly what
     * {@code REGULAR_REPROJECT} already does. This is also why {@link PayrollCalculator}'s legacy
     * {@code calculate()} engine (see that method's own comment on the two-limb split) hardcodes
     * director remuneration into its REGULAR limb, not the occasional one -- the two engines must not
     * disagree about directors. An earlier version of this method (and {@code V100}'s migration)
     * defaulted {@code DIRECTOR_REMUNERATION} to {@code EXTRA_CUMULATIVE_ACTUAL} on the premise that
     * some directors are paid annually rather than monthly -- that premise is factually wrong for GL&amp;R
     * and was corrected here before merge.
     */
    public static PayrollTaxTreatment defaultTaxTreatment(PayrollComponent component) {
        if (component == PayrollComponent.DIRECTOR_REMUNERATION) {
            return PayrollTaxTreatment.REGULAR_REPROJECT;
        }
        if (component == PayrollComponent.BONUS_PAY || component == PayrollComponent.OTHER_ONE_OFF_PAY) {
            return PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY;
        }
        return PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL;
    }

    /**
     * Per-employee, per-component SSO wage-base inclusion for a tax year. Unlike tax treatment,
     * a component with no stored row has no application-level default until {@link
     * #seedSsoInclusionDefaults} runs for that employee -- this method only returns what is
     * actually stored, it does not synthesize the seed defaults on read.
     *
     * <p>Defect fix (Opus review, 2026-07-29): resolved PER EMPLOYEE (a single {@code MAX(tax_year)}
     * per employee, not one table-wide MAX) -- correct as far as it goes, but no longer the same
     * resolution as {@link #findComponentTaxTreatmentsByEmployee}.
     *
     * <p><b>F4 (fifth Opus review, 2026-07-30): this javadoc used to claim it matches {@code
     * findComponentTaxTreatmentsByEmployee} -- that is now FALSE.</b> That sibling method was later
     * tightened (D2, fourth reachability audit) to resolve per (EMPLOYEE, COMPONENT) via {@code
     * DISTINCT ON}, because a per-EMPLOYEE-only resolution has the exact same "one component's new
     * row flips the year for every other component" cliff the per-employee fix was written to close
     * one level up (see that method's own javadoc for the full mechanism). This method was never
     * updated to match and still has that narrower cliff: HR editing ONE SSO-inclusion cell into a
     * new tax year would flip every OTHER component's resolved year forward too, silently excluding
     * their still-valid prior-year rows from the wage base. <b>Currently LATENT, not exercised in
     * production</b> -- the only writer, {@link #seedSsoInclusionDefaults}, always writes a full-year
     * snapshot (every component, one INSERT batch), so no real caller today produces the partial,
     * split-year shape this gap requires. It would become live the moment {@code
     * upsertComponentSsoInclusion} (F3) gets a caller that saves a single edited cell, the same way
     * {@code TaxTreatmentMatrixSection.save()} does for tax treatment -- and unlike that gate's 409,
     * the consequence here is a silently WRONG SSO wage base (money), not a blocked run. Recorded as
     * a known risk, not fixed here (out of this task's scope); fix in lockstep with F3.
     */
    public Map<Long, Map<PayrollComponent, Boolean>> findComponentSsoInclusionByEmployee(int taxYear) {
        record Row(long employeeId, PayrollComponent component, boolean included) {}
        List<Row> rows = jdbc.query("""
            WITH employee_years AS (
                SELECT employee_id, MAX(tax_year) AS effective_tax_year
                  FROM hr.payroll_component_sso_inclusion
                 WHERE tax_year <= :taxYear
                 GROUP BY employee_id
            )
            SELECT s.employee_id, s.component, s.is_included
              FROM hr.payroll_component_sso_inclusion s
              JOIN employee_years ey
                ON ey.employee_id = s.employee_id AND s.tax_year = ey.effective_tax_year
            """,
            Map.of("taxYear", taxYear),
            (rs, rowNum) -> new Row(
                rs.getLong("employee_id"),
                PayrollComponent.valueOf(rs.getString("component")),
                rs.getBoolean("is_included")
            ));
        return rows.stream().collect(Collectors.groupingBy(
            Row::employeeId,
            Collectors.toMap(Row::component, Row::included)));
    }

    /** Bulk upsert of per-employee, per-component SSO-inclusion ticks for a tax year. */
    public void upsertComponentSsoInclusion(int taxYear, List<ComponentSsoInclusionUpsertRequest> items, Long updatedById) {
        for (ComponentSsoInclusionUpsertRequest item : items) {
            jdbc.update("""
                INSERT INTO hr.payroll_component_sso_inclusion (
                    employee_id, tax_year, component, is_included, updated_by_id, updated_at
                ) VALUES (
                    :employeeId, :taxYear, :component, :isIncluded, :updatedById, now()
                )
                ON CONFLICT (employee_id, tax_year, component) DO UPDATE SET
                    is_included = EXCLUDED.is_included,
                    updated_by_id = EXCLUDED.updated_by_id,
                    updated_at = now()
                """,
                new MapSqlParameterSource()
                    .addValue("employeeId", item.employeeId())
                    .addValue("taxYear", taxYear)
                    .addValue("component", item.component().name())
                    .addValue("isIncluded", item.included())
                    .addValue("updatedById", updatedById));
        }
    }

    /**
     * Seeds the SSO-inclusion default for every canonical {@link PayrollComponent} for one
     * employee/tax year: TRUE except {@code DIRECTOR_REMUNERATION} and {@code NON_TAXABLE_INCOME}
     * (handoff section 5). {@code ON CONFLICT DO NOTHING} -- this only fills in components that
     * do not have a row yet, so calling it again (or after HR has already edited some rows) never
     * clobbers an existing tick. Intended to run once per employee, e.g. when the employee record
     * is created; wiring this into the actual employee-onboarding flow is service-layer work for a
     * later task -- this method provides only the seeding behaviour itself.
     */
    public void seedSsoInclusionDefaults(long employeeId, int taxYear, Long updatedById) {
        for (PayrollComponent component : PayrollComponent.values()) {
            jdbc.update("""
                INSERT INTO hr.payroll_component_sso_inclusion (
                    employee_id, tax_year, component, is_included, updated_by_id, updated_at
                ) VALUES (
                    :employeeId, :taxYear, :component, :isIncluded, :updatedById, now()
                )
                ON CONFLICT (employee_id, tax_year, component) DO NOTHING
                """,
                new MapSqlParameterSource()
                    .addValue("employeeId", employeeId)
                    .addValue("taxYear", taxYear)
                    .addValue("component", component.name())
                    .addValue("isIncluded", defaultSsoIncluded(component))
                    .addValue("updatedById", updatedById));
        }
    }

    /**
     * The company-wide SSO-inclusion default (handoff section 5): every component is IN the wage base
     * except {@code DIRECTOR_REMUNERATION} (not wages under the Social Security Act) and {@code
     * NON_TAXABLE_INCOME} (out of scope for any wage base by definition). Factored out of {@link
     * #seedSsoInclusionDefaults}'s inline boolean (P1 fix, Opus review 2026-07-30) so the SAME rule
     * backs both the SQL-writing seed path above and the read-side fallback in
     * {@link #findComponentSsoInclusionByEmployee} below -- see that method's own comment for why a
     * second, independent copy of this rule would be exactly the kind of drift this fix exists to
     * prevent.
     */
    public static boolean defaultSsoIncluded(PayrollComponent component) {
        return component != PayrollComponent.DIRECTOR_REMUNERATION
            && component != PayrollComponent.NON_TAXABLE_INCOME;
    }

    public Optional<PayrollPeriodDto> findPeriodByMonth(LocalDate payrollMonth) {
        return findPeriod("""
            SELECT period_id, payroll_month, period_start, period_end, pay_date, status, processed_at, processed_by_id
              FROM hr.payroll_period
             WHERE payroll_month = :payrollMonth
            """, new MapSqlParameterSource("payrollMonth", payrollMonth));
    }

    public Optional<PayrollPeriodDto> findPeriodById(long periodId) {
        return findPeriod("""
            SELECT period_id, payroll_month, period_start, period_end, pay_date, status, processed_at, processed_by_id
              FROM hr.payroll_period
             WHERE period_id = :periodId
            """, new MapSqlParameterSource("periodId", periodId));
    }

    public long saveProcessedPeriod(LocalDate payrollMonth, Long processedById, List<PayrollLineDto> lines) {
        acquirePayrollMonthLock(payrollMonth);
        long periodId = findPeriodId(payrollMonth).orElseGet(() -> insertPeriod(payrollMonth));
        jdbc.update("DELETE FROM hr.payroll_line WHERE period_id = :periodId", Map.of("periodId", periodId));
        jdbc.update("""
            UPDATE hr.payroll_period
               SET period_start = :periodStart,
                   period_end = :periodEnd,
                   pay_date = :payDate,
                   status = 'PROCESSED',
                   processed_at = now(),
                   processed_by_id = :processedById
             WHERE period_id = :periodId
            """,
            new MapSqlParameterSource()
                .addValue("periodId", periodId)
                .addValue("periodStart", payrollMonth)
                .addValue("periodEnd", payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()))
                .addValue("payDate", payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()))
                .addValue("processedById", processedById));
        for (PayrollLineDto line : lines) {
            insertLine(periodId, line);
        }
        return periodId;
    }

    /**
     * Serializes concurrent {@code saveProcessedPeriod} calls for the same month behind a
     * transaction-scoped Postgres advisory lock (auto-released on commit/rollback). Without this,
     * two concurrent "process payroll" requests for the same month race the get-or-create period
     * lookup and the DELETE+INSERT of {@code hr.payroll_line}: the unique constraints on
     * {@code payroll_period.payroll_month} and {@code payroll_line (period_id, employee_id)}
     * prevent silent data corruption, but the losing request fails with a raw
     * {@code DataIntegrityViolationException} (HTTP 500) instead of the two requests serializing
     * cleanly, which matters since reprocessing the same month is otherwise a supported, idempotent
     * operation (see {@code reprocessingTheSameMonthReplacesLinesInsteadOfDuplicating}).
     */
    private void acquirePayrollMonthLock(LocalDate payrollMonth) {
        jdbc.queryForList(
            "SELECT pg_advisory_xact_lock(:namespace, hashtext(:monthKey))",
            new MapSqlParameterSource()
                .addValue("namespace", PAYROLL_PROCESS_LOCK_NAMESPACE)
                .addValue("monthKey", payrollMonth.toString()));
    }

    public List<PayrollLineDto> findLines(long periodId) {
        return jdbc.query("""
            SELECT pl.line_id, pl.employee_id, e.employee_code,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
                   dep.name_th AS department_name,
                   bank.name_th AS bank_name,
                   ba.account_no AS bank_account,
                   pl.base_salary, pl.daily_rate, pl.hourly_rate,
                   pl.special_pay_1, pl.special_pay_2, pl.special_pay_3, pl.special_pay_4,
                   pl.special_pay_5, pl.special_pay_6, pl.special_pay_7, pl.special_pay_8,
                   pl.special_pay_9,
                   pl.special_pay_total, pl.overtime_pay, pl.commission_pay,
                   pl.gross_amount, pl.non_taxable_income,
                   pl.unpaid_leave_days, pl.unpaid_leave_deduction,
                   pl.gross_taxable_income, pl.sso_wage_base, pl.social_security,
                   pl.projected_annual_income, pl.tax_expense_deduction, pl.tax_allowance_total,
                   pl.taxable_annual_income, pl.annual_tax, pl.withholding_tax,
                   pl.student_loan_deduction, pl.legal_execution_deduction,
                   pl.other_post_tax_deductions, pl.deductions, pl.net_amount,
                   pl.calculation_note,
                   pl.director_remuneration, pl.warning_letter_deduction,
                   pl.customer_return_deduction, pl.other_pretax_deduction,
                   pl.leave_refund_days, pl.leave_deduction_refund,
                   pl.withholding_tax_override,
                   pl.regular_taxable_income, pl.variable_taxable_income,
                   pl.regular_withholding_tax, pl.variable_withholding_tax,
                   pl.bonus_pay, pl.other_one_off_pay, pl.excess_withheld_to_date,
                   pl.taxable_income_regular_limb, pl.taxable_income_known_limb, pl.taxable_income_cumulative_limb,
                   pl.withholding_tax_regular_limb, pl.withholding_tax_cumulative_limb,
                   pl.customer_return_already_earned, pl.garnishment_type,
                   pl.meal_allowance, pl.per_diem_exempt, pl.per_diem_taxable, pl.per_diem_basis,
                   pl.customer_return_requested
              FROM hr.payroll_line pl
              JOIN hr.employee e ON e.employee_id = pl.employee_id
              LEFT JOIN hr.department dep ON dep.department_id = e.department_id
              LEFT JOIN hr.employee_bank_account ba ON ba.employee_id = e.employee_id
              LEFT JOIN hr.bank bank ON bank.bank_id = ba.bank_id
             WHERE pl.period_id = :periodId
             ORDER BY e.employee_code
            """,
            Map.of("periodId", periodId),
            (rs, rowNum) -> mapLine(rs));
    }

    /**
     * Rows for the statutory export files (KBank/PND1/SSO): the already-computed {@code payroll_line}
     * amounts joined with the identity, bank and current-address fields the file formats need. Reads
     * the PDPA-restricted {@code hr_restricted.employee_pii} (national id / tax id / SSN) — the caller
     * (PayrollService) gates this to HR/CEO, and every access is audited.
     */
    public List<PayrollExportRow> findExportRows(long periodId) {
        return jdbc.query("""
            SELECT pl.employee_id, e.employee_code,
                   t.name_th AS title_th,
                   e.first_name_th, e.last_name_th, e.first_name_en,
                   pii.national_id, pii.tax_id, pii.social_security_no,
                   ba.account_no AS bank_account,
                   addr.house_no,
                   NULLIF(TRIM(CONCAT_WS(' ', addr.building, addr.soi, addr.road,
                       addr.subdistrict, addr.district, addr.province)), '') AS address_rest,
                   addr.postal_code,
                   pl.net_amount, pl.gross_taxable_income, pl.withholding_tax,
                   pl.sso_wage_base, pl.social_security
              FROM hr.payroll_line pl
              JOIN hr.employee e ON e.employee_id = pl.employee_id
              LEFT JOIN hr.title t ON t.title_id = e.title_id
              LEFT JOIN hr_restricted.employee_pii pii ON pii.employee_id = e.employee_id
              LEFT JOIN hr.employee_bank_account ba ON ba.employee_id = e.employee_id
              LEFT JOIN hr.employee_address addr
                     ON addr.employee_id = e.employee_id AND addr.address_type = 'CURRENT'
             WHERE pl.period_id = :periodId
             ORDER BY e.employee_code
            """,
            Map.of("periodId", periodId),
            (rs, rowNum) -> new PayrollExportRow(
                rs.getLong("employee_id"),
                rs.getString("employee_code"),
                rs.getString("title_th"),
                rs.getString("first_name_th"),
                rs.getString("last_name_th"),
                rs.getString("first_name_en"),
                rs.getString("national_id"),
                rs.getString("tax_id"),
                rs.getString("social_security_no"),
                rs.getString("bank_account"),
                rs.getString("house_no"),
                rs.getString("address_rest"),
                rs.getString("postal_code"),
                rs.getBigDecimal("net_amount"),
                rs.getBigDecimal("gross_taxable_income"),
                rs.getBigDecimal("withholding_tax"),
                rs.getBigDecimal("sso_wage_base"),
                rs.getBigDecimal("social_security")));
    }

    public Map<Long, String> findEmployeeEmailsByIds(Collection<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("""
            SELECT employee_id, NULLIF(BTRIM(email), '') AS email
              FROM hr.employee
             WHERE employee_id IN (:employeeIds)
               AND NULLIF(BTRIM(email), '') IS NOT NULL
            """,
            Map.of("employeeIds", employeeIds),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), rs.getString("email")))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Set<Long> findSentPayslipLineIds(long periodId) {
        return jdbc.query("""
            SELECT line_id
              FROM hr.payroll_payslip_email_delivery
             WHERE period_id = :periodId
               AND status = 'SENT'
            """,
            Map.of("periodId", periodId),
            (rs, rowNum) -> rs.getLong("line_id"))
            .stream()
            .collect(Collectors.toSet());
    }

    public boolean markPayslipEmailPending(long periodId, PayrollLineDto line, String recipientEmail) {
        if (line.id() == null) {
            return false;
        }
        int updated = jdbc.update("""
            INSERT INTO hr.payroll_payslip_email_delivery
                (period_id, line_id, employee_id, recipient_email, status, attempt_count, last_error)
            VALUES
                (:periodId, :lineId, :employeeId, :recipientEmail, 'PENDING', 1, NULL)
            ON CONFLICT (period_id, line_id) DO UPDATE
               SET recipient_email = EXCLUDED.recipient_email,
                   status = 'PENDING',
                   attempt_count = hr.payroll_payslip_email_delivery.attempt_count + 1,
                   last_error = NULL,
                   updated_at = now()
             WHERE hr.payroll_payslip_email_delivery.status <> 'SENT'
               AND (
                   hr.payroll_payslip_email_delivery.status <> 'PENDING'
                   OR hr.payroll_payslip_email_delivery.updated_at < now() - interval '15 minutes'
               )
            """,
            new MapSqlParameterSource()
                .addValue("periodId", periodId)
                .addValue("lineId", line.id())
                .addValue("employeeId", line.employeeId())
                .addValue("recipientEmail", recipientEmail));
        return updated > 0;
    }

    public void markPayslipEmailSent(long periodId, PayrollLineDto line, String recipientEmail) {
        if (line.id() == null) {
            return;
        }
        jdbc.update("""
            UPDATE hr.payroll_payslip_email_delivery
               SET recipient_email = :recipientEmail,
                   status = 'SENT',
                   last_error = NULL,
                   sent_at = now(),
                   updated_at = now()
             WHERE period_id = :periodId
               AND line_id = :lineId
            """,
            new MapSqlParameterSource()
                .addValue("periodId", periodId)
                .addValue("lineId", line.id())
                .addValue("recipientEmail", recipientEmail));
    }

    public void markPayslipEmailFailed(long periodId, PayrollLineDto line, String recipientEmail, String error) {
        if (line.id() == null) {
            return;
        }
        jdbc.update("""
            INSERT INTO hr.payroll_payslip_email_delivery
                (period_id, line_id, employee_id, recipient_email, status, attempt_count, last_error)
            VALUES
                (:periodId, :lineId, :employeeId, :recipientEmail, 'FAILED', 1, :error)
            ON CONFLICT (period_id, line_id) DO UPDATE
               SET recipient_email = EXCLUDED.recipient_email,
                   status = 'FAILED',
                   attempt_count = CASE
                       WHEN hr.payroll_payslip_email_delivery.status = 'PENDING'
                           THEN hr.payroll_payslip_email_delivery.attempt_count
                       ELSE hr.payroll_payslip_email_delivery.attempt_count + 1
                   END,
                   last_error = EXCLUDED.last_error,
                   updated_at = now()
            """,
            new MapSqlParameterSource()
                .addValue("periodId", periodId)
                .addValue("lineId", line.id())
                .addValue("employeeId", line.employeeId())
                .addValue("recipientEmail", recipientEmail)
                .addValue("error", truncate(error)));
    }

    private Optional<PayrollPeriodDto> findPeriod(String sql, MapSqlParameterSource params) {
        try {
            PayrollPeriodHeader header = jdbc.queryForObject(sql, params, (rs, rowNum) -> new PayrollPeriodHeader(
                rs.getLong("period_id"),
                rs.getObject("payroll_month", LocalDate.class),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getObject("pay_date", LocalDate.class),
                rs.getString("status"),
                rs.getObject("processed_at", OffsetDateTime.class),
                nullableLong(rs, "processed_by_id")
            ));
            if (header == null) {
                return Optional.empty();
            }
            List<PayrollLineDto> lines = findLines(header.periodId());
            return Optional.of(toPeriod(header, lines));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Optional<Long> findPeriodId(LocalDate payrollMonth) {
        try {
            Long id = jdbc.queryForObject("""
                SELECT period_id FROM hr.payroll_period WHERE payroll_month = :payrollMonth
                """, Map.of("payrollMonth", payrollMonth), Long.class);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private long insertPeriod(LocalDate payrollMonth) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (:payrollMonth, :periodStart, :periodEnd, :payDate, 'PROCESSED')
            """,
            new MapSqlParameterSource()
                .addValue("payrollMonth", payrollMonth)
                .addValue("periodStart", payrollMonth)
                .addValue("periodEnd", payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()))
                .addValue("payDate", payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth())),
            keyHolder,
            new String[]{"period_id"});
        return keyHolder.getKey().longValue();
    }

    private void insertLine(long periodId, PayrollLineDto line) {
        jdbc.update("""
            INSERT INTO hr.payroll_line (
                period_id, employee_id, base_salary, daily_rate, hourly_rate,
                special_pay_1, special_pay_2, special_pay_3, special_pay_4,
                special_pay_5, special_pay_6, special_pay_7, special_pay_8, special_pay_9,
                special_pay_total, overtime_pay, commission_pay, gross_amount,
                non_taxable_income,
                unpaid_leave_days, unpaid_leave_deduction, gross_taxable_income,
                sso_wage_base, social_security, projected_annual_income,
                tax_expense_deduction, tax_allowance_total, taxable_annual_income,
                annual_tax, withholding_tax, student_loan_deduction,
                legal_execution_deduction, other_post_tax_deductions, deductions,
                net_amount, calculation_note,
                director_remuneration, warning_letter_deduction,
                customer_return_deduction, other_pretax_deduction,
                leave_refund_days, leave_deduction_refund,
                withholding_tax_override,
                regular_taxable_income, variable_taxable_income,
                regular_withholding_tax, variable_withholding_tax,
                bonus_pay, other_one_off_pay, excess_withheld_to_date,
                taxable_income_regular_limb, taxable_income_known_limb, taxable_income_cumulative_limb,
                withholding_tax_regular_limb, withholding_tax_cumulative_limb,
                customer_return_already_earned, garnishment_type,
                meal_allowance, per_diem_exempt, per_diem_taxable, per_diem_basis,
                customer_return_requested
            )
            VALUES (
                :periodId, :employeeId, :baseSalary, :dailyRate, :hourlyRate,
                :specialPay1, :specialPay2, :specialPay3, :specialPay4,
                :specialPay5, :specialPay6, :specialPay7, :specialPay8, :specialPay9,
                :specialPayTotal, :overtimePay, :commissionPay, :grossAmount,
                :nonTaxableIncome,
                :unpaidLeaveDays, :unpaidLeaveDeduction, :grossTaxableIncome,
                :ssoWageBase, :socialSecurity, :projectedAnnualIncome,
                :taxExpenseDeduction, :taxAllowanceTotal, :taxableAnnualIncome,
                :annualTax, :withholdingTax, :studentLoanDeduction,
                :legalExecutionDeduction, :otherPostTaxDeductions, :deductions,
                :netAmount, :calculationNote,
                :directorRemuneration, :warningLetterDeduction,
                :customerReturnDeduction, :otherPretaxDeduction,
                :leaveRefundDays, :leaveDeductionRefund,
                :withholdingTaxOverride,
                :regularTaxableIncome, :variableTaxableIncome,
                :regularWithholdingTax, :variableWithholdingTax,
                :bonusPay, :otherOneOffPay, :excessWithheldToDate,
                :taxableIncomeRegularLimb, :taxableIncomeKnownLimb, :taxableIncomeCumulativeLimb,
                :withholdingTaxRegularLimb, :withholdingTaxCumulativeLimb,
                :customerReturnAlreadyEarned, :garnishmentType,
                :mealAllowance, :perDiemExempt, :perDiemTaxable, :perDiemBasis,
                :customerReturnRequested
            )
            """,
            new MapSqlParameterSource()
                .addValue("periodId", periodId)
                .addValue("employeeId", line.employeeId())
                .addValue("baseSalary", line.baseSalary())
                .addValue("dailyRate", line.dailyRate())
                .addValue("hourlyRate", line.hourlyRate())
                .addValue("specialPay1", amount(line.specialPays(), 0))
                .addValue("specialPay2", amount(line.specialPays(), 1))
                .addValue("specialPay3", amount(line.specialPays(), 2))
                .addValue("specialPay4", amount(line.specialPays(), 3))
                .addValue("specialPay5", amount(line.specialPays(), 4))
                .addValue("specialPay6", amount(line.specialPays(), 5))
                .addValue("specialPay7", amount(line.specialPays(), 6))
                .addValue("specialPay8", amount(line.specialPays(), 7))
                .addValue("specialPay9", amount(line.specialPays(), 8))
                .addValue("specialPayTotal", line.specialPayTotal())
                .addValue("overtimePay", line.overtimePay())
                .addValue("commissionPay", line.commissionPay())
                .addValue("grossAmount", line.grossEarnings())
                .addValue("nonTaxableIncome", line.nonTaxableIncome())
                .addValue("unpaidLeaveDays", line.unpaidLeaveDays())
                .addValue("unpaidLeaveDeduction", line.unpaidLeaveDeduction())
                .addValue("grossTaxableIncome", line.grossTaxableIncome())
                .addValue("ssoWageBase", line.ssoWageBase())
                .addValue("socialSecurity", line.socialSecurity())
                .addValue("projectedAnnualIncome", line.projectedAnnualIncome())
                .addValue("taxExpenseDeduction", line.taxExpenseDeduction())
                .addValue("taxAllowanceTotal", line.taxAllowanceTotal())
                .addValue("taxableAnnualIncome", line.taxableAnnualIncome())
                .addValue("annualTax", line.annualTax())
                .addValue("withholdingTax", line.withholdingTax())
                .addValue("studentLoanDeduction", line.studentLoanDeduction())
                .addValue("legalExecutionDeduction", line.legalExecutionDeduction())
                .addValue("otherPostTaxDeductions", line.otherPostTaxDeductions())
                .addValue("deductions", line.totalDeductions())
                .addValue("netAmount", line.netPay())
                .addValue("calculationNote", line.calculationNote())
                .addValue("directorRemuneration", line.directorRemuneration())
                .addValue("warningLetterDeduction", line.warningLetterDeduction())
                .addValue("customerReturnDeduction", line.customerReturnDeduction())
                .addValue("otherPretaxDeduction", line.otherPretaxDeduction())
                .addValue("leaveRefundDays", line.leaveRefundDays())
                .addValue("leaveDeductionRefund", line.leaveDeductionRefund())
                // Nullable per-run typed override -- pass through null (no COALESCE) so "none typed"
                // persists as SQL NULL, distinct from a 0 override.
                .addValue("withholdingTaxOverride", line.withholdingTaxOverride())
                .addValue("regularTaxableIncome", safe(line.regularTaxableIncome()))
                .addValue("variableTaxableIncome", safe(line.variableTaxableIncome()))
                .addValue("regularWithholdingTax", safe(line.regularWithholdingTax()))
                .addValue("variableWithholdingTax", safe(line.variableWithholdingTax()))
                .addValue("bonusPay", safe(line.bonusPay()))
                .addValue("otherOneOffPay", safe(line.otherOneOffPay()))
                .addValue("excessWithheldToDate", safe(line.excessWithheldToDate()))
                .addValue("taxableIncomeRegularLimb", safe(line.taxableIncomeRegularLimb()))
                .addValue("taxableIncomeKnownLimb", safe(line.taxableIncomeKnownLimb()))
                .addValue("taxableIncomeCumulativeLimb", safe(line.taxableIncomeCumulativeLimb()))
                .addValue("withholdingTaxRegularLimb", safe(line.withholdingTaxRegularLimb()))
                .addValue("withholdingTaxCumulativeLimb", safe(line.withholdingTaxCumulativeLimb()))
                .addValue("customerReturnAlreadyEarned", line.customerReturnAlreadyEarned())
                .addValue("garnishmentType", line.garnishmentType() == null ? "SALARY" : line.garnishmentType())
                .addValue("mealAllowance", safe(line.mealAllowance()))
                .addValue("perDiemExempt", safe(line.perDiemExempt()))
                .addValue("perDiemTaxable", safe(line.perDiemTaxable()))
                // Nullable: null = no per-diem paid this line, matching the
                // chk_payroll_line_per_diem_basis_present CHECK (no basis required when both
                // per_diem_exempt/per_diem_taxable are zero).
                .addValue("perDiemBasis", line.perDiemBasis())
                // D1 fix (fourth reachability audit, 2026-07-30, V101): the raw entered amount,
                // always -- see PayrollLineDto#customerReturnRequested's own javadoc.
                .addValue("customerReturnRequested", safe(line.customerReturnRequested())));
    }

    private PayrollPeriodDto toPeriod(PayrollPeriodHeader header, List<PayrollLineDto> lines) {
        return new PayrollPeriodDto(
            header.periodId(),
            header.payrollMonth(),
            header.periodStart(),
            header.periodEnd(),
            header.payDate(),
            header.status(),
            header.processedAt(),
            header.processedById(),
            lines.size(),
            sum(lines, PayrollLineDto::grossEarnings),
            sum(lines, PayrollLineDto::totalDeductions),
            sum(lines, PayrollLineDto::netPay),
            sum(lines, PayrollLineDto::socialSecurity),
            sum(lines, PayrollLineDto::withholdingTax),
            lines
        );
    }

    private PayrollLineDto mapLine(ResultSet rs) throws SQLException {
        return new PayrollLineDto(
            rs.getLong("line_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getString("department_name"),
            rs.getString("bank_name"),
            rs.getString("bank_account"),
            money(rs.getBigDecimal("base_salary")),
            rs.getBigDecimal("daily_rate"),
            rs.getBigDecimal("hourly_rate"),
            specialPays(rs),
            money(rs.getBigDecimal("special_pay_total")),
            money(rs.getBigDecimal("overtime_pay")),
            money(rs.getBigDecimal("commission_pay")),
            money(rs.getBigDecimal("gross_amount")),
            money(rs.getBigDecimal("non_taxable_income")),
            money(rs.getBigDecimal("unpaid_leave_days")),
            money(rs.getBigDecimal("unpaid_leave_deduction")),
            money(rs.getBigDecimal("gross_taxable_income")),
            money(rs.getBigDecimal("sso_wage_base")),
            money(rs.getBigDecimal("social_security")),
            money(rs.getBigDecimal("projected_annual_income")),
            money(rs.getBigDecimal("tax_expense_deduction")),
            money(rs.getBigDecimal("tax_allowance_total")),
            money(rs.getBigDecimal("taxable_annual_income")),
            money(rs.getBigDecimal("annual_tax")),
            money(rs.getBigDecimal("withholding_tax")),
            money(rs.getBigDecimal("student_loan_deduction")),
            money(rs.getBigDecimal("legal_execution_deduction")),
            money(rs.getBigDecimal("other_post_tax_deductions")),
            money(rs.getBigDecimal("deductions")),
            money(rs.getBigDecimal("net_amount")),
            rs.getString("calculation_note"),
            money(rs.getBigDecimal("director_remuneration")),
            money(rs.getBigDecimal("warning_letter_deduction")),
            money(rs.getBigDecimal("customer_return_deduction")),
            money(rs.getBigDecimal("other_pretax_deduction")),
            money(rs.getBigDecimal("leave_refund_days")),
            money(rs.getBigDecimal("leave_deduction_refund")),
            // Nullable per-run typed override -- read raw so SQL NULL stays null (money() would coerce
            // it to 0.00, which is a different, meaningful override value).
            rs.getBigDecimal("withholding_tax_override"),
            // ป.96/2543 limbs (V92, superseded going forward -- see PayrollLineDto's javadoc).
            money(rs.getBigDecimal("regular_taxable_income")),
            money(rs.getBigDecimal("variable_taxable_income")),
            money(rs.getBigDecimal("regular_withholding_tax")),
            money(rs.getBigDecimal("variable_withholding_tax")),
            money(rs.getBigDecimal("bonus_pay")),
            money(rs.getBigDecimal("other_one_off_pay")),
            money(rs.getBigDecimal("excess_withheld_to_date")),
            money(rs.getBigDecimal("taxable_income_regular_limb")),
            money(rs.getBigDecimal("taxable_income_known_limb")),
            money(rs.getBigDecimal("taxable_income_cumulative_limb")),
            money(rs.getBigDecimal("withholding_tax_regular_limb")),
            money(rs.getBigDecimal("withholding_tax_cumulative_limb")),
            rs.getBoolean("customer_return_already_earned"),
            rs.getString("garnishment_type"),
            money(rs.getBigDecimal("meal_allowance")),
            money(rs.getBigDecimal("per_diem_exempt")),
            money(rs.getBigDecimal("per_diem_taxable")),
            rs.getString("per_diem_basis"),
            money(rs.getBigDecimal("customer_return_requested"))
        );
    }

    /**
     * Slot labels, ALIGNED TO THE ACCOUNTANT'S WORKBOOK (2026-07-29, owner decision) -- MUST match
     * {@link PayrollService#specialPayDtos} exactly. Defect fix (Opus review, 2026-07-29): this
     * method still carried the OLD (pre-realignment) labels after {@code PayrollService} was moved
     * to the new numbering, so every processed payroll line was previewed under one label and read
     * back from the database under a different one -- silently wrong Thai labels on every payslip
     * PDF and HR screen for slots 2 through 9. See {@code PayrollService#specialPayDtos}'s javadoc
     * for the full renumbering rationale; {@code PayrollSlotLabelAlignmentIntegrationTest} pins the
     * two methods against each other so they cannot drift apart again.
     */
    private List<PayrollSpecialPayDto> specialPays(ResultSet rs) throws SQLException {
        return List.of(
            specialPay("specialPay1", "พิเศษ 1 (ค่าครองชีพ)", rs.getBigDecimal("special_pay_1")),
            specialPay("specialPay2", "พิเศษ 2 (ค่าเช่าบ้าน)", rs.getBigDecimal("special_pay_2")),
            specialPay("specialPay3", "พิเศษ 3 (เบี้ยเลี้ยงประจำ)", rs.getBigDecimal("special_pay_3")),
            specialPay("specialPay4", "พิเศษ 4 (ค่าตำแหน่ง)", rs.getBigDecimal("special_pay_4")),
            specialPay("specialPay5", "พิเศษ 5 (เบี้ยขยันประจำ)", rs.getBigDecimal("special_pay_5")),
            specialPay("specialPay6", "พิเศษ 6 (ค่า GPRS)", rs.getBigDecimal("special_pay_6")),
            specialPay("specialPay7", "พิเศษ 7 (คอมมิชชั่น)", rs.getBigDecimal("special_pay_7")),
            specialPay("specialPay8", "พิเศษ 8 (ทำได้ตาม KPI)", rs.getBigDecimal("special_pay_8")),
            specialPay("specialPay9", "พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)", rs.getBigDecimal("special_pay_9"))
        );
    }

    private PayrollSpecialPayDto specialPay(String key, String label, BigDecimal amount) {
        return new PayrollSpecialPayDto(key, label, money(amount));
    }

    private BigDecimal amount(List<PayrollSpecialPayDto> values, int index) {
        return values == null || values.size() <= index ? BigDecimal.ZERO : values.get(index).amount();
    }

    private BigDecimal sum(List<PayrollLineDto> lines, MoneyExtractor extractor) {
        return lines.stream().map(extractor::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record PayrollPeriodHeader(
        long periodId,
        LocalDate payrollMonth,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate payDate,
        String status,
        OffsetDateTime processedAt,
        Long processedById
    ) {}

    private interface MoneyExtractor {
        BigDecimal value(PayrollLineDto line);
    }
}
