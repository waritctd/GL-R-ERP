package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.mail.MailSendException;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Audience;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Change;
import th.co.glr.hr.notification.ReleaseNoticeDtos.EmailResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.EmailStatus;
import th.co.glr.hr.notification.ReleaseNoticeDtos.PublishResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.PublishWithEmailResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * The enforcement half of the release-notice gate, through the REAL ReleaseNoticeService, its real repositories
 * and a real Postgres (CLAUDE.md "Permission changes must ship evidence"). Who is targeted is decided by the real
 * DivisionAccessPolicy#roleFor over real hr.employee / hr.division / hr.position rows, so a sales-rep fixture is a
 * sales rep for the same reason it is one at login. Only the Mailer (the provider) is a mock.
 *
 * <p>Written wrong-way-round first: a caller outside {ceo, hr} gets 403 and NOTHING is written or mailed; an
 * employee whose role no change targets gets no row and no mail; a second publish or email adds nothing; a
 * reader's mail never names another role's entry.
 *
 * <p>The sender runs synchronously after commit here (hand-wired, no @Async proxy), so an outcome row exists by
 * the time the call returns. In production it arrives a moment later; the "sending" state covers that gap.
 */
class ReleaseNoticeIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String RELEASE_ID = "2026-11-16";
    private static final String LINK = "/whats-new/2026-11-16";
    private static final String RELEASE_TITLE = "อัปเดตระบบขายและที่อยู่ใหม่ของพอร์ทัล";

    private static final String MY_WORK = "หน้า งานของฉัน รวมสิ่งที่ต้องทำวันนี้";
    private static final String MY_WORK_WHY = "เห็นงานที่ต้องทำวันนี้ในหน้าเดียว";
    private static final String BOARD = "ภาพรวมดีล: ใครถือดีลไหน";
    private static final String BOARD_WHY = "ผู้จัดการเห็นดีลทั้งทีมในหน้าเดียว";
    private static final String RECEIPTS = "นำเข้ารายการรับเงินและจับคู่กับดีล";
    private static final String RECEIPTS_WHY = "ฝ่ายบัญชีจับคู่เงินเข้ากับดีลได้เอง";

    /** Sales + managers + account only: warehouse, hr, import, qc and plain employees are NOT targeted. */
    private static final Request REQUEST = request(RELEASE_ID, false);

    private static final List<String> NON_MANAGER_ROLES =
        List.of("sales", "sales_manager", "import", "account", "warehouse", "qc", "employee");

    private static final String REP_MAIL = "rep@example.com";
    private static final String SM_MAIL = "sm@example.com";
    private static final String CEO_MAIL = "ceo@example.com";
    private static final String AC_MAIL = "ac@example.com";
    private static final String WH2_MAIL = "wh2@example.com";

    private Mailer mailer;
    private NotificationEmailService emailService;
    private AuditService audit;
    private ReleaseNoticeService service;

    private long rep;
    private long repWithoutEmail;
    private long inactiveRep;
    private long salesManager;
    private long ceo;
    private long account;
    private long warehouse;
    private long warehouseWithQuotationGrant;
    private long hr;

    @BeforeEach
    void wire() {
        mailer = mock(Mailer.class);
        // A real provider: the release notice refuses a Mailer that does not deliver.
        when(mailer.delivers()).thenReturn(true);
        emailService = new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://glr-hub.com");
        audit = new AuditService(new AuditLogRepository(jdbc), new ObjectMapper());
        service = service(new ReleaseNoticeRepository(jdbc), realDelivery(audit), Duration.ofMinutes(30));

        long sa = insertDivision("SA", "SA-ฝ่ายขาย");
        long ac = insertDivision("AC", "AC-ฝ่ายบัญชี");
        long wh = insertDivision("WH", "WH-คลังสินค้า");
        long hrDivision = insertDivision("HR", "HR-บุคคล");
        long md = insertDivision("MD", "MD-ผู้บริหารระดับสูง");

        rep = insertEmployee("REP-1", sa, true, null, REP_MAIL, "ยา", false);
        repWithoutEmail = insertEmployee("REP-2", sa, true, null, "", "หมวย", false);
        inactiveRep = insertEmployee("REP-3", sa, false, null, "gone@example.com", "ออก", false);
        salesManager = insertEmployee("SM-1", sa, true, "ผู้จัดการฝ่ายขาย", SM_MAIL, "วาริส", false);
        ceo = insertEmployee("CEO-1", md, true, null, CEO_MAIL, "ซีอีโอ", false);
        account = insertEmployee("AC-1", ac, true, null, AC_MAIL, "บัญชี", false);
        warehouse = insertEmployee("WH-1", wh, true, null, "wh@example.com", "คลัง", false);
        // roleFor turns an employee-tier holder of can_create_quotation into `sales` — targeting must agree.
        warehouseWithQuotationGrant = insertEmployee("WH-2", wh, true, null, WH2_MAIL, "คลังขาย", true);
        hr = insertEmployee("HR-1", hrDivision, true, null, "hr@example.com", "บุคคล", false);
    }

    // ── Wrong-way-round: the gate ────────────────────────────────────────────────────────────────────────────

    @Test
    void everyNonManagerRoleIsRefusedPublish_andNoRowIsInserted() {
        for (String role : NON_MANAGER_ROLES) {
            assertThatThrownBy(() -> service.publish(actor(rep, role), REQUEST))
                .as(role)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        }
        assertThat(releaseNoteRowCount()).isZero();
        assertThat(auditRowCount()).isZero();
        verifyNoInteractions(mailer);
    }

    // ── Publish (bell only, kept for the bundle deployed before publish-with-email) ─────────────────────────

    @Test
    void hrPublish_notifiesExactlyTheActiveEmployeesWhoseRoleIsTargeted() {
        PublishResult result = service.publish(actor(hr, "hr"), REQUEST);

        assertThat(result).isEqualTo(new PublishResult(6, 6, 0));
        for (long targeted : List.of(rep, repWithoutEmail, salesManager, ceo, account, warehouseWithQuotationGrant)) {
            List<NotificationDto> rows = releaseNotes(targeted);
            assertThat(rows).as("employee %d", targeted).hasSize(1);
            NotificationDto row = rows.get(0);
            assertThat(row.type()).isEqualTo("RELEASE_NOTE");
            assertThat(row.link()).isEqualTo(LINK);
            assertThat(row.read()).isFalse();
            assertThat(row.title()).isEqualTo("มีอะไรใหม่: " + RELEASE_TITLE);
            assertThat(row.message()).contains(RELEASE_TITLE);
        }
        // The cannot-reach cases: not targeted, or not active.
        assertThat(releaseNotes(warehouse)).isEmpty();
        assertThat(releaseNotes(hr)).isEmpty();
        assertThat(releaseNotes(inactiveRep)).isEmpty();
        // The bell row's count is per role: a rep hears about 1 change, a manager about 2.
        assertThat(releaseNotes(rep).get(0).message()).contains("1 เรื่อง");
        assertThat(releaseNotes(salesManager).get(0).message()).contains("2 เรื่อง");
    }

    @Test
    void publishingTwiceDoesNotDuplicate() {
        service.publish(actor(hr, "hr"), REQUEST);
        PublishResult second = service.publish(actor(ceo, "ceo"), REQUEST);

        assertThat(second).isEqualTo(new PublishResult(6, 0, 6));
        assertThat(releaseNotes(rep)).hasSize(1);
        assertThat(releaseNoteRowCount()).isEqualTo(6);
    }

    // ── Publish WITH email: one press, bell + mail ──────────────────────────────────────────────────────────

    @Test
    void publishWithEmail_bellsAndMailsTheTargets_eachOnlyTheirOwnEntries() {
        PublishWithEmailResult result = service.publishWithEmail(actor(hr, "hr"), REQUEST);

        assertThat(result).isEqualTo(new PublishWithEmailResult(6, 6, 0, new EmailResult(6, 5, 0, 1, 0, null), null));
        assertThat(releaseNoteRowCount()).isEqualTo(6);
        Map<String, Sent> mail = sentByRecipient(5);
        assertThat(mail).containsOnlyKeys(REP_MAIL, SM_MAIL, CEO_MAIL, AC_MAIL, WH2_MAIL);

        // A rep is told about the rep's entry, with its why — and NOT about the manager's or account's entries.
        Sent repMail = mail.get(REP_MAIL);
        for (String part : List.of(repMail.html(), repMail.text())) {
            assertThat(part).contains("คุณยา").contains(MY_WORK).contains(MY_WORK_WHY)
                .contains("https://glr-hub.com/whats-new/2026-11-16")
                .doesNotContain(BOARD).doesNotContain(BOARD_WHY)
                .doesNotContain(RECEIPTS).doesNotContain(RECEIPTS_WHY);
        }
        assertThat(repMail.subject()).contains("16 พ.ย. 2569").contains(RELEASE_TITLE);
        // The can_create_quotation grant makes WH-2 a rep: same mail as a rep, nothing else.
        assertThat(mail.get(WH2_MAIL).html()).contains(MY_WORK_WHY).doesNotContain(BOARD).doesNotContain(RECEIPTS);
        assertThat(mail.get(SM_MAIL).html()).contains(MY_WORK).contains(BOARD).contains(BOARD_WHY)
            .doesNotContain(RECEIPTS).doesNotContain(RECEIPTS_WHY);
        // The bell row and the mail come from the same per-role filter: same count for the same reader.
        assertThat(releaseNotes(salesManager).get(0).message()).contains("2 เรื่อง");
        assertThat(mail.get(SM_MAIL).text()).contains("มี 2 เรื่องที่เกี่ยวกับงานของคุณ");
        assertThat(releaseNotes(rep).get(0).message()).contains("1 เรื่อง");
        assertThat(mail.get(REP_MAIL).text()).contains("มี 1 เรื่องที่เกี่ยวกับงานของคุณ");
        assertThat(mail.get(AC_MAIL).html()).contains(RECEIPTS).contains(RECEIPTS_WHY)
            .doesNotContain(MY_WORK).doesNotContain(MY_WORK_WHY).doesNotContain(BOARD);
        // No attachment path.
        verify(mailer, never()).sendWithAttachment(anyString(), anyString(), anyString(), anyString(), any());
        verify(mailer, never()).sendWithAttachments(anyString(), anyString(), anyString(), anyList());
        // Every queued mail has an attempt AND a sent outcome; nobody untargeted has either.
        assertThat(auditCount("RELEASE_NOTE_EMAILED")).isEqualTo(5);
        assertThat(auditCount("RELEASE_NOTE_EMAIL_SENT")).isEqualTo(5);
        assertThat(auditCount("RELEASE_NOTE_EMAIL_FAILED")).isZero();
        assertThat(auditRowsFor(warehouse)).isZero();
        assertThat(auditRowsFor(hr)).isZero();
        assertThat(auditRowsFor(inactiveRep)).isZero();

        EmailStatus status = service.audience(actor(ceo, "ceo"), REQUEST).email();
        assertThat(status).isEqualTo(new EmailStatus(5, 0, 0, 0, 0, 1, List.of("หมวย"), List.of(), List.of()));
    }

    @Test
    void anEmailStageFailureDoesNotRollBackTheBell() {
        ReleaseNoticeRepository broken = new ReleaseNoticeRepository(jdbc) {
            @Override
            public Map<Long, EmailState> emailStates(String releaseId, Duration inFlightWindow) {
                throw new IllegalStateException("simulated read failure");
            }
        };
        ReleaseNoticeService withBrokenEmail = service(broken, realDelivery(audit), Duration.ofMinutes(30));

        PublishWithEmailResult result = withBrokenEmail.publishWithEmail(actor(hr, "hr"), REQUEST);

        assertThat(result.inserted()).isEqualTo(6);
        assertThat(result.email()).isNull();
        assertThat(result.emailError()).isNotBlank();
        assertThat(releaseNoteRowCount()).isEqualTo(6);
        verifyNoInteractions(mailer);
        assertThat(auditCount("RELEASE_NOTE_EMAILED")).isZero();
    }

    // ── Failure, retry, and the crash window ────────────────────────────────────────────────────────────────

    @Test
    void aFailedSendIsRecordedAndTheRetryMailsExactlyThatPerson() {
        doThrow(rejected()).doNothing()
            .when(mailer).sendHtml(eq(SM_MAIL), anyString(), anyString(), anyString(), anyList());

        PublishWithEmailResult first = service.publishWithEmail(actor(hr, "hr"), REQUEST);

        assertThat(first.email()).isEqualTo(new EmailResult(6, 5, 0, 1, 0, null));
        assertThat(auditCount("RELEASE_NOTE_EMAIL_FAILED")).isEqualTo(1);
        Audience afterFailure = service.audience(actor(hr, "hr"), REQUEST);
        assertThat(afterFailure.email()).isEqualTo(
            new EmailStatus(4, 1, 0, 0, 0, 1, List.of("หมวย"), List.of("วาริส"), List.of()));
        assertThat(afterFailure.pendingEmail()).isEqualTo(1);
        assertThat(afterFailure.alreadyEmailed()).isEqualTo(4);

        // The retry action mails only the failed person (and never the four who got it).
        EmailResult retry = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(retry).isEqualTo(new EmailResult(6, 1, 4, 1, 0, null));
        verify(mailer, times(2)).sendHtml(eq(SM_MAIL), anyString(), anyString(), anyString(), anyList());
        for (String address : List.of(REP_MAIL, CEO_MAIL, AC_MAIL, WH2_MAIL)) {
            verify(mailer, times(1)).sendHtml(eq(address), anyString(), anyString(), anyString(), anyList());
        }
        assertThat(service.audience(actor(hr, "hr"), REQUEST).email())
            .isEqualTo(new EmailStatus(5, 0, 0, 0, 0, 1, List.of("หมวย"), List.of(), List.of()));
        assertThat(service.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 0, 5, 1, 0, null));
    }

    // ── The crash window (fix round 1): a send is "in flight" from its OWN start, and a send that never started is
    //    known not sent. A STARTED row is written (under the email lock) just before each provider call. ──────

    @Test
    void aQueuedBatchThatStartsAfterARetryIsSkipped_soNobodyGetsTwo() {
        List<List<ReleaseNoticeDelivery.Delivery>> held = new ArrayList<>();
        service(new ReleaseNoticeRepository(jdbc), holding(held), Duration.ofMinutes(30))
            .publishWithEmail(actor(hr, "hr"), REQUEST);
        ReleaseNoticeService later = service(new ReleaseNoticeRepository(jdbc), realDelivery(audit), Duration.ZERO);
        assertThat(later.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 5, 0, 1, 0, null));

        // The original batch finally leaves the queue: every one of its attempts is superseded, so it sends nothing.
        realDelivery(audit).deliver(actor(hr, "hr"), RELEASE_ID, held.get(0));

        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThat(later.audience(actor(hr, "hr"), REQUEST).email().sent()).isEqualTo(5);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private static Request request(String releaseId, boolean resendUnconfirmed) {
        return new Request(releaseId, RELEASE_TITLE, List.of(
            new Change(MY_WORK, List.of("sales", "sales_manager", "ceo"), MY_WORK_WHY),
            new Change(BOARD, List.of("sales_manager", "ceo"), BOARD_WHY),
            new Change(RECEIPTS, List.of("account"), RECEIPTS_WHY)),
            releaseId, resendUnconfirmed ? Boolean.TRUE : null);
    }

    /** A sender that keeps each batch and sends nothing: a run still in the queue, or a crash before any start. */
    private ReleaseNoticeDelivery holding(List<List<ReleaseNoticeDelivery.Delivery>> held) {
        return new ReleaseNoticeDelivery(emailService, audit, new ReleaseNoticeRepository(jdbc), transactionManager) {
            @Override
            public void deliver(UserPrincipal actor, String releaseId, List<Delivery> deliveries) {
                held.add(deliveries);
            }
        };
    }

    /** What the Resend mailer throws for an HTTP error response: the provider certainly did not accept the mail. */
    private static MailSendException rejected() {
        return new MailSendException("Resend send failed (status=422)",
            new com.resend.core.exception.ResendException(422, "{\"name\":\"validation_error\",\"message\":\"bad\"}"));
    }

    private ReleaseNoticeDelivery realDelivery(AuditService auditService) {
        return new ReleaseNoticeDelivery(emailService, auditService, new ReleaseNoticeRepository(jdbc), transactionManager);
    }

    private ReleaseNoticeService service(ReleaseNoticeRepository repository, ReleaseNoticeDelivery delivery,
                                         Duration inFlightWindow) {
        return transactional(new ReleaseNoticeService(
            new EmployeeAuthRepository(jdbc),
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            repository,
            delivery,
            audit,
            transactionManager,
            inFlightWindow));
    }

    private record Sent(String subject, String html, String text) {
    }

    private Map<String, Sent> sentByRecipient(int expectedCalls) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer, times(expectedCalls))
            .sendHtml(to.capture(), subject.capture(), html.capture(), text.capture(), anyList());
        Map<String, Sent> byRecipient = new HashMap<>();
        for (int i = 0; i < to.getAllValues().size(); i++) {
            byRecipient.put(to.getAllValues().get(i), new Sent(subject.getAllValues().get(i),
                html.getAllValues().get(i), text.getAllValues().get(i)));
        }
        return byRecipient;
    }

    private static UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "@example.com", "ผู้ทดสอบ", role, employeeId, true,
            LocalDate.of(2026, 1, 1), false, null, false);
    }

    private List<NotificationDto> releaseNotes(long employeeId) {
        return new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP).findByEmployeeId(employeeId).stream()
            .filter(n -> "RELEASE_NOTE".equals(n.type()))
            .toList();
    }

    private Integer releaseNoteRowCount() {
        return jdbc.getJdbcTemplate().queryForObject(
            "SELECT COUNT(*) FROM hr.notification WHERE type = 'RELEASE_NOTE'", Integer.class);
    }

    private Integer auditRowCount() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM hr.audit_log", Integer.class);
    }

    private Integer auditCount(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.audit_log WHERE action = :action",
            Map.of("action", action), Integer.class);
    }

    private Integer auditRowsFor(long employeeId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.audit_log WHERE entity = 'employee' AND entity_id = :id
               AND action LIKE 'RELEASE_NOTE%'
            """, Map.of("id", employeeId), Integer.class);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th) VALUES (:code, :name) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long divisionId(String code) {
        return jdbc.queryForObject("SELECT division_id FROM hr.division WHERE source_code = :code",
            Map.of("code", code), Long.class);
    }

    private long insertEmployee(String code, long divisionId, boolean active, String positionTh, String email,
                                String firstName, boolean canCreateQuotation) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("firstName", firstName);
        params.put("email", email);
        params.put("divisionId", divisionId);
        params.put("active", active);
        params.put("grant", canCreateQuotation);
        params.put("positionId", positionTh == null ? null : ensurePosition(positionTh));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, email, division_id, position_id, is_active,
                                     can_create_quotation)
            VALUES (:code, :firstName, :email, :divisionId, :positionId, :active, :grant)
            RETURNING employee_id
            """, params, Long.class);
    }

    private long ensurePosition(String nameTh) {
        List<Long> existing = jdbc.queryForList(
            "SELECT position_id FROM hr.position WHERE name_th = :name LIMIT 1", Map.of("name", nameTh), Long.class);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        return jdbc.queryForObject("INSERT INTO hr.position (name_th) VALUES (:name) RETURNING position_id",
            Map.of("name", nameTh), Long.class);
    }
}
