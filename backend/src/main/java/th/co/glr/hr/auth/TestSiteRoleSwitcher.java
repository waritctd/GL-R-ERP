package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;

/**
 * Test-site only: a full re-sign-in between a configured list of demo accounts. Unlike
 * {@link ViewAsService} the result has full write access. The list is config; blank = nobody.
 */
@Service
public class TestSiteRoleSwitcher {
    public record Account(String email, String name, String role) {}

    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";

    private final EmployeeAuthRepository employees;
    private final AuditService auditService;
    private final List<String> allowed;

    public TestSiteRoleSwitcher(EmployeeAuthRepository employees, AuditService auditService,
                                @Value("${app.test-site.role-switcher.accounts:}") String accounts) {
        this.employees = employees;
        this.auditService = auditService;
        this.allowed = accounts == null ? List.of() : Arrays.stream(accounts.split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    private static String norm(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void requireCaller(UserPrincipal current) {
        if (!allowed.contains(norm(current.email()))) throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
    }

    private Optional<EmployeeLoginRecord> activeAccount(String email) {
        return employees.findByEmail(norm(email)).filter(EmployeeLoginRecord::active);
    }

    public List<Account> accounts(UserPrincipal current) {
        requireCaller(current);
        return allowed.stream().map(this::activeAccount).flatMap(Optional::stream)
            .map(e -> new Account(norm(e.email()), e.name(), DivisionAccessPolicy.roleFor(e))).toList();
    }

    public void switchTo(String email, UserPrincipal current, HttpServletRequest request) {
        requireCaller(current);
        if (!allowed.contains(norm(email))) throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        EmployeeLoginRecord target = activeAccount(email)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "บัญชีที่เลือกไม่พร้อมใช้งาน"));
        HttpSession session = request.getSession(true);
        if (request.getRequestedSessionId() != null) request.changeSessionId();
        SessionContext.signIn(session, SessionAccountResolver.principalOf(target, false));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", current.email());
        details.put("to", target.email());
        details.put("toRole", DivisionAccessPolicy.roleFor(target));
        auditService.record(current, "TEST_SITE_SWITCH", "employee", target.employeeId(), null, details);
    }
}
