package th.co.glr.hr.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the session's principal into the request's authentication — after first bringing that
 * principal up to date with the employee row it stands for.
 *
 * <p><b>The re-check</b> (owner ruling 2026-10-06, alongside "a session never expires" — sales
 * feedback #8). This filter used to trust whatever {@link UserPrincipal} the session carried,
 * however long ago it was put there. That was tolerable only because an idle session died after
 * 30 minutes. With no idle timeout, a signed-in session is checked against the database on every
 * request:
 *
 * <ul>
 *   <li>the account is gone or no longer active → the session is ENDED server-side and the
 *       request goes on unauthenticated (401 from {@code SecurityConfig}'s entry point). Ended,
 *       not merely refused: re-activating the account later does not revive an old cookie;</li>
 *   <li>the account has changed — moved to another ฝ่าย, flagged must-change-password, renamed —
 *       → the session's principal is replaced with the current one, so the role this request runs
 *       with, the one every controller reads back out of the session
 *       ({@link SessionContext#requireUser}), and the one {@link MustChangePasswordFilter} reads
 *       right after this filter are all today's;</li>
 *   <li>nothing has changed → the session is left untouched (no write).</li>
 * </ul>
 *
 * <p>Only a session carrying a sign-in record is re-checked — see
 * {@link SessionContext#SESSION_SIGNED_IN_AT_KEY} for why one without it (a test's planted
 * principal; never a production session) is trusted as before.
 *
 * <p>A lookup that fails is an exception, not a verdict: it propagates and the session is left
 * alone. See {@link SessionAccountResolver#current}.
 */
@Component
public class SessionSecurityFilter extends OncePerRequestFilter {

    private final SessionAccountResolver accounts;

    public SessionSecurityFilter(SessionAccountResolver accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            UserPrincipal user = currentPrincipal(request);
            if (user != null) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    List.of(new SimpleGrantedAuthority(toRoleAuthority(user.role())))
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** The principal this request should run as, or {@code null} for none. */
    private UserPrincipal currentPrincipal(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(SessionContext.SESSION_USER_KEY);
        if (!(value instanceof UserPrincipal stored)) {
            return null;
        }
        if (session.getAttribute(SessionContext.SESSION_SIGNED_IN_AT_KEY) == null) {
            return stored;
        }
        Optional<UserPrincipal> current = accounts.current(stored.id());
        if (current.isEmpty()) {
            session.invalidate();
            return null;
        }
        if (!current.get().equals(stored)) {
            SessionContext.replacePrincipal(session, current.get());
        }
        return current.get();
    }

    private String toRoleAuthority(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return "ROLE_" + normalized;
    }
}
