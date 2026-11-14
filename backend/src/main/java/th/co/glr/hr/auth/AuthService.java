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
    private static final String INVALID_LOGIN_MESSAGE = "อีเมลหรือรหัสผ่านไม่ถูกต้อง";

    private final EmployeeAuthRepository employees;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ViewAsService viewAs;

    public AuthService(EmployeeAuthRepository employees, PasswordEncoder passwordEncoder,
                       AuditService auditService, ViewAsService viewAs) {
        this.viewAs = viewAs;
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        LoginRequest safeRequest = request == null ? new LoginRequest(null, null, null) : request;
        if (hasText(safeRequest.role())) {
            // Left in English: `role` is only ever populated by the mock-mode quick-login buttons (LoginPage.jsx, gated on
            // VITE_USE_MOCKS), which never call this real service — so this branch has no reachable Thai-UI path to
            // translate for.
            throw new ApiException(HttpStatus.FORBIDDEN, "Role login is disabled");
        }
        if (!hasText(safeRequest.email()) || !hasText(safeRequest.password())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_MESSAGE);
        }
        // No .trim() here any more: LoginRequest's constructor already trimmed and lowercased, and it cannot be
        // bypassed — a record's canonical constructor runs however the instance is built. Removing it is also what
        // gives LoginEmailNormalizationIntegrationTest's whitespace case something to prove.
        EmployeeLoginRecord employee = employees.findByEmail(safeRequest.email())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_MESSAGE));
        if (!employee.active() || !passwordMatches(safeRequest.password(), employee)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_MESSAGE);
        }

        UserPrincipal principal = toPrincipal(employee);
        HttpSession session = httpRequest.getSession(true);
        // signIn drops a live view silently, so close it in the audit first (as the previous real actor).
        if (session.getAttribute(SessionContext.VIEW_AS_USER_KEY) instanceof UserPrincipal target
            && session.getAttribute(SessionContext.SESSION_USER_KEY) instanceof UserPrincipal real) {
            viewAs.recordStopQuietly(real, target);
        }
        if (httpRequest.getRequestedSessionId() != null) {
            httpRequest.changeSessionId();
        }
        // signIn, not a bare setAttribute: it also writes the sign-in record that makes SessionSecurityFilter re-check
        // this session against the employee row on every request from now on (owner ruling 2026-10-06 — see
        // SessionAccountResolver).
        SessionContext.signIn(session, principal);
        recordLogin(principal);
        return response(principal, session);
    }

    /** Records the successful login in hr.audit_log, so "who used the portal today" is answerable exactly instead
     * of being inferred from whatever side effects a session happened to leave behind. That contract exists to
     * protect mutations, and a login mutates nothing, so there is nothing to roll back. */
    private void recordLogin(UserPrincipal principal) {
        recordAuthEvent(principal, "LOGIN");
    }

    /** @see #recordLogin for why this swallows rather than propagates. */
    private void recordAuthEvent(UserPrincipal principal, String action) {
        try {
            // LinkedHashMap, not Map.of: Map.of throws on a null value, and a null role would otherwise cost the whole row
            // rather than one field.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("role", principal.role());
            details.put("mustChangePassword", principal.mustChangePassword());
            auditService.record(principal, action, "employee", principal.id(), null, details);
        } catch (RuntimeException e) {
            log.warn("Could not record the {} audit row for employee {}", action, principal.id(), e);
        }
    }

    public AuthResponse me(HttpSession session) {
        // The EFFECTIVE user: while the owner is viewing as someone this is the target, with the
        // target's own flags, so the SPA renders exactly what that employee would see.
        UserPrincipal user = SessionContext.effectiveUser(session);
        if (user != null) {
            // Re-read per call rather than trusting the session, so granting or revoking admin shows up on the next page
            // load instead of at the holder's next login.
            return response(user, session);
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
        rejectEmployeeCodeAsNewPassword(request.newPassword(), employee);

        employees.updatePassword(employee.employeeId(), passwordEncoder.encode(request.newPassword()));

        UserPrincipal refreshed = toPrincipal(employee.employeeId(), employee, false);
        SessionContext.replacePrincipal(session, refreshed);
        // Never the password, old or new, and no before/after payload: the only fact worth keeping is that this
        // employee set their own password at this moment. Until now the sole trace was must_change_password flipping
        // to false, which fires once per person and never again.
        recordAuthEvent(refreshed, "CHANGE_PASSWORD");
        return response(refreshed, session);
    }

    /** Flags for {@code user} plus the view-as gate and, while viewing, who is really behind it. */
    private AuthResponse response(UserPrincipal user, HttpSession session) {
        AuthResponse.ViewAsInfo info = null;
        boolean canViewAs = false;
        if (session.getAttribute(SessionContext.SESSION_USER_KEY) instanceof UserPrincipal real) {
            canViewAs = viewAs.mayViewAs(real.id());
            if (session.getAttribute(SessionContext.VIEW_AS_USER_KEY) != null) {
                info = new AuthResponse.ViewAsInfo(real.id(), real.name());
            }
        }
        return new AuthResponse(user, employees.isAdmin(user.id()), employees.canCreateQuotation(user.id()),
            employees.canIssueBillingNote(user.id()), canViewAs, info);
    }

    public void logout(HttpSession session) {
        if (session.getAttribute(SessionContext.VIEW_AS_USER_KEY) instanceof UserPrincipal target
            && session.getAttribute(SessionContext.SESSION_USER_KEY) instanceof UserPrincipal real) {
            viewAs.recordStopQuietly(real, target);
        }
        session.invalidate();
    }

    private UserPrincipal toPrincipal(EmployeeLoginRecord employee) {
        return toPrincipal(employee.employeeId(), employee, employee.mustChangePassword());
    }

    /** id has only ever been employee.employeeId() at both call sites; the mapping itself lives in
     * SessionAccountResolver#principalOf so that what a login hands out and what SessionSecurityFilter refreshes
     * an open session to are one function. */
    private UserPrincipal toPrincipal(long id, EmployeeLoginRecord employee, boolean mustChangePassword) {
        return SessionAccountResolver.principalOf(employee, mustChangePassword);
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

    /** Rejects a new password that equals the employee's own employeeCode — shared with
     * PasswordResetService#resetPassword, which needs the identical check but cannot replicate #changePassword's
     * "reject reusing the current password" rule above it. */
    static void rejectEmployeeCodeAsNewPassword(String newPassword, EmployeeLoginRecord employee) {
        if (hasText(employee.employeeCode()) && newPassword.equals(employee.employeeCode().trim())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รหัสผ่านใหม่ต้องไม่ใช่รหัสพนักงานของคุณ");
        }
    }
}
