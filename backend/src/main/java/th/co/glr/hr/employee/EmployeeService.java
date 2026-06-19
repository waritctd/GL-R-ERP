package th.co.glr.hr.employee;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.profile.ProfileRequestRepository;

@Service
public class EmployeeService {
    private final EmployeeRepository employees;
    private final ProfileRequestRepository profileRequests;
    private final AuditService audit;

    public EmployeeService(EmployeeRepository employees, ProfileRequestRepository profileRequests, AuditService audit) {
        this.employees = employees;
        this.profileRequests = profileRequests;
        this.audit = audit;
    }

    public List<EmployeeDto> list(EmployeeFilter filter) {
        Map<Long, Integer> pendingCounts = profileRequests.pendingCountsByEmployee();
        return employees.findEmployees(filter, false).stream()
            .map(employee -> employee.withPendingRequestCount(pendingCounts.getOrDefault(employee.id(), 0)))
            .toList();
    }

    public EmployeeDto get(long id, UserPrincipal user) {
        boolean canSeeAnyEmployee = user.role().equals("hr") || user.role().equals("director") || user.role().equals("admin");
        if (!canSeeAnyEmployee && (user.employeeId() == null || user.employeeId() != id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        boolean includeSensitive = user.role().equals("hr") || user.role().equals("admin");
        EmployeeDto employee = employees.findEmployeeById(id, includeSensitive)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        int pendingCount = profileRequests.pendingCountByEmployee(employee.id());
        return employee.withPendingRequestCount(pendingCount);
    }

    @Transactional
    public EmployeeDto create(UpsertEmployeeRequest request, UserPrincipal user) {
        long id = employees.create(request);
        EmployeeDto created = get(id, user);
        audit.record(user.id(), "employee.create", "employee", id, null, created);
        return created;
    }

    @Transactional
    public EmployeeDto update(long id, UpsertEmployeeRequest request, UserPrincipal user) {
        EmployeeDto before = get(id, user);
        employees.update(id, request);
        EmployeeDto after = get(id, user);
        audit.record(user.id(), "employee.update", "employee", id, before, after);
        return after;
    }
}
