package th.co.glr.hr.employee;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.profile.ProfileRequestRepository;

@Service
public class EmployeeService {
    // Dedicated audit channel so PII-access events can be shipped/retained separately (issue #21).
    private static final Logger AUDIT = LoggerFactory.getLogger("th.co.glr.hr.audit");

    private final EmployeeRepository employees;
    private final ProfileRequestRepository profileRequests;

    public EmployeeService(EmployeeRepository employees, ProfileRequestRepository profileRequests) {
        this.employees = employees;
        this.profileRequests = profileRequests;
    }

    public List<EmployeeDto> list(EmployeeFilter filter, UserPrincipal user) {
        List<EmployeeDto> filteredEmployees = employees.findEmployees(filter, false);
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
        boolean canSeeAnyEmployee = user.role().equals("hr");
        if (!canSeeAnyEmployee && (user.employeeId() == null || user.employeeId() != id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }

        boolean includeSensitive = user.role().equals("hr");
        EmployeeDto employee = employees.findEmployeeById(id, includeSensitive)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found"));
        if (includeSensitive) {
            AUDIT.info(
                "sensitive_data_access action=VIEW_EMPLOYEE_DETAIL actorId={} actorEmail=\"{}\" targetEmployeeId={} fields=\"restricted_pii,current_salary,salary_history\" salaryHistoryCount={}",
                user.id(), user.email(), id, employee.salaryHistory().size());
        }
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

    private void auditSalarySummaryAccess(UserPrincipal user, List<EmployeeDto> rows) {
        if (user == null || !"hr".equals(user.role()) || rows.isEmpty()) {
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
}
