package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpSession;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.user.AppUserRecord;
import th.co.glr.hr.user.AppUserRepository;

@Service
public class AuthService {
    private static final List<String> ROLE_PRIORITY = List.of("admin", "hr", "director", "supervisor", "employee");

    private final AppUserRepository users;
    private final AppProperties properties;
    private final boolean demoProfileActive;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(AppUserRepository users, AppProperties properties, Environment environment) {
        this.users = users;
        this.properties = properties;
        // Passwordless quick-role login is a demo-only convenience. Binding it to the
        // "demo" profile guarantees it cannot activate in production even if the
        // APP_QUICK_ROLE_LOGIN_ENABLED flag is mistakenly set there.
        this.demoProfileActive = environment.acceptsProfiles(Profiles.of("demo"));
    }

    public AuthResponse login(LoginRequest request, HttpSession session) {
        AppUserRecord user;
        String selectedRole;

        if (hasText(request.role())) {
            if (!demoProfileActive || !properties.getAuth().isQuickRoleLoginEnabled()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Quick role login is disabled");
            }
            user = users.findFirstEnabledByRole(request.role())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid role or inactive user"));
            selectedRole = request.role();
        } else {
            if (!hasText(request.email())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or inactive user");
            }
            user = users.findByEmail(request.email().trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or inactive user"));
            if (!user.active()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or inactive user");
            }
            if (!passwordMatches(request.password(), user.passwordHash())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid password");
            }
            selectedRole = primaryRole(user.roles());
        }

        UserPrincipal principal = toPrincipal(user, selectedRole);
        session.setAttribute(SessionContext.SESSION_USER_KEY, principal);
        return new AuthResponse(principal);
    }

    public AuthResponse me(HttpSession session) {
        Object value = session.getAttribute(SessionContext.SESSION_USER_KEY);
        if (value instanceof UserPrincipal user) {
            return new AuthResponse(user);
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }

    public void logout(HttpSession session) {
        session.invalidate();
    }

    public String hashPassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    private UserPrincipal toPrincipal(AppUserRecord user, String role) {
        if (!user.roles().contains(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return new UserPrincipal(user.id(), user.email(), user.name(), role, user.employeeId(), user.active(), user.createdAt());
    }

    private String primaryRole(List<String> roles) {
        return roles.stream()
            .min(Comparator.comparingInt(role -> {
                int index = ROLE_PRIORITY.indexOf(role);
                return index < 0 ? ROLE_PRIORITY.size() : index;
            }))
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "User has no application role"));
    }

    private boolean passwordMatches(String rawPassword, String passwordHash) {
        if (!hasText(rawPassword) || !hasText(passwordHash)) {
            return false;
        }
        if (passwordHash.startsWith("{noop}")) {
            return rawPassword.equals(passwordHash.substring("{noop}".length()));
        }
        return passwordEncoder.matches(rawPassword, passwordHash);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
