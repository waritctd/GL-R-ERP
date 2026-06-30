package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import th.co.glr.hr.common.ApiException;

@Service
public class AuthService {
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final EmployeeAuthRepository employees;
    private final PasswordEncoder passwordEncoder;

    public AuthService(EmployeeAuthRepository employees, PasswordEncoder passwordEncoder) {
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        LoginRequest safeRequest = request == null ? new LoginRequest(null, null, null) : request;
        if (hasText(safeRequest.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Role login is disabled");
        }
        if (!hasText(safeRequest.email()) || !hasText(safeRequest.password())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }
        EmployeeLoginRecord employee = employees.findByEmail(safeRequest.email().trim())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS));
        if (!employee.active() || !passwordMatches(safeRequest.password(), employee)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }

        UserPrincipal principal = toPrincipal(employee);
        HttpSession session = httpRequest.getSession(true);
        if (httpRequest.getRequestedSessionId() != null) {
            httpRequest.changeSessionId();
        }
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

    public AuthResponse changePassword(ChangePasswordRequest request, HttpSession session) {
        Object value = session.getAttribute(SessionContext.SESSION_USER_KEY);
        if (!(value instanceof UserPrincipal user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        EmployeeLoginRecord employee = employees.findByEmployeeId(user.id())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        if (!employee.active() || !passwordMatches(request.currentPassword(), employee)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), employee.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must differ from the current password");
        }
        if (hasText(employee.employeeCode())
            && request.newPassword().equals(employee.employeeCode().trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must not be your employee code");
        }

        employees.updatePassword(employee.employeeId(), passwordEncoder.encode(request.newPassword()));

        UserPrincipal refreshed = toPrincipal(employee.employeeId(), employee, false);
        session.setAttribute(SessionContext.SESSION_USER_KEY, refreshed);
        return new AuthResponse(refreshed);
    }

    public void logout(HttpSession session) {
        session.invalidate();
    }

    private UserPrincipal toPrincipal(EmployeeLoginRecord employee) {
        return toPrincipal(employee.employeeId(), employee, employee.mustChangePassword());
    }

    private UserPrincipal toPrincipal(long id, EmployeeLoginRecord employee, boolean mustChangePassword) {
        return new UserPrincipal(
            id,
            employee.email(),
            employee.name(),
            DivisionAccessPolicy.roleFor(employee),
            employee.employeeId(),
            employee.active(),
            employee.createdAt(),
            mustChangePassword
        );
    }

    private boolean passwordMatches(String rawPassword, EmployeeLoginRecord employee) {
        if (rawPassword == null || !hasText(employee.passwordHash())) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, employee.passwordHash());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
