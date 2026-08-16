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
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.ProcessPayrollRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationOverrideRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.RemittanceLedgerRowDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Real-Postgres coverage of the payroll-engine wiring for issue #373: the monthly figure derived
 * from a {@code hr.deduction_obligation} row (not retyped), the draw-down clamp (final instalment =
 * the remainder), the auto-stop at {@code instructedTotal}, the HR override resuming deduction, and
 * -- the part a mocked repository cannot demonstrate -- that reprocessing the SAME payroll month
 * self-corrects the remittance ledger instead of double-counting.
 *
 * <p>Driven exclusively through the real {@link PayrollService#process}/{@code #preview} and {@link
 * DeductionObligationService}, per CLAUDE.md's requirement for a business-logic change: "a mocked
 * repository happily 'passes' while the SQL does something else."
 */
class DeductionObligationPayrollEngineIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;
    private DeductionObligationRepository obligationRepository;
    private DeductionObligationService obligationService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        obligationRepository = new DeductionObligationRepository(jdbc);
        obligationService = new DeductionObligationService(
            obligationRepository, mock(EmployeeRepository.class), mock(AuditService.class),
            new PayrollDeductionShortfallRepository(jdbc));
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
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

    /** No per-run field is submitted for this employee at all -- the figure must still appear. */
    @Test
    void theMonthlyDeductionIsDerivedFromTheObligationWithoutHrRetypingIt() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("OBL-DERIVE", new BigDecimal("30000.00"));
        obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), null,
            "กยศ-DERIVE", month, null), employeeId);

        PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(period, employeeId);

        assertThat(line.studentLoanDeduction()).isEqualByComparingTo("1000.00");
    }

    /** The draw-down clamp, auto-stop, HR override, and the remittance ledger, across four real months. */
    @Test
    void theFinalInstalmentClampsThenAutoStopsThenResumesOnlyAfterHrOverride() {
        long employeeId = seedEmployee("OBL-LIFECYCLE", new BigDecimal("30000.00"));
        long obligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), new BigDecimal("2640.00"),
            "กยศ-LIFECYCLE", LocalDate.of(2026, 1, 1), null), employeeId);

        BigDecimal jan = processAndDeduction(LocalDate.of(2026, 1, 1), employeeId);
        assertThat(jan).isEqualByComparingTo("1000.00");

        BigDecimal feb = processAndDeduction(LocalDate.of(2026, 2, 1), employeeId);
        assertThat(feb).isEqualByComparingTo("1000.00");

        // 2,000 remitted so far; 640 left -- the final instalment must be exactly the remainder, never
        // the full 1,000 (which would overshoot the instructed total).
        BigDecimal mar = processAndDeduction(LocalDate.of(2026, 3, 1), employeeId);
        assertThat(mar).isEqualByComparingTo("640.00");
        assertThat(obligationRepository.findById(obligationId).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.COMPLETED);

        // Auto-stop: completed, no override yet -- April deducts nothing, not a stale 1,000.
        BigDecimal apr = processAndDeduction(LocalDate.of(2026, 4, 1), employeeId);
        assertThat(apr).isEqualByComparingTo("0.00");

        // HR's explicit, recorded override is the ONLY way deduction resumes.
        obligationService.overrideContinue(obligationId, new DeductionObligationOverrideRequest("ยังมียอดค้างตามที่กยศ.แจ้งเพิ่ม"), hr());
        BigDecimal may = processAndDeduction(LocalDate.of(2026, 5, 1), employeeId);
        assertThat(may).isEqualByComparingTo("1000.00");

        // Paid-to-date is a real SUM over the ledger, matching every period actually deducted.
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("3640.00");
        // Jan/Feb/Mar/May each moved real money -- April deducted ZERO (auto-stop) and is NOT a
        // remittance, so it must not appear in the ledger (see recordOne's zero-amount delete path).
        assertThat(obligationRepository.findLedger(obligationId)).hasSize(4);
        assertThat(obligationRepository.findLedger(obligationId))
            .extracting(RemittanceLedgerRowDto::payrollMonth)
            .containsExactly(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1));
    }

    /**
     * D1 fix (Opus review): reproduces the exact probe from the review -- process the completing
     * month, then re-process it (and a third time), asserting the SAME ฿640 final instalment every
     * time. Before the fix, {@code resolveMonthlyAmount} trusted the STORED {@code status} column
     * (written by the previous run) instead of recomputing the remaining balance from the ledger
     * excluding this month, so the second run saw "COMPLETED" and returned 0, silently deleting the
     * final instalment (the obligation's status even bounced back to ACTIVE as a result). A
     * single-run test cannot catch this -- the bug only appears on RE-processing the month that
     * causes completion.
     */
    @Test
    void theCompletingMonthYieldsTheSameFinalInstalmentOnEveryReprocess() {
        long employeeId = seedEmployee("OBL-REPROCESS-FINAL", new BigDecimal("30000.00"));
        long obligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), new BigDecimal("2640.00"),
            "กยศ-REPROCESS-FINAL", LocalDate.of(2026, 1, 1), null), employeeId);

        processAndDeduction(LocalDate.of(2026, 1, 1), employeeId);
        processAndDeduction(LocalDate.of(2026, 2, 1), employeeId);

        BigDecimal firstRun = processAndDeduction(LocalDate.of(2026, 3, 1), employeeId);
        assertThat(firstRun).isEqualByComparingTo("640.00");
        assertThat(obligationRepository.findById(obligationId).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.COMPLETED);
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("2640.00");

        // RE-PROCESS the same completing month. Must yield the identical 640.00, not 0.00.
        BigDecimal secondRun = processAndDeduction(LocalDate.of(2026, 3, 1), employeeId);
        assertThat(secondRun).isEqualByComparingTo("640.00");
        assertThat(obligationRepository.findById(obligationId).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.COMPLETED);
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("2640.00");

        // A third time, for good measure -- must still be stable.
        BigDecimal thirdRun = processAndDeduction(LocalDate.of(2026, 3, 1), employeeId);
        assertThat(thirdRun).isEqualByComparingTo("640.00");
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("2640.00");
        assertThat(obligationRepository.findLedger(obligationId)).hasSize(3); // Jan, Feb, Mar -- never duplicated
    }

    /**
     * D2 fix (Opus review): an obligation recorded with a start_date AFTER the payroll month being
     * processed must not drive that month's deduction at all -- it must behave exactly as if no
     * obligation existed (falls back to the HR-typed/absent manual figure), and must record no
     * ledger row. Before the fix, {@code findDrivingObligation} filtered only on employee/kind/status,
     * so an obligation entered ahead of its instructed start date (or a fat-fingered year) deducted
     * for every month before the authority actually said to.
     */
    @Test
    void anObligationWithAFutureStartDateDoesNotDeductBeforeItStarts() {
        long employeeId = seedEmployee("OBL-FUTURESTART", new BigDecimal("30000.00"));
        LocalDate startDate = LocalDate.of(2027, 6, 1);
        long obligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), null,
            "กยศ-FUTURESTART", startDate, null), employeeId);

        // A payroll month well BEFORE the obligation's start_date must deduct nothing and record nothing.
        BigDecimal beforeStart = processAndDeduction(LocalDate.of(2026, 1, 1), employeeId);
        assertThat(beforeStart).isEqualByComparingTo("0.00");
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("0.00");
        assertThat(obligationRepository.findLedger(obligationId)).isEmpty();

        // Once the payroll month reaches the start_date, the obligation drives the deduction normally.
        BigDecimal atStart = processAndDeduction(startDate, employeeId);
        assertThat(atStart).isEqualByComparingTo("1000.00");
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("1000.00");
    }

    /**
     * Off-by-one-month fix (Opus re-review): {@code payrollMonth} is always normalised to the 1st,
     * but an authority's document is rarely dated the 1st. Before this fix, comparing the raw dates
     * (`start_date &lt;= payrollMonth`) made {@code 2026-01-15 <= 2026-01-01} false, so a document
     * dated 15 Jan silently skipped its own start month -- the most common real case (any document
     * not dated the 1st), and with no {@code instructedTotal} that skipped instalment is never
     * recovered (the clamp has nothing to make it up against). Fixed by truncating {@code
     * start_date} to its own month-start before comparing. A control obligation started on the 1st
     * proves the fix did not just widen the window indiscriminately.
     */
    @Test
    void aMidMonthStartDateStillDeductsInItsOwnStartingMonth() {
        long midMonthEmployee = seedEmployee("OBL-MIDMONTH", new BigDecimal("30000.00"));
        long midMonthObligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            midMonthEmployee, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), null,
            "กยศ-MIDMONTH", LocalDate.of(2026, 1, 15), null), midMonthEmployee);

        long controlEmployee = seedEmployee("OBL-MIDMONTH-CTRL", new BigDecimal("30000.00"));
        obligationRepository.insert(new DeductionObligationCreateRequest(
            controlEmployee, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), null,
            "กยศ-MIDMONTH-CTRL", LocalDate.of(2026, 1, 1), null), controlEmployee);

        BigDecimal midMonthJanuary = processAndDeduction(LocalDate.of(2026, 1, 1), midMonthEmployee);
        BigDecimal controlJanuary = processAndDeduction(LocalDate.of(2026, 1, 1), controlEmployee);

        // The document is dated mid-January, but it still drives the WHOLE of January -- not zero,
        // and not deferred to February.
        assertThat(midMonthJanuary).isEqualByComparingTo("1000.00");
        assertThat(controlJanuary).isEqualByComparingTo("1000.00");
        assertThat(obligationRepository.sumRemittanceTotal(midMonthObligationId)).isEqualByComparingTo("1000.00");
    }

    /**
     * The core "must survive a re-run" requirement: reprocessing the SAME month upserts that period's
     * own ledger row instead of inserting a second one. A mocked repository cannot demonstrate this --
     * it would happily "insert" twice and the test would still pass against the mock.
     */
    @Test
    void reprocessingTheSameMonthSelfCorrectsTheLedgerInsteadOfDoubleCounting() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("OBL-REPROCESS", new BigDecimal("30000.00"));
        long obligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.STUDENT_LOAN, new BigDecimal("1000.00"), new BigDecimal("2500.00"),
            "กยศ-REPROCESS", month, null), employeeId);

        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("1000.00");
        assertThat(obligationRepository.findLedger(obligationId)).hasSize(1);

        // Reprocess the SAME month three more times (e.g. HR re-runs after an unrelated correction,
        // as this system's payroll routinely does -- see PayrollRepository#saveProcessedPeriod's
        // delete-then-insert). The ledger must stay at exactly one row for this month, not four.
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());

        assertThat(obligationRepository.findLedger(obligationId)).hasSize(1);
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("1000.00");
    }

    /**
     * Composition with the ALREADY-enforced ป.วิ.พ. ม.302 garnishment cap (PayrollCalculator
     * #garnishmentDeduction, PayrollGarnishmentType.SALARY: 30% of gross taxable income). Case 1: the
     * obligation's own instructed monthly figure is so large the STATUTORY cap binds tighter -- the
     * persisted deduction must be capped well below the instructed figure, and the ledger must record
     * the REDUCED (actually remitted) amount, not the raw instruction.
     */
    @Test
    void theStatutoryGarnishmentCapCanBindTighterThanTheObligationsOwnMonthlyFigure() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("OBL-STATCAP", new BigDecimal("30000.00"));
        long obligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("20000.00"), null,
            "LED-STATCAP", month, null), employeeId);

        PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(period, employeeId);

        // 30% of a ~30,000 gross taxable income is far below the instructed 20,000/month -- the
        // statutory cap, not this obligation's own (uncapped) clamp, must be what actually bound.
        assertThat(line.legalExecutionDeduction()).isLessThan(new BigDecimal("20000.00"));
        BigDecimal capByRate = line.grossTaxableIncome().multiply(new BigDecimal("0.30"));
        assertThat(line.legalExecutionDeduction()).isLessThanOrEqualTo(capByRate);
        // The ledger records what was ACTUALLY remitted (post-cap), never the raw instruction.
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo(line.legalExecutionDeduction());
    }

    /** Case 2: near completion, THIS obligation's own remaining balance binds tighter than the statutory cap. */
    @Test
    void theObligationsOwnRemainingBalanceCanBindTighterThanTheStatutoryCap() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("OBL-OWNCAP", new BigDecimal("30000.00"));
        long obligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("1000.00"), new BigDecimal("50.00"),
            "LED-OWNCAP", month, null), employeeId);

        PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(period, employeeId);

        // Far below any statutory cap for a ฿30,000 salary -- this obligation's own remaining balance
        // (50.00 total, nothing remitted yet) is what actually bound.
        assertThat(line.legalExecutionDeduction()).isEqualByComparingTo("50.00");
        assertThat(obligationRepository.findById(obligationId).orElseThrow().status())
            .isEqualTo(DeductionObligationStatus.COMPLETED);
    }

    // --- helpers ------------------------------------------------------------

    private BigDecimal processAndDeduction(LocalDate month, long employeeId) {
        PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        return lineFor(period, employeeId).studentLoanDeduction();
    }

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
        // Mirrors PayrollWithholdingTaxOverrideIntegrationTest#seedEmployee: real production
        // SSO-inclusion defaults so socialSecurity/grossTaxableIncome come out non-zero and realistic.
        // SALARY needs no tax-treatment seed (locked to REGULAR_REPROJECT regardless).
        payrollRepository.seedSsoInclusionDefaults(employeeId, 2026, employeeId);
        return employeeId;
    }
}
