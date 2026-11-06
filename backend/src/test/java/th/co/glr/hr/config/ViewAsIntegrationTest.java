package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Read-only "view as employee", end to end: real server, real login, real CSRF, real
 * {@code @PreAuthorize}. The owner gate is the made-up employee code {@code VAS-OWNER}.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "glr.view-as.owner-employee-code=VAS-OWNER")
class ViewAsIntegrationTest extends AbstractRealSessionIntegrationTest {

    static final String MESSAGE = "โหมดดูแทนพนักงานเป็นแบบอ่านอย่างเดียว ไม่สามารถทำรายการนี้ได้";
    static final ObjectMapper JSON = new ObjectMapper();

    Account owner;
    Account other;
    Account hrTarget;
    Account plainTarget;
    Account inactive;

    @BeforeEach
    void fixtures() {
        jdbc.update("UPDATE hr.employee SET employee_code = 'VAS-OLD-' || employee_id WHERE employee_code = 'VAS-OWNER'",
            Map.of());
        owner = employee("IT", "IT-ไอที");
        jdbc.update("UPDATE hr.employee SET employee_code = 'VAS-OWNER' WHERE employee_id = :id", Map.of("id", owner.id()));
        other = employee("IT", "IT-ไอที");
        hrTarget = employee("HR", "HR-ทรัพยากรบุคคล");
        plainTarget = employee("IT", "IT-ไอที");
        inactive = employee("IT", "IT-ไอที");
        setActive(inactive, false);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    Browser viewing(Account target) throws Exception {
        Browser browser = signIn(owner);
        HttpResponse<String> start = browser.postJson("/api/view-as", "{\"employeeId\":" + target.id() + "}");
        assertThat(start.statusCode()).as("start view-as: %s", start.body()).isEqualTo(200);
        return browser;
    }

    JsonNode me(Browser browser) throws Exception {
        HttpResponse<String> response = browser.get(ME);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    List<Map<String, Object>> audit(String action, long entityId) {
        return jdbc.queryForList(
            "SELECT actor_user_id, entity_id FROM hr.audit_log WHERE action = :a AND entity_id = :e ORDER BY id",
            Map.of("a", action, "e", entityId));
    }

    // ── 1. gate ──────────────────────────────────────────────────────────────────────────────

    @Test
    void nonOwner_cannotStartOrListCandidates() throws Exception {
        Browser browser = signIn(other);

        assertThat(browser.postJson("/api/view-as", "{\"employeeId\":" + plainTarget.id() + "}").statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/view-as/candidates").statusCode()).isEqualTo(403);
        assertThat(me(browser).path("canViewAs").asBoolean()).isFalse();
    }

    @Test
    void owner_seesCandidates_activeOthersOnly() throws Exception {
        Browser browser = signIn(owner);

        HttpResponse<String> response = browser.get("/api/view-as/candidates");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).containsPattern("\"employeeId\":" + hrTarget.id() + "[,}]");
        assertThat(response.body()).doesNotContainPattern("\"employeeId\":" + owner.id() + "[,}]");
        assertThat(response.body()).doesNotContainPattern("\"employeeId\":" + inactive.id() + "[,}]");
        assertThat(me(browser).path("canViewAs").asBoolean()).isTrue();
    }

    // ── 2. switching the identity ────────────────────────────────────────────────────────────

    @Test
    void start_makesMeReturnTheTarget_andNamesTheRealActor() throws Exception {
        Browser browser = viewing(hrTarget);

        JsonNode me = me(browser);

        assertThat(me.path("user").path("id").asLong()).isEqualTo(hrTarget.id());
        assertThat(me.path("user").path("role").asText()).isEqualTo("hr");
        assertThat(me.path("viewAs").path("actorId").asLong()).isEqualTo(owner.id());
    }

    // ── 3. faithful authorization ────────────────────────────────────────────────────────────

    @Test
    void authorizationIsTheTargets_notTheOwners() throws Exception {
        assertThat(signIn(owner).get(HR_ONLY).statusCode()).as("owner alone cannot reach HR-only").isEqualTo(403);

        assertThat(viewing(hrTarget).get(HR_ONLY).statusCode()).as("as the HR target").isEqualTo(200);
        assertThat(viewing(plainTarget).get(HR_ONLY).statusCode()).as("as a plain employee").isEqualTo(403);
    }

    // ── 4. read-only ─────────────────────────────────────────────────────────────────────────

    @Test
    void writesAreRefused_andNothingChanges() throws Exception {
        Browser browser = viewing(hrTarget);
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM hr.employee WHERE employee_id = :id",
            Map.of("id", hrTarget.id()), String.class);
        String nameBefore = jdbc.queryForObject("SELECT first_name_th FROM hr.employee WHERE employee_id = :id",
            Map.of("id", hrTarget.id()), String.class);

        List<HttpResponse<String>> refused = List.of(
            browser.postJson("/api/auth/change-password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"An0therPassw0rd!\"}"),
            browser.patch("/api/employees/" + hrTarget.id(), "{\"nameTh\":\"Changed Name\"}"),
            browser.delete("/api/payroll/tax-allowances/declarations/1"),
            browser.postJson("/api/leave", "{\"leaveTypeCode\":\"SICK\",\"startDate\":\"2030-01-07\","
                + "\"endDate\":\"2030-01-07\",\"reason\":\"x\"}"));

        for (HttpResponse<String> response : refused) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            assertThat(response.body()).contains(MESSAGE);
        }
        assertThat(jdbc.queryForObject("SELECT password_hash FROM hr.employee WHERE employee_id = :id",
            Map.of("id", hrTarget.id()), String.class)).isEqualTo(hashBefore);
        assertThat(jdbc.queryForObject("SELECT first_name_th FROM hr.employee WHERE employee_id = :id",
            Map.of("id", hrTarget.id()), String.class)).isEqualTo(nameBefore);
    }

