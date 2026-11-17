package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.ApiException;

@Component
public class SessionContext {
    public static final String SESSION_USER_KEY = "GLR_HR_USER";

    /** When this session was signed in, epoch milliseconds — written by #signIn, and only by it. The sessions
     * WITHOUT it are the test suite's. So the filter trusts a session with no sign-in record exactly as it always
     * did, and re-checks every session that has one. */
    public static final String SESSION_SIGNED_IN_AT_KEY = "GLR_HR_SIGNED_IN_AT";

    /**
     * Read-only "view as employee": the TARGET principal, present only while the owner is viewing.
     * {@link #SESSION_USER_KEY} stays the REAL actor the whole time (activity log, audit, the
     * must-change-password and account re-check all keep seeing who is really there); {@link
     * #requireUser} answers with the target so every controller runs as them.
     */
    public static final String VIEW_AS_USER_KEY = "GLR_HR_VIEW_AS_USER";

    /** Records a real sign-in: the principal, and the mark that makes SessionSecurityFilter re-check it from then
     * on. */
    public static void signIn(HttpSession session, UserPrincipal principal) {
        session.setAttribute(SESSION_USER_KEY, principal);
        session.setAttribute(SESSION_SIGNED_IN_AT_KEY, System.currentTimeMillis());
        // A login on a browser that was viewing someone must not inherit that view.
        session.removeAttribute(VIEW_AS_USER_KEY);
    }

    /** Replaces the principal of a session that is already signed in — its sign-in record, and so its re-check, is
     * left exactly as it was. */
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
