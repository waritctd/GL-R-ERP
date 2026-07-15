package th.co.glr.hr.employee;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.TemporaryPasswordGenerator;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.Page;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.profile.ProfileRequestRepository;

@Service
public class EmployeeService {
    // Dedicated audit channel so PII-access events can be shipped/retained separately (issue #21).
    private static final Logger AUDIT = LoggerFactory.getLogger("th.co.glr.hr.audit");
    private static final java.util.Set<String> PRIVILEGED_EMPLOYEE_ROLES = java.util.Set.of("hr");

    private final EmployeeRepository employees;
    private final ProfileRequestRepository profileRequests;
    private final AuditService auditService;
    private final EmployeeAuthRepository employeeAuth;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final PasswordEncoder passwordEncoder;

    public EmployeeService(EmployeeRepository employees, ProfileRequestRepository profileRequests,
                           AuditService auditService, EmployeeAuthRepository employeeAuth,
                           TemporaryPasswordGenerator temporaryPasswordGenerator,
                           PasswordEncoder passwordEncoder) {
        this.employees = employees;
        this.profileRequests = profileRequests;
        this.auditService = auditService;
        this.employeeAuth = employeeAuth;
        this.temporaryPasswordGenerator = temporaryPasswordGenerator;
        this.passwordEncoder = passwordEncoder;
    }

    public List<EmployeeDto> list(EmployeeFilter filter, UserPrincipal user) {
        return enrich(employees.findEmployees(filter, false), user);
    }

    public Page<EmployeeDto> listPage(EmployeeFilter filter, UserPrincipal user, PageRequest page) {
        List<EmployeeDto> rows = enrich(employees.findEmployees(filter, false, page), user);
        // Avoid the COUNT round-trip in the common case where the whole result set fits on page 0.
        int total = (page.page() == 0 && rows.size() < page.size())
            ? rows.size()
            : employees.countEmployees(filter);
        return new Page<>(rows, page.page(), page.size(), total);
    }

    private List<EmployeeDto> enrich(List<EmployeeDto> filteredEmployees, UserPrincipal user) {
        if (filteredEmployees.isEmpty()) {
            return List.of();
        }
        auditSalarySummaryAccess(user, filteredEmployees);
        List<Long> employeeIds = filteredEmployees.stream().map(EmployeeDto::id).toList();
        Map<Long, Integer> pendingCounts = profileRequests.pendingCountsByEmployeeIds(employeeIds);
        return filteredEmployees.stream()
            .map(employee -> employee.withPendingRequestCount(pendingCounts.getOrDefault(employee.id(), 0)))
            .toList();
    }

    public EmployeeDto get(long id, UserPrincipal user) {
        boolean canSeeAnyEmployee = canSeeSensitiveEmployeeFields(user);
        if (!canSeeAnyEmployee && (user.employeeId() == null || user.employeeId() != id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        boolean includeSensitive = canSeeSensitiveEmployeeFields(user);
        EmployeeDto employee = employees.findEmployeeById(id, includeSensitive)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        if (includeSensitive) {
            AUDIT.info(
                "sensitive_data_access action=VIEW_EMPLOYEE_DETAIL actorId={} actorEmail=\"{}\" targetEmployeeId={} fields=\"restricted_pii,current_salary,salary_history\" salaryHistoryCount={}",
                user.id(), user.email(), id, employee.salaryHistory().size());
        }
        int pendingCount = profileRequests.pendingCountByEmployee(employee.id());
        EmployeeDto withPendingCount = employee.withPendingRequestCount(pendingCount);
        return includeSensitive ? withPendingCount : withPendingCount.withoutSensitiveSelfServiceFields();
    }

    @Transactional
    public EmployeeDto create(UpsertEmployeeRequest request, UserPrincipal user) {
        long id = employees.create(request);
        EmployeeDto created = get(id, user);
        auditService.record(user, "CREATE_EMPLOYEE", "employee", id, null, created);
        return created;
    }

    @Transactional
    public EmployeeDto update(long id, UpsertEmployeeRequest request, UserPrincipal user) {
        EmployeeDto before = employees.findEmployeeById(id, true).orElse(null);
        employees.update(id, request);
        EmployeeDto after = get(id, user);
        auditService.record(user, "UPDATE_EMPLOYEE", "employee", id, before, after);
        return after;
    }

    /**
     * HR-only: issues a fresh random temporary password for the target employee, forcing a change
     * on next login. Returns the plaintext ONCE for the HR caller to hand over; the plaintext is
     * never logged or persisted (only its BCrypt hash is stored).
     */
    @Transactional
    public PasswordResetResult resetPassword(long employeeId, UserPrincipal actingUser) {
        if (!employees.exists(employeeId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Employee not found");
        }
        String plaintext = temporaryPasswordGenerator.generate();
        employeeAuth.setTemporaryPassword(employeeId, passwordEncoder.encode(plaintext));
        auditService.record(actingUser, "RESET_EMPLOYEE_PASSWORD", "employee", employeeId, null, null);
        return new PasswordResetResult(plaintext);
    }

    private void auditSalarySummaryAccess(UserPrincipal user, List<EmployeeDto> rows) {
        if (user == null || !canSeeSensitiveEmployeeFields(user) || rows.isEmpty()) {
            return;
        }
        String targetEmployeeIds = rows.stream()
            .map(EmployeeDto::id)
            .map(String::valueOf)
            .collect(Collectors.joining(","));
        AUDIT.info(
            "sensitive_data_access action=LIST_EMPLOYEE_SALARY_SUMMARY actorId={} actorEmail=\"{}\" targetEmployeeIds=\"{}\" resultCount={} fields=\"current_salary\"",
            user.id(), user.email(), targetEmployeeIds, rows.size());
    }

    private boolean canSeeSensitiveEmployeeFields(UserPrincipal user) {
        return user != null && PRIVILEGED_EMPLOYEE_ROLES.contains(user.role());
    }
}
