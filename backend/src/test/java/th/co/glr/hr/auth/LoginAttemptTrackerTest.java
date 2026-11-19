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
    void locksOnceMaxFailuresReached() {
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure("acct:a@b.co", 5, WINDOW, LOCKOUT, t0);
        }
        assertThat(tracker.retryAfterSeconds("acct:a@b.co", t0)).isGreaterThan(0);
    }

}
