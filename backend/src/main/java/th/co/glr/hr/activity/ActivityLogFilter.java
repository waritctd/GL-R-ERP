package th.co.glr.hr.activity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/**
 * Records one {@link ActivityLogEntry} per {@code /api/} request, so "what did this employee
 * actually do today" is answerable across the whole product rather than only for the handful of
 * actions that happen to write an {@code hr.audit_log} row.
 *
 * <p>Runs at {@code @Order(1)}, immediately after {@code CsrfCookieFilter} ({@code @Order(0)}), so
 * it wraps the entire chain and sees the final status of every request — including the 403s that
 * filter itself rejects and any exception translated further down.
 */
@Component
@Order(1)
public class ActivityLogFilter extends OncePerRequestFilter {

    private final ActivityLogRecorder recorder;

    public ActivityLogFilter(ActivityLogRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        long startedNanos = System.nanoTime();
        // Captured BEFORE the chain as well as after, because logout invalidates the session:
        // read only afterwards and every logout would be recorded as anonymous.
        UserPrincipal before = currentUser(request);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Preferred AFTER the chain, because login establishes the session during it — this is
            // what lets POST /api/auth/login be attributed to the employee who just signed in
            // rather than to nobody.
            UserPrincipal actor = firstNonNull(currentUser(request), before);
            record(request, response, actor, startedNanos);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response,
                        UserPrincipal actor, long startedNanos) {
        try {
            int durationMs = (int) Math.min(Integer.MAX_VALUE,
                (System.nanoTime() - startedNanos) / 1_000_000L);
            recorder.record(new ActivityLogEntry(
                actor == null ? null : actor.id(),
                actor == null ? null : actor.email(),
                request.getMethod(),
                // getRequestURI() only — never the query string, and never the body. Both carry
                // credentials and PII; POST /api/auth/login alone would otherwise put every
                // password in this table in plaintext.
                request.getRequestURI(),
                response.getStatus(),
                durationMs,
                OffsetDateTime.now()));
        } catch (RuntimeException e) {
            // Observability must never break the request it is describing. The recorder already
            // swallows its own failures; this is the belt to that pair of braces.
            logger.warn("Could not capture an activity-log entry", e);
        }
    }

    private UserPrincipal currentUser(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null) {
                return null;
            }
            Object value = session.getAttribute(SessionContext.SESSION_USER_KEY);
            return value instanceof UserPrincipal user ? user : null;
        } catch (IllegalStateException alreadyInvalidated) {
            // logout() invalidated the session mid-request; the pre-chain capture covers it.
            return null;
        }
    }

    private static UserPrincipal firstNonNull(UserPrincipal preferred, UserPrincipal fallback) {
        return preferred != null ? preferred : fallback;
    }
}
