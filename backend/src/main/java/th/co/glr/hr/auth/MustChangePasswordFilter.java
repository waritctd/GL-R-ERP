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

/** Confines a session whose principal still carries mustChangePassword = TRUE to the four endpoints needed to
 * get out of that state, and 403s everything else under /api/. The forced-change gate used to live only in the
 * SPA (App.jsx: if (user.mustChangePassword) return <ChangePasswordModal forced/>). */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    /** "METHOD /path" pairs a must-change session may still reach. Method is part of the key on purpose: GET
     * /api/auth/me is a read of your own principal, whereas a hypothetical POST to the same path would not be, and
     * an allowlist keyed on path alone would wave both through. */
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

    /** Reads the flag off the session principal directly rather than from SecurityContextHolder. The session
     * attribute is the source of truth AuthService writes and SessionSecurityFilter merely copies, so this stays
     * correct regardless of where in the chain the filter ends up. */
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
