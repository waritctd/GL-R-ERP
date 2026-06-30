package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginAttemptTrackerTest {
    private static final long WINDOW = 900;
    private static final long LOCKOUT = 900;

    private final LoginAttemptTracker tracker = new LoginAttemptTracker();
    private final Instant t0 = Instant.parse("2026-06-29T10:00:00Z");

    @Test
    void notLockedBelowThreshold() {
        for (int i = 0; i < 4; i++) {
            tracker.recordFailure("acct:a@b.co", 5, WINDOW, LOCKOUT, t0);
        }
        assertThat(tracker.retryAfterSeconds("acct:a@b.co", t0)).isZero();
    }

    @Test
    void locksOnceMaxFailuresReached() {
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure("acct:a@b.co", 5, WINDOW, LOCKOUT, t0);
        }
        assertThat(tracker.retryAfterSeconds("acct:a@b.co", t0)).isGreaterThan(0);
    }

    @Test
    void lockExpiresAfterLockoutWindow() {
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure("acct:a@b.co", 5, WINDOW, LOCKOUT, t0);
        }
        assertThat(tracker.retryAfterSeconds("acct:a@b.co", t0.plusSeconds(LOCKOUT + 1))).isZero();
    }

    @Test
    void resetClearsFailures() {
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure("acct:a@b.co", 5, WINDOW, LOCKOUT, t0);
        }
        tracker.reset("acct:a@b.co");
        assertThat(tracker.retryAfterSeconds("acct:a@b.co", t0)).isZero();
    }

    @Test
    void failuresSpacedBeyondWindowDoNotAccumulate() {
        // Each failure lands after the previous window has elapsed, so the count keeps resetting.
        for (int i = 0; i < 10; i++) {
            tracker.recordFailure("acct:a@b.co", 5, WINDOW, LOCKOUT, t0.plusSeconds(i * (WINDOW + 1)));
        }
        Instant last = t0.plusSeconds(9L * (WINDOW + 1));
        assertThat(tracker.retryAfterSeconds("acct:a@b.co", last)).isZero();
    }

    @Test
    void nullKeyIsIgnored() {
        tracker.recordFailure(null, 5, WINDOW, LOCKOUT, t0);
        assertThat(tracker.retryAfterSeconds(null, t0)).isZero();
    }
}
