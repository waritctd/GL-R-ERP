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

    // ── 1. gate ──────────────────────────────────────────────────────────────────────────────

    @Test
    void nonOwner_cannotStartOrListCandidates() throws Exception {
        Browser browser = signIn(other);

        assertThat(browser.postJson("/api/view-as", "{\"employeeId\":" + plainTarget.id() + "}").statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/view-as/candidates").statusCode()).isEqualTo(403);
        assertThat(me(browser).path("canViewAs").asBoolean()).isFalse();
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

}
