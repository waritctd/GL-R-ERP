package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Audience;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Change;
import th.co.glr.hr.notification.ReleaseNoticeDtos.EmailResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.PublishResult;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * The enforcement half of the release-notice gate, through the REAL ReleaseNoticeService, its real repositories
 * and a real Postgres (CLAUDE.md "Permission changes must ship evidence"). Who is targeted is decided by the real
 * DivisionAccessPolicy#roleFor over real hr.employee / hr.division / hr.position rows, so a sales-rep fixture is a
 * sales rep for the same reason it is one at login.
 *
 * <p>Written wrong-way-round first: a caller outside {ceo, hr} gets 403 and NOTHING is written or mailed; an
 * employee whose role no change targets gets no row and no mail; a second publish or email adds nothing.
 */
class ReleaseNoticeIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String RELEASE_ID = "2026-11-16";
    private static final String LINK = "/whats-new/2026-11-16";
    private static final String RELEASE_TITLE = "อัปเดตระบบขายและที่อยู่ใหม่ของพอร์ทัล";

    private static final String MY_WORK = "หน้า งานของฉัน รวมสิ่งที่ต้องทำวันนี้";
    private static final String BOARD = "ภาพรวมดีล: ใครถือดีลไหน";
    private static final String RECEIPTS = "นำเข้ารายการรับเงินและจับคู่กับดีล";

    /** Sales + managers + account only: warehouse, hr, import, qc and plain employees are NOT targeted. */
    private static final Request REQUEST = new Request(RELEASE_ID, RELEASE_TITLE, List.of(
        new Change(MY_WORK, List.of("sales", "sales_manager", "ceo")),
        new Change(BOARD, List.of("sales_manager", "ceo")),
        new Change(RECEIPTS, List.of("account"))));

    private static final List<String> NON_MANAGER_ROLES =
        List.of("sales", "sales_manager", "import", "account", "warehouse", "qc", "employee");

    private Mailer mailer;
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
        NotificationEmailService emailService =
            new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://glr-hub.com");
        AuditService audit = new AuditService(new AuditLogRepository(jdbc), new ObjectMapper());
        service = transactional(new ReleaseNoticeService(
            new EmployeeAuthRepository(jdbc),
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            new ReleaseNoticeRepository(jdbc),
            emailService,
            audit));

        long sa = insertDivision("SA", "SA-ฝ่ายขาย");
        long ac = insertDivision("AC", "AC-ฝ่ายบัญชี");
        long wh = insertDivision("WH", "WH-คลังสินค้า");
        long hrDivision = insertDivision("HR", "HR-บุคคล");
        long md = insertDivision("MD", "MD-ผู้บริหารระดับสูง");

        rep = insertEmployee("REP-1", sa, true, null, "rep@glr.co.th", "ยา", false);
        repWithoutEmail = insertEmployee("REP-2", sa, true, null, "", "หมวย", false);
        inactiveRep = insertEmployee("REP-3", sa, false, null, "gone@glr.co.th", "ออก", false);
        salesManager = insertEmployee("SM-1", sa, true, "ผู้จัดการฝ่ายขาย", "sm@glr.co.th", "วาริส", false);
        ceo = insertEmployee("CEO-1", md, true, null, "ceo@glr.co.th", "ซีอีโอ", false);
        account = insertEmployee("AC-1", ac, true, null, "ac@glr.co.th", "บัญชี", false);
        warehouse = insertEmployee("WH-1", wh, true, null, "wh@glr.co.th", "คลัง", false);
        // roleFor turns an employee-tier holder of can_create_quotation into `sales` — targeting must agree.
        warehouseWithQuotationGrant = insertEmployee("WH-2", wh, true, null, "wh2@glr.co.th", "คลังขาย", true);
        hr = insertEmployee("HR-1", hrDivision, true, null, "hr@glr.co.th", "บุคคล", false);
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
    void everyNonManagerRoleIsRefusedEmail_andNothingIsMailedOrRecorded() {
        for (String role : NON_MANAGER_ROLES) {
            assertThatThrownBy(() -> service.email(actor(rep, role), REQUEST))
                .as(role)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
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

    // ── Publish (bell) ──────────────────────────────────────────────────────────────────────────────────────

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
    void publishNeverSendsEmail() {
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
        long newRep = insertEmployee("REP-9", divisionId("SA"), true, null, "new@glr.co.th", "ใหม่", false);

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

    // ── Email (separate, explicit) ──────────────────────────────────────────────────────────────────────────

    @Test
    void ceoEmail_mailsEachTargetedEmployeeWithAnAddress_listingOnlyTheirOwnRolesChanges() {
        EmailResult result = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(result).isEqualTo(new EmailResult(6, 5, 0, 1));
        Map<String, String> htmlByRecipient = sentHtmlByRecipient(5);
        assertThat(htmlByRecipient).containsOnlyKeys(
            "rep@glr.co.th", "sm@glr.co.th", "ceo@glr.co.th", "ac@glr.co.th", "wh2@glr.co.th");

        String repMail = htmlByRecipient.get("rep@glr.co.th");
        assertThat(repMail).contains("เรียน คุณยา").contains(MY_WORK)
            .doesNotContain(BOARD).doesNotContain(RECEIPTS)
            .contains("https://glr-hub.com/whats-new/2026-11-16");
        assertThat(htmlByRecipient.get("sm@glr.co.th")).contains(MY_WORK).contains(BOARD).doesNotContain(RECEIPTS);
        assertThat(htmlByRecipient.get("ac@glr.co.th")).contains(RECEIPTS).doesNotContain(MY_WORK);
        // No attachment: only the HTML+text path is used.
        verify(mailer, never()).sendWithAttachment(anyString(), anyString(), anyString(), anyString(), any());
        verify(mailer, never()).sendWithAttachments(anyString(), anyString(), anyString(), anyList());
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
        jdbc.update("UPDATE hr.employee SET email = 'muay@glr.co.th' WHERE employee_id = :id",
            Map.of("id", repWithoutEmail));

        EmailResult second = service.email(actor(hr, "hr"), REQUEST);

        assertThat(second).isEqualTo(new EmailResult(6, 1, 5, 0));
        verify(mailer, times(6)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void audienceCountsWhatPublishAndEmailWouldStillDo() {
        assertThat(service.audience(actor(hr, "hr"), REQUEST)).isEqualTo(new Audience(6, 0, 6, 0, 5, 1));

        service.publish(actor(hr, "hr"), REQUEST);
        service.email(actor(hr, "hr"), REQUEST);

        assertThat(service.audience(actor(ceo, "ceo"), REQUEST)).isEqualTo(new Audience(6, 6, 0, 5, 0, 1));
    }

    @Test
    void anotherReleaseIsIndependent() {
        service.publish(actor(hr, "hr"), REQUEST);
        service.email(actor(hr, "hr"), REQUEST);
        Request next = new Request("2026-12-01", "ฉบับถัดไป", REQUEST.changes());

        assertThat(service.publish(actor(hr, "hr"), next)).isEqualTo(new PublishResult(6, 6, 0));
        assertThat(service.email(actor(hr, "hr"), next)).isEqualTo(new EmailResult(6, 5, 0, 1));
    }

    // ── Email reset: undoing a run whose sends all failed (hr.audit_log is append-only) ─────────────────────

    @Test
    void aResetMarkerLetsARunWhoseSendsFailedBeSentAgain() {
        service.email(actor(hr, "hr"), REQUEST);
        insertEmailReset(RELEASE_ID);

        assertThat(service.audience(actor(ceo, "ceo"), REQUEST)).isEqualTo(new Audience(6, 0, 6, 0, 5, 1));
        EmailResult afterReset = service.email(actor(ceo, "ceo"), REQUEST);

        assertThat(afterReset).isEqualTo(new EmailResult(6, 5, 0, 1));
        verify(mailer, times(10)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
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

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

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

    private Map<String, String> sentHtmlByRecipient(int expectedCalls) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mailer, times(expectedCalls)).sendHtml(to.capture(), anyString(), html.capture(), anyString(), anyList());
        Map<String, String> byRecipient = new HashMap<>();
        for (int i = 0; i < to.getAllValues().size(); i++) {
            byRecipient.put(to.getAllValues().get(i), html.getAllValues().get(i));
        }
        return byRecipient;
    }

    private static UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "@glr.co.th", "ผู้ทดสอบ", role, employeeId, true,
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
