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

/** Real-Postgres coverage of the payroll-engine wiring for issue #373: the monthly figure derived from a
 * hr.deduction_obligation row, the draw-down clamp, the auto-stop at instructedTotal, the HR override resuming
 * deduction, and. */
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

        // 2,000 remitted so far; 640 left -- the final instalment must be exactly the remainder, never the full 1,000
        // (which would overshoot the instructed total).
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
        // Jan/Feb/Mar/May each moved real money -- April deducted ZERO (auto-stop) and is NOT a remittance, so it must
        // not appear in the ledger (see recordOne's zero-amount delete path).
        assertThat(obligationRepository.findLedger(obligationId)).hasSize(4);
        assertThat(obligationRepository.findLedger(obligationId))
            .extracting(RemittanceLedgerRowDto::payrollMonth)
            .containsExactly(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 1));
    }

    /** The core "must survive a re-run" requirement: reprocessing the SAME month upserts that period's own ledger
     * row instead of inserting a second one. A mocked repository cannot demonstrate this -- it would happily
     * "insert" twice and the test would still pass against the mock. */
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

        // Reprocess the SAME month three more times (e.g. HR re-runs after an unrelated correction, as this system's
        // payroll routinely does -- see PayrollRepository#saveProcessedPeriod's delete-then-insert). The ledger must
        // stay at exactly one row for this month, not four.
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());

        assertThat(obligationRepository.findLedger(obligationId)).hasSize(1);
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo("1000.00");
    }

    /** Composition with the ALREADY-enforced ป.วิ.พ. ม.302 garnishment cap (PayrollCalculator
     * #garnishmentDeduction, PayrollGarnishmentType.SALARY: 30% of gross taxable income). */
    @Test
    void theStatutoryGarnishmentCapCanBindTighterThanTheObligationsOwnMonthlyFigure() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("OBL-STATCAP", new BigDecimal("30000.00"));
        long obligationId = obligationRepository.insert(new DeductionObligationCreateRequest(
            employeeId, DeductionObligationKind.LEGAL_EXECUTION, new BigDecimal("20000.00"), null,
            "LED-STATCAP", month, null), employeeId);

        PayrollPeriodDto period = payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());
        PayrollLineDto line = lineFor(period, employeeId);

        // 30% of a ~30,000 gross taxable income is far below the instructed 20,000/month -- the statutory cap, not
        // this obligation's own (uncapped) clamp, must be what actually bound.
        assertThat(line.legalExecutionDeduction()).isLessThan(new BigDecimal("20000.00"));
        BigDecimal capByRate = line.grossTaxableIncome().multiply(new BigDecimal("0.30"));
        assertThat(line.legalExecutionDeduction()).isLessThanOrEqualTo(capByRate);
        // The ledger records what was ACTUALLY remitted (post-cap), never the raw instruction.
        assertThat(obligationRepository.sumRemittanceTotal(obligationId)).isEqualByComparingTo(line.legalExecutionDeduction());
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
        // Mirrors PayrollWithholdingTaxOverrideIntegrationTest#seedEmployee: real production SSO-inclusion defaults so
        // socialSecurity/grossTaxableIncome come out non-zero and realistic. SALARY needs no tax-treatment seed
        // (locked to REGULAR_REPROJECT regardless).
        payrollRepository.seedSsoInclusionDefaults(employeeId, 2026, employeeId);
        return employeeId;
    }
}
