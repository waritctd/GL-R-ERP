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

/** FIFTH Opus review, 2026-07-30 — the SAME-YEAR sibling of the "D2" defect the branch already fixed in the
 * cross-year direction. PayrollService#treatmentsFor synthesizes the company-wide default classification map
 * only when an employee has ZERO stored hr.payroll_component_tax_treatment rows. */
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
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));
    }

    @Test
    void classifyingOneComponentThroughTheMatrixScreenMustNotHaltPayrollForTheOthers() {
        // An employee reached by NO backfill and NO API create -- the shape of every one of the 91 employees
        // db/migration-uat/V900 inserts by raw SQL on a freshly rebuilt uat, where Flyway orders V900 AFTER V96/V100
        // so neither CROSS JOIN backfill ever sees them.
        long employeeId = seedEmployee("PCC-001", "จัดประเภท", "บางส่วน", new BigDecimal("30000.00"));
        when(commissionService.payrollCommissionTotalsByEmployee(MONTH)).thenReturn(Map.of());

        // Non-zero พิเศษ 3 (เบี้ยเลี้ยงประจำ) and พิเศษ 7 (คอมมิชชั่น) -- the classification gate is armed for both.
        // uat V902/V903 seed approved OT and commission for real seeded employees, so a non-zero component is the
        // normal case there, not a contrived one.
        ProcessPayrollRequest request = new ProcessPayrollRequest(MONTH, List.of(inputWithSpecialPays(employeeId)));

        // Precondition: with ZERO stored rows the read-time safety net synthesizes every default and the run succeeds.
        // This is what task 6's polarity fix bought, and it must survive step 2.
        assertThat(payrollRepository.findComponentTaxTreatmentsByEmployee(TAX_YEAR))
            .as("precondition: this employee must have no stored classification at all")
            .doesNotContainKey(employeeId);
        assertThatCode(() -> payrollService.preview(request, hr()))
            .as("precondition: an employee with zero rows in both matrices runs on synthesized defaults")
            .doesNotThrowAnyException();

        // The screen shows every cell blank for this employee -- it renders the RAW stored map, not the synthesized
        // one -- so HR does the one thing the red "ยังไม่จัดประเภท" badge asks and classifies a SINGLE cell.
        // PayrollPage.jsx's save() sends only that diff.
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

    /** The surviving-mutation half of the cliff fix: SPECIAL_PAY_1 is classified REGULAR_REPROJECT, which DIFFERS
     * from its default (EXTRA_CUMULATIVE_ACTUAL) -- the only way the two resolutions produce different,
     * hand-traceable numbers. */
    @Test
    void anExplicitlyStoredTreatmentIsNeverReplacedByTheSynthesizedDefaultForThatComponent() {
        LocalDate january = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("STB-001", "จัดเอง", "ไม่ใช่ค่าเริ่มต้น", new BigDecimal("50000.00"));
        when(commissionService.payrollCommissionTotalsByEmployee(january)).thenReturn(Map.of());

        // HR deliberately classifies พิเศษ 1 as ประจำ (ข้อ 1(4)) -- a fixed monthly cost-of-living allowance, the
        // exact case the EXTRA_CUMULATIVE_ACTUAL default gets WRONG and the matrix screen exists to let HR correct.
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(
                employeeId, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), employeeId);

        assertThat(PayrollRepository.defaultTaxTreatment(PayrollComponent.SPECIAL_PAY_1))
            .as("premise: the stored choice must DIFFER from the default, or this test proves nothing")
            .isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);

        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(january, List.of(inputWithSpecialPays(
                employeeId, new BigDecimal("10000.00"), BigDecimal.ZERO, BigDecimal.ZERO))),
            hr());

        PayrollLineDto line = period.lines().stream()
            .filter(l -> l.employeeId() == employeeId)
            .findFirst()
            .orElseThrow();

        // Hand trace, REGULAR_REPROJECT (HR's stored choice): the regular limb annualises salary AND the พิเศษ 1
        // allowance together -- (50,000 + 10,000) x 12 = 720,000.
        assertThat(line.projectedAnnualIncome())
            .withFailMessage("HR's stored REGULAR_REPROJECT must annualise พิเศษ 1 into the regular "
                + "limb ((50,000 + 10,000) x 12 = 720,000). 600,000 means mergeWithDefaults silently "
                + "substituted the EXTRA_CUMULATIVE_ACTUAL default over HR's explicit choice.")
            .isEqualByComparingTo("720000.00");
        assertThat(line.withholdingTaxCumulativeLimb())
            .withFailMessage("nothing is classified EXTRA_CUMULATIVE_ACTUAL this run, so the "
                + "cumulative limb must be exactly zero -- a non-zero value means the default was "
                + "applied to พิเศษ 1 instead of HR's stored REGULAR_REPROJECT")
            .isEqualByComparingTo("0.00");
        assertThat(line.withholdingTaxRegularLimb())
            .withFailMessage("the whole withholding must come from the regular limb")
            .isEqualByComparingTo(line.withholdingTax());
    }

    // ------------------------------------------------------------------

    private PayrollEmployeeInputRequest inputWithSpecialPays(long employeeId) {
        BigDecimal zero = BigDecimal.ZERO;
        return inputWithSpecialPays(employeeId, zero, new BigDecimal("1500.00"), new BigDecimal("2500.00"));
    }

    private PayrollEmployeeInputRequest inputWithSpecialPays(
        long employeeId, BigDecimal specialPay1, BigDecimal specialPay3, BigDecimal specialPay7
    ) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPay1, zero, specialPay3, zero, zero, zero, specialPay7, zero, zero,
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
