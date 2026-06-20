package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.common.ApiException;

@Service
public class AuthService {
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final EmployeeAuthRepository employees;

    public AuthService(EmployeeAuthRepository employees) {
        this.employees = employees;
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

    public void logout(HttpSession session) {
        session.invalidate();
    }

    private UserPrincipal toPrincipal(EmployeeLoginRecord employee) {
        return new UserPrincipal(
            employee.employeeId(),
            employee.email(),
            employee.name(),
            DivisionAccessPolicy.roleFor(employee),
            employee.employeeId(),
            employee.active(),
            employee.createdAt()
        );
    }

    private boolean passwordMatches(String rawPassword, EmployeeLoginRecord employee) {
        String password = rawPassword.trim();
        return hasText(employee.employeeCode()) && password.equals(employee.employeeCode().trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
