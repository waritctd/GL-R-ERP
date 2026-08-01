package th.co.glr.hr.payroll.declaration;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.payroll.PayrollTaxAllowanceInput;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;

/**
 * Persistence for {@code hr.tax_allowance_declaration} (V105). Deliberately writes NO SQL against
 * {@code hr.employee_tax_allowance} — the parent table is only ever touched by
 * {@code PayrollRepository#upsertTaxAllowances}/{@code #markTaxAllowanceVerified}/
 * {@code #setTaxAllowanceVerificationDeadline}, called from
 * {@link TaxAllowanceDeclarationService#apply}. That separation is the whole point of this table:
 * see {@code V105}'s header comment.
 *
 * <p>Every mutation here is a conditional {@code UPDATE ... WHERE declaration_id = :id AND status =
 * '...'} with a rowcount check in the caller, never a bare row load followed by a save — there is no
 * {@code @Version} anywhere in this repo (copy of the profile-request concurrency idiom).
 */
@Repository
public class TaxAllowanceDeclarationRepository {
    private static final String SELECT_COLUMNS = """
        d.declaration_id, d.employee_id, e.employee_code,
        COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), e.email, e.employee_code) AS employee_name,
        d.tax_year, d.effective_month,
        d.spouse_allowance, d.child_allowance, d.parent_care_allowance, d.disabled_care_allowance,
        d.maternity_allowance, d.life_insurance_allowance, d.health_insurance_allowance,
        d.parent_health_insurance_allowance, d.rmf_allowance, d.ssf_allowance,
        d.pension_insurance_allowance, d.thai_esg_allowance, d.home_loan_interest_allowance,
        d.education_donation, d.general_donation, d.political_donation,
        d.child_count, d.child_count_double, d.disabled_care_count, d.disability_card_holder,
        d.parent_care_count, d.document_reference,
        d.status, d.submitted_by_id, d.submitted_at, d.on_behalf,
        d.reviewed_by_id, d.reviewed_at, d.reviewer_note,
        d.applied_at, d.applied_by_id, d.applied_effective_month,
        d.expires_on, d.expired_at, d.reverified_at, d.reverified_by_id, d.superseded_by_id
        """;
    private static final String FROM_JOIN = """
        FROM hr.tax_allowance_declaration d
        JOIN hr.employee e ON e.employee_id = d.employee_id
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public TaxAllowanceDeclarationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TaxAllowanceDeclarationDto> findById(long declarationId) {
        List<TaxAllowanceDeclarationDto> rows = jdbc.query(
            "SELECT " + SELECT_COLUMNS + FROM_JOIN + " WHERE d.declaration_id = :id",
            Map.of("id", declarationId),
            this::mapRow);
        return rows.stream().findFirst();
    }

    /** Every declaration for one employee/tax-year, latest submission first — "current" is items.get(0). */
    public List<TaxAllowanceDeclarationDto> findForEmployee(long employeeId, int taxYear) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + FROM_JOIN
                + " WHERE d.employee_id = :employeeId AND d.tax_year = :taxYear"
                + " ORDER BY d.submitted_at DESC",
            Map.of("employeeId", employeeId, "taxYear", taxYear),
            this::mapRow);
    }

    /** HR/CEO register: every declaration, optionally filtered by tax year and/or status. */
    public List<TaxAllowanceDeclarationDto> findRegister(Integer taxYear, TaxAllowanceDeclarationStatus status) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS + FROM_JOIN + " WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (taxYear != null) {
            sql.append(" AND d.tax_year = :taxYear");
            params.addValue("taxYear", taxYear);
        }
        if (status != null) {
            sql.append(" AND d.status = :status");
            params.addValue("status", status.name());
        }
        sql.append(" ORDER BY e.employee_code, d.submitted_at DESC");
        return jdbc.query(sql.toString(), params, this::mapRow);
    }

    /** True if this employee already has a PENDING declaration for this tax year (the partial unique index). */
    public boolean existsPending(long employeeId, int taxYear) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.tax_allowance_declaration
             WHERE employee_id = :employeeId AND tax_year = :taxYear AND status = 'PENDING'
            """,
            Map.of("employeeId", employeeId, "taxYear", taxYear), Integer.class);
        return count != null && count > 0;
    }

    /** Inserts a new PENDING declaration and returns its generated id. */
    public long insert(
        long employeeId, int taxYear, int effectiveMonth, PayrollTaxAllowanceInput allowances,
        String documentReference, long submittedById, boolean onBehalf
    ) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.tax_allowance_declaration (
                employee_id, tax_year, effective_month,
                spouse_allowance, child_allowance, parent_care_allowance, disabled_care_allowance,
                maternity_allowance, life_insurance_allowance, health_insurance_allowance,
                parent_health_insurance_allowance, rmf_allowance, ssf_allowance,
                pension_insurance_allowance, thai_esg_allowance, home_loan_interest_allowance,
                education_donation, general_donation, political_donation,
                child_count, child_count_double, disabled_care_count, disability_card_holder,
                parent_care_count, document_reference,
                status, submitted_by_id, submitted_at, on_behalf
            ) VALUES (
                :employeeId, :taxYear, :effectiveMonth,
                :spouseAllowance, :childAllowance, :parentCareAllowance, :disabledCareAllowance,
                :maternityAllowance, :lifeInsuranceAllowance, :healthInsuranceAllowance,
                :parentHealthInsuranceAllowance, :rmfAllowance, :ssfAllowance,
                :pensionInsuranceAllowance, :thaiEsgAllowance, :homeLoanInterestAllowance,
                :educationDonation, :generalDonation, :politicalDonation,
                :childCount, :childCountDouble, :disabledCareCount, :disabilityCardHolder,
                :parentCareCount, :documentReference,
                'PENDING', :submittedById, now(), :onBehalf
            )
            RETURNING declaration_id
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("taxYear", taxYear)
                .addValue("effectiveMonth", effectiveMonth)
                .addValue("spouseAllowance", money(allowances.spouseAllowance()))
                .addValue("childAllowance", money(allowances.childAllowance()))
                .addValue("parentCareAllowance", money(allowances.parentCareAllowance()))
                .addValue("disabledCareAllowance", money(allowances.disabledCareAllowance()))
                .addValue("maternityAllowance", money(allowances.maternityAllowance()))
                .addValue("lifeInsuranceAllowance", money(allowances.lifeInsuranceAllowance()))
                .addValue("healthInsuranceAllowance", money(allowances.healthInsuranceAllowance()))
                .addValue("parentHealthInsuranceAllowance", money(allowances.parentHealthInsuranceAllowance()))
                .addValue("rmfAllowance", money(allowances.rmfAllowance()))
                .addValue("ssfAllowance", money(allowances.ssfAllowance()))
                .addValue("pensionInsuranceAllowance", money(allowances.pensionInsuranceAllowance()))
                .addValue("thaiEsgAllowance", money(allowances.thaiEsgAllowance()))
                .addValue("homeLoanInterestAllowance", money(allowances.homeLoanInterestAllowance()))
                .addValue("educationDonation", money(allowances.educationDonation()))
                .addValue("generalDonation", money(allowances.generalDonation()))
                .addValue("politicalDonation", money(allowances.politicalDonation()))
                .addValue("childCount", allowances.childCount())
                .addValue("childCountDouble", allowances.childCountDouble())
                .addValue("disabledCareCount", allowances.disabledCareCount())
                .addValue("disabilityCardHolder", allowances.disabilityCardHolder())
                .addValue("parentCareCount", allowances.parentCareCount() == null ? 0 : allowances.parentCareCount())
                .addValue("documentReference", documentReference)
                .addValue("submittedById", submittedById)
                .addValue("onBehalf", onBehalf),
            Long.class);
        return id;
    }

    /** Withdraw own PENDING declaration. Caller checks employee ownership BEFORE calling (404, not 403, on a foreign row). */
    public int withdrawPending(long declarationId, long employeeId) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'WITHDRAWN'
             WHERE declaration_id = :id AND employee_id = :employeeId AND status = 'PENDING'
            """,
            Map.of("id", declarationId, "employeeId", employeeId));
    }

    /**
     * Administrative withdraw of ANY pending declaration for an employee/tax-year, used only by
     * {@code createOnBehalf} to clear the way before inserting an HR-authored row — otherwise an
     * employee-submitted PENDING row still sitting in the queue would collide with
     * {@code uq_tad_one_pending_per_employee_year} and the insert would fail with a raw constraint
     * violation instead of a clean outcome.
     */
    public int withdrawAnyPending(long employeeId, int taxYear) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'WITHDRAWN'
             WHERE employee_id = :employeeId AND tax_year = :taxYear AND status = 'PENDING'
            """,
            Map.of("employeeId", employeeId, "taxYear", taxYear));
    }

    /**
     * Marks every OTHER APPROVED declaration for this employee/tax-year as SUPERSEDED by the given
     * (newly-approved) id. MUST run before the conditional approve UPDATE below, in the same
     * transaction — {@code uq_tad_one_approved_per_employee_year} is not deferrable.
     */
    public int supersedeApproved(long employeeId, int taxYear, long supersededById) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'SUPERSEDED', superseded_by_id = :supersededById
             WHERE employee_id = :employeeId AND tax_year = :taxYear AND status = 'APPROVED'
               AND declaration_id <> :supersededById
            """,
            new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("taxYear", taxYear)
                .addValue("supersededById", supersededById));
    }

    /** Conditional PENDING -> APPROVED. Returns 0 if the row was not PENDING (already decided, or missing). */
    public int approve(long declarationId, long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'APPROVED', reviewed_by_id = :reviewedById, reviewed_at = now(),
                   reviewer_note = :reviewerNote
             WHERE declaration_id = :id AND status = 'PENDING'
            """,
            new MapSqlParameterSource()
                .addValue("id", declarationId)
                .addValue("reviewedById", reviewedById)
                .addValue("reviewerNote", reviewerNote));
    }

    /** Conditional PENDING -> REJECTED. {@code reviewerNote} must be non-blank (checked by the service, enforced by chk_tad_rejected_has_reason). */
    public int reject(long declarationId, long reviewedById, String reviewerNote) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET status = 'REJECTED', reviewed_by_id = :reviewedById, reviewed_at = now(),
                   reviewer_note = :reviewerNote
             WHERE declaration_id = :id AND status = 'PENDING'
            """,
            new MapSqlParameterSource()
                .addValue("id", declarationId)
                .addValue("reviewedById", reviewedById)
                .addValue("reviewerNote", reviewerNote));
    }

    /**
     * Conditional flag-the-declaration-applied step. Deliberately runs BEFORE the
     * {@code hr.employee_tax_allowance} promotion in {@link TaxAllowanceDeclarationService#apply}:
     * the {@code applied_at IS NULL} guard in the WHERE clause is what makes a concurrent double-apply
     * 409 on the SECOND caller before it ever reaches the allowance table, with no {@code @Version}
     * needed.
     */
    public int markApplied(long declarationId, long appliedById, int appliedEffectiveMonth) {
        return jdbc.update("""
            UPDATE hr.tax_allowance_declaration
               SET applied_at = now(), applied_by_id = :appliedById,
                   applied_effective_month = :appliedEffectiveMonth
             WHERE declaration_id = :id AND status = 'APPROVED' AND applied_at IS NULL
            """,
            new MapSqlParameterSource()
                .addValue("id", declarationId)
                .addValue("appliedById", appliedById)
                .addValue("appliedEffectiveMonth", appliedEffectiveMonth));
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private TaxAllowanceDeclarationDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        PayrollTaxAllowanceInput allowances = new PayrollTaxAllowanceInput(
            rs.getBigDecimal("spouse_allowance"),
            rs.getBigDecimal("child_allowance"),
            rs.getBigDecimal("parent_care_allowance"),
            rs.getBigDecimal("disabled_care_allowance"),
            rs.getBigDecimal("maternity_allowance"),
            rs.getBigDecimal("life_insurance_allowance"),
            rs.getBigDecimal("health_insurance_allowance"),
            rs.getBigDecimal("parent_health_insurance_allowance"),
            rs.getBigDecimal("rmf_allowance"),
            rs.getBigDecimal("ssf_allowance"),
            rs.getBigDecimal("pension_insurance_allowance"),
            rs.getBigDecimal("thai_esg_allowance"),
            rs.getBigDecimal("home_loan_interest_allowance"),
            rs.getBigDecimal("education_donation"),
            rs.getBigDecimal("general_donation"),
            rs.getBigDecimal("political_donation"),
            rs.getInt("child_count"),
            rs.getInt("child_count_double"),
            rs.getInt("disabled_care_count"),
            rs.getBoolean("disability_card_holder"),
            rs.getInt("parent_care_count")
        );
        return new TaxAllowanceDeclarationDto(
            rs.getLong("declaration_id"),
            rs.getLong("employee_id"),
            rs.getString("employee_code"),
            rs.getString("employee_name"),
            rs.getInt("tax_year"),
            rs.getInt("effective_month"),
            allowances,
            rs.getString("document_reference"),
            TaxAllowanceDeclarationStatus.valueOf(rs.getString("status")),
            nullableLong(rs, "submitted_by_id"),
            rs.getObject("submitted_at", OffsetDateTime.class),
            rs.getBoolean("on_behalf"),
            nullableLong(rs, "reviewed_by_id"),
            rs.getObject("reviewed_at", OffsetDateTime.class),
            rs.getString("reviewer_note"),
            rs.getObject("applied_at", OffsetDateTime.class),
            nullableLong(rs, "applied_by_id"),
            (Integer) rs.getObject("applied_effective_month"),
            rs.getObject("expires_on", LocalDate.class),
            rs.getObject("expired_at", OffsetDateTime.class),
            rs.getObject("reverified_at", OffsetDateTime.class),
            nullableLong(rs, "reverified_by_id"),
            nullableLong(rs, "superseded_by_id")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
