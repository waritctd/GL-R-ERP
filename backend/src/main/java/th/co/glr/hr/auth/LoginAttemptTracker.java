package th.co.glr.hr.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window failure counter with temporary lockout, keyed by an arbitrary
 * string (e.g. {@code ip:1.2.3.4} or {@code acct:user@example.com}). Used by
 * {@link LoginRateLimitFilter} to throttle brute-force login attempts (advisory
 * GHSA-8m9r-9vhj-mr52).
 *
 * <p>State is per-instance. On the current single-instance Render deployment that is
 * sufficient; a horizontally scaled deployment would need a shared store (Redis), the same
 * caveat that applies to the in-memory HTTP sessions (issue #23).
 */
@Component
public class LoginAttemptTracker {
    /** Drop idle keys once the map grows past this, to bound memory under IP churn. */
    private static final int PURGE_THRESHOLD = 10_000;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** Seconds remaining until {@code key} is allowed again, or 0 if not currently locked. */
    public long retryAfterSeconds(String key, Instant now) {
        if (key == null) {
            return 0;
        }
        Counter counter = counters.get(key);
        if (counter == null) {
            return 0;
        }
        synchronized (counter) {
            if (counter.lockedUntil == null || !now.isBefore(counter.lockedUntil)) {
                return 0;
            }
            return Math.max(1, Duration.between(now, counter.lockedUntil).toSeconds());
        }
    }

    /** Records one failed attempt for {@code key}; locks the key once it exceeds {@code maxFailures}. */
    public void recordFailure(String key, int maxFailures, long windowSeconds, long lockoutSeconds, Instant now) {
        if (key == null) {
            return;
        }
        if (counters.size() > PURGE_THRESHOLD) {
            purgeExpired(now);
        }
        Counter counter = counters.computeIfAbsent(key, ignored -> new Counter());
        synchronized (counter) {
            if (counter.windowStart == null
                || Duration.between(counter.windowStart, now).getSeconds() >= windowSeconds) {
                counter.windowStart = now;
                counter.failures = 0;
                counter.lockedUntil = null;
            }
            counter.failures++;
            if (counter.failures >= maxFailures) {
                counter.lockedUntil = now.plusSeconds(lockoutSeconds);
            }
        }
    }

    /** Clears all failure state for {@code key} (call on a successful login). */
    public void reset(String key) {
        if (key != null) {
            counters.remove(key);
        }
    }

    private void purgeExpired(Instant now) {
        counters.values().removeIf(counter -> {
            synchronized (counter) {
                boolean locked = counter.lockedUntil != null && now.isBefore(counter.lockedUntil);
                boolean recentWindow = counter.windowStart != null
                    && Duration.between(counter.windowStart, now).toHours() < 1;
                return !locked && !recentWindow;
            }
        });
    }

    private static final class Counter {
        private int failures;
        private Instant windowStart;
        private Instant lockedUntil;
    }
}
