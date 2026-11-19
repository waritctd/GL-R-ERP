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
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** D1 (fourth reachability audit, 2026-07-30): customer_return_deduction stopped round-tripping what HR
 * actually typed, a REGRESSION against origin/main -- see V101__payroll_customer_return_requested_amount.sql
 * for the full failure narrative. */
class PayrollCustomerReturnRoundTripIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate MONTH = LocalDate.of(2026, 3, 1);

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

    /** The entered amount must survive a reload even though it is netted pre-tax and the post-tax bookkeeping
     * figure stays zero. Also proves the unearned path nets pre-tax EXACTLY ONCE and does not additionally deduct
     * post-tax. */
    @Test
    void enteredAmountSurvivesAReloadAndTheUnearnedPathNetsPreTaxExactlyOnce() {
        long employeeId = seedEmployee("CRR-001", "คืนสินค้า", "ทดสอบ", new BigDecimal("30000.00"));
        when(commissionService.payrollCommissionTotalsByEmployee(MONTH))
            .thenReturn(Map.of(employeeId, new BigDecimal("10000.00")));

        PayrollEmployeeInputRequest input = customerReturnInputOf(employeeId, new BigDecimal("3000.00"), false);
        payrollService.process(new ProcessPayrollRequest(MONTH, List.of(input)), hr());

        // Read back from the DB, not the in-memory process() result -- the round trip is the point.
        PayrollLineDto reloaded = reloadedLineFor(employeeId);

        assertThat(reloaded.customerReturnRequested())
            .as("the amount HR typed must round-trip verbatim, even though it was netted pre-tax "
                + "rather than persisted as a post-tax deduction")
            .isEqualByComparingTo("3000.00");
        assertThat(reloaded.customerReturnDeduction())
            .as("the post-tax bookkeeping figure stays 0 in the unearned path -- unchanged by this "
                + "fix, and must not ALSO carry the entered amount")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reloaded.commissionPay())
            .as("commission must be netted pre-tax exactly once: 10,000 - 3,000 = 7,000, not "
                + "double-deducted and not left at the gross 10,000")
            .isEqualByComparingTo("7000.00");
    }

    // ------------------------------------------------------------------

    private PayrollLineDto reloadedLineFor(long employeeId) {
        long periodId = payrollRepository.findPeriodByMonth(MONTH)
            .map(PayrollPeriodDto::id)
            .orElseThrow(() -> new AssertionError("no period persisted for " + MONTH));
        return payrollRepository.findLines(periodId).stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no persisted line for employee " + employeeId));
    }

    private PayrollEmployeeInputRequest customerReturnInputOf(
        long employeeId, BigDecimal customerReturnDeduction, boolean customerReturnAlreadyEarned
    ) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            zero, zero, zero, zero, zero, zero, zero, zero, zero, // specialPay1-9
            zero, // nonTaxableIncome
            zero, // unpaidLeaveDays
            zero, // studentLoanDeduction
            zero, // legalExecutionDeduction
            zero, // otherPostTaxDeductions
            zero, zero, zero, zero, zero, // spouse..maternity
            zero, zero, zero, zero, zero, // life..ssf
            zero, zero, zero, zero, zero, zero, // pension..political
            zero, // warningLetterDeduction
            customerReturnDeduction,
            zero, // otherPretaxDeduction
            null, // withholdingTaxOverride
            zero, // mealAllowance
            zero, // perDiemExempt
            zero, // perDiemTaxable
            null, // perDiemBasis
            zero, // bonusPay
            zero, // otherOneOffPay
            customerReturnAlreadyEarned,
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
