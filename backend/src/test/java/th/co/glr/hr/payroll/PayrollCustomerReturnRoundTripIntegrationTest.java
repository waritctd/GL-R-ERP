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

/**
 * D1 (fourth reachability audit, 2026-07-30): {@code customer_return_deduction} stopped
 * round-tripping what HR actually typed, a REGRESSION against origin/main -- see
 * {@code V102__payroll_customer_return_requested_amount.sql} for the full failure narrative.
 *
 * <p>On origin/main the legacy engine passed the field through verbatim, so the stored figure
 * always survived a reload. This branch's classified engine gave the column a second job -- 0 in
 * the "not yet earned" path (netted pre-tax out of commission instead), the full amount in the
 * "already earned" path (a post-tax clawback) -- and only ONE persisted column tried to carry both
 * meanings. In the unearned path (the only reachable state before this branch), the entered amount
 * never survived a reload: {@code PayrollPage.jsx}'s {@code adjustmentFromLine} read {@code
 * customer_return_deduction} back as 0, silently wiping the form field AND the checkbox that gates
 * on it being {@code > 0}, and a reprocess of the same month silently stopped re-applying the
 * pre-tax netting -- net pay would change with no HR action.
 *
 * <p>Fixed with a new column, {@code customer_return_requested}, that always echoes the raw
 * entered amount regardless of the earned flag, leaving {@code customer_return_deduction}'s
 * existing post-tax-bookkeeping meaning untouched. Driven through the real {@link PayrollService}
 * and real Postgres, not Mockito: the whole point under test is that a value survives an actual
 * INSERT/SELECT round trip through {@link PayrollRepository}, which a mocked repository would
 * "pass" regardless of what the SQL actually persists.
 */
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
            new th.co.glr.hr.config.AppProperties());
    }

    /**
     * (a) The entered amount must survive a reload even though it is netted pre-tax and the
     * post-tax bookkeeping figure stays zero. (c) Also proves the unearned path nets pre-tax
     * EXACTLY ONCE and does not additionally deduct post-tax -- commissionPay must reflect the
     * netting exactly once (10,000 - 3,000 = 7,000), and customerReturnDeduction (the post-tax
     * figure) must be zero, never also 3,000.
     */
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

    /**
     * (b) The silent-net-pay-change failure mode itself: reprocessing the same month, with the
     * second run's input hydrated from what the FIRST run persisted (exactly what {@code
     * PayrollPage.jsx}'s {@code adjustmentFromLine} does on a reload), must not change net pay.
     * Before this fix, hydrating from {@code customerReturnDeduction} (which reads back 0 in the
     * unearned path) would submit a second run with no return amount at all, silently un-netting
     * the commission and raising net pay with no HR action.
     */
    @Test
    void reprocessingWithInputsHydratedFromThePersistedLineProducesIdenticalNetPayBothTimes() {
        long employeeId = seedEmployee("CRR-002", "รอบสอง", "ทดสอบ", new BigDecimal("30000.00"));
        when(commissionService.payrollCommissionTotalsByEmployee(MONTH))
            .thenReturn(Map.of(employeeId, new BigDecimal("10000.00")));

        PayrollEmployeeInputRequest firstInput = customerReturnInputOf(employeeId, new BigDecimal("3000.00"), false);
        PayrollPeriodDto firstRun = payrollService.process(new ProcessPayrollRequest(MONTH, List.of(firstInput)), hr());
        BigDecimal firstNetPay = lineFor(firstRun, employeeId).netPay();

        // Simulate a reload: hydrate the SECOND run's request from the persisted line's
        // customerReturnRequested, exactly as the frontend now does.
        PayrollLineDto persistedAfterFirstRun = reloadedLineFor(employeeId);
        PayrollEmployeeInputRequest secondInput = customerReturnInputOf(
            employeeId, persistedAfterFirstRun.customerReturnRequested(), false);
        PayrollPeriodDto secondRun = payrollService.process(new ProcessPayrollRequest(MONTH, List.of(secondInput)), hr());
        BigDecimal secondNetPay = lineFor(secondRun, employeeId).netPay();

        assertThat(secondNetPay)
            .as("reprocessing the same month, with inputs hydrated from what was persisted, must "
                + "not silently change net pay -- the exact failure mode when the entered amount "
                + "does not round-trip and a reload/reprocess drops the pre-tax netting")
            .isEqualByComparingTo(firstNetPay);
    }

    /**
     * The already-earned (post-tax clawback) path is the control case: it must be UNCHANGED by
     * this fix. customerReturnDeduction (post-tax) carries the full amount, customerReturnRequested
     * carries the identical figure (they agree in this path, unlike the unearned path above), and
     * commissionPay is NOT netted -- the amount only ever reduces net pay once, post-tax.
     */
    @Test
    void alreadyEarnedPathStillDeductsPostTaxOnlyAndDoesNotNetCommission() {
        long employeeId = seedEmployee("CRR-003", "รับแล้ว", "ทดสอบ", new BigDecimal("30000.00"));
        when(commissionService.payrollCommissionTotalsByEmployee(MONTH))
            .thenReturn(Map.of(employeeId, new BigDecimal("10000.00")));

        PayrollEmployeeInputRequest input = customerReturnInputOf(employeeId, new BigDecimal("3000.00"), true);
        payrollService.process(new ProcessPayrollRequest(MONTH, List.of(input)), hr());

        PayrollLineDto reloaded = reloadedLineFor(employeeId);

        assertThat(reloaded.commissionPay())
            .as("already-earned path must NOT net the commission -- it was already paid and earned")
            .isEqualByComparingTo("10000.00");
        assertThat(reloaded.customerReturnDeduction())
            .as("already-earned path applies the full amount as a post-tax clawback")
            .isEqualByComparingTo("3000.00");
        assertThat(reloaded.customerReturnRequested())
            .as("requested and applied agree in the already-earned path")
            .isEqualByComparingTo("3000.00");
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

    private PayrollLineDto lineFor(PayrollPeriodDto period, long employeeId) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no line for employee " + employeeId));
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
