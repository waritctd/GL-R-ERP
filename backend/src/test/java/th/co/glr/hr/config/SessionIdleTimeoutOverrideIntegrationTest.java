package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/** Verifies APP_SESSION_TIMEOUT can restore idle expiry while sessions inside the limit remain valid. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "APP_SESSION_TIMEOUT=30m")
class SessionIdleTimeoutOverrideIntegrationTest extends AbstractRealSessionIntegrationTest {

    @Test
    void withAnIdleLimitConfigured_aNewSessionCarriesExactlyThatLimit() throws Exception {
        Browser browser = signIn(employee("IT", "IT-ไอที"));

        assertThat(stored(browser).getMaxInactiveInterval()).isEqualTo((int) Duration.ofMinutes(30).toSeconds());
    }

    @Test
    void withAnIdleLimitConfigured_aSessionInsideItKeepsWorking() throws Exception {
        Browser browser = signIn(employee("IT", "IT-ไอที"));

        ageSession(browser, Duration.ofMinutes(10));
        runExpirySweep();

        assertThat(browser.get(ME).statusCode()).isEqualTo(200);
    }

    @Test
    void withAnIdleLimitConfigured_aSessionIdlePastItIsRefused() throws Exception {
        Browser browser = signIn(employee("IT", "IT-ไอที"));
        assertThat(browser.get(ME).statusCode()).isEqualTo(200);

        ageSession(browser, Duration.ofMinutes(31));

        assertThat(browser.get(ME).statusCode()).isEqualTo(401);
        assertThat(stored(browser)).isNull();
    }

    /** …and the background sweep alone, with no request at all, clears it out. */
    @Test
    void withAnIdleLimitConfigured_theExpirySweepRemovesAnIdleSession() throws Exception {
        Browser browser = signIn(employee("IT", "IT-ไอที"));

        ageSession(browser, Duration.ofMinutes(31));
        runExpirySweep();

        assertThat(stored(browser)).isNull();
    }
}
