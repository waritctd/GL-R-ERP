package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.ApiException;

@Component
public class SessionContext {
    public static final String SESSION_USER_KEY = "GLR_HR_USER";

    /**
     * When this session was signed in, epoch milliseconds — written by {@link #signIn}, and only
     * by it. Its PRESENCE is what tells {@link SessionSecurityFilter} that the session came from a
     * real login and must therefore be kept in step with the employee row on every request (owner
     * ruling 2026-10-06 — see {@link SessionAccountResolver}).
     *
     * <p>In production every session has it: {@link AuthService#login} is the only thing that
     * ever puts a principal into a session, and sessions live in the servlet container's memory,
     * so none survives from before the deploy that introduced this key.
     *
     * <p>The sessions WITHOUT it are the test suite's. Some fifty test classes plant a hand-made
     * principal straight into a {@code MockHttpSession} — {@code principal(id, "account")} — to
     * drive a request as a given role, with no login and usually no employee row whose ฝ่าย would
     * derive that role. Re-checking those against the database would replace every planted role
     * with whatever the row derives, or end the session outright, and the repo's role-gate
     * evidence would stop testing role gates. So the filter trusts a session with no sign-in
     * record exactly as it always did, and re-checks every session that has one.
     */
    public static final String SESSION_SIGNED_IN_AT_KEY = "GLR_HR_SIGNED_IN_AT";

    /**
     * Read-only "view as employee": the TARGET principal, present only while the owner is viewing.
     * {@link #SESSION_USER_KEY} stays the REAL actor the whole time (activity log, audit, the
     * must-change-password and account re-check all keep seeing who is really there); {@link
     * #requireUser} answers with the target so every controller runs as them.
     */
    public static final String VIEW_AS_USER_KEY = "GLR_HR_VIEW_AS_USER";

    /**
     * Records a real sign-in: the principal, and the mark that makes {@link SessionSecurityFilter}
     * re-check it from then on. Every production path that starts a session MUST come through
     * here — {@code SessionSignInEntryPointTest} fails the build if anything else writes
     * {@link #SESSION_USER_KEY}, because a session given a principal any other way would never be
     * re-checked and would quietly keep whatever role it started with.
     */
    public static void signIn(HttpSession session, UserPrincipal principal) {
        session.setAttribute(SESSION_USER_KEY, principal);
        session.setAttribute(SESSION_SIGNED_IN_AT_KEY, System.currentTimeMillis());
        // A login on a browser that was viewing someone must not inherit that view.
        session.removeAttribute(VIEW_AS_USER_KEY);
    }

    /** Replaces the principal of a session that is already signed in — its sign-in record, and so
     * its re-check, is left exactly as it was. */
    public static void replacePrincipal(HttpSession session, UserPrincipal principal) {
        session.setAttribute(SESSION_USER_KEY, principal);
    }

    /** The EFFECTIVE user: the view-as target while viewing, otherwise the signed-in actor. */
    public UserPrincipal requireUser(HttpSession session) {
        UserPrincipal user = effectiveUser(session);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "กรุณาเข้าสู่ระบบก่อนใช้งาน");
        }
        return user;
    }

    /** The view-as target while viewing, otherwise the signed-in actor; {@code null} if neither. */
    public static UserPrincipal effectiveUser(HttpSession session) {
        if (session.getAttribute(VIEW_AS_USER_KEY) instanceof UserPrincipal target) {
            return target;
        }
        return session.getAttribute(SESSION_USER_KEY) instanceof UserPrincipal real ? real : null;
    }

    /** The REAL signed-in actor, ignoring any view-as. Only the view-as endpoints may use this. */
    public UserPrincipal requireRealUser(HttpSession session) {
        Object value = session.getAttribute(SESSION_USER_KEY);
        if (value instanceof UserPrincipal user) {
            return user;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "กรุณาเข้าสู่ระบบก่อนใช้งาน");
    }

    public static void startViewAs(HttpSession session, UserPrincipal target) {
        session.setAttribute(VIEW_AS_USER_KEY, target);
    }

    public static void stopViewAs(HttpSession session) {
        session.removeAttribute(VIEW_AS_USER_KEY);
    }

    public void requireAnyRole(UserPrincipal user, String... roles) {
        boolean allowed = Arrays.stream(roles).anyMatch(role -> role.equals(user.role()));
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }
}
