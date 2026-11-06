package th.co.glr.hr.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes a "view as employee" session read-only: while {@link SessionContext#VIEW_AS_USER_KEY} is set,
 * every {@code /api/} write is refused with 403 unless it is on the short allowlist below.
 *
 * <p>Every GET/HEAD/OPTIONS passes, with no denylist. A sweep of all GET handlers found none that
 * sends mail or notifications, allocates a number or stores a document. The only GET writes are two
 * derived-state self-heals that happen identically when the employee opens the page themselves:
 * {@code BillingNoteRepository.settle()} flips fully-paid ISSUED notes to SETTLED, and {@code
 * LeaveRepository.insertCarryoverIfAbsent} is {@code INSERT ... ON CONFLICT DO NOTHING}. Refusing
 * them would hide leave balances, which the owner needs to see.
 *
 * <p>The allowlisted POSTs are render/compute calls whose services persist nothing (the ล.ย.01 draft
 * PDF, the estimates, the previews and the simulator); the rest are the exit and sign-in routes.
 * Registered after {@link SessionSecurityFilter}, inside the Spring Security chain, so the path is
 * already normalised.
 */
@Component
public class ViewAsReadOnlyFilter extends OncePerRequestFilter {

    private static final String MESSAGE = "โหมดดูแทนพนักงานเป็นแบบอ่านอย่างเดียว ไม่สามารถทำรายการนี้ได้";

    /** {@code "METHOD /path"} patterns; method is part of the key, and each path matches whole. */
    private static final List<String> ALLOWED = List.of(
        "POST /api/view-as",
        "DELETE /api/view-as",
        "POST /api/auth/logout",
        "POST /api/auth/login",
        "POST /api/payroll/tax-allowances/declarations/me/form.pdf",
        "POST /api/payroll/tax-allowances/declarations/me/estimate",
        "POST /api/payroll/preview",
        "POST /api/payroll/preview/export/{kind}",
        "POST /api/deposit-notices/{docId}/preview",
        "POST /api/customer-quotations/{id}/preview",
        "POST /api/deal-quotations/calculate-line",
        "POST /api/leave/preview",
        "POST /api/commissions/simulator"
    );

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (isViewing(request) && !isRead(request) && !isAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\":\"" + MESSAGE + "\",\"status\":403}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isViewing(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(SessionContext.VIEW_AS_USER_KEY) != null;
    }

    private static boolean isRead(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private static boolean isAllowed(HttpServletRequest request) {
        String key = request.getMethod() + " " + request.getRequestURI();
        return ALLOWED.stream().anyMatch(pattern -> MATCHER.match(pattern, key));
    }
}
