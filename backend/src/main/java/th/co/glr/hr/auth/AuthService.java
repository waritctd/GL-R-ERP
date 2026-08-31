package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String INVALID_CREDENTIALS = "อีเมลหรือรหัสผ่านไม่ถูกต้อง";

    private final EmployeeAuthRepository employees;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AuthService(EmployeeAuthRepository employees, PasswordEncoder passwordEncoder,
                       AuditService auditService) {
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        LoginRequest safeRequest = request == null ? new LoginRequest(null, null, null) : request;
        if (hasText(safeRequest.role())) {
            // Left in English: `role` is only ever populated by the mock-mode quick-login buttons
            // (LoginPage.jsx, gated on VITE_USE_MOCKS), which never call this real service — so this
            // branch has no reachable Thai-UI path to translate for.
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
        recordLogin(principal);
        return new AuthResponse(principal, employees.isAdmin(principal.id()));
    }

    /**
     * Records the successful login in {@code hr.audit_log}, so "who used the portal today" is
     * answerable exactly instead of being inferred from whatever side effects a session happened
     * to leave behind. Before this existed the only traces were the handful of audited actions and
     * a one-off {@code must_change_password} flip, so anyone who logged in and only read pages was
     * invisible.
     *
     * <p><strong>Deliberately swallows every failure</strong>, which inverts {@link AuditService}'s
     * usual contract — it normally joins the caller's transaction so that a mutation can never
     * persist without its audit row. That contract exists to protect mutations, and a login mutates
     * nothing, so there is nothing to roll back. Letting an audit-table problem propagate from here
     * would instead turn it into a company-wide lockout: every login would 500. A dropped audit row
     * is by far the cheaper failure, so it is logged and the login proceeds.
     */
    private void recordLogin(UserPrincipal principal) {
        recordAuthEvent(principal, "LOGIN");
    }

    /**
     * @see #recordLogin for why this swallows rather than propagates. The same reasoning covers
     *     {@code CHANGE_PASSWORD} for a different reason: by the time it runs the new hash is
     *     already committed, so a thrown audit error would return a failure for a change that
     *     actually succeeded — telling the employee their password is unchanged when it is not.
     */
    private void recordAuthEvent(UserPrincipal principal, String action) {
        try {
            // LinkedHashMap, not Map.of: Map.of throws on a null value, and a null role would
            // otherwise cost the whole row rather than one field.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("role", principal.role());
            details.put("mustChangePassword", principal.mustChangePassword());
            auditService.record(principal, action, "employee", principal.id(), null, details);
        } catch (RuntimeException e) {
            log.warn("Could not record the {} audit row for employee {}", action, principal.id(), e);
        }
    }

    public AuthResponse me(HttpSession session) {
        Object value = session.getAttribute(SessionContext.SESSION_USER_KEY);
        if (value instanceof UserPrincipal user) {
            // Re-read per call rather than trusting the session, so granting or revoking admin
            // shows up on the next page load instead of at the holder's next login.
            return new AuthResponse(user, employees.isAdmin(user.id()));
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "กรุณาเข้าสู่ระบบก่อนใช้งาน");
    }

    public AuthResponse changePassword(ChangePasswordRequest request, HttpSession session) {
        Object value = session.getAttribute(SessionContext.SESSION_USER_KEY);
        if (!(value instanceof UserPrincipal user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "กรุณาเข้าสู่ระบบก่อนใช้งาน");
        }

        EmployeeLoginRecord employee = employees.findByEmployeeId(user.id())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "กรุณาเข้าสู่ระบบก่อนใช้งาน"));
        if (!employee.active() || !passwordMatches(request.currentPassword(), employee)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "รหัสผ่านปัจจุบันไม่ถูกต้อง");
        }
        if (passwordEncoder.matches(request.newPassword(), employee.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รหัสผ่านใหม่ต้องไม่ซ้ำกับรหัสผ่านเดิม");
        }
        if (hasText(employee.employeeCode())
            && request.newPassword().equals(employee.employeeCode().trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รหัสผ่านใหม่ต้องไม่ใช่รหัสพนักงานของคุณ");
        }

        employees.updatePassword(employee.employeeId(), passwordEncoder.encode(request.newPassword()));

        UserPrincipal refreshed = toPrincipal(employee.employeeId(), employee, false);
        session.setAttribute(SessionContext.SESSION_USER_KEY, refreshed);
        // Never the password, old or new, and no before/after payload: the only fact worth keeping
        // is that this employee set their own password at this moment. Until now the sole trace was
        // must_change_password flipping to false, which fires once per person and never again.
        recordAuthEvent(refreshed, "CHANGE_PASSWORD");
        return new AuthResponse(refreshed, employees.isAdmin(refreshed.id()));
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
            mustChangePassword,
            employee.divisionId(),
            DivisionAccessPolicy.isManager(employee)
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
