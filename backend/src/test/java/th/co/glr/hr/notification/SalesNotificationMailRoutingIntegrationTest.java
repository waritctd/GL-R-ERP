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
