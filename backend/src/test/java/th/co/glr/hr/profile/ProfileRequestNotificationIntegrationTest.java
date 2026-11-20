package th.co.glr.hr.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesMailRecipientRepository;
import th.co.glr.hr.notification.SalesNotificationMailRouter;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Confirms ProfileRequestService's notification wiring against the real service and the real SQL. Wired with
 * the REAL SalesNotificationMailRouter/SalesMailRecipientRepository chain, mocked only at the
 * NotificationEmailService boundary -- not SalesNotificationMailer.NO_OP. */
class ProfileRequestNotificationIntegrationTest extends AbstractPostgresIntegrationTest {

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    /** The one double in the chain -- see the class Javadoc for why NO_OP would not do here. */
    private final NotificationEmailService emailService = mock(NotificationEmailService.class);

    private ProfileRequestService service;

    private long hrDivision;
    private long salesDivision;
    private long hrEmployee;
    private long salesEmployee;
    private long accountDivision;
    private long importDivision;

    @BeforeEach
    void wireRealCollaborators() {
        when(employees.findEmployeeSummariesByIds(any())).thenReturn(Map.of());
        when(employees.findEmployeeSummaryById(anyLong())).thenReturn(Optional.empty());

        ProfileRequestRepository profileRequests = new ProfileRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(
            jdbc, new SalesNotificationMailRouter(emailService, new SalesMailRecipientRepository(jdbc)));
        NotificationService notificationService = new NotificationService(notifications, emailService);
        // @Transactional on ProfileRequestService#create/#update is inert without a real AOP proxy (no Spring context
        // here) -- see AbstractPostgresIntegrationTest#transactional's Javadoc.
        service = transactional(
            new ProfileRequestService(profileRequests, employees, auditService, notifications, notificationService));

        // "HR" (not a prefix match) is this company's actual HR division coding -- confirmed by
        // PendingApproverSql#SINGLE_ACTIVE_HR_NAME_SQL's Javadoc, which cites
        // V115__work_schedule_and_holiday_calendar.sql's seed.
        hrDivision = insertDivision("HR", "HR-บุคคล");
        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        hrEmployee = insertEmployee("HR001", hrDivision, null);
        salesEmployee = insertEmployee("S001", salesDivision, "rep.sales@glr.co.th");

        // The two shared-mailbox divisions SalesMailRecipientRepository#findRecipient classifies by source_code prefix
        // (ILIKE 'AC%' / ILIKE 'PCIM%').
        accountDivision = insertDivision("AC", "ฝ่ายบัญชี");
        insertEmployee("AC001", accountDivision, "sunee.account@glr.co.th");
        importDivision = insertDivision("PCIM", "จัดซื้อต่างประเทศ");
        insertEmployee("PCIM001", importDivision, "buyer.import@glr.co.th");
    }

    // --- create(): notifies HR -----------------------------------------------

    @Test
    void createNotifiesTheHrDivisionWithTheRequestsLink() {
        service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        List<NotificationRow> hrRows = notificationsFor(hrEmployee, "PROFILE_REQUEST_SUBMITTED");
        assertThat(hrRows).hasSize(1);
        assertThat(hrRows.get(0).link()).isEqualTo("/requests");
    }

    // --- helpers ----------------------------------------------------------------

    private record NotificationRow(String type, String title, String message, String link) {
    }

    private List<NotificationRow> notificationsFor(long employeeId, String type) {
        return jdbc.query("""
            SELECT type, title, message, link
              FROM hr.notification
             WHERE employee_id = :employeeId AND type = :type
             ORDER BY notification_id
            """,
            Map.of("employeeId", employeeId, "type", type),
            (rs, rowNum) -> new NotificationRow(
                rs.getString("type"), rs.getString("title"), rs.getString("message"), rs.getString("link")));
    }

    private CreateProfileRequestRequest emailRequest(String oldValue, String newValue) {
        return new CreateProfileRequestRequest("email", "อีเมล", oldValue, newValue);
    }

    private UserPrincipal salesUser() {
        return new UserPrincipal(500L, "sales@glr.co.th", "sales", "sales", salesEmployee, true,
            LocalDate.now(), false, salesDivision, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    /** email is genuinely nullable here (an HR employee with no address on file, matching real rows) -- HashMap,
     * not Map#of, because the latter rejects a null value. */
    private long insertEmployee(String code, Long divisionId, String email) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        params.put("email", email);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active, email)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE, :email)
            RETURNING employee_id
            """, params, Long.class);
    }
}
