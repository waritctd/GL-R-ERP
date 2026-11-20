package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
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
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** V128: approved สวัสดิการ reaching payroll, end to end, against real Postgres. V66 built the welfare model in
 * 2018 and said outright that approved amounts do NOT flow into payroll; nothing consumed
 * hr.special_money_request until now, and welfare was keyed in by hand. */
class WelfareReachesPayrollIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal SALARY = new BigDecimal("30000.00");
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 9, 1);

    private PayrollService payrollService;
    private PayrollRepository payrollRepository;
    private long division;
    private long staff;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
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
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));

        division = insertDivision("SLS", "ฝ่ายขาย");
        staff = insertEmployee("S001");
    }

    /** The headline case: ฿5,000 of approved funeral aid for this payroll month must appear on the line, be
     * included in gross, and persist to its own column. */
    @Test
    void approvedWelfareReachesTheLineAndGross() {
        insertApprovedWelfare(staff, "AID_FUNERAL", new BigDecimal("5000.00"), PAYROLL_MONTH);

        PayrollLineDto line = previewLineFor(staff);

        assertThat(line.welfarePay()).isEqualByComparingTo("5000.00");
        // Gross must actually carry it -- a figure shown on the line but missing from gross is money the employee is
        // told about and never paid.
        assertThat(line.grossEarnings()).isEqualByComparingTo(SALARY.add(new BigDecimal("5000.00")));
    }

    // Fixtures

    private PayrollLineDto previewLineFor(long employeeId) {
        return payrollService.preview(new ProcessPayrollRequest(PAYROLL_MONTH, List.of()), hrActor()).lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private long insertApprovedWelfare(long employeeId, String type, BigDecimal amount, LocalDate payrollMonth) {
        return insertWelfare(employeeId, type, amount, payrollMonth, "APPROVED");
    }

    private long insertWelfare(
            long employeeId, String type, BigDecimal amount, LocalDate payrollMonth, String status) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("type", type);
        params.put("amount", amount);
        // Only an APPROVED row carries a payroll_month in production (it is assigned at CEO approval); mirror that so
        // a non-approved fixture cannot pass for the wrong reason.
        params.put("payrollMonth", "APPROVED".equals(status) ? payrollMonth : null);
        params.put("approvedAmount", "APPROVED".equals(status) ? amount : null);
        params.put("status", status);
        return jdbc.queryForObject("""
            INSERT INTO hr.special_money_request
                (employee_id, request_type, event_date, requested_amount, approved_amount,
                 payroll_bucket, policy_version, reason, status, payroll_month)
            VALUES (:employeeId, :type, DATE '2026-09-05', :amount, :approvedAmount,
                 'AID', 1, 'test', :status, :payrollMonth)
            RETURNING special_money_request_id
            """, params, Long.class);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", staff, true,
            LocalDate.now(), false, null, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", division);
        params.put("salary", SALARY);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, current_salary, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :salary, DATE '2020-01-01', TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
