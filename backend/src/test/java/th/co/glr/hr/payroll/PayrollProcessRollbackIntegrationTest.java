package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
 * Is {@link PayrollService#process} atomic? It is the highest-consequence write in the codebase —
 * it is what turns a preview into money owed — and it mutates <b>three different tables in three
 * different directions</b> before it returns:
 *
 * <ol>
 *   <li>INSERT/UPDATE {@code hr.payroll_period} to {@code PROCESSED}, and INSERT one
 *       {@code hr.payroll_line} per employee ({@link PayrollRepository#saveProcessedPeriod},
 *       itself a delete-then-insert),</li>
 *   <li>UPDATE the rows that fed it — {@code hr.leave_payroll_correction} resolved against this
 *       period, {@code hr.special_money_request.included_period_id} attributed to it, deduction
 *       remittances recorded,</li>
 *   <li>DELETE {@code hr.payroll_input_draft} for the month, so a stale draft can never resurrect
 *       over the processed values.</li>
 * </ol>
 *
 * <p>A torn {@code process} is the worst state this system can reach: a {@code PROCESSED} period
 * whose input draft was destroyed and whose audit row was never written is a payroll run nobody can
 * reconstruct or explain. So the question this class answers is narrow and worth answering
 * precisely — <b>when the transaction rolls back, does every one of those three go back too?</b>
 *
 * <p><b>Why this needs the proxy, and why the existing payroll suite cannot answer it.</b>
 * {@link AbstractPostgresIntegrationTest} runs with no Spring context: every integration test here
 * hand-wires its services with {@code new}, so there is no AOP proxy and a bare
 * {@code @Transactional} does nothing whatsoever. The 188 {@code @Transactional} methods in this
 * backend are, in this suite, 188 methods with no transaction. A rollback test written the obvious
 * way would roll nothing back and pass for reasons unrelated to the code under test. Both
 * assertions below therefore run through {@link AbstractPostgresIntegrationTest#transactional}
 * (PR #695), which builds a REAL transactional proxy from
 * {@link org.springframework.transaction.annotation.AnnotationTransactionAttributeSource} — so the
 * transaction genuinely comes from {@code PayrollService#process}'s own annotation. Delete that
 * annotation and {@link #processRollsBackThePeriodTheLinesAndTheDraftDeleteTogether()} goes red.
 *
 * <p>{@link #processWithoutTheProxyLeavesTornPayrollState()} is the vacuity control, and it is also
 * the clearest statement of what the annotation is buying: identical injected failure, raw
 * un-proxied service, and the result is a PROCESSED period with payroll lines, an input draft
 * already deleted, and no audit row — every one of the three writes above committed independently.
 *
 * <p>The Mockito {@link AuditService} is a <b>fault injector</b>, not the system under test.
 * {@code auditService.record} is the last write {@code process} performs (the
 * {@code auditPayrollAccess} call just above it only writes to a logger), so by the time it throws,
 * every period row, every payroll line and the draft deletion have really happened against real
 * Postgres. Everything asserted below is real state, read back with fresh queries.
 *
 * <p>Computes nothing about payroll itself: no figure is asserted here, only whether rows exist.
 */
class PayrollProcessRollbackIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate MONTH = LocalDate.of(2026, 8, 1);

    private PayrollRepository payrollRepository;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
    }

    @Test
    void processRollsBackThePeriodTheLinesAndTheDraftDeleteTogether() {
        long employeeId = seedEmployee("PR-RB-001", "ย้อนกลับ", "ทั้งชุด");
        payrollRepository.saveInputDrafts(MONTH, List.of(input(employeeId, "1234.00")), 1L);
        PayrollService service = transactional(payrollService(failingAudit()));

        assertThatThrownBy(() -> service.process(
                new ProcessPayrollRequest(MONTH, List.of(input(employeeId, "1234.00"))), hr()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(processedPeriods())
            .as("the audit write failed AFTER saveProcessedPeriod had already inserted the period "
                + "and flipped it to PROCESSED; a rollback must take that row with it, or the month "
                + "reads as paid with no audit trail explaining who ran it")
            .isZero();
        assertThat(payrollLines())
            .as("every hr.payroll_line for the month — one per employee, each carrying a net_pay — "
                + "must roll back with the period that owns them")
            .isZero();
        assertThat(payrollRepository.findInputDrafts(MONTH))
            .as("deleteInputDrafts ran before the failure. HR's unsaved input for the month is not "
                + "recoverable from anywhere else, so a rollback that leaves it deleted destroys "
                + "data for a payroll run that never happened")
            .hasSize(1);
    }

    @Test
    void processCommittingStillWritesThePeriodTheLinesAndClearsTheDraft() {
        long employeeId = seedEmployee("PR-RB-002", "สำเร็จ", "ครบถ้วน");
        payrollRepository.saveInputDrafts(MONTH, List.of(input(employeeId, "1234.00")), 1L);
        PayrollService service = transactional(payrollService(mock(AuditService.class)));

        service.process(new ProcessPayrollRequest(MONTH, List.of(input(employeeId, "1234.00"))), hr());

        // Without this, the rollback test above would pass just as happily if process() had been
        // broken into writing nothing at all.
        assertThat(processedPeriods()).as("the month really does process when nothing fails").isOne();
        assertThat(payrollLines()).isOne();
        assertThat(payrollRepository.findInputDrafts(MONTH))
            .as("and the draft really is cleared on the success path — the behaviour "
                + "PayrollInputDraftClearedOnProcessIntegrationTest pins, unchanged by the rollback")
            .isEmpty();
    }

    /**
     * The vacuity control. Identical injected failure, but the RAW un-proxied service — how every
     * other integration test in this suite drives its services. With no proxy {@code @Transactional}
     * is inert, so each write auto-commits on its own and the failure strands the rest.
     *
     * <p>This asserts today's harness defect, not a desired outcome. Its job is to prove the test
     * above is discriminating: if this one ever starts finding zero processed periods, the proxy is
     * no longer what makes the difference and the rollback evidence above has quietly become
     * vacuous. Delete it the day this harness runs inside a real Spring context with proxied beans.
     */
    @Test
    void processWithoutTheProxyLeavesTornPayrollState() {
        long employeeId = seedEmployee("PR-RB-003", "ไม่มีพรอกซี", "สภาพฉีกขาด");
        payrollRepository.saveInputDrafts(MONTH, List.of(input(employeeId, "1234.00")), 1L);
        PayrollService rawService = payrollService(failingAudit());

        assertThatThrownBy(() -> rawService.process(
                new ProcessPayrollRequest(MONTH, List.of(input(employeeId, "1234.00"))), hr()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(processedPeriods())
            .as("no proxy, no transaction: the period insert auto-committed and survives the failure")
            .isOne();
        assertThat(payrollLines()).isOne();
        assertThat(payrollRepository.findInputDrafts(MONTH))
            .as("and the draft deletion auto-committed too — a PROCESSED month, no audit row, and "
                + "HR's input gone. This is the control that proves the proxied test above is not "
                + "passing trivially")
            .isEmpty();
    }

    // ── wiring ────────────────────────────────────────────────────────────────────────────

    /**
     * Same wiring as {@link PayrollInputDraftClearedOnProcessIntegrationTest}: real
     * {@link PayrollRepository}, real {@link PayrollCalculator}, real {@link LeaveRepository} and a
     * real {@code DeductionObligationService} on the same {@code jdbc}, so every write
     * {@code process} performs is a real write against real Postgres. Only the collaborators that
     * do not write payroll state ({@code CommissionService}, {@code PayslipRenderer}) are mocked.
     */
    private PayrollService payrollService(AuditService audit) {
        CommissionService commissionService = mock(CommissionService.class);
        when(commissionService.payrollCommissionTotalsByEmployee(any())).thenReturn(Map.of());
        return new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            audit,
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

    /**
     * Fails {@code process}'s LAST write. {@code auditPayrollAccess} immediately above it only
     * writes to a logger, so this is genuinely the final DB write of the method — everything the
     * assertions look at has already landed when it throws.
     */
    private AuditService failingAudit() {
        AuditService audit = mock(AuditService.class);
        doThrow(new IllegalStateException("injected failure after the payroll period was written"))
            .when(audit).record(any(), any(), any(), any(), any(), any());
        return audit;
    }

    // ── assertion helpers ─────────────────────────────────────────────────────────────────

    private int processedPeriods() {
        return count("SELECT COUNT(*) FROM hr.payroll_period WHERE payroll_month = :m AND status = 'PROCESSED'");
    }

    private int payrollLines() {
        return count("""
            SELECT COUNT(*) FROM hr.payroll_line l
              JOIN hr.payroll_period p ON p.period_id = l.period_id
             WHERE p.payroll_month = :m
            """);
    }

    private int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Map.of("m", MONTH), Integer.class);
        return count == null ? 0 : count;
    }

    // ── fixture ───────────────────────────────────────────────────────────────────────────

    private PayrollEmployeeInputRequest input(long employeeId, String specialPay1) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            new BigDecimal(specialPay1), zero, zero, zero, zero, zero, zero, zero, zero,
            zero, zero, zero, zero, zero,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            zero, zero, zero, null,
            zero, zero, zero, null, zero, zero,
            false, null, null, null);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, 30000, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh),
            Long.class);
    }
}
