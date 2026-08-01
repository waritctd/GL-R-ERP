package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * FIFTH Opus review, 2026-07-30 — the SAME-YEAR sibling of the "D2" defect the branch already fixed
 * in the cross-year direction.
 *
 * <p>{@code PayrollService#treatmentsFor} synthesizes the company-wide default classification map
 * only when an employee has ZERO stored {@code hr.payroll_component_tax_treatment} rows. The instant
 * ONE row exists it returns the stored map <em>verbatim</em>:
 *
 * <pre>
 * if (stored != null || !hasBeenOnboarded) { return stored == null ? Map.of() : stored; }
 * </pre>
 *
 * <p>The matrix screen ({@code PayrollPage.jsx}'s {@code TaxTreatmentMatrixSection}) saves only the
 * EDITED CELL — {@code save()} builds {@code changes} from {@code edits}, a diff, never the full
 * resolved matrix. And {@code componentTaxTreatmentsSnapshot} renders {@code
 * byEmployee.getOrDefault(id, Map.of())}, the RAW stored map, so on any population the V96/V100
 * backfills never reached (a freshly rebuilt uat's 91 raw-SQL {@code V900} employees, {@code
 * db/migration-demo}'s seeds, any bulk import) every cell renders blank and the section's own badge
 * reads "ยังไม่จัดประเภท N รายการ" — while payroll runs perfectly well on the synthesized defaults.
 *
 * <p>So HR is shown a red unclassified badge, does exactly what it asks — classifies ONE component
 * for ONE employee — and that single save takes the employee from "zero rows, defaults synthesized,
 * run succeeds" to "one row, fifteen components unclassified, {@code
 * PayrollCalculator#requireEveryNonZeroComponentClassified} 409s". Because {@code preview()} maps
 * over every active employee in one stream, the 409 aborts the WHOLE period for EVERY employee, not
 * just the edited one: one dropdown edit bricks the payroll page.
 *
 * <p>Written wrong-way-round and driven through the real {@link PayrollService} + real Postgres (the
 * real HTTP-surface method {@code upsertComponentTaxTreatments}, which is exactly what the screen's
 * save calls) — a mocked repository would "pass" regardless of what the resolution layer does.
 */
class PayrollPartialClassificationCliffReviewTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate MONTH = LocalDate.of(2026, 3, 1);
    private static final int TAX_YEAR = 2026;

    private PayrollRepository payrollRepository;
    private PayrollService payrollService;
    private CommissionService commissionService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        commissionService = mock(CommissionService.class);
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties());
    }

    @Test
    void classifyingOneComponentThroughTheMatrixScreenMustNotHaltPayrollForTheOthers() {
        // An employee reached by NO backfill and NO API create -- the shape of every one of the 91
        // employees db/migration-uat/V900 inserts by raw SQL on a freshly rebuilt uat, where Flyway
        // orders V900 AFTER V96/V100 so neither CROSS JOIN backfill ever sees them.
        long employeeId = seedEmployee("PCC-001", "จัดประเภท", "บางส่วน", new BigDecimal("30000.00"));
        when(commissionService.payrollCommissionTotalsByEmployee(MONTH)).thenReturn(Map.of());

        // Non-zero พิเศษ 3 (เบี้ยเลี้ยงประจำ) and พิเศษ 7 (คอมมิชชั่น) -- the classification gate is
        // armed for both. uat V902/V903 seed approved OT and commission for real seeded employees,
        // so a non-zero component is the normal case there, not a contrived one.
        ProcessPayrollRequest request = new ProcessPayrollRequest(MONTH, List.of(inputWithSpecialPays(employeeId)));

        // Precondition: with ZERO stored rows the read-time safety net synthesizes every default and
        // the run succeeds. This is what task 6's polarity fix bought, and it must survive step 2.
        assertThat(payrollRepository.findComponentTaxTreatmentsByEmployee(TAX_YEAR))
            .as("precondition: this employee must have no stored classification at all")
            .doesNotContainKey(employeeId);
        assertThatCode(() -> payrollService.preview(request, hr()))
            .as("precondition: an employee with zero rows in both matrices runs on synthesized defaults")
            .doesNotThrowAnyException();

        // The screen shows every cell blank for this employee -- it renders the RAW stored map, not
        // the synthesized one -- so HR does the one thing the red "ยังไม่จัดประเภท" badge asks and
        // classifies a SINGLE cell. PayrollPage.jsx's save() sends only that diff.
        payrollService.upsertComponentTaxTreatments(
            TAX_YEAR,
            List.of(new ComponentTaxTreatmentUpsertRequest(
                employeeId, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT)),
            hr());

        assertThatCode(() -> payrollService.preview(request, hr()))
            .as("classifying ONE component must not strand the other fifteen: before this save the "
                + "run succeeded on synthesized defaults, and a single-cell edit through the real "
                + "matrix-screen writer must not 409 the entire period for every employee in it")
            .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------

    private PayrollEmployeeInputRequest inputWithSpecialPays(long employeeId) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            zero, zero, new BigDecimal("1500.00"), zero, zero, zero, new BigDecimal("2500.00"), zero, zero,
            zero, // nonTaxableIncome
            zero, // unpaidLeaveDays
            zero, // studentLoanDeduction
            zero, // legalExecutionDeduction
            zero, // otherPostTaxDeductions
            zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero, zero,
            zero, // warningLetterDeduction
            zero, // customerReturnDeduction
            zero, // otherPretaxDeduction
            null, // withholdingTaxOverride
            zero, // mealAllowance
            zero, // perDiemExempt
            zero, // perDiemTaxable
            null, // perDiemBasis
            zero, // bonusPay
            zero, // otherOneOffPay
            false, // customerReturnAlreadyEarned
            null, // garnishmentType
            null // parentCareCount
        );
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
    }
}
