package th.co.glr.hr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Stateless CSRF protection using the OWASP double-submit cookie pattern. A forged cross-site request cannot
 * read the cookie (same-origin policy) and therefore cannot supply the matching header, so the request is
 * rejected with 403. */
@Component
@Order(0)
public class CsrfCookieFilter extends OncePerRequestFilter {
    static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    // Login must succeed before the client has a token; CSRF on login itself is low impact. The same reasoning
    // covers the two self-service forgot-password endpoints: a client landing fresh on /forgot-password, or
    // arriving via an emailed /reset-password?token=...
    private static final Set<String> EXEMPT_PATHS = Set.of(
        "/api/auth/login",
        "/api/auth/forgot-password",
        "/api/auth/reset-password",
        "/api/attendance/punch"
    );

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = readCsrfCookie(request);
        if (token == null) {
            token = issueToken(request, response);
        }

        if (requiresCsrfCheck(request)) {
            String header = request.getHeader(CSRF_HEADER_NAME);
            if (!tokensMatch(token, header)) {
                writeForbidden(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresCsrfCheck(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        return !EXEMPT_PATHS.contains(request.getRequestURI());
    }

    private String readCsrfCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (CSRF_COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String issueToken(HttpServletRequest request, HttpServletResponse response) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = encoder.encodeToString(bytes);

        StringBuilder cookie = new StringBuilder()
            .append(CSRF_COOKIE_NAME).append('=').append(token)
            .append("; Path=/")
            .append("; SameSite=Lax");
        if (request.isSecure()) {
            cookie.append("; Secure");
        }
        response.addHeader("Set-Cookie", cookie.toString());
        return token;
    }

    private boolean tokensMatch(String cookieToken, String headerToken) {
        if (cookieToken == null || headerToken == null || headerToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            cookieToken.getBytes(StandardCharsets.UTF_8),
            headerToken.getBytes(StandardCharsets.UTF_8));
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"Invalid CSRF token\",\"status\":403}");
    }
}
