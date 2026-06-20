package th.co.glr.hr.employee;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.profile.ProfileRequestRepository;

@Service
public class EmployeeService {
    private final EmployeeRepository employees;
    private final ProfileRequestRepository profileRequests;

    public EmployeeService(EmployeeRepository employees, ProfileRequestRepository profileRequests) {
        this.employees = employees;
        this.profileRequests = profileRequests;
    }

    public List<EmployeeDto> list(EmployeeFilter filter) {
        List<EmployeeDto> filteredEmployees = employees.findEmployees(filter, false);
        if (filteredEmployees.isEmpty()) {
            return List.of();
        }
        List<Long> employeeIds = filteredEmployees.stream().map(EmployeeDto::id).toList();
        Map<Long, Integer> pendingCounts = profileRequests.pendingCountsByEmployeeIds(employeeIds);
        return filteredEmployees.stream()
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
        return get(id, user);
    }

    @Transactional
    public EmployeeDto update(long id, UpsertEmployeeRequest request, UserPrincipal user) {
        employees.update(id, request);
        return get(id, user);
    }
}
