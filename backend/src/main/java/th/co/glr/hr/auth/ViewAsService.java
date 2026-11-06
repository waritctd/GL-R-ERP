package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;

/**
 * Read-only "view as employee" for one account. The gate is the REAL actor's {@code employee_code},
 * read live, against {@code glr.view-as.owner-employee-code} (blank = feature off for everyone).
 * The code is config, never source: it is per-environment and the repo is public.
 */
@Service
public class ViewAsService {

    public record Candidate(long employeeId, String name, String employeeCode, String role, String divisionName) {}

    private final EmployeeAuthRepository employees;
    private final AuditService auditService;
    private final String ownerEmployeeCode;

    public ViewAsService(EmployeeAuthRepository employees, AuditService auditService,
                         @Value("${glr.view-as.owner-employee-code:}") String ownerEmployeeCode) {
        this.employees = employees;
        this.auditService = auditService;
        this.ownerEmployeeCode = ownerEmployeeCode == null ? "" : ownerEmployeeCode.trim();
    }

    public boolean mayViewAs(long realActorId) {
        if (ownerEmployeeCode.isEmpty()) return false;
        return employees.findByEmployeeId(realActorId)
            .filter(EmployeeLoginRecord::active)
            .map(e -> e.employeeCode() != null && ownerEmployeeCode.equals(e.employeeCode().trim()))
            .orElse(false);
    }

    private void requireGate(UserPrincipal real) {
        if (!mayViewAs(real.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    public List<Candidate> candidates(UserPrincipal real) {
        requireGate(real);
        return employees.findAllActive().stream()
            .filter(e -> e.employeeId() != real.id())
            .map(e -> new Candidate(e.employeeId(), e.name(), e.employeeCode(),
                DivisionAccessPolicy.roleFor(e), e.divisionName()))
            .toList();
    }

    public void start(long targetId, UserPrincipal real, HttpSession session) {
        requireGate(real);
        if (targetId == real.id()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่สามารถดูในมุมมองของตัวเองได้");
        }
        EmployeeLoginRecord target = employees.findByEmployeeId(targetId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบพนักงานที่เลือก"));
        if (!target.active()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "พนักงานที่เลือกไม่ได้ใช้งานอยู่แล้ว");
        }
        UserPrincipal principal = SessionAccountResolver.principalOf(target, false);
        stop(real, session); // switching target: close the previous view's audit interval first
        auditService.record(real, "VIEW_AS_START", "employee", principal.id(), null, details(principal));
        SessionContext.startViewAs(session, principal);
    }

    /** No gate: clearing your own state needs no permission. Writes VIEW_AS_STOP only if viewing. */
    public void stop(UserPrincipal real, HttpSession session) {
        if (session.getAttribute(SessionContext.VIEW_AS_USER_KEY) instanceof UserPrincipal target) {
            auditService.record(real, "VIEW_AS_STOP", "employee", target.id(), null, details(target));
            SessionContext.stopViewAs(session);
        }
    }

    /** Adds the real actor's gate and, while viewing, who is behind the view. */
    public AuthResponse decorate(AuthResponse response, HttpSession session) {
        if (!(session.getAttribute(SessionContext.SESSION_USER_KEY) instanceof UserPrincipal real)) {
            return response;
        }
        AuthResponse.ViewAsInfo info = session.getAttribute(SessionContext.VIEW_AS_USER_KEY) != null
            ? new AuthResponse.ViewAsInfo(real.id(), real.name()) : null;
        return new AuthResponse(response.user(), response.admin(), response.canCreateQuotation(),
            response.canIssueBillingNote(), mayViewAs(real.id()), info);
    }

    private static Map<String, Object> details(UserPrincipal target) {
        return Map.of("targetEmployeeId", target.id(), "targetName", String.valueOf(target.name()),
            "targetRole", String.valueOf(target.role()));
    }
}
