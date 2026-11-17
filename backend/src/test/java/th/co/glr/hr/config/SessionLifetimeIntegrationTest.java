package th.co.glr.hr.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
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

    /** The mechanism, pinned. To the servlet container a session whose max-inactive-interval is zero or negative
     * never times out (HttpSession#setMaxInactiveInterval); any positive number of seconds is an idle limit. */
    @Test
    void aNewSession_carriesNoIdleLimitAtAll() throws Exception {
        Browser browser = signIn(employee("IT", "IT-ไอที"));

        assertThat(stored(browser).getMaxInactiveInterval()).isLessThanOrEqualTo(0);
        // …and it works straight away: a "never" the container read as "expired" would fail here.
        assertThat(browser.get(ME).statusCode()).isEqualTo(200);
    }

    /** Logging out is now the ONLY way a session ends, so it has to keep working. */
    @Test
    void loggingOut_stillEndsTheSession_andTheOldCookieIsDead() throws Exception {
        Browser browser = signIn(employee("IT", "IT-ไอที"));
        assertThat(browser.get(ME).statusCode()).isEqualTo(200);

        assertThat(browser.post("/api/auth/logout").statusCode()).isBetween(200, 299);

        assertThat(stored(browser)).isNull();
        assertThat(browser.get(ME).statusCode()).isEqualTo(401);
    }

    /** "Never expires" must not have become "always signed in". */
    @Test
    void withNoSession_theApiStillSays401() throws Exception {
        Browser stranger = new Browser();
        assertThat(stranger.get(ME).statusCode()).isEqualTo(401);
        assertThat(stranger.get(HR_ONLY).statusCode()).isEqualTo(401);
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

    /** The same rule the other way: the session follows the account, it is not a one-way ratchet. */
    @Test
    void anEmployeeMovedIntoHr_gainsHrAccessWithoutSigningInAgain() throws Exception {
        Account account = employee("IT", "IT-ไอที");
        Browser browser = signIn(account);
        assertThat(browser.get(HR_ONLY).statusCode()).isEqualTo(403);

        moveToDivision(account, "HR", "HR-บุคคล");

        assertThat(browser.get(HR_ONLY).statusCode()).isEqualTo(200);
        assertThat(browser.get(ME).body()).contains("\"role\":\"hr\"");
    }

    /** Resetting a password flags the account must-change. A session already open under the OLD password is
     * confined to changing it at once (MustChangePasswordFilter), instead of carrying on with full access for as
     * long as its owner cares to keep it. */
    @Test
    void anAccountFlaggedMustChangePassword_confinesItsOpenSessionAtOnce() throws Exception {
        Account account = employee("HR", "HR-บุคคล");
        Browser browser = signIn(account);
        assertThat(browser.get(HR_ONLY).statusCode()).isEqualTo(200);

        jdbc.update("UPDATE hr.employee SET must_change_password = TRUE WHERE employee_id = :id",
            Map.of("id", account.id()));

        assertThat(browser.get(HR_ONLY).statusCode()).isEqualTo(403);
        HttpResponse<String> me = browser.get(ME); // the one read a must-change session may make
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"mustChangePassword\":true");
    }

    /** Regression guard for the tests above: an account that has NOT changed is not disturbed — same role, request
     * after request, and its session is still the same one. */
    @Test
    void anUnchangedAccount_keepsItsSessionAndItsRole() throws Exception {
        Browser browser = signIn(employee("HR", "HR-บุคคล"));

        for (int i = 0; i < 3; i++) {
            assertThat(browser.get(HR_ONLY).statusCode()).isEqualTo(200);
            assertThat(browser.get(ME).body()).contains("\"role\":\"hr\"");
        }
        assertThat(stored(browser)).isNotNull();
    }
}
