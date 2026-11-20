package th.co.glr.hr.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.feedback.FeedbackDtos.DecisionRequest;
import th.co.glr.hr.feedback.FeedbackDtos.FeedbackDto;
import th.co.glr.hr.feedback.FeedbackDtos.SubmitRequest;
import th.co.glr.hr.feedback.FeedbackDtos.UpdateRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationDto;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * "แจ้งปัญหา / ขอฟีเจอร์" through the REAL FeedbackService, repositories and a real Postgres. Wrong-way-round first:
 * who may NOT list / decide / update, what a bad submit must NOT write, what a second decision must NOT change.
 *
 * <p>Admin is the live {@code hr.employee.is_admin} capability (same as the activity log), never a role: the fixtures
 * give the admin the plain {@code employee} role and give ceo / hr no admin flag, so a role-based gate goes red.
 * The service is wrapped in a real transactional proxy ({@code transactional(...)}) so mail-after-commit is real.
 */
class FeedbackServiceIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String LINK = "/whats-new?tab=feedback";
    private static final String DETAILS = "กดพิมพ์ใบเสนอราคาแล้ว PDF ตัดบรรทัดสุดท้าย";

    private Mailer mailer;
    private FeedbackService service;

    private long rep;
    private long otherRep;
    private long admin;
    private long inactiveAdmin;
    private long ceo;
    private long hr;

    @BeforeEach
    void wire() {
        mailer = mock(Mailer.class);
        NotificationEmailService emailService =
            new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://glr-hub.com");
        NotificationService notifications = new NotificationService(
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), emailService);
        service = transactional(new FeedbackService(
            new FeedbackRepository(jdbc), new EmployeeAuthRepository(jdbc), notifications, ""));

        long sales = division("SA", "ฝ่ายขาย");
        long accounting = division("AC", "ฝ่ายบัญชี");
        rep = employee("FB-REP-1", sales, "rep@glr.co.th", false, true);
        otherRep = employee("FB-REP-2", accounting, "rep2@glr.co.th", false, true);
        admin = employee("FB-ADM-1", accounting, "admin@glr.co.th", true, true);
        inactiveAdmin = employee("FB-ADM-2", accounting, "gone@glr.co.th", true, false);
        ceo = employee("FB-CEO-1", sales, "ceo@glr.co.th", false, true);
        hr = employee("FB-HR-1", accounting, "hr@glr.co.th", false, true);
    }

    // ── Reads: own-only, admin-only ─────────────────────────────────────────────────────────────────────────

    @Test
    void listMineReturnsOnlyTheCallersOwnSubmissions_newestFirst() {
        FeedbackDto first = service.submit(principal(rep, "sales"), submit("BUG", "QUOTATIONS", DETAILS, null, null));
        FeedbackDto second = service.submit(principal(rep, "sales"), submit("FEATURE", "CATALOG", DETAILS, null, null));
        service.submit(principal(otherRep, "account"), submit("BUG", "LEAVE", DETAILS, null, null));

        assertThat(service.listMine(principal(rep, "sales"))).extracting(FeedbackDto::id)
            .containsExactly(second.id(), first.id());
        assertThat(service.listMine(principal(otherRep, "account"))).hasSize(1)
            .allSatisfy(f -> assertThat(f.reporterEmployeeId()).isEqualTo(otherRep));
        assertThat(service.listMine(principal(ceo, "ceo"))).isEmpty();
        // Even the admin's "mine" is only their own.
        assertThat(service.listMine(principal(admin, "employee"))).isEmpty();
    }

    @Test
    void listAllIsAdminOnly_noRoleGrantsIt() {
        service.submit(principal(rep, "sales"), submit("BUG", "QUOTATIONS", DETAILS, null, null));
        service.submit(principal(otherRep, "account"), submit("BUG", "LEAVE", DETAILS, null, null));

        for (UserPrincipal denied : List.of(principal(rep, "sales"), principal(ceo, "ceo"), principal(hr, "hr"),
            principal(inactiveAdmin, "employee"))) {
            assertThatThrownBy(() -> service.listAll(denied))
                .as(denied.role())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        }
        assertThat(service.listAll(principal(admin, "employee"))).hasSize(2);
    }

    // ── Decide: accept / deny ───────────────────────────────────────────────────────────────────────────────

    @Test
    void acceptWithEtaRecordsDecisionAndTellsTheReporterByBellAndEmail() {
        long id = pending();
        clearInvocations(mailer);

        FeedbackDto accepted = service.decide(principal(admin, "employee"), id,
            new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null, null));

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.eta()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(decidedBy(id)).isEqualTo(admin);
        List<NotificationDto> bells = feedbackBells(rep);
        assertThat(bells).hasSize(1);
        assertThat(bells.get(0).type()).isEqualTo("FEEDBACK_DECIDED");
        assertThat(bells.get(0).link()).isEqualTo(LINK);
        // Thai Buddhist-era date, never ISO.
        assertThat(bells.get(0).message()).contains("5 ธันวาคม 2569").doesNotContain("2026-12-05");
        // รับเรื่อง is emailed to the reporter, and to nobody else.
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer, times(1)).sendHtml(to.capture(), anyString(), anyString(), text.capture(), anyList());
        assertThat(to.getValue()).isEqualTo("rep@glr.co.th");
        // Detailed: which request (number, kind, page), its status, the date, and the reporter's own words back.
        assertThat(text.getValue()).contains("#" + id).contains("แจ้งปัญหา").contains("ใบเสนอราคา")
            .contains("รับเรื่อง").contains("5 ธันวาคม 2569").contains(DETAILS).doesNotContain("ACCEPTED");
        // The bell stays one short line: the reporter's own text is for the email only.
        assertThat(bells.get(0).message()).doesNotContain(DETAILS);
        assertThat(feedbackBells(otherRep)).isEmpty();
    }

    @Test
    void silentAcceptAndSilentReleaseNotifyNobody() {
        long id = pending();
        clearInvocations(mailer);

        service.decide(principal(admin, "employee"), id, new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null, true));
        FeedbackDto released = service.update(principal(admin, "employee"), id, new UpdateRequest("RELEASED", null, true));

        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(feedbackBells(rep)).isEmpty();
        verify(mailer, never()).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void nonAdminsCanNotDecide_includingCeoHrAndTheReporter_andNothingChanges() {
        long id = pending();
        long before = bellCount(rep);

        for (UserPrincipal denied : List.of(principal(rep, "sales"), principal(ceo, "ceo"), principal(hr, "hr"))) {
            assertThatThrownBy(() -> service.decide(denied, id, new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null, null)))
                .as(denied.role())
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
            assertThatThrownBy(() -> service.decide(denied, id, new DecisionRequest("DENY", null, "ไม่รับเรื่องนี้", null)))
                .as(denied.role())
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        }
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(bellCount(rep)).isEqualTo(before);
    }

    @Test
    void submittedBccGoesOnExactlyOneAdminEmail() {
        employee("FB-ADM-3", division("AC3", "ฝ่ายบัญชี 3"), "admin3@glr.co.th", true, true);
        FeedbackService withBcc = transactional(new FeedbackService(
            new FeedbackRepository(jdbc), new EmployeeAuthRepository(jdbc),
            new NotificationService(new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
                new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://glr-hub.com")),
            "dev@example.com, "));
        clearInvocations(mailer);

        withBcc.submit(principal(rep, "sales"), submit("BUG", "QUOTATIONS", DETAILS, null, null));

        ArgumentCaptor<Mailer.OutgoingEmail> sent = ArgumentCaptor.forClass(Mailer.OutgoingEmail.class);
        verify(mailer, times(1)).send(sent.capture());
        assertThat(sent.getValue().bcc()).containsExactly("dev@example.com");
        // The other active admin gets the plain mail, no BCC.
        verify(mailer, times(1)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    // ── Several requesters ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void adminSetsRequesters_theyShareTheRequestAndAllGetTheNotification_reporterStays() {
        long id = pending();
        UserPrincipal adminUser = principal(admin, "employee");

        FeedbackDto set = service.setRequesters(adminUser, id, new FeedbackDtos.RequestersRequest(List.of(otherRep)));

        // The reporter is silently kept and listed first; adding people notifies nobody.
        assertThat(set.requesters()).extracting(FeedbackDtos.Requester::employeeId).containsExactly(rep, otherRep);
        assertThat(service.listMine(principal(otherRep, "account"))).extracting(FeedbackDto::id).containsExactly(id);
        assertThat(bellCount(otherRep)).isZero();
        clearInvocations(mailer);

        service.decide(adminUser, id, new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null, null));

        assertThat(bellCount(rep)).isEqualTo(1);
        assertThat(bellCount(otherRep)).isEqualTo(1);
        verify(mailer, times(2)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());

        // Removing everyone else leaves just the reporter.
        assertThat(service.setRequesters(adminUser, id, new FeedbackDtos.RequestersRequest(List.of())).requesters())
            .extracting(FeedbackDtos.Requester::employeeId).containsExactly(rep);
        assertThat(service.listMine(principal(otherRep, "account"))).isEmpty();
    }

    @Test
    void requestersAndPeopleAreAdminOnly_andAnInactiveOrUnknownPersonIs400() {
        long id = pending();
        for (UserPrincipal denied : List.of(principal(rep, "sales"), principal(ceo, "ceo"))) {
            assertThatThrownBy(() -> service.setRequesters(denied, id, new FeedbackDtos.RequestersRequest(List.of(otherRep))))
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
            assertThatThrownBy(() -> service.people(denied))
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        }
        UserPrincipal adminUser = principal(admin, "employee");
        assertThatThrownBy(() -> service.setRequesters(adminUser, id, new FeedbackDtos.RequestersRequest(List.of(inactiveAdmin))))
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.setRequesters(adminUser, id + 999, new FeedbackDtos.RequestersRequest(List.of())))
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
        assertThat(service.people(adminUser)).extracting(FeedbackDtos.Person::employeeId)
            .contains(rep, otherRep, admin).doesNotContain(inactiveAdmin);
        assertThat(service.listAll(adminUser)).allSatisfy(f -> assertThat(f.requesters()).hasSize(1));

        // A reporter who has since left must not block edits.
        service.setRequesters(adminUser, id, new FeedbackDtos.RequestersRequest(List.of(otherRep)));
        jdbc.update("UPDATE hr.employee SET is_active = false WHERE employee_id IN (:a, :b)", Map.of("a", rep, "b", otherRep));
        assertThat(service.setRequesters(adminUser, id, new FeedbackDtos.RequestersRequest(List.of(otherRep, ceo))).requesters())
            .extracting(FeedbackDtos.Requester::employeeId).containsExactly(rep, otherRep, ceo);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private long pending() {
        return service.submit(principal(rep, "sales"), submit("BUG", "QUOTATIONS", DETAILS, null, null)).id();
    }

    private static SubmitRequest submit(String kind, String area, String details, String extra, String impact) {
        return new SubmitRequest(kind, area, details, extra, impact, "/quotations/812", "Chrome", "390x844");
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "ผู้ทดสอบ " + employeeId, role, employeeId,
            true, LocalDate.of(2026, 1, 1), false, null, false);
    }

    private List<NotificationDto> feedbackBells(long employeeId) {
        return new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP).findByEmployeeId(employeeId).stream()
            .filter(n -> n.type().startsWith("FEEDBACK_"))
            .toList();
    }

    private long bellCount(long employeeId) {
        return feedbackBells(employeeId).size();
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM hr.feedback_request WHERE feedback_id = :id",
            Map.of("id", id), String.class);
    }

    private Long decidedBy(long id) {
        return jdbc.queryForObject("SELECT decided_by FROM hr.feedback_request WHERE feedback_id = :id",
            Map.of("id", id), Long.class);
    }

    private long division(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th) VALUES (:code, :name) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long employee(String code, long divisionId, String email, boolean isAdmin, boolean active) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, email, division_id, is_active, is_admin)
            VALUES (:code, :code, :email, :divisionId, :active, :admin)
            RETURNING employee_id
            """, Map.of("code", code, "email", email, "divisionId", divisionId, "active", active, "admin", isAdmin),
            Long.class);
    }
}
