package th.co.glr.hr.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import th.co.glr.hr.config.AppProperties;

/**
 * Throttles {@code POST /api/auth/login} to blunt credential brute-forcing
 * (advisory GHSA-8m9r-9vhj-mr52). Counts failures per client IP and per account (email);
 * once either trips its limit the key is locked for a cool-down window and further attempts
 * get {@code 429 Too Many Requests} with a {@code Retry-After} header before the request ever
 * reaches the auth logic. A successful login clears both counters.
 *
 * <p>Ordered after {@link th.co.glr.hr.config.CsrfCookieFilter} ({@code @Order(0)}); login is
 * CSRF-exempt so ordering is not security-sensitive here.
 */
@Component
@Order(1)
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private static final String LOGIN_PATH = "/api/auth/login";

    private final AppProperties properties;
    private final LoginAttemptTracker tracker;
    private final ObjectMapper objectMapper;

    public LoginRateLimitFilter(AppProperties properties, LoginAttemptTracker tracker, ObjectMapper objectMapper) {
        this.properties = properties;
        this.tracker = tracker;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
            && LOGIN_PATH.equals(request.getRequestURI())
            && properties.getLoginRateLimit().isEnabled());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        AppProperties.LoginRateLimit config = properties.getLoginRateLimit();
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        String ipKey = "ip:" + clientIp(request);
        String accountKey = accountKey(cachedRequest);
        Instant now = Instant.now();

        long retryAfter = Math.max(
            tracker.retryAfterSeconds(ipKey, now),
            tracker.retryAfterSeconds(accountKey, now));
        if (retryAfter > 0) {
            writeTooManyRequests(response, retryAfter);
            return;
        }

        filterChain.doFilter(cachedRequest, response);

        int status = response.getStatus();
        if (status == HttpServletResponse.SC_OK) {
            tracker.reset(ipKey);
            tracker.reset(accountKey);
        } else if (status == HttpServletResponse.SC_UNAUTHORIZED || status == HttpServletResponse.SC_FORBIDDEN) {
            Instant recordedAt = Instant.now();
            tracker.recordFailure(ipKey, config.getMaxIpFailures(),
                config.getWindowSeconds(), config.getLockoutSeconds(), recordedAt);
            tracker.recordFailure(accountKey, config.getMaxAccountFailures(),
                config.getWindowSeconds(), config.getLockoutSeconds(), recordedAt);
        }
    }

    private String accountKey(CachedBodyHttpServletRequest request) {
        try {
            byte[] body = request.body();
            if (body.length == 0) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode email = root.get("email");
            if (email == null || !email.isTextual()) {
                return null;
            }
            String normalized = email.asText().trim().toLowerCase(Locale.ROOT);
            return normalized.isBlank() ? null : "acct:" + normalized;
        } catch (IOException malformedJson) {
            // Let the controller's validation produce the 400; just skip account-keying.
            return null;
        }
    }

    /**
     * Best-effort client IP. Behind the Vercel/Render proxy the real client is the leftmost
     * X-Forwarded-For entry; falls back to the socket address otherwise. XFF is client-spoofable,
     * which is why per-account limiting runs alongside per-IP.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
            "{\"message\":\"Too many login attempts. Try again later.\",\"status\":429}");
    }
}
