package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.EmployeeTaxAllowanceUpsertRequest;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedDto;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest;

@Repository
public class PayrollRepository {
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
                   COALESCE(e.director_remuneration, 0) AS director_remuneration
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
                rs.getBigDecimal("director_remuneration")
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
        return jdbc.query("""
            SELECT employee_id,
                   COALESCE(SUM(taxable_income), 0) AS taxable_income,
                   COALESCE(SUM(social_security), 0) AS social_security,
                   COALESCE(SUM(withholding_tax), 0) AS withholding_tax
              FROM (
                  SELECT pl.employee_id,
                         pl.gross_taxable_income AS taxable_income,
                         pl.social_security AS social_security,
                         pl.withholding_tax AS withholding_tax
                    FROM hr.payroll_line pl
                    JOIN hr.payroll_period pp ON pp.period_id = pl.period_id
                   WHERE pp.payroll_month >= date_trunc('year', :payrollMonth::date)::date
                     AND pp.payroll_month < :payrollMonth
                     AND pp.status <> 'VOID'
                  UNION ALL
                  SELECT s.employee_id, s.taxable_income, s.social_security, s.withholding_tax
                    FROM hr.payroll_year_to_date_seed s
                   WHERE s.tax_year = EXTRACT(YEAR FROM :payrollMonth::date)::int
              ) combined
             GROUP BY employee_id
            """,
            Map.of("payrollMonth", payrollMonth),
            (rs, rowNum) -> Map.entry(rs.getLong("employee_id"), new PayrollYearToDate(
                money(rs.getBigDecimal("taxable_income")),
                money(rs.getBigDecimal("social_security")),
                money(rs.getBigDecimal("withholding_tax"))
            )))
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** C1: the standing tax-allowance declaration per employee for a given tax year. */
    public Map<Long, PayrollTaxAllowanceInput> findTaxAllowancesByEmployee(int taxYear) {
        return jdbc.query("""
            SELECT employee_id, spouse_allowance, child_allowance, parent_care_allowance,
                   disabled_care_allowance, maternity_allowance, life_insurance_allowance,
                   health_insurance_allowance, parent_health_insurance_allowance, rmf_allowance,
                   ssf_allowance, pension_insurance_allowance, thai_esg_allowance,
                   home_loan_interest_allowance, education_donation, general_donation, political_donation
              FROM hr.employee_tax_allowance
             WHERE tax_year = :taxYear
            """,
            Map.of("taxYear", taxYear),
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
                money(rs.getBigDecimal("political_donation"))
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
                   eta.political_donation, eta.updated_at
              FROM hr.employee e
              JOIN hr.employee_tax_allowance eta ON eta.employee_id = e.employee_id AND eta.tax_year = :taxYear
             ORDER BY e.employee_code
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
                    money(rs.getBigDecimal("political_donation"))
                ),
                rs.getObject("updated_at", OffsetDateTime.class)
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
                    political_donation, updated_by_id, updated_at
                ) VALUES (
                    :employeeId, :taxYear, :spouseAllowance, :childAllowance, :parentCareAllowance,
                    :disabledCareAllowance, :maternityAllowance, :lifeInsuranceAllowance,
                    :healthInsuranceAllowance, :parentHealthInsuranceAllowance, :rmfAllowance,
                    :ssfAllowance, :pensionInsuranceAllowance, :thaiEsgAllowance,
                    :homeLoanInterestAllowance, :educationDonation, :generalDonation,
                    :politicalDonation, :updatedById, now()
                )
                ON CONFLICT (employee_id, tax_year) DO UPDATE SET
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
                    .addValue("updatedById", updatedById));
        }
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
                   pl.customer_return_deduction, pl.other_pretax_deduction
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
                special_pay_5, special_pay_6, special_pay_7, special_pay_8,
                special_pay_total, overtime_pay, commission_pay, gross_amount,
                non_taxable_income,
                unpaid_leave_days, unpaid_leave_deduction, gross_taxable_income,
                sso_wage_base, social_security, projected_annual_income,
                tax_expense_deduction, tax_allowance_total, taxable_annual_income,
                annual_tax, withholding_tax, student_loan_deduction,
                legal_execution_deduction, other_post_tax_deductions, deductions,
                net_amount, calculation_note,
                director_remuneration, warning_letter_deduction,
                customer_return_deduction, other_pretax_deduction
            )
            VALUES (
                :periodId, :employeeId, :baseSalary, :dailyRate, :hourlyRate,
                :specialPay1, :specialPay2, :specialPay3, :specialPay4,
                :specialPay5, :specialPay6, :specialPay7, :specialPay8,
                :specialPayTotal, :overtimePay, :commissionPay, :grossAmount,
                :nonTaxableIncome,
                :unpaidLeaveDays, :unpaidLeaveDeduction, :grossTaxableIncome,
                :ssoWageBase, :socialSecurity, :projectedAnnualIncome,
                :taxExpenseDeduction, :taxAllowanceTotal, :taxableAnnualIncome,
                :annualTax, :withholdingTax, :studentLoanDeduction,
                :legalExecutionDeduction, :otherPostTaxDeductions, :deductions,
                :netAmount, :calculationNote,
                :directorRemuneration, :warningLetterDeduction,
                :customerReturnDeduction, :otherPretaxDeduction
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
                .addValue("otherPretaxDeduction", line.otherPretaxDeduction()));
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
            money(rs.getBigDecimal("other_pretax_deduction"))
        );
    }

    private List<PayrollSpecialPayDto> specialPays(ResultSet rs) throws SQLException {
        return List.of(
            specialPay("specialPay1", "พิเศษ 1 (ค่าครองชีพ)", rs.getBigDecimal("special_pay_1")),
            specialPay("specialPay2", "พิเศษ 2 (เบี้ยเลี้ยงประจำ)", rs.getBigDecimal("special_pay_2")),
            specialPay("specialPay3", "พิเศษ 3 (ค่าตำแหน่ง)", rs.getBigDecimal("special_pay_3")),
            specialPay("specialPay4", "พิเศษ 4 (เบี้ยขยันประจำ)", rs.getBigDecimal("special_pay_4")),
            specialPay("specialPay5", "พิเศษ 5 (ค่า GPRS)", rs.getBigDecimal("special_pay_5")),
            specialPay("specialPay6", "พิเศษ 6 (คอมมิชชั่น)", rs.getBigDecimal("special_pay_6")),
            specialPay("specialPay7", "พิเศษ 7 (ทำได้ตาม KPI)", rs.getBigDecimal("special_pay_7")),
            specialPay("specialPay8", "พิเศษ 8 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)", rs.getBigDecimal("special_pay_8"))
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
