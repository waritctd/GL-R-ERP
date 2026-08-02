package th.co.glr.hr.payroll.obligation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.ProcessPayrollRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationOverrideRequest;
import th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallDtos.PayrollDeductionShortfallDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Real-Postgres coverage of issue #376's shortfall tracking: generalising #373's requested/actual/
 * shortfall triple to the ALREADY-enforced ป.วิ.พ. ม.302 garnishment cap. Every case here reprocesses
 * through the real {@link PayrollService#process}, exactly like {@link
 * DeductionObligationPayrollEngineIntegrationTest} -- a mocked repository cannot prove the ledger
 * self-corrects on a real re-run.
 *
 * <p><b>This is pure record-keeping.</b> No test here asserts a DIFFERENT {@code
 * legalExecutionDeduction} than {@link DeductionObligationPayrollEngineIntegrationTest} already
 * asserts for the equivalent scenario -- the shortfall ledger only ever reads back what {@code
 * PayrollCalculator} already computed and {@code hr.payroll_line} already persisted.
 */
class PayrollDeductionShortfallIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;
    private DeductionObligationRepository obligationRepository;
    private DeductionObligationService obligationService;
    private PayrollDeductionShortfallRepository shortfallRepository;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        obligationRepository = new DeductionObligationRepository(jdbc);
        shortfallRepository = new PayrollDeductionShortfallRepository(jdbc);
        obligationService = new DeductionObligationService(
            obligationRepository, mock(EmployeeRepository.class), mock(AuditService.class), shortfallRepository);
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class));
        payrollService = new PayrollService(
            payrollRepository,
            new th.co.glr.hr.payroll.PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(th.co.glr.hr.payroll.PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties(),
            obligationService);
    }

    /**
     * The obligation's own instructed monthly figure (฿20,000) is well above what ป.วิ.พ. ม.302's
     * default SALARY rule (30% of gross taxable income, ~฿30,000 salary) allows -- the exact
     * scenario {@link DeductionObligationPayrollEngineIntegrationTest
     * #theStatutoryGarnishmentCapCanBindTighterThanTheObligationsOwnMonthlyFigure} already proves
     * changes no persisted amount. This test additionally proves the shortfall ledger records it.
     */
    @Test
    void aStatutoryCapShortfallIsRecordedWithRequestedActualAndAReason() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("SHORTFALL-STATCAP", new BigDecimal("30000.00"));
        obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("20000.00"), null,
            "LED-SHORTFALL-STATCAP", month, null), employeeId);

        PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(period, employeeId);
        assertThat(line.legalExecutionDeduction()).isLessThan(new BigDecimal("20000.00"));

        List<PayrollDeductionShortfallDto> rows = shortfallRepository.findAll(employeeId, PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT);
        assertThat(rows).hasSize(1);
        PayrollDeductionShortfallDto row = rows.get(0);
        assertThat(row.requestedAmount()).isEqualByComparingTo("20000.00");
        assertThat(row.actualAmount()).isEqualByComparingTo(line.legalExecutionDeduction());
        assertThat(row.shortfallAmount()).isEqualByComparingTo(
            new BigDecimal("20000.00").subtract(line.legalExecutionDeduction()));
        assertThat(row.shortfallReason()).contains("ป.วิ.พ. ม.302").contains("SALARY");
        assertThat(row.payrollMonth()).isEqualTo(month);
    }

    /**
     * D-376-1 regression (Opus review): reproduces the reviewer's exact probe. An employee has TWO
     * LEGAL_EXECUTION obligations -- an OLDER-by-insertion-order but LATER-start-date one that is
     * already COMPLETED with an HR override (monthly ฿5,000), and a NEWER-by-insertion-order but
     * EARLIER-start-date one that is still ACTIVE and genuinely drives this run (monthly ฿100, total
     * ฿100). Processing this month satisfies the ACTIVE obligation's total in full (deducts exactly
     * its own requested ฿100) and, as a side effect, flips ITS OWN status to COMPLETED.
     *
     * <p>Before the fix, {@code recordGarnishmentShortfalls} re-resolved "requested" via {@code
     * resolveMonthlyAmount} AFTER that flip. With both obligations now COMPLETED,
     * {@code findDrivingObligation}'s {@code ORDER BY (status='ACTIVE') DESC, start_date DESC} then
     * picked the OTHER obligation (later start_date, override set) -- ฿5,000 -- and fabricated a
     * ฿4,900 shortfall against a court order that was satisfied in full at ฿100. A single-obligation
     * test cannot reach this: the flip only matters when a SECOND obligation exists to be picked up
     * by the changed ordering.
     *
     * <p>After the fix, "requested" is read from {@code PayrollLineDto#legalExecutionRequested()},
     * frozen at the point {@code calculateLine} actually resolved it (the ACTIVE obligation, ฿100,
     * BEFORE any status flip) -- so requested == actual == ฿100 and no shortfall row is written.
     */
    @Test
    void aSecondObligationCompletingDuringThisRunDoesNotFabricateAShortfallOnAnUnrelatedObligation() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("SHORTFALL-TWOOBLIG", new BigDecimal("30000.00"));

        // The LATER-start-date obligation, already fully paid off and COMPLETED before this run even
        // starts, with an HR override recorded (so #resolveMonthlyAmount would return its full ฿5,000
        // monthly figure if it were ever picked as driving).
        long olderCompletedObligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("5000.00"),
            new BigDecimal("60000.00"), "LED-TWOOBLIG-COMPLETED", LocalDate.of(2025, 6, 1), null), employeeId);
        obligationRepository.upsertRemittance(olderCompletedObligationId, LocalDate.of(2025, 12, 1), new BigDecimal("60000.00"), null);
        obligationRepository.applyCompletionTransition(olderCompletedObligationId, true);
        obligationService.overrideContinue(olderCompletedObligationId,
            new DeductionObligationOverrideRequest("reviewer probe D-376-1"), hr());
        assertThat(obligationRepository.findById(olderCompletedObligationId).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.COMPLETED);

        // The EARLIER-start-date obligation, still ACTIVE going into this run -- the one that
        // actually drives it (ORDER BY (status='ACTIVE') DESC ranks it first regardless of start_date).
        long activeObligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("100.00"),
            new BigDecimal("100.00"), "LED-TWOOBLIG-ACTIVE", LocalDate.of(2025, 1, 1), null), employeeId);

        PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(period, employeeId);

        // The court order behind the ACTIVE obligation was satisfied in full at its own requested
        // figure -- no statutory cap came anywhere near binding (30% of ~30,000 is ~9,000).
        assertThat(line.legalExecutionDeduction()).isEqualByComparingTo("100.00");
        // This run's own remittance completed the ACTIVE obligation -- the state transition that
        // exposed the bug.
        assertThat(obligationRepository.findById(activeObligationId).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.COMPLETED);
        // The OTHER (unrelated, already-completed) obligation must be completely untouched by this run.
        assertThat(obligationRepository.findById(olderCompletedObligationId).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.COMPLETED);

        // THE regression assertion: no phantom ฿4,900 shortfall against a court order that was paid
        // in full. Before the fix this returned one row: requested=5000.00, actual=100.00,
        // shortfall=4900.00.
        assertThat(shortfallRepository.findAll(employeeId, PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT)).isEmpty();
    }

    /** A monthly figure well inside the statutory cap never produces a shortfall row at all. */
    @Test
    void noShortfallRowWhenTheDeductionIsNotCapped() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("SHORTFALL-NOCAP", new BigDecimal("30000.00"));
        obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("500.00"), null,
            "LED-SHORTFALL-NOCAP", month, null), employeeId);

        PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(period, employeeId);
        assertThat(line.legalExecutionDeduction()).isEqualByComparingTo("500.00");

        assertThat(shortfallRepository.findAll(employeeId, PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT)).isEmpty();
    }

    /** Reprocessing the SAME month upserts the one row instead of inserting a second one. */
    @Test
    void reprocessingTheSameMonthSelfCorrectsTheShortfallLedgerInsteadOfDuplicating() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("SHORTFALL-REPROCESS", new BigDecimal("30000.00"));
        obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("20000.00"), null,
            "LED-SHORTFALL-REPROCESS", month, null), employeeId);

        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());

        List<PayrollDeductionShortfallDto> rows = shortfallRepository.findAll(employeeId, PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT);
        assertThat(rows).hasSize(1);
    }

    /**
     * A correction that removes the shortfall (HR lowers the instructed monthly figure to something
     * the statutory cap no longer binds) must DELETE the stale row on reprocess, not strand it.
     */
    @Test
    void aShortfallThatDisappearsOnCorrectionIsDeletedOnReprocess() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("SHORTFALL-CORRECTED", new BigDecimal("30000.00"));
        long obligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("20000.00"), null,
            "LED-SHORTFALL-CORRECTED", month, null), employeeId);

        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        assertThat(shortfallRepository.findAll(employeeId, PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT)).hasSize(1);

        obligationRepository.update(obligationId, new DeductionObligationDtos.DeductionObligationUpdateRequest(
            new BigDecimal("500.00"), null, "LED-SHORTFALL-CORRECTED", null), employeeId);
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());

        assertThat(shortfallRepository.findAll(employeeId, PayrollDeductionKind.LEGAL_EXECUTION_GARNISHMENT)).isEmpty();
    }

    /**
     * STUDENT_LOAN is never capped by anything today (item (1), cap_group NONE -- see
     * PayrollDeductionClassification), so completion (the final instalment, then auto-stop at zero)
     * must never be mistaken for a shortfall -- it is the obligation being satisfied correctly,
     * already fully visible via #373's own remittance ledger and status column.
     */
    @Test
    void studentLoanNeverProducesAShortfallRowEvenAtCompletion() {
        long employeeId = seedEmployee("SHORTFALL-STULOAN", new BigDecimal("30000.00"));
        obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), new BigDecimal("2640.00"),
            "กยศ-SHORTFALL", LocalDate.of(2026, 1, 1), null), employeeId);

        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of()), hr());
        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 2, 1), List.of()), hr());
        // Final instalment (640, not 1000) then auto-stop (0) -- neither is a shortfall.
        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 3, 1), List.of()), hr());
        payrollService.process(new ProcessPayrollRequest(LocalDate.of(2026, 4, 1), List.of()), hr());

        assertThat(shortfallRepository.findAll(employeeId, PayrollDeductionKind.STUDENT_LOAN)).isEmpty();
    }

    // --- helpers ------------------------------------------------------------

    private PayrollLineDto lineFor(PayrollPeriodDto period, long employeeId) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, BigDecimal salary) {
        long employeeId = jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, 'ทดสอบ', :code, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "salary", salary),
            Long.class);
        payrollRepository.seedSsoInclusionDefaults(employeeId, 2026, employeeId);
        return employeeId;
    }
}
