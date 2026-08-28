package th.co.glr.hr.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Confines a session whose principal still carries {@code mustChangePassword = TRUE} to the four
 * endpoints needed to get out of that state, and 403s everything else under {@code /api/}.
 *
 * <p><b>Why this exists.</b> The forced-change gate used to live only in the SPA
 * ({@code App.jsx}: {@code if (user.mustChangePassword) return <ChangePasswordModal forced/>}).
 * That is a rendering decision, not an authorization one — {@link AuthService#login} puts a fully
 * populated {@link UserPrincipal} into the session regardless of the flag, and
 * {@link SessionSecurityFilter} then grants it its full role authority. So anyone holding a
 * temporary password could authenticate and drive the entire API directly (curl, any HTTP client,
 * the browser console) without ever loading the React app that was supposed to be stopping them —
 * reading payroll, salaries and employee records with a password they were only ever meant to use
 * once. The modal blocked the UI; it never blocked the session.
 *
 * <p>That gap is the difference between "a temporary password is a one-time key" and "a temporary
 * password is a full account". It matters most exactly when a shared, predictable initial password
 * is handed out across the whole company, because then the window between issuing it and each
 * person's first login is a window in which any known email address is a working login.
 *
 * <p><b>The allowlist is deliberately tiny</b> — a must-change session can log in again, read its
 * own principal (the SPA's session-restore call, which is how the modal learns it must render),
 * change its password, and log out. Nothing else. {@code POST /api/auth/change-password} clears the
 * flag via {@link EmployeeAuthRepository#updatePassword} and replaces the session principal, so the
 * very next request passes this filter normally.
 *
 * <p><b>Placement.</b> Registered inside the Spring Security chain, immediately after
 * {@link SessionSecurityFilter} (see {@code SecurityConfig}), for two reasons: the principal is
 * guaranteed to be resolved by then, and requests have already cleared Spring Security's
 * {@code StrictHttpFirewall} — so the exact-match allowlist below cannot be walked around with
 * {@code ..} segments or encoded slashes in the path.
 */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    /**
     * {@code "METHOD /path"} pairs a must-change session may still reach. Method is part of the key
     * on purpose: {@code GET /api/auth/me} is a read of your own principal, whereas a hypothetical
     * {@code POST} to the same path would not be, and an allowlist keyed on path alone would wave
     * both through.
     */
    private static final Set<String> ALLOWED = Set.of(
        "POST /api/auth/login",           // re-authenticating is always allowed
        "POST /api/auth/logout",          // the modal's "ออกจากระบบ" escape
        "POST /api/auth/change-password", // the one action that clears the flag
        "GET /api/auth/me"                // session restore; the SPA needs the flag to render the modal
    );

    private static final String MESSAGE = "กรุณาเปลี่ยนรหัสผ่านก่อนใช้งานระบบ";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (mustChangePassword(request) && !isAllowed(request)) {
            writeForbidden(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Reads the flag off the session principal directly rather than from {@code SecurityContextHolder}.
     * The session attribute is the source of truth {@link AuthService} writes and
     * {@link SessionSecurityFilter} merely copies, so this stays correct regardless of where in the
     * chain the filter ends up.
     */
    private boolean mustChangePassword(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(SessionContext.SESSION_USER_KEY);
        return value instanceof UserPrincipal user && user.mustChangePassword();
    }

    private boolean isAllowed(HttpServletRequest request) {
        return ALLOWED.contains(request.getMethod() + " " + request.getRequestURI());
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + MESSAGE + "\",\"status\":403}");
    }
}
