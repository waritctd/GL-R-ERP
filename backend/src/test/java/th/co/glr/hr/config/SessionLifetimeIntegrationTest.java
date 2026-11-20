package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/** Sales feedback #8 (แจ้งปัญหาการใช้งาน เสนอราคา, 2026-10-06) and the owner's rulings of the same day: A
 * session never expires. A rep left a quotation open for an hour, came back to a dead session and retyped
 * everything. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SessionLifetimeIntegrationTest extends AbstractRealSessionIntegrationTest {

    // ── 1. a session never expires ───────────────────────────────────────────────────────────

    /** The rep's own case, with a year of margin: the old limit was 30 minutes. */
    @Test
    void aSessionLeftIdleForOverAYear_isStillAccepted_evenAfterTheExpirySweepRuns() throws Exception {
        Account account = employee("IT", "IT-ไอที");
        Browser browser = signIn(account);

        ageSession(browser, Duration.ofDays(400));
        runExpirySweep();

        HttpResponse<String> me = browser.get(ME);
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        assertThat(me.body()).contains(account.email());
        assertThat(stored(browser)).isNotNull();
    }

    // ── 2. every request re-checks the account ───────────────────────────────────────────────

    @Test
    void aDeactivatedEmployee_isShutOutOnTheVeryNextRequest_onEveryDeviceTheyWereSignedInOn() throws Exception {
        Account account = employee("HR", "HR-บุคคล");
        Browser laptop = signIn(account);
        Browser phone = signIn(account);
        assertThat(laptop.get(HR_ONLY).statusCode()).isEqualTo(200);
        assertThat(phone.get(ME).statusCode()).isEqualTo(200);

        setActive(account, false);

        assertThat(laptop.get(HR_ONLY).statusCode()).as("the HR register").isEqualTo(401);
        assertThat(laptop.get(ME).statusCode()).isEqualTo(401);
        assertThat(phone.get(ME).statusCode()).as("the other device").isEqualTo(401);
        // Ended server-side, not merely refused: the sessions themselves are gone…
        assertThat(stored(laptop)).isNull();
        assertThat(stored(phone)).isNull();

        // …so re-activating the account does not bring the old cookies back to life. Coming back means signing in
        // again.
        setActive(account, true);
        assertThat(laptop.get(ME).statusCode()).isEqualTo(401);
        assertThat(phone.get(ME).statusCode()).isEqualTo(401);
        assertThat(signIn(account).get(ME).statusCode()).isEqualTo(200);
    }

    @Test
    void anEmployeeMovedOutOfHr_losesHrAccessOnTheVeryNextRequest() throws Exception {
        Account account = employee("HR", "HR-บุคคล");
        Browser browser = signIn(account);
        assertThat(browser.get(HR_ONLY).statusCode()).isEqualTo(200);

        moveToDivision(account, "IT", "IT-ไอที");

        HttpResponse<String> register = browser.get(HR_ONLY);
        assertThat(register.statusCode()).as(register.body()).isEqualTo(403);
        // Still signed in — as what they are NOW.
        HttpResponse<String> me = browser.get(ME);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"role\":\"employee\"").doesNotContain("\"role\":\"hr\"");
    }

}