    @Test
    void getsWithDerivedWrites_stillWork_leaveBalances() throws Exception {
        HttpResponse<String> response = viewing(plainTarget).get("/api/leave/balances");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    // ── 5. render-only POST exception ────────────────────────────────────────────────────────

    @Test
    void draftPdfPost_isNotRefusedByTheFilter() throws Exception {
        HttpResponse<String> response = viewing(plainTarget)
            .postJson("/api/payroll/tax-allowances/declarations/me/form.pdf", "{\"taxYear\":2026}");

        assertThat(response.body()).doesNotContain(MESSAGE);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
    }

    // ── 6. audit attribution ─────────────────────────────────────────────────────────────────

    @Test
    void auditRowsWrittenWhileViewing_nameTheRealActor_andWhoseDataWasRead() throws Exception {
        String path = "/api/payroll/tax-allowances/declarations/me?year=2026";
        // A row the owner writes for himself, NOT viewing: no viewingAs.
        Browser own = signIn(owner);
        assertThat(own.get(path).statusCode()).isEqualTo(200);

        Browser browser = viewing(plainTarget);
        String targetName = me(browser).path("user").path("name").asText();
        HttpResponse<String> response = browser.get(path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        List<Long> actors = jdbc.queryForList("""
            SELECT actor_user_id FROM hr.audit_log
            WHERE action = 'VIEW_OWN_TAX_ALLOWANCE_DECLARATIONS' AND actor_user_id IN (:o, :t)
            """, Map.of("o", owner.id(), "t", plainTarget.id()), Long.class);
        assertThat(actors).hasSize(2).containsOnly(owner.id());

        List<String> viewingAs = jdbc.queryForList("""
            SELECT after_json->'viewingAs'->>'employeeId' || '|' || (after_json->'viewingAs'->>'name') FROM hr.audit_log
            WHERE action = 'VIEW_OWN_TAX_ALLOWANCE_DECLARATIONS' AND actor_user_id = :o
              AND after_json->'viewingAs' IS NOT NULL
            """, Map.of("o", owner.id()), String.class);
        assertThat(viewingAs).containsExactly(plainTarget.id() + "|" + targetName);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM hr.audit_log WHERE action = 'VIEW_OWN_TAX_ALLOWANCE_DECLARATIONS'
              AND actor_user_id = :o AND after_json->'viewingAs' IS NULL
            """, Map.of("o", owner.id()), Long.class)).as("owner's own, non-viewing row").isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM hr.audit_log WHERE action = 'VIEW_AS_START' AND entity_id = :t
              AND after_json->'viewingAs' IS NOT NULL
            """, Map.of("t", plainTarget.id()), Long.class)).as("VIEW_AS_START is not swapped").isZero();
    }

    // ── 7. start / stop ──────────────────────────────────────────────────────────────────────

