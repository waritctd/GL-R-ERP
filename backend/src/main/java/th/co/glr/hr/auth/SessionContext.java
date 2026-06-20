package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.ApiException;

@Component
public class SessionContext {
    public static final String SESSION_USER_KEY = "GLR_HR_USER";

    public UserPrincipal requireUser(HttpSession session) {
        Object value = session.getAttribute(SESSION_USER_KEY);
        if (value instanceof UserPrincipal user) {
            return user;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }

    public void requireAnyRole(UserPrincipal user, String... roles) {
        boolean allowed = Arrays.stream(roles).anyMatch(role -> role.equals(user.role()));
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }
}
