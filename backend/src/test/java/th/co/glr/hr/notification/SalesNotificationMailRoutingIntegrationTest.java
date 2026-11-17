package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Where a sales-pipeline notification email actually lands, against a real PostgreSQL database and the real
 * NotificationRepository → SalesNotificationMailRouter → NotificationEmailService chain. MUTATION-CHECK RECORD
 * (actually run against a real Postgres, not simulated). */
class SalesNotificationMailRoutingIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PORTAL = "https://portal.test.glr";

    private NotificationRepository notifications;
    private CapturingMailer mailer;

    @BeforeEach
    void wireTheRealChain() {
        mailer = new CapturingMailer();
        NotificationEmailService emailService = new NotificationEmailService(
            mailer, new BrandAssets(), "", "", PORTAL);
        notifications = new NotificationRepository(jdbc,
            new SalesNotificationMailRouter(emailService, new SalesMailRecipientRepository(jdbc)));
    }

    // ── Ruling 1 — the shared box REPLACES individual delivery ────────────────────────────────

    @Test
    void importRoleNotificationReachesTheSharedBoxAndNobodyPersonally() {
        long importDivision = insertDivision("PCIM", "จัดซื้อต่างประเทศ");
        insertEmployee("IMP-1", importDivision, "somsak.import@glr.co.th");
        insertEmployee("IMP-2", importDivision, "malee.import@glr.co.th");

        notifications.notifyByRoleForPricingRequest("import", 42L, "PRICING_REQUEST_SUBMITTED",
            "คำขอราคา PCR-2026-0001 รอการรับเรื่อง");

        assertThat(mailer.recipients()).containsExactly("import@glr.co.th");
        assertThat(mailer.recipients())
            .doesNotContain("somsak.import@glr.co.th", "malee.import@glr.co.th");
    }

    /** The assignedImportId path — PricingDecisionService returns a costing to the one import staffer who owns it,
     * by employee id. Ruling 1 still applies: they are reached through the shared box, not personally. */
    @Test
    void anImportStafferAddressedByIdIsStillOnlyReachedThroughTheSharedBox() {
        long importDivision = insertDivision("PCIM", "จัดซื้อต่างประเทศ");
        long assignedImporter = insertEmployee("IMP-3", importDivision, "assigned.importer@glr.co.th");

        notifications.notifyEmployeeForPricingRequest(assignedImporter, 42L, "PRICING_DECISION_RETURNED",
            "คำขอราคา PCR-2026-0001 ถูก CEO ตีกลับให้แก้ไขต้นทุน");

        assertThat(mailer.recipients()).containsExactly("import@glr.co.th");
        assertThat(mailer.recipients()).doesNotContain("assigned.importer@glr.co.th");
    }

    @Test
    void anAccountStafferAddressedByIdIsOnlyReachedThroughTheSharedBox() {
        long accountDivision = insertDivision("AC", "ฝ่ายบัญชี");
        long accountant = insertEmployee("ACC-1", accountDivision, "wichai.account@glr.co.th");

        notifications.notifyEmployee(accountant, 7L, "APPROVED", "Ticket PR-2026-0007 ได้รับการอนุมัติราคาแล้ว");

        assertThat(mailer.recipients()).containsExactly("account@glr.co.th");
        assertThat(mailer.recipients()).doesNotContain("wichai.account@glr.co.th");
    }

    /** GLA-32: notifyByRoleInternal's division switch used to cover only import, hr, ceo, sales and sales_manager —
     * no account arm at all, so an account-role fan-out wrote no in-app row and the early return meant
     * salesMailer.emailForRole was never even reached. */
    @Test
    void accountRoleFanOutInsertsAnInAppRowAndMailsOnlyTheSharedBox() {
        long accountEmployee = insertEmployee("ACC-2", insertDivision("AC", "ฝ่ายบัญชี"), "wichai.account@glr.co.th");

        notifications.notifyByRole("account", 7L, "DEPOSIT_NOTICE_ISSUED", "ออกใบแจ้งรับมัดจำ GLRD001 แล้ว");

        assertThat(countNotificationRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT employee_id FROM hr.notification WHERE employee_id = :id",
            Map.of("id", accountEmployee), Long.class)).isEqualTo(accountEmployee);
        assertThat(mailer.recipients()).containsExactly("account@glr.co.th");
        assertThat(mailer.recipients()).doesNotContain("wichai.account@glr.co.th");
    }

    /** The precedence guard mirrors DivisionAccessPolicy#roleFor: an executive-titled AC-division employee resolves
     * to ceo in Java, so this predicate must exclude them too rather than double-notifying. */
    @Test
    void accountRoleFanOutExcludesAnExecutiveTitledAcDivisionEmployee() {
        long accountDivision = insertDivision("AC", "ฝ่ายบัญชี");
        long executivePosition = insertPosition("AC-EXEC", "กรรมการ");
        insertEmployee("ACC-EXEC", accountDivision, executivePosition, "exec.account@glr.co.th", null);

        notifications.notifyByRole("account", 7L, "DEPOSIT_NOTICE_ISSUED", "ออกใบแจ้งรับมัดจำ GLRD001 แล้ว");

        assertThat(countNotificationRows()).isZero();
        assertThat(mailer.recipients()).containsExactly("account@glr.co.th");
        assertThat(mailer.recipients()).doesNotContain("exec.account@glr.co.th");
    }

    /** Opus review: pins the predicate against the naive alternative — a division coded ACX would satisfy
     * d.source_code ILIKE 'AC%' but must NOT satisfy the exact match this predicate actually uses (mirroring
     * DivisionAccessPolicy#roleFor's "ac".equals(divisionCode), not a prefix test). */
    @Test
    void accountRoleFanOutExcludesADivisionCodedAcxNotJustAc() {
        long acxDivision = insertDivision("ACX", "ฝ่ายอื่นที่ขึ้นต้นด้วยเอซี");
        insertEmployee("ACX-1", acxDivision, "acx.employee@glr.co.th");

        notifications.notifyByRole("account", 7L, "DEPOSIT_NOTICE_ISSUED", "ออกใบแจ้งรับมัดจำ GLRD001 แล้ว");

        assertThat(countNotificationRows()).isZero();
    }

    /** The fallback half of the SAME predicate: DivisionAccessPolicy#roleFor's divisionCode falls back to the
     * name_th prefix (split on the FIRST '-') whenever source_code is blank/NULL — real production rows take this
     * path (see the "hr" case's own Javadoc a few lines up, which this account case mirrors). */
    @Test
    void accountRoleFanOutMatchesTheNameThPrefixFallbackWhenSourceCodeIsNull() {
        long fallbackDivision = insertDivisionWithNullCode("AC-ฝ่ายบัญชี");
        long accountEmployee = insertEmployee("AC-FALLBACK", fallbackDivision, "fallback.account@glr.co.th");

        notifications.notifyByRole("account", 7L, "DEPOSIT_NOTICE_ISSUED", "ออกใบแจ้งรับมัดจำ GLRD001 แล้ว");

        assertThat(countNotificationRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT employee_id FROM hr.notification WHERE employee_id = :id",
            Map.of("id", accountEmployee), Long.class)).isEqualTo(accountEmployee);
    }

    /** The pre-existing e.is_active = TRUE guard (shared by every role, not new to this predicate) must still
     * exclude a deactivated account-division employee. */
    @Test
    void accountRoleFanOutExcludesAnInactiveAccountEmployee() {
        long accountDivision = insertDivision("AC", "ฝ่ายบัญชี");
        insertInactiveEmployee("ACC-INACTIVE", accountDivision, "inactive.account@glr.co.th");

        notifications.notifyByRole("account", 7L, "DEPOSIT_NOTICE_ISSUED", "ออกใบแจ้งรับมัดจำ GLRD001 แล้ว");

        assertThat(countNotificationRows()).isZero();
    }

    /** Isolates the ROUTER half of the account contract from the fan-out's own division predicate above — GLA-32
     * renamed this from theRouterSendsAccountRoleMailToTheSharedBoxIfThe FanOutEverGainsIt once
     * DepositNoticeService.issue became that real caller, so "if it ever gains one" is no longer accurate. */
    @Test
    void theRouterSendsAccountRoleMailToTheSharedBoxDirectly() {
        SalesNotificationMailRouter router = new SalesNotificationMailRouter(
            new NotificationEmailService(mailer, new BrandAssets(), "", "", PORTAL),
            new SalesMailRecipientRepository(jdbc));

        router.emailForRole("account", List.of(), "ราคาได้รับการอนุมัติ", "Ticket PR-2026-0007", "/tickets/7");

        assertThat(mailer.recipients()).containsExactly("account@glr.co.th");
    }

    // ── Ruling 2 — the CEO address is hardcoded ───────────────────────────────────────────────

    @Test
    void ceoNotificationGoesToTheHardcodedAddressRegardlessOfTheEmployeeRecord() {
        long executiveDivision = insertDivision("MD", "ผู้บริหาร");
        long managingDirectorPosition = insertPosition("MD1", "กรรมการผู้จัดการ");
        // The real กรรมการผู้จัดการ row, carrying an address that is NOT the hardcoded one. If the router ever
        // resolved the CEO address from hr.employee — the thing ruling 2 forbids — this address is what would show up
        // instead.
        insertEmployee("MD-1", executiveDivision, managingDirectorPosition, "someone.else@example.com", null);

        notifications.notifyByRoleForPricingRequest("ceo", 42L, "PRICING_COSTING_SUBMITTED",
            "คำขอราคา PCR-2026-0001 ส่งต้นทุนให้ CEO แล้ว");

        assertThat(mailer.recipients()).containsExactly("rarm@glr.co.th");
        assertThat(mailer.recipients()).doesNotContain("someone.else@example.com");
    }

    /** CeoApproverRule's documented empty set: with no active กรรมการผู้จัดการ the in-app queue goes silent.
     * Hardcoding the address is what keeps the email arriving anyway — which is the stated reason the owner ruled
     * for it, so it is pinned rather than assumed. */
    @Test
    void ceoNotificationStillMailsWhenNoManagingDirectorExistsToReceiveAnInAppRow() {
        notifications.notifyByRoleForPricingRequest("ceo", 42L, "PRICING_COSTING_SUBMITTED",
            "คำขอราคา PCR-2026-0001 ส่งต้นทุนให้ CEO แล้ว");

        assertThat(countNotificationRows()).isZero();
        assertThat(mailer.recipients()).containsExactly("rarm@glr.co.th");
    }

    // ── Ruling 3 — the rep, then their manager, then loudly nothing ───────────────────────────

    @Test
    void salesNotificationReachesTheRepPersonallyAndNeverASharedBox() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long rep = insertEmployee("SA-1", salesDivision, "napa.sales@glr.co.th");

        notifications.notifyEmployee(rep, 55L, "APPROVED", "Ticket PR-2026-0055 ได้รับการอนุมัติราคาแล้ว");

        assertThat(mailer.recipients()).containsExactly("napa.sales@glr.co.th");
        assertThat(mailer.recipients())
            .doesNotContain("import@glr.co.th", "account@glr.co.th", "rarm@glr.co.th");
    }

    @Test
    void aRepWithNoAddressFallsBackToTheirManager() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long manager = insertEmployee("SA-MGR", salesDivision, "praphan.manager@glr.co.th");
        long rep = insertEmployeeReportingTo("SA-2", salesDivision, null, manager);

        notifications.notifyEmployeeForPricingRequest(rep, 42L, "PICKED_UP",
            "คำขอราคา PCR-2026-0001 ถูกรับเรื่องแล้ว");

        assertThat(mailer.recipients()).containsExactly("praphan.manager@glr.co.th");
        // The mail is landing in the MANAGER's inbox, so it greets the manager — not the rep whose notification it is,
        // and not the generic fallback.
        assertThat(mailer.last().textBody()).contains("เรียน คุณประพันธ์,");
    }

    @Test
    void aRepAndManagerBothWithoutAnAddressSendsNothingAtAll() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long manager = insertEmployee("SA-MGR2", salesDivision, null);
        long rep = insertEmployeeReportingTo("SA-3", salesDivision, null, manager);

        notifications.notifyEmployeeForPricingRequest(rep, 42L, "PICKED_UP",
            "คำขอราคา PCR-2026-0001 ถูกรับเรื่องแล้ว");

        assertThat(mailer.sent()).isEmpty();
        // The in-app notification is untouched — this is a delivery gap, not a lost notification.
        assertThat(countNotificationRows()).isEqualTo(1);
    }

    /** Owner ruling 3 stops at the direct manager: "do not chain further up". The grandmanager here has a perfectly
     * good address and must still receive nothing. */
    @Test
    void theFallbackStopsAtTheDirectManagerAndDoesNotChainUpTheReportingLine() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long grandManager = insertEmployee("SA-GM", salesDivision, "director@glr.co.th");
        long manager = insertEmployeeReportingTo("SA-MGR3", salesDivision, null, grandManager);
        long rep = insertEmployeeReportingTo("SA-4", salesDivision, null, manager);

        notifications.notifyEmployeeForPricingRequest(rep, 42L, "PICKED_UP",
            "คำขอราคา PCR-2026-0001 ถูกรับเรื่องแล้ว");

        assertThat(mailer.recipients()).doesNotContain("director@glr.co.th");
        assertThat(mailer.sent()).isEmpty();
    }

    @Test
    void theSalesRoleFanOutMailsEachRepAtTheirOwnAddress() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        insertEmployee("SA-5", salesDivision, "rep.one@glr.co.th");
        insertEmployee("SA-6", salesDivision, "rep.two@glr.co.th");

        notifications.notifyByRole("sales", 55L, "APPROVED", "Ticket PR-2026-0055 ได้รับการอนุมัติราคาแล้ว");

        assertThat(mailer.recipients())
            .containsExactlyInAnyOrder("rep.one@glr.co.th", "rep.two@glr.co.th");
        assertThat(mailer.recipients())
            .doesNotContain("import@glr.co.th", "account@glr.co.th", "rarm@glr.co.th");
    }

    // ── The two traps this surface has already paid for ───────────────────────────────────────

    /** The subject is the Thai title the in-app row carries, never the machine type. The last copy round shipped
     * TRAVEL_PER_DIEM at employees as a raw enum string; here PRICING_DECISION_APPROVED is exactly the same shape
     * of value, sitting one field away from the subject line. */
    @Test
    void theSubjectIsTheThaiTitleAndNeverTheMachineType() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long rep = insertEmployee("SA-7", salesDivision, "subject.check@glr.co.th");

        notifications.notifyEmployeeForPricingRequest(rep, 42L, "PRICING_DECISION_APPROVED",
            "คำขอราคา PCR-2026-0001 ได้รับอนุมัติราคาขายแล้ว");

        assertThat(mailer.last().subject()).isEqualTo("[GL&R Portal] ราคาขายได้รับการอนุมัติแล้ว");
        assertThat(mailer.last().subject()).doesNotContain("PRICING_DECISION_APPROVED");
    }

    /**
     * The other half of the enum-leak trap. {@code TICKET_EVENT_TITLES} does not cover every event
     * kind the pipeline raises — {@code PRICING_REQUEST_PICKED_UP} and {@code FACTORY_PO_CREATED}
     * both fall through today — so the subject line of those emails is
     * whatever {@code ticketEventTitle}'s default produces. It must be the Thai generic, because the
     * alternative is mailing {@code FACTORY_PO_CREATED} at a person.
     */
    @Test
    void anUnmappedEventTypeStillSubjectsTheEmailInThaiRatherThanTheRawCode() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long rep = insertEmployee("SA-10", salesDivision, "unmapped.subject@glr.co.th");

        notifications.notifyEmployeeForPricingRequest(rep, 42L, "FACTORY_PO_CREATED",
            "สร้างใบสั่งซื้อโรงงาน 1 ฉบับสำหรับคำขอราคา PCR-2026-0001");

        assertThat(mailer.last().subject()).isEqualTo("[GL&R Portal] อัปเดตสถานะคำขอราคา");
        assertThat(mailer.last().subject()).doesNotContain("FACTORY_PO_CREATED");
    }

    /** Not one email in the previous programme carried a portal link; 3,548 unit tests passed and an e2e caught it. */
    @Test
    void theEmailCarriesAWorkingPortalLinkToTheNotificationsOwnTarget() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long rep = insertEmployee("SA-8", salesDivision, "link.check@glr.co.th");

        notifications.notifyEmployeeForPricingRequest(rep, 42L, "PRICING_DECISION_APPROVED",
            "คำขอราคา PCR-2026-0001 ได้รับอนุมัติราคาขายแล้ว");

        assertThat(mailer.last().htmlBody())
            .contains("href=\"" + PORTAL + "/pricing-requests/42\"")
            .contains("ดูรายละเอียดในระบบ");
        assertThat(mailer.last().textBody()).contains(PORTAL + "/pricing-requests/42");
    }

    @Test
    void aTicketNotificationLinksToTheTicketRouteNotThePricingRequestRoute() {
        long salesDivision = insertDivision("SA", "ฝ่ายขาย");
        long rep = insertEmployee("SA-9", salesDivision, "ticket.link@glr.co.th");

        notifications.notifyEmployee(rep, 55L, "APPROVED", "Ticket PR-2026-0055 ได้รับการอนุมัติราคาแล้ว");

        assertThat(mailer.last().htmlBody()).contains("href=\"" + PORTAL + "/tickets/55\"");
    }

    /** An unrecognised role writes no in-app row today; it must not invent an email either. */
    @Test
    void anUnroutableRoleWritesNothingAndMailsNothing() {
        notifications.notifyByRole("warehouse", 55L, "APPROVED", "ignored");

        assertThat(countNotificationRows()).isZero();
        assertThat(mailer.sent()).isEmpty();
    }

    /** Review fix: an exclusion that matches nobody in the role did not empty the fan-out, so the shared box is still mailed. */
    @Test
    void roleFanOutWithAnExclusionNobodyInTheRoleMatches_stillMailsTheSharedBox() {
        long outsider = insertEmployee("OUT-1", insertDivision("SA", "ฝ่ายขาย"), "outsider@glr.co.th");

        notifications.notifyByRoleAtLink("import", "PRICING_COSTING_SUBMITTED", "ข้อความ", "/tickets/1", outsider);

        assertThat(countNotificationRows()).isZero();
        assertThat(mailer.recipients()).containsExactly("import@glr.co.th");
    }

    /** The exclusion IS what emptied the fan-out (the only member did the thing themselves): no bell, no mail. */
    @Test
    void roleFanOutWhoseOnlyMemberIsTheExcludedActor_mailsNothing() {
        long onlyImporter = insertEmployee("IMP-9", insertDivision("PCIM", "จัดซื้อต่างประเทศ"), "only.importer@glr.co.th");

        notifications.notifyByRoleAtLink("import", "PRICING_COSTING_SUBMITTED", "ข้อความ", "/tickets/1", onlyImporter);

        assertThat(countNotificationRows()).isZero();
        assertThat(mailer.sent()).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private Long countNotificationRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification", Map.of(), Long.class);
    }

    private long insertDivision(String code, String name) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th) VALUES (:code, :name) RETURNING division_id
            """, Map.of("code", code, "name", name), Number.class);
        return id.longValue();
    }

    /** Map.of rejects a null value outright, so a NULL source_code — the name_th-prefix fallback's own precondition
     * — needs MapSqlParameterSource instead. */
    private long insertDivisionWithNullCode(String name) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th) VALUES (:code, :name) RETURNING division_id
            """, new MapSqlParameterSource().addValue("code", null).addValue("name", name), Number.class);
        return id.longValue();
    }

    /** hr.position.source_code is VARCHAR(10), so the code is short and the Thai title — the string
     * CeoApproverRule#SQL_PREDICATE actually matches on — goes in name_th. */
    private long insertPosition(String code, String nameTh) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", code, "name", nameTh), Number.class);
        return id.longValue();
    }

    private long insertEmployee(String code, long divisionId, String email) {
        return insertEmployee(code, divisionId, null, email, null);
    }

    private long insertEmployeeReportingTo(String code, long divisionId, String email, long managerId) {
        return insertEmployee(code, divisionId, null, email, managerId);
    }

    /** first_name_th is seeded from a fixed map so the greeting assertions have a real Thai first name to match;
     * email and reports_to_employee_id are genuinely nullable here, which is the whole point of the fallback
     * cases. */
    private long insertEmployee(String code, long divisionId, Long positionId, String email, Long managerId) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, email, division_id, position_id,
                                     reports_to_employee_id, is_active)
            VALUES (:code, :name, :email, :divisionId, :positionId, :managerId, TRUE)
            RETURNING employee_id
            """,
            new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("name", FIRST_NAMES.getOrDefault(code, code))
                .addValue("email", email)
                .addValue("divisionId", divisionId)
                .addValue("positionId", positionId)
                .addValue("managerId", managerId),
            Number.class);
        return id.longValue();
    }

    /** Same shape as #insertEmployee(String, long, String), is_active = FALSE — for pinning the pre-existing
     * e.is_active = TRUE guard against the account branch. */
    private long insertInactiveEmployee(String code, long divisionId, String email) {
        Number id = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, email, division_id, is_active)
            VALUES (:code, :name, :email, :divisionId, FALSE)
            RETURNING employee_id
            """,
            new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("name", FIRST_NAMES.getOrDefault(code, code))
                .addValue("email", email)
                .addValue("divisionId", divisionId),
            Number.class);
        return id.longValue();
    }

    private static final Map<String, String> FIRST_NAMES = Map.of(
        "SA-MGR", "ประพันธ์",
        "SA-MGR2", "สมพร",
        "SA-MGR3", "สมพร",
        "SA-GM", "อนันต์");

    /** Records what would have gone on the wire. Deliberately a hand-written double rather than a Mockito mock: the
     * assertions here are about the exact recipient string and the rendered body/subject, and a captor chain over
     * four overloads reads far worse than a list of records. */
    private static final class CapturingMailer implements Mailer {
        record SentMail(String to, String subject, String htmlBody, String textBody) {}

        private final List<SentMail> sent = new ArrayList<>();

        List<SentMail> sent() {
            return sent;
        }

        List<String> recipients() {
            return sent.stream().map(SentMail::to).toList();
        }

        SentMail last() {
            assertThat(sent).as("expected at least one email to have been sent").isNotEmpty();
            return sent.get(sent.size() - 1);
        }

        @Override
        public void send(String to, String subject, String body) {
            sent.add(new SentMail(to, subject, null, body));
        }

        @Override
        public void sendHtml(String to, String subject, String htmlBody, String textBody,
                             List<InlineImage> inlineImages) {
            sent.add(new SentMail(to, subject, htmlBody, textBody));
        }

        @Override
        public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
            sent.add(new SentMail(to, subject, null, body));
        }

        @Override
        public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
            sent.add(new SentMail(to, subject, null, body));
        }
    }
}