    @Test
    void stop_restoresTheOwner_andRecordsStartAndStop() throws Exception {
        Browser browser = viewing(hrTarget);
        assertThat(browser.postJson("/api/auth/change-password", "{}").statusCode()).isEqualTo(403);

        HttpResponse<String> stop = browser.delete("/api/view-as");

        assertThat(stop.statusCode()).as(stop.body()).isEqualTo(200);
        JsonNode me = me(browser);
        assertThat(me.path("user").path("id").asLong()).isEqualTo(owner.id());
        assertThat(me.path("viewAs").isNull()).isTrue();
        assertThat(browser.postJson("/api/auth/change-password", "{}").body())
            .as("no longer refused by the read-only filter").doesNotContain(MESSAGE);
        assertThat(audit("VIEW_AS_START", hrTarget.id())).extracting(r -> r.get("actor_user_id")).containsExactly(owner.id());
        assertThat(audit("VIEW_AS_STOP", hrTarget.id())).extracting(r -> r.get("actor_user_id")).containsExactly(owner.id());
    }

    @Test
    void switchingTarget_closesThePreviousView_andStopRowsAreNotSwapped() throws Exception {
        Browser browser = viewing(hrTarget);

        HttpResponse<String> switched = browser.postJson("/api/view-as", "{\"employeeId\":" + plainTarget.id() + "}");

        assertThat(switched.statusCode()).as(switched.body()).isEqualTo(200);
        assertThat(audit("VIEW_AS_STOP", hrTarget.id())).as("previous view closed")
            .extracting(r -> r.get("actor_user_id")).containsExactly(owner.id());
        assertThat(browser.delete("/api/view-as").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM hr.audit_log WHERE action IN ('VIEW_AS_START', 'VIEW_AS_STOP')
              AND actor_user_id = :o AND (after_json->'viewingAs' IS NOT NULL OR after_json->'targetEmployeeId' IS NULL)
            """, Map.of("o", owner.id()), Long.class)).as("start/stop rows keep their own shape").isZero();
    }

    // ── 8. validation ────────────────────────────────────────────────────────────────────────

    @Test
    void start_rejectsInactiveAndSelf() throws Exception {
        Browser browser = signIn(owner);

        int inactiveStatus = browser.postJson("/api/view-as", "{\"employeeId\":" + inactive.id() + "}").statusCode();
        int selfStatus = browser.postJson("/api/view-as", "{\"employeeId\":" + owner.id() + "}").statusCode();

        assertThat(inactiveStatus).isBetween(400, 404);
        assertThat(selfStatus).isEqualTo(400);
        assertThat(me(browser).path("viewAs").isNull()).isTrue();
    }

    // ── 9. login clears view-as ──────────────────────────────────────────────────────────────

    @Test
    void loggingInAsSomeoneElseOnTheSameBrowser_dropsTheView() throws Exception {
        Browser browser = viewing(hrTarget);

        assertThat(browser.signIn(other.email()).statusCode()).isEqualTo(200);

        JsonNode me = me(browser);
        assertThat(me.path("user").path("id").asLong()).isEqualTo(other.id());
        assertThat(me.path("viewAs").isNull()).isTrue();
    }

    // ── 10. logout ───────────────────────────────────────────────────────────────────────────

    @Test
    void logoutWhileViewing_recordsStop() throws Exception {
        Browser browser = viewing(hrTarget);

        assertThat(browser.post("/api/auth/logout").statusCode()).isBetween(200, 299);

        assertThat(audit("VIEW_AS_STOP", hrTarget.id())).extracting(r -> r.get("actor_user_id")).containsExactly(owner.id());
    }

    // ── 11. target deactivated mid-view ──────────────────────────────────────────────────────

    @Test
    void targetDeactivatedMidView_fallsBackToTheOwner_sessionStaysSignedIn() throws Exception {
        Browser browser = viewing(plainTarget);
        setActive(plainTarget, false);

        JsonNode me = me(browser);

        assertThat(me.path("user").path("id").asLong()).isEqualTo(owner.id());
        assertThat(me.path("viewAs").isNull()).isTrue();
        assertThat(browser.get(ME).statusCode()).as("owner still signed in").isEqualTo(200);
    }

    // ── 12. stop when not viewing ────────────────────────────────────────────────────────────

    @Test
    void stopWhenNotViewing_isHarmless_andWritesNoRow() throws Exception {
        Browser browser = signIn(owner);

        HttpResponse<String> stop = browser.delete("/api/view-as");

        assertThat(stop.statusCode()).as(stop.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hr.audit_log WHERE action = 'VIEW_AS_STOP' AND actor_user_id = :o",
            Map.of("o", owner.id()), Long.class)).isZero();
    }
}
