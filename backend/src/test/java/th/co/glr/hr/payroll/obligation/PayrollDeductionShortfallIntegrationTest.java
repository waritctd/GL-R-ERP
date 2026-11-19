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
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.ProcessPayrollRequest;
import th.co.glr.hr.payroll.obligation.DeductionObligationDtos.DeductionObligationCreateRequest;
import th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallDtos.PayrollDeductionShortfallDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Real-Postgres coverage of issue #376's shortfall tracking: generalising #373's requested/actual/ shortfall
 * triple to the ALREADY-enforced ป.วิ.พ. ม.302 garnishment cap. This is pure record-keeping. */
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

    /** The obligation's own instructed monthly figure is well above what ป.วิ.พ. ม.302's default SALARY rule allows -- the
     * exact scenario DeductionObligationPayrollEngineIntegrationTest
     * #theStatutoryGarnishmentCapCanBindTighterThanTheObligationsOwnMonthlyFigure already proves changes no persisted amount. */
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

    /** STUDENT_LOAN is never capped by anything today (item, cap_group NONE -- see PayrollDeductionClassification),
     * so completion must never be mistaken for a shortfall -- it is the obligation being satisfied correctly,
     * already fully visible via #373's own remittance ledger and status column. */
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
