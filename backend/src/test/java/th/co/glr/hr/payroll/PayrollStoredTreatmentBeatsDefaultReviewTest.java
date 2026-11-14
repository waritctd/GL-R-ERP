package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
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

/** SIXTH Opus review, 2026-07-30 — the surviving-mutation half of the "partial classification cliff" fix.
 * SPECIAL_PAY_1 is classified REGULAR_REPROJECT, which DIFFERS from its default (EXTRA_CUMULATIVE_ACTUAL) —
 * the only way the two resolutions produce different, hand-traceable numbers. */
class PayrollStoredTreatmentBeatsDefaultReviewTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final int TAX_YEAR = 2026;

    private PayrollService payrollService;
    private CommissionService commissionService;

    @BeforeEach
    void wireRealCollaborators() {
        commissionService = mock(CommissionService.class);
        payrollService = new PayrollService(
            new PayrollRepository(jdbc),
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
    void anExplicitlyStoredTreatmentIsNeverReplacedByTheSynthesizedDefaultForThatComponent() {
        long employeeId = seedEmployee("STB-001", "จัดเอง", "ไม่ใช่ค่าเริ่มต้น", new BigDecimal("50000.00"));
        when(commissionService.payrollCommissionTotalsByEmployee(JANUARY)).thenReturn(Map.of());

        // HR deliberately classifies พิเศษ 1 as ประจำ (ข้อ 1(4)) -- a fixed monthly cost-of-living allowance, the
        // exact case handoff "Finding 3" says the EXTRA_CUMULATIVE_ACTUAL default gets WRONG and the matrix screen
        // exists to let HR correct. It must actually take effect.
        new PayrollRepository(jdbc).upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(
                employeeId, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), employeeId);

        assertThat(PayrollRepository.defaultTaxTreatment(PayrollComponent.SPECIAL_PAY_1))
            .as("premise: the stored choice must DIFFER from the default, or this test proves nothing")
            .isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);

        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(JANUARY, List.of(specialPay1Of(employeeId, new BigDecimal("10000.00")))),
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

    /** SIXTH Opus review, 2026-07-30 — the coordinator's open question, answered with evidence rather than
     * reasoning: would a FRESHLY REBUILT uat run payroll for all of its seeded employees?
     * PayrollPartialClassificationCliffReviewTest proves this for ONE such employee. */
    @Test
    void afreshlyRebuiltUatPopulationStillRunsAfterHrClassifiesASingleCellOnOneEmployee() {
        long untouchedA = seedEmployee("UAT-901", "ก", "ยูเอที", new BigDecimal("30000.00"));
        long editedByHr = seedEmployee("UAT-902", "ข", "ยูเอที", new BigDecimal("28000.00"));
        long untouchedB = seedEmployee("UAT-903", "ค", "ยูเอที", new BigDecimal("32000.00"));
        when(commissionService.payrollCommissionTotalsByEmployee(JANUARY)).thenReturn(Map.of());

        // TWO non-zero components each, and HR classifies only ONE of them below.
        ProcessPayrollRequest request = new ProcessPayrollRequest(JANUARY, List.of(
            specialPaysOf(untouchedA, new BigDecimal("1500.00"), new BigDecimal("900.00")),
            specialPaysOf(editedByHr, new BigDecimal("2000.00"), new BigDecimal("1100.00")),
            specialPaysOf(untouchedB, new BigDecimal("2500.00"), new BigDecimal("1300.00"))));

        // HR opens the matrix on a fresh uat and classifies ONE cell for ONE employee. PayrollPage's save() sends only
        // that diff, through this exact service method.
        payrollService.upsertComponentTaxTreatments(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(
                editedByHr, PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), hr());

        PayrollPeriodDto period = payrollService.preview(request, hr());

        for (long employeeId : List.of(untouchedA, editedByHr, untouchedB)) {
            PayrollLineDto line = period.lines().stream()
                .filter(l -> l.employeeId() == employeeId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
            assertThat(line.socialSecurity())
                .withFailMessage("employee %d has ZERO stored SSO-inclusion rows (raw-SQL insert, no "
                    + "backfill, no API create) -- the read-time default must give a real wage base, "
                    + "not silently compute a ฿0.00 contribution", employeeId)
                .isGreaterThan(BigDecimal.ZERO);
        }
    }

    // ------------------------------------------------------------------

    private PayrollEmployeeInputRequest specialPay1Of(long employeeId, BigDecimal specialPay1) {
        return specialPaysOf(employeeId, specialPay1, BigDecimal.ZERO);
    }

    private PayrollEmployeeInputRequest specialPaysOf(
        long employeeId, BigDecimal specialPay1, BigDecimal specialPay3
    ) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            specialPay1, zero, specialPay3, zero, zero, zero, zero, zero, zero, // specialPay1-9
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
