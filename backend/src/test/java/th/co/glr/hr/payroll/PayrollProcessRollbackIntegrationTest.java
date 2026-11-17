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

/** Is PayrollService#process atomic? A torn process is the worst state this system can reach: a PROCESSED
 * period whose input draft was destroyed and whose audit row was never written is a payroll run nobody can
 * reconstruct or explain. */
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

        // Without this, the rollback test above would pass just as happily if process() had been broken into writing
        // nothing at all.
        assertThat(processedPeriods()).as("the month really does process when nothing fails").isOne();
        assertThat(payrollLines()).isOne();
        assertThat(payrollRepository.findInputDrafts(MONTH))
            .as("and the draft really is cleared on the success path — the behaviour "
                + "PayrollInputDraftClearedOnProcessIntegrationTest pins, unchanged by the rollback")
            .isEmpty();
    }

    /** The vacuity control. Identical injected failure, but the RAW un-proxied service — how every other
     * integration test in this suite drives its services. With no proxy @Transactional is inert, so each write
     * auto-commits on its own and the failure strands the rest. */
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

    /** Same wiring as PayrollInputDraftClearedOnProcessIntegrationTest: real PayrollRepository, real
     * PayrollCalculator, real LeaveRepository and a real DeductionObligationService on the same jdbc, so every
     * write process performs is a real write against real Postgres. */
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

    /** Fails process's LAST write. auditPayrollAccess immediately above it only writes to a logger, so this is
     * genuinely the final DB write of the method — everything the assertions look at has already landed when it
     * throws. */
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
