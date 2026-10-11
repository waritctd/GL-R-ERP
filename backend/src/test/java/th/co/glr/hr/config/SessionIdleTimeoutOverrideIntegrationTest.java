package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/**
 * The way back. Sessions never expiring is the DEFAULT (owner ruling 2026-10-06, sales feedback
 * #8 — see {@link SessionLifetimeIntegrationTest}); {@code APP_SESSION_TIMEOUT} is still read, so
 * setting it on the host restores an idle limit with no code change and no new image:
 *
 * <pre>APP_SESSION_TIMEOUT=30m</pre>
 *
 * <p>This class boots the application with exactly that and pins both halves — a session inside
 * the limit keeps working, one past it is refused. It is also what makes
 * {@link SessionLifetimeIntegrationTest}'s "idle for over a year and still accepted" mean
 * something: the SAME ageing ({@link AbstractRealSessionIntegrationTest#ageSession}) that leaves a
 * session alive there ends one here, so that test cannot be passing merely because ageing does
 * nothing.
 */
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
