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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
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

    @Test
    void everyNonManagerRoleIsRefusedPublishWithEmail_andNothingIsWrittenOrSent() {
        for (String role : NON_MANAGER_ROLES) {
            assertThatThrownBy(() -> service.publishWithEmail(actor(rep, role), REQUEST))
                .as(role)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        }
        assertThat(releaseNoteRowCount()).isZero();
        assertThat(auditRowCount()).isZero();
        verifyNoInteractions(mailer);
    }

    @Test
    void everyNonManagerRoleIsRefusedEmail_includingTheUnconfirmedResend_andNothingIsMailedOrRecorded() {
        Request resend = request(RELEASE_ID, true);
        for (String role : NON_MANAGER_ROLES) {
            for (Request request : List.of(REQUEST, resend)) {
                assertThatThrownBy(() -> service.email(actor(rep, role), request))
                    .as(role)
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
            }
        }
        verifyNoInteractions(mailer);
        assertThat(auditRowCount()).isZero();
        assertThat(releaseNoteRowCount()).isZero();
    }

    @Test
    void everyNonManagerRoleIsRefusedTheAudienceCount() {
        for (String role : NON_MANAGER_ROLES) {
            assertThatThrownBy(() -> service.audience(actor(rep, role), REQUEST))
                .as(role)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        }
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
    void publishAloneNeverSendsEmail() {
        service.publish(actor(ceo, "ceo"), REQUEST);

        verifyNoInteractions(mailer);
    }

    @Test
    void publishingTwiceDoesNotDuplicate() {
        service.publish(actor(hr, "hr"), REQUEST);
        PublishResult second = service.publish(actor(ceo, "ceo"), REQUEST);

        assertThat(second).isEqualTo(new PublishResult(6, 0, 6));
        assertThat(releaseNotes(rep)).hasSize(1);
        assertThat(releaseNoteRowCount()).isEqualTo(6);
    }

    @Test
    void republishingReachesOnlyEmployeesWhoJoinedSince() {
        service.publish(actor(hr, "hr"), REQUEST);
        long newRep = insertEmployee("REP-9", divisionId("SA"), true, null, "new@example.com", "ใหม่", false);

        PublishResult second = service.publish(actor(hr, "hr"), REQUEST);

        assertThat(second).isEqualTo(new PublishResult(7, 1, 6));
        assertThat(releaseNotes(newRep)).hasSize(1);
        assertThat(releaseNotes(rep)).hasSize(1);
    }

    @Test
    void aReadNoticeIsNotResentOnRepublish() {
        service.publish(actor(hr, "hr"), REQUEST);
        jdbc.update("UPDATE hr.notification SET is_read = TRUE WHERE employee_id = :id", Map.of("id", rep));

        service.publish(actor(hr, "hr"), REQUEST);

        assertThat(releaseNotes(rep)).hasSize(1);
        assertThat(releaseNotes(rep).get(0).read()).isTrue();
    }

    // ── Publish WITH email: one press, bell + mail ──────────────────────────────────────────────────────────

    @Test
    void publishWithEmail_bellsAndMailsTheTargets_eachOnlyTheirOwnEntries() {
        PublishWithEmailResult result = service.publishWithEmail(actor(hr, "hr"), REQUEST);

        assertThat(result).isEqualTo(new PublishWithEmailResult(6, 6, 0, new EmailResult(6, 5, 0, 1), null));
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
    void republishingWithEmailNeverReMailsAnyoneAlreadySent() {
        service.publishWithEmail(actor(hr, "hr"), REQUEST);

        PublishWithEmailResult second = service.publishWithEmail(actor(ceo, "ceo"), REQUEST);

        assertThat(second).isEqualTo(new PublishWithEmailResult(6, 0, 6, new EmailResult(6, 0, 5, 1), null));
        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThat(releaseNoteRowCount()).isEqualTo(6);
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
        doThrow(new MailSendException("provider refused", null)).doNothing()
            .when(mailer).sendHtml(eq(SM_MAIL), anyString(), anyString(), anyString(), anyList());

        PublishWithEmailResult first = service.publishWithEmail(actor(hr, "hr"), REQUEST);

        assertThat(first.email()).isEqualTo(new EmailResult(6, 5, 0, 1));
        assertThat(auditCount("RELEASE_NOTE_EMAIL_FAILED")).isEqualTo(1);
        Audience afterFailure = service.audience(actor(hr, "hr"), REQUEST);
        assertThat(afterFailure.email()).isEqualTo(
            new EmailStatus(4, 1, 0, 0, 0, 1, List.of("หมวย"), List.of("วาริส"), List.of()));
        assertThat(afterFailure.pendingEmail()).isEqualTo(1);
        assertThat(afterFailure.alreadyEmailed()).isEqualTo(4);

        // The retry action mails only the failed person (and never the four who got it).
        EmailResult retry = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(retry).isEqualTo(new EmailResult(6, 1, 4, 1));
        verify(mailer, times(2)).sendHtml(eq(SM_MAIL), anyString(), anyString(), anyString(), anyList());
        for (String address : List.of(REP_MAIL, CEO_MAIL, AC_MAIL, WH2_MAIL)) {
            verify(mailer, times(1)).sendHtml(eq(address), anyString(), anyString(), anyString(), anyList());
        }
        assertThat(service.audience(actor(hr, "hr"), REQUEST).email())
            .isEqualTo(new EmailStatus(5, 0, 0, 0, 0, 1, List.of("หมวย"), List.of(), List.of()));
        assertThat(service.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 0, 5, 1));
    }

    @Test
    void republishingAfterAFailureMailsOnlyTheFailedPerson() {
        doThrow(new MailSendException("provider refused", null)).doNothing()
            .when(mailer).sendHtml(eq(AC_MAIL), anyString(), anyString(), anyString(), anyList());
        service.publishWithEmail(actor(hr, "hr"), REQUEST);

        PublishWithEmailResult again = service.publishWithEmail(actor(hr, "hr"), REQUEST);

        assertThat(again).isEqualTo(new PublishWithEmailResult(6, 0, 6, new EmailResult(6, 1, 4, 1), null));
        verify(mailer, times(2)).sendHtml(eq(AC_MAIL), anyString(), anyString(), anyString(), anyList());
        verify(mailer, times(6)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aRetryWithNoOutcomeYetIsNotAnsweredByTheEarlierFailure() {
        // First attempt fails (outcome FAILED). The retry's attempt then gets no outcome (crash). The old FAILED row
        // belongs to the OLD attempt, so it must not make the person "failed" again — that would re-send a mail
        // which may already have gone out.
        doThrow(new MailSendException("provider refused", null))
            .when(mailer).sendHtml(eq(SM_MAIL), anyString(), anyString(), anyString(), anyList());
        service.publishWithEmail(actor(hr, "hr"), REQUEST);
        ReleaseNoticeDelivery crashed = new ReleaseNoticeDelivery(emailService, audit, transactionManager) {
            @Override
            public void deliver(UserPrincipal actor, String releaseId, List<Delivery> deliveries) {
            }
        };
        assertThat(service(new ReleaseNoticeRepository(jdbc), crashed, Duration.ofMinutes(30))
            .email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 1, 4, 1));

        EmailStatus status = service.audience(actor(hr, "hr"), REQUEST).email();
        assertThat(status.failed()).isZero();
        assertThat(status.sending()).isEqualTo(1);
        assertThat(service.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 0, 5, 1));
        verify(mailer, times(1)).sendHtml(eq(SM_MAIL), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aCrashAfterTheAttemptIsNeverReportedSentAndNeverSilentlyResent() {
        // The process dies after the attempt rows commit and before the sender runs: no mail, no outcome.
        ReleaseNoticeDelivery crashed = new ReleaseNoticeDelivery(emailService, audit, transactionManager) {
            @Override
            public void deliver(UserPrincipal actor, String releaseId, List<Delivery> deliveries) {
            }
        };
        service(new ReleaseNoticeRepository(jdbc), crashed, Duration.ofMinutes(30))
            .publishWithEmail(actor(hr, "hr"), REQUEST);
        verifyNoInteractions(mailer);

        // Inside the in-flight window: "sending" — not sent, and nothing re-sends it, not even the opt-in.
        Audience young = service.audience(actor(hr, "hr"), REQUEST);
        assertThat(young.email()).isEqualTo(new EmailStatus(0, 0, 5, 0, 0, 1, List.of("หมวย"), List.of(), List.of()));
        assertThat(service.publishWithEmail(actor(hr, "hr"), REQUEST).email()).isEqualTo(new EmailResult(6, 0, 5, 1));
        assertThat(service.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 0, 5, 1));
        assertThat(service.email(actor(hr, "hr"), request(RELEASE_ID, true))).isEqualTo(new EmailResult(6, 0, 5, 1));
        verifyNoInteractions(mailer);

        // Past the window: "unconfirmed", named — still not sent, still not re-sent by publish or the plain retry.
        ReleaseNoticeService later = service(new ReleaseNoticeRepository(jdbc), realDelivery(audit), Duration.ZERO);
        EmailStatus stale = later.audience(actor(hr, "hr"), REQUEST).email();
        assertThat(stale.sent()).isZero();
        assertThat(stale.unconfirmed()).isEqualTo(5);
        assertThat(stale.unconfirmedNames()).containsExactlyInAnyOrder("ยา", "วาริส", "ซีอีโอ", "บัญชี", "คลังขาย");
        assertThat(later.publishWithEmail(actor(hr, "hr"), REQUEST).email()).isEqualTo(new EmailResult(6, 0, 5, 1));
        assertThat(later.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 0, 5, 1));
        verifyNoInteractions(mailer);

        // Only the explicit opt-in re-sends them, once.
        assertThat(later.email(actor(ceo, "ceo"), request(RELEASE_ID, true))).isEqualTo(new EmailResult(6, 5, 0, 1));
        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThat(later.audience(actor(hr, "hr"), REQUEST).email().sent()).isEqualTo(5);
        assertThat(later.email(actor(ceo, "ceo"), request(RELEASE_ID, true))).isEqualTo(new EmailResult(6, 0, 5, 1));
        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void anOutcomeThatCannotBeRecordedStaysUnconfirmed_neverSent() {
        AuditService outcomeWriteFails = new AuditService(new AuditLogRepository(jdbc), new ObjectMapper()) {
            @Override
            public void record(UserPrincipal actor, String action, String entity, Long entityId, Object before,
                               Object after) {
                if (action.startsWith("RELEASE_NOTE_EMAIL_")) {
                    throw new IllegalStateException("simulated outcome write failure");
                }
                super.record(actor, action, entity, entityId, before, after);
            }
        };
        service(new ReleaseNoticeRepository(jdbc), realDelivery(outcomeWriteFails), Duration.ofMinutes(30))
            .publishWithEmail(actor(hr, "hr"), REQUEST);
        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());

        EmailStatus status = service(new ReleaseNoticeRepository(jdbc), realDelivery(audit), Duration.ZERO)
            .audience(actor(hr, "hr"), REQUEST).email();

        assertThat(status.sent()).isZero();
        assertThat(status.failed()).isZero();
        assertThat(status.unconfirmed()).isEqualTo(5);
    }

    @Test
    void anAttemptRowFromBeforeOutcomesWereRecordedIsUnconfirmed_notSent() {
        // Production's 16 Nov rows: RELEASE_NOTE_EMAILED with only the release id, and nothing after them.
        jdbc.update("""
            INSERT INTO hr.audit_log (action, entity, entity_id, after_json)
            VALUES ('RELEASE_NOTE_EMAILED', 'employee', :id, jsonb_build_object('releaseId', :releaseId))
            """, Map.of("id", rep, "releaseId", RELEASE_ID));
        ReleaseNoticeService later = service(new ReleaseNoticeRepository(jdbc), realDelivery(audit), Duration.ZERO);

        EmailStatus status = later.audience(actor(hr, "hr"), REQUEST).email();

        assertThat(status.sent()).isZero();
        assertThat(status.unconfirmed()).isEqualTo(1);
        assertThat(status.unconfirmedNames()).containsExactly("ยา");
        assertThat(later.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 4, 1, 1));
        verify(mailer, never()).sendHtml(eq(REP_MAIL), anyString(), anyString(), anyString(), anyList());
    }

    // ── Email (the retry / "not yet emailed" action) ────────────────────────────────────────────────────────

    @Test
    void ceoEmail_mailsEachTargetedEmployeeWithAnAddress_listingOnlyTheirOwnRolesChanges() {
        EmailResult result = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(result).isEqualTo(new EmailResult(6, 5, 0, 1));
        Map<String, Sent> mail = sentByRecipient(5);
        assertThat(mail).containsOnlyKeys(REP_MAIL, SM_MAIL, CEO_MAIL, AC_MAIL, WH2_MAIL);
        assertThat(mail.get(REP_MAIL).html()).contains("เรียน คุณยา").contains(MY_WORK)
            .doesNotContain(BOARD).doesNotContain(RECEIPTS)
            .contains("https://glr-hub.com/whats-new/2026-11-16");
        // Emailing is not publishing: no bell row appears.
        assertThat(releaseNoteRowCount()).isZero();
    }

    @Test
    void emailingTwiceDoesNotDoubleSend() {
        service.email(actor(hr, "hr"), REQUEST);
        EmailResult second = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(second).isEqualTo(new EmailResult(6, 0, 5, 1));
        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void anEmployeeWhoGainsAnAddressIsMailedOnTheNextCall_andOnlyThey() {
        service.email(actor(hr, "hr"), REQUEST);
        jdbc.update("UPDATE hr.employee SET email = 'muay@example.com' WHERE employee_id = :id",
            Map.of("id", repWithoutEmail));

        EmailResult second = service.email(actor(hr, "hr"), REQUEST);

        assertThat(second).isEqualTo(new EmailResult(6, 1, 5, 0));
        verify(mailer, times(6)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void audienceCountsWhatPublishAndEmailWouldStillDo_andNamesThePeopleWithoutAnAddress() {
        assertThat(service.audience(actor(hr, "hr"), REQUEST)).isEqualTo(new Audience(6, 0, 6, 0, 5, 1,
            new EmailStatus(0, 0, 0, 0, 5, 1, List.of("หมวย"), List.of(), List.of())));

        service.publishWithEmail(actor(hr, "hr"), REQUEST);

        assertThat(service.audience(actor(ceo, "ceo"), REQUEST)).isEqualTo(new Audience(6, 6, 0, 5, 0, 1,
            new EmailStatus(5, 0, 0, 0, 0, 1, List.of("หมวย"), List.of(), List.of())));
    }

    @Test
    void anotherReleaseIsIndependent() {
        service.publishWithEmail(actor(hr, "hr"), REQUEST);
        Request next = new Request("2026-12-01", "ฉบับถัดไป", REQUEST.changes(), "2026-12-01", null);

        assertThat(service.publishWithEmail(actor(hr, "hr"), next))
            .isEqualTo(new PublishWithEmailResult(6, 6, 0, new EmailResult(6, 5, 0, 1), null));
    }

    // ── Email reset: undoing a run whose sends all failed (hr.audit_log is append-only) ─────────────────────

    @Test
    void aResetMarkerLetsARunBeSentAgain_andVoidsItsRecordedOutcomesToo() {
        service.email(actor(hr, "hr"), REQUEST);
        insertEmailReset(RELEASE_ID);

        assertThat(service.audience(actor(ceo, "ceo"), REQUEST)).isEqualTo(new Audience(6, 0, 6, 0, 5, 1,
            new EmailStatus(0, 0, 0, 0, 5, 1, List.of("หมวย"), List.of(), List.of())));
        EmailResult afterReset = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(afterReset).isEqualTo(new EmailResult(6, 5, 0, 1));
        verify(mailer, times(10)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aResetAlsoReopensUnconfirmedLegacyRows() {
        jdbc.update("""
            INSERT INTO hr.audit_log (action, entity, entity_id, after_json)
            VALUES ('RELEASE_NOTE_EMAILED', 'employee', :id, jsonb_build_object('releaseId', :releaseId))
            """, Map.of("id", rep, "releaseId", RELEASE_ID));
        insertEmailReset(RELEASE_ID);

        assertThat(service.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 5, 0, 1));
        verify(mailer, times(1)).sendHtml(eq(REP_MAIL), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void afterAResetAndAResend_emailingAgainStillDoesNotDoubleSend() {
        service.email(actor(hr, "hr"), REQUEST);
        insertEmailReset(RELEASE_ID);
        service.email(actor(hr, "hr"), REQUEST);

        EmailResult third = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(third).isEqualTo(new EmailResult(6, 0, 5, 1));
        verify(mailer, times(10)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aResetForAnotherReleaseDoesNotReopenThisOne() {
        service.email(actor(hr, "hr"), REQUEST);
        insertEmailReset("2026-12-01");

        EmailResult second = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(second).isEqualTo(new EmailResult(6, 0, 5, 1));
        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void twoResetsEachReopenTheRun_andEachRunStillSendsOnce() {
        service.email(actor(hr, "hr"), REQUEST);
        insertEmailReset(RELEASE_ID);
        assertThat(service.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 5, 0, 1));
        insertEmailReset(RELEASE_ID);
        assertThat(service.email(actor(hr, "hr"), REQUEST)).isEqualTo(new EmailResult(6, 5, 0, 1));

        // within the third run nothing is double-sent
        assertThat(service.email(actor(ceo, "ceo"), REQUEST)).isEqualTo(new EmailResult(6, 0, 5, 1));
        verify(mailer, times(15)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aMarkerWithTheRightActionButAnotherEntityDoesNotReopenTheRun() {
        service.email(actor(hr, "hr"), REQUEST);
        jdbc.update("""
            INSERT INTO hr.audit_log (action, entity, after_json)
            VALUES ('RELEASE_NOTE_EMAIL_RESET', 'employee', jsonb_build_object('releaseId', :releaseId))
            """, Map.of("releaseId", RELEASE_ID));

        assertThat(service.email(actor(ceo, "ceo"), REQUEST)).isEqualTo(new EmailResult(6, 0, 5, 1));
        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void aMarkerWithNoReleaseIdInItsJsonReopensNothingAndDoesNotError() {
        service.email(actor(hr, "hr"), REQUEST);
        jdbc.update("""
            INSERT INTO hr.audit_log (action, entity, after_json)
            VALUES ('RELEASE_NOTE_EMAIL_RESET', 'release_note', NULL),
                   ('RELEASE_NOTE_EMAIL_RESET', 'release_note', jsonb_build_object('other', 'x'))
            """, Map.of());

        assertThat(service.email(actor(ceo, "ceo"), REQUEST)).isEqualTo(new EmailResult(6, 0, 5, 1));
        verify(mailer, times(5)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void theDocumentedOperatorLockClassIsTheOneTheEmailPathLocks() {
        // The runbook SQL in ReleaseNoticeRepository's javadoc spells the class id in decimal (0x52454C4E).
        assertThat(ReleaseNoticeRepository.LOCK_CLASS_ID).isEqualTo(1380273230);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private static Request request(String releaseId, boolean resendUnconfirmed) {
        return new Request(releaseId, RELEASE_TITLE, List.of(
            new Change(MY_WORK, List.of("sales", "sales_manager", "ceo"), MY_WORK_WHY),
            new Change(BOARD, List.of("sales_manager", "ceo"), BOARD_WHY),
            new Change(RECEIPTS, List.of("account"), RECEIPTS_WHY)),
            releaseId, resendUnconfirmed ? Boolean.TRUE : null);
    }

    private ReleaseNoticeDelivery realDelivery(AuditService auditService) {
        return new ReleaseNoticeDelivery(emailService, auditService, transactionManager);
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

    /**
     * The operator's append-only "forget the earlier email run" marker, run exactly as the runbook says: take the
     * same advisory lock the email path holds, then insert, in ONE transaction (see EMAIL_RESET_ACTION javadoc).
     */
    private void insertEmailReset(String releaseId) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(1380273230, hashtext('email:' || :releaseId))",
                Map.of("releaseId", releaseId), (rs, rowNum) -> 0);
            jdbc.update("""
                INSERT INTO hr.audit_log (action, entity, after_json)
                VALUES ('RELEASE_NOTE_EMAIL_RESET', 'release_note', jsonb_build_object('releaseId', :releaseId))
                """, Map.of("releaseId", releaseId));
        });
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
