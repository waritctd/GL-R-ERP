package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link SessionSecurityFilter}'s per-request account re-check (owner ruling 2026-10-06), at the
 * level where the cases that are awkward to stage over real HTTP can be stated exactly — above
 * all the one that matters most operationally: a lookup that FAILS must not log anybody out.
 *
 * <p>The behaviour end to end (real login, real cookie, real database row changed underneath it)
 * is {@code config.SessionLifetimeIntegrationTest}; this class does not repeat that, and is not a
 * substitute for it — a mocked resolver proves the filter's branches, not that a deactivated
 * employee is actually shut out.
 */
class SessionSecurityFilterTest {

    private final SessionAccountResolver accounts = mock(SessionAccountResolver.class);
    private final SessionSecurityFilter filter = new SessionSecurityFilter(accounts);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── a session with a sign-in record is re-checked ────────────────────────────────────────

    @Test
    void signedIn_andNothingHasChanged_runsAsThatPrincipal_withoutWritingToTheSession() throws Exception {
        UserPrincipal hr = principal(7, "hr", false);
        CountingSession session = signedIn(hr);
        when(accounts.current(7)).thenReturn(Optional.of(principal(7, "hr", false)));

        Authentication seen = run(session);

        assertThat(seen.getPrincipal()).isEqualTo(hr);
        assertThat(seen.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_HR");
        // An unchanged account costs a read, never a session write.
        assertThat(session.writes).isZero();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void signedIn_andTheAccountHasChanged_runsAsWhatItIsNow_andTheSessionCarriesThatOnwards() throws Exception {
        CountingSession session = signedIn(principal(7, "hr", false));
        UserPrincipal now = principal(7, "employee", false);
        when(accounts.current(7)).thenReturn(Optional.of(now));

        Authentication seen = run(session);

        assertThat(seen.getPrincipal()).isEqualTo(now);
        assertThat(seen.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_EMPLOYEE");
        // Controllers and MustChangePasswordFilter read the principal back out of the SESSION,
        // not the security context — so it has to be there, not only on the request.
        assertThat(session.getAttribute(SessionContext.SESSION_USER_KEY)).isEqualTo(now);
        // …and it is still a signed-in session, so the next request is re-checked too.
        assertThat(session.getAttribute(SessionContext.SESSION_SIGNED_IN_AT_KEY)).isNotNull();
    }

    @Test
    void signedIn_andNowFlaggedMustChangePassword_theSessionCarriesTheFlag() throws Exception {
        CountingSession session = signedIn(principal(7, "hr", false));
        when(accounts.current(7)).thenReturn(Optional.of(principal(7, "hr", true)));

        run(session);

        assertThat(((UserPrincipal) session.getAttribute(SessionContext.SESSION_USER_KEY)).mustChangePassword())
            .isTrue();
    }

    @Test
    void signedIn_andTheAccountIsGoneOrInactive_endsTheSession_andTheRequestRunsUnauthenticated() throws Exception {
        CountingSession session = signedIn(principal(7, "hr", false));
        when(accounts.current(7)).thenReturn(Optional.empty());

        Authentication seen = run(session);

        assertThat(seen).isNull();
        assertThat(session.isInvalid()).isTrue();
    }

    /**
     * The safety property. The database being unreachable for one request must cost that request —
     * a 500 — and nothing else. Reading a failed lookup as "this account is inactive" would end
     * the session of every single person who happened to make a request during the blip.
     */
    @Test
    void signedIn_andTheLookupFails_theFailurePropagates_andTheSessionIsLeftAlone() {
        UserPrincipal hr = principal(7, "hr", false);
        CountingSession session = signedIn(hr);
        when(accounts.current(7)).thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> run(session)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute(SessionContext.SESSION_USER_KEY)).isEqualTo(hr);
        assertThat(session.writes).isZero();
    }

    // ── a session with no sign-in record is trusted as it always was ─────────────────────────

    /**
     * Not a production case — {@link SessionContext#signIn} is the only thing that ever puts a
     * principal into a session there. This is the shape ~50 test classes plant by hand
     * ({@code session.setAttribute(SESSION_USER_KEY, principal(id, "account"))}), usually with no
     * employee row behind the role. Pinned so that it stays exactly what those tests rely on: the
     * planted role is the role, and the database is not consulted at all.
     */
    @Test
    void plantedPrincipal_withNoSignInRecord_runsAsPlanted_andTheDatabaseIsNotAsked() throws Exception {
        UserPrincipal planted = principal(999, "account", false);
        CountingSession session = new CountingSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, planted);
        session.writes = 0;

        Authentication seen = run(session);

        assertThat(seen.getPrincipal()).isEqualTo(planted);
        assertThat(seen.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ACCOUNT");
        verifyNoInteractions(accounts);
        assertThat(session.writes).isZero();
    }

    // ── no session, no principal ─────────────────────────────────────────────────────────────

    @Test
    void noSession_runsUnauthenticated_andCreatesNoSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        Authentication seen = run(request);

        assertThat(seen).isNull();
        assertThat(request.getSession(false)).as("the filter must never CREATE a session").isNull();
        verifyNoInteractions(accounts);
    }

    @Test
    void aSessionThatHoldsNoPrincipal_runsUnauthenticated() throws Exception {
        CountingSession session = new CountingSession();
        session.setAttribute(SessionContext.SESSION_SIGNED_IN_AT_KEY, 1L); // a record with nobody behind it

        assertThat(run(session)).isNull();
        verifyNoInteractions(accounts);
    }

    @Test
    void theSecurityContextIsEmptyAgainOnceTheRequestIsOver() throws Exception {
        CountingSession session = signedIn(principal(7, "hr", false));
        when(accounts.current(anyLong())).thenReturn(Optional.of(principal(7, "hr", false)));

        run(session);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(accounts).current(7);
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
