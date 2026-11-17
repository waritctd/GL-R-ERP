package th.co.glr.hr.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
            new FeedbackRepository(jdbc), new EmployeeAuthRepository(jdbc), notifications));

        long sales = division("SA", "ฝ่ายขาย");
        long accounting = division("AC", "ฝ่ายบัญชี");
        rep = employee("FB-REP-1", sales, "rep@glr.co.th", false, true);
        otherRep = employee("FB-REP-2", accounting, "rep2@glr.co.th", false, true);
        admin = employee("FB-ADM-1", accounting, "admin@glr.co.th", true, true);
        inactiveAdmin = employee("FB-ADM-2", accounting, "gone@glr.co.th", true, false);
        ceo = employee("FB-CEO-1", sales, "ceo@glr.co.th", false, true);
        hr = employee("FB-HR-1", accounting, "hr@glr.co.th", false, true);
    }

    // ── Submit: validation (nothing written on a bad request) ───────────────────────────────────────────────

    @Test
    void invalidSubmitsAreRefusedWith400_andWriteNothingNotifyNobody() {
        List<SubmitRequest> bad = List.of(
            submit(null, "QUOTATIONS", DETAILS, null, null),
            submit("OTHER_KIND", "QUOTATIONS", DETAILS, null, null),
            submit("BUG", null, DETAILS, null, null),
            submit("BUG", "NOT_AN_AREA", DETAILS, null, null),
            submit("BUG", "QUOTATIONS", "123456789", null, null),
            submit("BUG", "QUOTATIONS", "   สั้นไป   ", null, null),
            submit("BUG", "QUOTATIONS", null, null, null),
            submit("BUG", "QUOTATIONS", "ก".repeat(4001), null, null),
            submit("BUG", "QUOTATIONS", DETAILS, "ข".repeat(4001), null),
            submit("BUG", "QUOTATIONS", DETAILS, null, "DAILY"),        // a FEATURE impact on a BUG
            submit("FEATURE", "QUOTATIONS", DETAILS, null, "BLOCKED"),  // a BUG impact on a FEATURE
            submit("BUG", "QUOTATIONS", DETAILS, null, "WHATEVER"));
        for (int i = 0; i < bad.size(); i++) {
            SubmitRequest request = bad.get(i);
            assertThatThrownBy(() -> service.submit(principal(rep, "sales"), request))
                .as("case %d", i)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        }
        assertThat(rowCount()).isZero();
        assertThat(feedbackBells(admin)).isEmpty();
        verifyNoInteractions(mailer);
    }

    @Test
    void boundaryLengthsAreAccepted_andOptionalFieldsMayBeAbsent() {
        FeedbackDto ten = service.submit(principal(rep, "sales"),
            submit("FEATURE", "CATALOG", "ครบสิบตัวอักษร", null, null));
        FeedbackDto max = service.submit(principal(rep, "sales"),
            submit("BUG", "OTHER", "ก".repeat(4000), "ข".repeat(4000), "LOW"));

        assertThat(ten.status()).isEqualTo("PENDING");
        assertThat(ten.extra()).isNull();
        assertThat(ten.impact()).isNull();
        assertThat(max.details()).hasSize(4000);
        assertThat(max.extra()).hasSize(4000);
    }

    @Test
    void lengthsCountCodePointsLikePostgres_emojiAreOneCharacterEach() {
        // 5 emoji = 10 UTF-16 units but 5 characters: too short, so 400 (never a CHECK-violation 500).
        assertThatThrownBy(() -> service.submit(principal(rep, "sales"),
            submit("BUG", "QUOTATIONS", "😀".repeat(5), null, null)))
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        assertThat(rowCount()).isZero();

        // 4000 emoji = 8000 units but exactly the 4000-character maximum: accepted.
        FeedbackDto max = service.submit(principal(rep, "sales"),
            submit("BUG", "QUOTATIONS", "😀".repeat(4000), "😀".repeat(4000), null));
        assertThat(max.details().codePointCount(0, max.details().length())).isEqualTo(4000);

        // 4001 is refused; deny reasons count the same way (4 emoji too short, 5 fine).
        assertThatThrownBy(() -> service.submit(principal(rep, "sales"),
            submit("BUG", "QUOTATIONS", "😀".repeat(4001), null, null)))
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        long id = pending();
        assertThatThrownBy(() -> service.decide(principal(admin, "employee"), id,
            new DecisionRequest("DENY", null, "😀".repeat(4))))
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        assertThat(service.decide(principal(admin, "employee"), id,
            new DecisionRequest("DENY", null, "😀".repeat(5))).status()).isEqualTo("DENIED");
    }

    @Test
    void telemetryTruncationNeverSplitsASurrogatePair() {
        // The emoji sits at UTF-16 units 299-300, so a plain substring(0, 300) would keep only its high half.
        String path = "a".repeat(299) + "😀" + "b".repeat(5);

        FeedbackDto saved = service.submit(principal(rep, "sales"), new SubmitRequest("BUG", "QUOTATIONS", DETAILS,
            null, null, path, "Chrome", "390x844"));

        assertThat(saved.pagePath().codePointCount(0, saved.pagePath().length())).isEqualTo(300);
        assertThat(saved.pagePath()).isEqualTo("a".repeat(299) + "😀");
        assertThat(Character.isHighSurrogate(saved.pagePath().charAt(saved.pagePath().length() - 1))).isFalse();
    }

    @Test
    void aReporterMaySubmitTenPerHour_theEleventhIs429_noRowNoNotification() {
        for (int i = 0; i < 10; i++) {
            pending();
        }
        long adminBells = feedbackBells(admin).size();
        clearInvocations(mailer);

        assertThatThrownBy(this::pending)
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.TOO_MANY_REQUESTS)
            .hasMessageContaining("10");

        assertThat(rowCount()).isEqualTo(10);
        assertThat(feedbackBells(admin)).hasSize((int) adminBells);
        verifyNoInteractions(mailer);
        // Another reporter has their own allowance.
        service.submit(principal(otherRep, "account"), submit("BUG", "LEAVE", DETAILS, null, null));
        assertThat(rowCount()).isEqualTo(11);
    }

    @Test
    void submissionsOlderThanAnHourNoLongerCount() {
        for (int i = 0; i < 10; i++) {
            pending();
        }
        jdbc.update("UPDATE hr.feedback_request SET created_at = now() - interval '61 minutes'", Map.of());

        assertThat(pending()).isPositive();
        assertThat(rowCount()).isEqualTo(11);
    }

    @Test
    void clientCapturedTelemetryIsTruncatedNotRejected() {
        SubmitRequest request = new SubmitRequest("BUG", "QUOTATIONS", DETAILS, null, "LOW",
            "/quotations/" + "9".repeat(500), "UA".repeat(500), "1".repeat(100));

        FeedbackDto saved = service.submit(principal(rep, "sales"), request);

        assertThat(saved.pagePath()).hasSize(300).startsWith("/quotations/");
        assertThat(saved.userAgent()).hasSize(300);
        assertThat(saved.viewport()).hasSize(30);
    }

    // ── Submit: server-side snapshot ────────────────────────────────────────────────────────────────────────

    @Test
    void submitSnapshotsReporterFromTheServer_notFromTheClient() {
        // The request record has no identity fields; the row must carry the signed-in actor's own snapshot.
        FeedbackDto saved = service.submit(new UserPrincipal(rep, "rep@glr.co.th", "สมศรี ทดสอบ", "sales", rep, true,
            LocalDate.of(2026, 1, 1), false, null, false), submit("BUG", "QUOTATIONS", DETAILS, null, "BLOCKED"));

        assertThat(saved.reporterEmployeeId()).isEqualTo(rep);
        assertThat(saved.reporterName()).isEqualTo("สมศรี ทดสอบ");
        assertThat(saved.reporterRole()).isEqualTo("sales");
        assertThat(saved.reporterDepartment()).isEqualTo("ฝ่ายขาย"); // read from hr.division, not from the principal
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(saved.eta()).isNull();
        assertThat(saved.denyReason()).isNull();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(rowCount()).isEqualTo(1);
    }

    // ── Submit: notifications ───────────────────────────────────────────────────────────────────────────────

    @Test
    void submitNotifiesEveryActiveAdminWithBellAndEmail_andNobodyElse() {
        service.submit(principal(rep, "sales"), submit("BUG", "QUOTATIONS", DETAILS, null, null));

        List<NotificationDto> bells = feedbackBells(admin);
        assertThat(bells).hasSize(1);
        assertThat(bells.get(0).type()).isEqualTo("FEEDBACK_SUBMITTED");
        assertThat(bells.get(0).link()).isEqualTo(LINK);
        assertThat(bells.get(0).read()).isFalse();
        // The email goes to the admin only.
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(mailer, times(1)).sendHtml(to.capture(), anyString(), anyString(), anyString(), anyList());
        assertThat(to.getValue()).isEqualTo("admin@glr.co.th");
        // Wrong-way-round: not the reporter, not other roles, not a deactivated admin.
        for (long other : List.of(rep, otherRep, ceo, hr, inactiveAdmin)) {
            assertThat(feedbackBells(other)).as("employee %d", other).isEmpty();
        }
    }

    @Test
    void adminEmailEscapesUserTextAsHtml() {
        String hostile = "<script>alert(1)</script> & \"ปัญหา\" ที่ยาวพอสมควร";

        service.submit(principal(rep, "sales"), submit("BUG", "QUOTATIONS", hostile, null, null));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendHtml(eq("admin@glr.co.th"), anyString(), html.capture(), anyString(), anyList());
        assertThat(html.getValue()).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html.getValue()).doesNotContain("<script>");
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

    @Test
    void adminCapabilityIsReadLive_revokingItTakesEffectOnTheNextCall() {
        assertThat(service.listAll(principal(admin, "employee"))).isEmpty();

        jdbc.update("UPDATE hr.employee SET is_admin = FALSE WHERE employee_id = :id", Map.of("id", admin));

        assertThatThrownBy(() -> service.listAll(principal(admin, "employee")))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    // ── Decide: accept / deny ───────────────────────────────────────────────────────────────────────────────

    @Test
    void acceptWithoutEtaIs400_andLeavesItPending() {
        long id = pending();

        assertThatThrownBy(() -> service.decide(principal(admin, "employee"), id, new DecisionRequest("ACCEPT", null, null)))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(feedbackBells(rep)).isEmpty();
    }

    @Test
    void acceptWithEtaRecordsDecisionAndTellsTheReporterByBellOnly() {
        long id = pending();
        clearInvocations(mailer);

        FeedbackDto accepted = service.decide(principal(admin, "employee"), id,
            new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null));

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.eta()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(decidedBy(id)).isEqualTo(admin);
        List<NotificationDto> bells = feedbackBells(rep);
        assertThat(bells).hasSize(1);
        assertThat(bells.get(0).type()).isEqualTo("FEEDBACK_DECIDED");
        assertThat(bells.get(0).link()).isEqualTo(LINK);
        // Thai Buddhist-era date, never ISO.
        assertThat(bells.get(0).message()).contains("5 ธันวาคม 2569").doesNotContain("2026-12-05");
        // Bell only: the reporter is not emailed about a decision.
        verifyNoInteractions(mailer);
        assertThat(feedbackBells(otherRep)).isEmpty();
    }

    @Test
    void denyNeedsAReasonOfAtLeastFiveCharsAndAtMost2000() {
        long id = pending();
        for (String reason : new String[] {null, "", "    ", "สั้น", "ก".repeat(2001)}) {
            assertThatThrownBy(() -> service.decide(principal(admin, "employee"), id,
                new DecisionRequest("DENY", null, reason)))
                .as("reason=%s", reason == null ? "null" : "len" + reason.length())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        }
        assertThat(statusOf(id)).isEqualTo("PENDING");

        FeedbackDto denied = service.decide(principal(admin, "employee"), id,
            new DecisionRequest("DENY", null, "ห้าตัวพอดี"));

        assertThat(denied.status()).isEqualTo("DENIED");
        assertThat(denied.denyReason()).isEqualTo("ห้าตัวพอดี");
        assertThat(denied.eta()).isNull();
        List<NotificationDto> bells = feedbackBells(rep);
        assertThat(bells).hasSize(1);
        assertThat(bells.get(0).type()).isEqualTo("FEEDBACK_DECIDED");
        assertThat(bells.get(0).message()).contains("ห้าตัวพอดี");
    }

    @Test
    void unknownDecisionIs400_unknownIdIs404() {
        long id = pending();

        assertThatThrownBy(() -> service.decide(principal(admin, "employee"), id, new DecisionRequest("MAYBE", null, null)))
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.decide(principal(admin, "employee"), id + 999,
            new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null)))
            .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    @Test
    void aDecidedRequestCanNotBeDecidedAgain_noChangeNoSecondNotification() {
        long accepted = pending();
        service.decide(principal(admin, "employee"), accepted, new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null));
        long denied = pending();
        service.decide(principal(admin, "employee"), denied, new DecisionRequest("DENY", null, "ไม่อยู่ในแผนปีนี้"));
        long before = bellCount(rep);

        for (DecisionRequest again : List.of(
            new DecisionRequest("DENY", null, "เปลี่ยนใจแล้ว"),
            new DecisionRequest("ACCEPT", LocalDate.of(2027, 1, 1), null))) {
            assertThatThrownBy(() -> service.decide(principal(admin, "employee"), accepted, again))
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
            assertThatThrownBy(() -> service.decide(principal(admin, "employee"), denied, again))
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        }

        assertThat(statusOf(accepted)).isEqualTo("ACCEPTED");
        assertThat(statusOf(denied)).isEqualTo("DENIED");
        assertThat(bellCount(rep)).isEqualTo(before);
    }

    @Test
    void nonAdminsCanNotDecide_includingCeoHrAndTheReporter_andNothingChanges() {
        long id = pending();
        long before = bellCount(rep);

        for (UserPrincipal denied : List.of(principal(rep, "sales"), principal(ceo, "ceo"), principal(hr, "hr"))) {
            assertThatThrownBy(() -> service.decide(denied, id, new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null)))
                .as(denied.role())
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
            assertThatThrownBy(() -> service.decide(denied, id, new DecisionRequest("DENY", null, "ไม่รับเรื่องนี้")))
                .as(denied.role())
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        }
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(bellCount(rep)).isEqualTo(before);
    }

    // ── Update: ETA + progress on an accepted request ───────────────────────────────────────────────────────

    @Test
    void progressMovesInAnyDirectionBetweenTheFourAcceptedStates_andNotifiesTheReporterEachTime() {
        long id = acceptedRequest();
        long before = bellCount(rep);
        UserPrincipal adminActor = principal(admin, "employee");

        for (String status : List.of("TESTING", "RELEASED", "IN_PROGRESS", "ACCEPTED", "RELEASED")) {
            FeedbackDto updated = service.update(adminActor, id, new UpdateRequest(status, null));
            assertThat(updated.status()).isEqualTo(status);
        }

        assertThat(bellCount(rep)).isEqualTo(before + 5);
        // Thai step labels and a พ.ศ. date, never raw codes. Newest first: the last change was RELEASED.
        assertThat(feedbackBells(rep).get(0).message()).contains("ใช้งานได้แล้ว").contains("5 ธันวาคม 2569")
            .doesNotContain("RELEASED").doesNotContain("2026-12-05");
        assertThat(feedbackBells(rep).get(1).message()).contains("ความคืบหน้า: รับเรื่อง").doesNotContain("ACCEPTED");
        assertThat(feedbackBells(rep).get(4).message()).contains("ความคืบหน้า: ทดสอบ").doesNotContain("TESTING");
        assertThat(feedbackBells(rep)).extracting(NotificationDto::type).contains("FEEDBACK_UPDATED");
        assertThat(feedbackBells(rep).get(0).link()).isEqualTo(LINK);
    }

    @Test
    void changingOnlyTheEtaNotifiesTheReporter_aNoOpUpdateDoesNot() {
        long id = acceptedRequest();
        UserPrincipal adminActor = principal(admin, "employee");
        long before = bellCount(rep);

        FeedbackDto moved = service.update(adminActor, id, new UpdateRequest(null, LocalDate.of(2026, 12, 19)));
        assertThat(moved.eta()).isEqualTo(LocalDate.of(2026, 12, 19));
        assertThat(moved.status()).isEqualTo("ACCEPTED");
        assertThat(bellCount(rep)).isEqualTo(before + 1);

        service.update(adminActor, id, new UpdateRequest("ACCEPTED", LocalDate.of(2026, 12, 19)));
        service.update(adminActor, id, new UpdateRequest(null, null));
        assertThat(bellCount(rep)).isEqualTo(before + 1);
    }

    @Test
    void progressIsRefusedUnlessAccepted_pendingAndDeniedStayPut() {
        long pending = pending();
        long denied = pending();
        service.decide(principal(admin, "employee"), denied, new DecisionRequest("DENY", null, "ไม่อยู่ในแผนปีนี้"));
        UserPrincipal adminActor = principal(admin, "employee");

        for (long id : List.of(pending, denied)) {
            assertThatThrownBy(() -> service.update(adminActor, id, new UpdateRequest("IN_PROGRESS", null)))
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
            assertThatThrownBy(() -> service.update(adminActor, id, new UpdateRequest(null, LocalDate.of(2026, 12, 19))))
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        }
        assertThat(statusOf(pending)).isEqualTo("PENDING");
        assertThat(statusOf(denied)).isEqualTo("DENIED");
    }

    @Test
    void updateCanNotSetPendingDeniedOrNonsenseStatus() {
        long id = acceptedRequest();

        for (String status : new String[] {"PENDING", "DENIED", "DONE", ""}) {
            assertThatThrownBy(() -> service.update(principal(admin, "employee"), id, new UpdateRequest(status, null)))
                .as(status)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        }
        assertThat(statusOf(id)).isEqualTo("ACCEPTED");
    }

    @Test
    void nonAdminsCanNotUpdate_notEvenTheReporter() {
        long id = acceptedRequest();
        long before = bellCount(rep);

        for (UserPrincipal denied : List.of(principal(rep, "sales"), principal(ceo, "ceo"), principal(hr, "hr"))) {
            assertThatThrownBy(() -> service.update(denied, id, new UpdateRequest("RELEASED", null)))
                .as(denied.role())
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        }
        assertThat(statusOf(id)).isEqualTo("ACCEPTED");
        assertThat(bellCount(rep)).isEqualTo(before);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private long pending() {
        return service.submit(principal(rep, "sales"), submit("BUG", "QUOTATIONS", DETAILS, null, null)).id();
    }

    private long acceptedRequest() {
        long id = pending();
        service.decide(principal(admin, "employee"), id, new DecisionRequest("ACCEPT", LocalDate.of(2026, 12, 5), null));
        return id;
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

    private Integer rowCount() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM hr.feedback_request", Integer.class);
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
