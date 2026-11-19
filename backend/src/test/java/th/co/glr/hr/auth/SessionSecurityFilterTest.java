package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** SessionSecurityFilter's per-request account re-check (owner ruling 2026-10-06), at the level where the cases
 * that are awkward to stage over real HTTP can be stated exactly — above all the one that matters most
 * operationally: a lookup that FAILS must not log anybody out. */
class SessionSecurityFilterTest {

    private final SessionAccountResolver accounts = mock(SessionAccountResolver.class);
    private final ViewAsService viewAs = mock(ViewAsService.class);
    private final SessionSecurityFilter filter = new SessionSecurityFilter(accounts, viewAs);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── a session with a sign-in record is re-checked ────────────────────────────────────────

    @Test
    void signedIn_andTheAccountHasChanged_runsAsWhatItIsNow_andTheSessionCarriesThatOnwards() throws Exception {
        CountingSession session = signedIn(principal(7, "hr", false));
        UserPrincipal now = principal(7, "employee", false);
        when(accounts.current(7)).thenReturn(Optional.of(now));

        Authentication seen = run(session);

        assertThat(seen.getPrincipal()).isEqualTo(now);
        assertThat(seen.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_EMPLOYEE");
        // Controllers and MustChangePasswordFilter read the principal back out of the SESSION, not the security
        // context — so it has to be there, not only on the request.
        assertThat(session.getAttribute(SessionContext.SESSION_USER_KEY)).isEqualTo(now);
        // …and it is still a signed-in session, so the next request is re-checked too.
        assertThat(session.getAttribute(SessionContext.SESSION_SIGNED_IN_AT_KEY)).isNotNull();
    }

    @Test
    void signedIn_andTheAccountIsGoneOrInactive_endsTheSession_andTheRequestRunsUnauthenticated() throws Exception {
        CountingSession session = signedIn(principal(7, "hr", false));
        when(accounts.current(7)).thenReturn(Optional.empty());

        Authentication seen = run(session);

        assertThat(seen).isNull();
        assertThat(session.isInvalid()).isTrue();
    }

    // ── no session, no principal ─────────────────────────────────────────────────────────────

    @Test
    void viewingAs_authenticatesAsTheRefreshedTarget_withTheTargetsRole() throws Exception {
        CountingSession session = signedIn(principal(7, "employee", false));
        SessionContext.startViewAs(session, principal(9, "employee", false));
        when(accounts.current(7)).thenReturn(Optional.of(principal(7, "employee", false)));
        when(accounts.current(9)).thenReturn(Optional.of(principal(9, "hr", true)));
        when(viewAs.mayViewAs(7)).thenReturn(true);

        Authentication seen = run(session);

        assertThat(((UserPrincipal) seen.getPrincipal()).id()).isEqualTo(9L);
        assertThat(((UserPrincipal) seen.getPrincipal()).mustChangePassword()).isFalse();
        assertThat(seen.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_HR");
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    /** Runs the filter and returns the authentication the REST of the chain saw. */
    private Authentication run(MockHttpSession session) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return run(request);
    }

    private Authentication run(MockHttpServletRequest request) throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen.get();
    }

    /** A session as {@link AuthService#login} leaves it. */
    private static CountingSession signedIn(UserPrincipal principal) {
        CountingSession session = new CountingSession();
        SessionContext.signIn(session, principal);
        session.writes = 0;
        return session;
    }

    private static UserPrincipal principal(long id, String role, boolean mustChangePassword) {
        return new UserPrincipal(id, "user" + id + "@glr.co.th", "User " + id, role, id, true,
            LocalDate.of(2026, 1, 1), mustChangePassword, 1L, false);
    }

    private static final class CountingSession extends MockHttpSession {
        private int writes;

        @Override
        public void setAttribute(String name, Object value) {
            writes++;
            super.setAttribute(name, value);
        }
    }
}
