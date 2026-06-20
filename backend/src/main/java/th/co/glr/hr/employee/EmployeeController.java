package th.co.glr.hr.employee;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeResponses.EmployeeResponse;
import th.co.glr.hr.employee.EmployeeResponses.EmployeesResponse;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {
    private final EmployeeService employeeService;
    private final SessionContext sessions;

    public EmployeeController(EmployeeService employeeService, SessionContext sessions) {
        this.employeeService = employeeService;
        this.sessions = sessions;
    }

    @GetMapping
    EmployeesResponse list(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String divisionId,
        @RequestParam(required = false) String departmentTh,
        @RequestParam(required = false) String statusId,
        @RequestParam(required = false) String active,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "director", "admin");
        Boolean activeFilter = "true".equalsIgnoreCase(active) ? Boolean.TRUE : "false".equalsIgnoreCase(active) ? Boolean.FALSE : null;
        return new EmployeesResponse(employeeService.list(new EmployeeFilter(search, divisionId, departmentTh, statusId, activeFilter)));
    }

    @PostMapping
    EmployeeResponse create(@Valid @RequestBody UpsertEmployeeRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "admin");
        return new EmployeeResponse(employeeService.create(request, user));
    }

    @GetMapping("/{id}")
    EmployeeResponse get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new EmployeeResponse(employeeService.get(id, user));
    }

    @PatchMapping("/{id}")
    EmployeeResponse update(@PathVariable long id, @Valid @RequestBody UpsertEmployeeRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr", "admin");
        return new EmployeeResponse(employeeService.update(id, request, user));
    }
}
