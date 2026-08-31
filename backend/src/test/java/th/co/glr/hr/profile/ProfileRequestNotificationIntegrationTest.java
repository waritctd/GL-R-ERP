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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms {@link ProfileRequestService}'s new notification wiring against the real service and
 * the real SQL -- {@link NotificationRepository#notifyByRoleInternal}'s new {@code "hr"} division
 * predicate in particular. {@code ProfileRequestService} emitted zero notifications before this
 * class existed (verified by grep before the change): an employee filing a request went unheard by
 * HR, and HR's decision went unheard by the employee.
 *
 * <p>Modeled on the sibling {@code ProfileRequestScopeIntegrationTest} (this package) and on
 * {@code AttendanceScopeIntegrationTest} for the {@code insertDivision}/{@code insertEmployee}
 * helper shape (CLAUDE.md, "Permission changes must ship evidence" -- this is not a role/scope gate,
 * but it IS a new division predicate against real SQL, which a mocked repository cannot prove: it
 * would happily "pass" a predicate that matched the wrong division, or every division).
 *
 * <p>{@code employees} is mocked here for the same reason {@code ProfileRequestScopeIntegrationTest}
 * mocks it: it plays no part in the behaviour under test.
 * {@link ProfileRequestService#create} falls back to {@link ProfileRequestRecord#requestedBy()} for
 * the notification's "who" text when the {@code EmployeeDto} lookup is empty, and the
 * {@code fieldLabel}/{@code fieldKey} copy guard reads {@link ProfileRequestRecord} directly either
 * way -- so every assertion below (division targeting, link, message copy) holds regardless of what
 * {@code EmployeeRepository} returns.
 *
 * <p>Wired with {@link SalesNotificationMailer#NO_OP} throughout: every case here asserts on
 * {@code hr.notification} rows, not mail. {@code NO_OP}'s own Javadoc is explicit that a "no mail
 * was sent" assertion against it would be vacuous (it can never send, regardless of what routing
 * decided), so this class does not attempt one.
 */
class ProfileRequestNotificationIntegrationTest extends AbstractPostgresIntegrationTest {

    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private ProfileRequestService service;

    private long hrDivision;
    private long salesDivision;
    private long hrEmployee;
    private long salesEmployee;

    @BeforeEach
    void wireRealCollaborators() {
        when(employees.findEmployeeSummariesByIds(any())).thenReturn(Map.of());
        when(employees.findEmployeeSummaryById(anyLong())).thenReturn(Optional.empty());

        ProfileRequestRepository profileRequests = new ProfileRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        // @Transactional on ProfileRequestService#create/#update is inert without a real AOP proxy
        // (no Spring context here) -- see AbstractPostgresIntegrationTest#transactional's Javadoc.
        service = transactional(new ProfileRequestService(profileRequests, employees, auditService, notifications));

        // "HR" (not a prefix match) is this company's actual HR division coding -- confirmed by
        // PendingApproverSql#SINGLE_ACTIVE_HR_NAME_SQL's Javadoc, which cites
        // V115__work_schedule_and_holiday_calendar.sql's seed. Deliberately no position_id on either
        // inserted employee, so the "hr" predicate's NOT LIKE '%กรรมการ%' executive guard reads
        // COALESCE(p.name_th, '') = '' and is a no-op for these fixtures -- exactly like a real
        // position-less HR staffer, who must still be notified.
        hrDivision = insertDivision("HR", "HR-บุคคล");
        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        hrEmployee = insertEmployee("HR001", hrDivision);
        salesEmployee = insertEmployee("S001", salesDivision);
    }

    // --- create(): notifies HR -----------------------------------------------

    @Test
    void createNotifiesTheHrDivisionWithTheRequestsLink() {
        service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        List<NotificationRow> hrRows = notificationsFor(hrEmployee, "PROFILE_REQUEST_SUBMITTED");
        assertThat(hrRows).hasSize(1);
        assertThat(hrRows.get(0).link()).isEqualTo("/requests");
    }

    /**
     * Wrong-way-round, per CLAUDE.md's "ask the question the wrong way round" discipline: this is
     * the case that actually proves the new {@code "hr"} division predicate FILTERS, rather than
     * merely that HR happens to receive a row. A predicate mutated to match every division would
     * still pass {@link #createNotifiesTheHrDivisionWithTheRequestsLink} above -- only this case
     * catches it. (Mutation-checked: see the PR body / session report.)
     */
    @Test
    void createDoesNotNotifyANonHrEmployee() {
        service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        assertThat(notificationsFor(salesEmployee, "PROFILE_REQUEST_SUBMITTED")).isEmpty();
    }

    // --- update(): notifies the requesting employee ---------------------------

    @Test
    void approvingNotifiesTheRequestingEmployeeWithTheProfileLink() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        service.update(created.id(), new UpdateProfileRequestRequest("approved", null), hrUser());

        List<NotificationRow> rows = notificationsFor(salesEmployee, "PROFILE_REQUEST_APPROVED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).link()).isEqualTo("/profile");
    }

    @Test
    void rejectingCarriesTheReviewerNoteToTheRequestingEmployee() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        service.update(created.id(), new UpdateProfileRequestRequest("rejected", "ข้อมูลไม่ตรงกับเอกสาร"), hrUser());

        List<NotificationRow> rows = notificationsFor(salesEmployee, "PROFILE_REQUEST_REJECTED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).message()).contains("เหตุผล: ข้อมูลไม่ตรงกับเอกสาร");
    }

    @Test
    void rejectingWithABlankReviewerNoteLeavesNoDanglingReasonLabel() {
        ProfileRequestDto created = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());

        service.update(created.id(), new UpdateProfileRequestRequest("rejected", ""), hrUser());

        List<NotificationRow> rows = notificationsFor(salesEmployee, "PROFILE_REQUEST_REJECTED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).message()).doesNotContain("เหตุผล");
    }

    // --- copy guard: fieldLabel only, never the raw fieldKey -------------------

    /**
     * Regression guard for the TRAVEL_PER_DIEM class of defect that CLAUDE.md records: a raw
     * machine field code reaching a human-facing message instead of its Thai label. Covers all
     * three message builders (submitted/approved/rejected) in one pass, rather than three separate
     * assertions that could each individually go stale without the others noticing.
     *
     * <p>Deliberately asserts nothing about ROW COUNT -- only message content. An earlier version
     * of this test also asserted {@code hasSize(4)}, and the case-2/case-5 mutation check below
     * caught that as a real defect in the test itself: loosening the "hr" division predicate (case
     * 2's own mutation) changes how many employees get fanned out to, which changed the count and
     * made this test go red FOR THE WRONG REASON alongside case 2, violating "case 2 must go red,
     * alone". Content-only keeps this test isolated to the one thing it claims to guard.
     */
    @Test
    void everyNotificationMessageCarriesTheThaiLabelNeverTheRawFieldKey() {
        ProfileRequestDto toApprove = service.create(emailRequest("old@glr.co.th", "new@glr.co.th"), salesUser());
        ProfileRequestDto toReject = service.create(emailRequest("old2@glr.co.th", "new2@glr.co.th"), salesUser());
        service.update(toApprove.id(), new UpdateProfileRequestRequest("approved", null), hrUser());
        service.update(toReject.id(), new UpdateProfileRequestRequest("rejected", "ข้อมูลไม่ตรงกับเอกสาร"), hrUser());

        List<String> messages = profileRequestNotificationMessages();

        assertThat(messages).isNotEmpty();
        assertThat(messages).allSatisfy(message -> {
            assertThat(message).contains("อีเมล");
            assertThat(message).doesNotContain("email");
        });
    }

    // --- helpers ----------------------------------------------------------------

    private record NotificationRow(String type, String message, String link) {
    }

    private List<NotificationRow> notificationsFor(long employeeId, String type) {
        return jdbc.query("""
            SELECT type, message, link
              FROM hr.notification
             WHERE employee_id = :employeeId AND type = :type
             ORDER BY notification_id
            """,
            Map.of("employeeId", employeeId, "type", type),
            (rs, rowNum) -> new NotificationRow(rs.getString("type"), rs.getString("message"), rs.getString("link")));
    }

    private List<String> profileRequestNotificationMessages() {
        return jdbc.query("""
            SELECT message FROM hr.notification
             WHERE type LIKE 'PROFILE_REQUEST%'
             ORDER BY notification_id
            """, Map.of(), (rs, rowNum) -> rs.getString("message"));
    }

    private CreateProfileRequestRequest emailRequest(String oldValue, String newValue) {
        return new CreateProfileRequestRequest("email", "อีเมล", oldValue, newValue);
    }

    private UserPrincipal salesUser() {
        return new UserPrincipal(500L, "sales@glr.co.th", "sales", "sales", salesEmployee, true,
            LocalDate.now(), false, salesDivision, false);
    }

    private UserPrincipal hrUser() {
        return new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", hrEmployee, true,
            LocalDate.now(), false, hrDivision, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
