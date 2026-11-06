package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/** With the owner code left blank (the default) the feature is off for everyone. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "glr.view-as.owner-employee-code=")
class ViewAsGateOffIntegrationTest extends AbstractRealSessionIntegrationTest {

    @Test
    void blankConfig_refusesEvenAnAnyEmployee() throws Exception {
        Account account = employee("IT", "IT-ไอที");
        Account target = employee("IT", "IT-ไอที");
        Browser browser = signIn(account);

        assertThat(browser.postJson("/api/view-as", "{\"employeeId\":" + target.id() + "}").statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/view-as/candidates").statusCode()).isEqualTo(403);
    }
}
