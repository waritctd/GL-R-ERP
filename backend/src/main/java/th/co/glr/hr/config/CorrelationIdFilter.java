package th.co.glr.hr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every request with a correlation id so a single request can be traced across log lines
 * (P1-3 observability floor). The id is read from an inbound {@code X-Correlation-Id} header when
 * present (sanitized so a caller cannot inject control characters or an unbounded value into logs),
 * otherwise a fresh UUID is generated. The id is placed in SLF4J's MDC under {@code correlationId}
 * (picked up by the {@code logging.pattern.level} pattern in {@code application.yml}) and echoed
 * back on the {@code X-Correlation-Id} response header. {@code @Order(HIGHEST_PRECEDENCE)} ensures
 * it wraps the entire filter chain so the id is available for the whole request lifecycle, and the
 * MDC entry is always removed in a {@code finally} block — critical because servlet containers reuse
 * pooled threads, and a leaked MDC value would bleed into an unrelated later request's logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String id = sanitize(request.getHeader(HEADER_NAME));
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER_NAME, id);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Trims the inbound value, strips control/non-printable characters (log-injection defense),
     * and caps the length so a malicious or malformed caller can't blow up log lines.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(trimmed.length(), MAX_LENGTH));
        for (int i = 0; i < trimmed.length() && cleaned.length() < MAX_LENGTH; i++) {
            char c = trimmed.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                cleaned.append(c);
            }
        }
        return cleaned.isEmpty() ? null : cleaned.toString();
    }
}
