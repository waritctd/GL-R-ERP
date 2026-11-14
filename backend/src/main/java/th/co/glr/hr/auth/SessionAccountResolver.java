package th.co.glr.hr.auth;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** What an account looks like RIGHT NOW — the principal this employee would be handed if they signed in at this
 * moment. The 30-minute idle timeout was therefore the only thing that ever ended a stale session — and that
 * timeout is now gone (a session lasts until its owner logs out). */
@Component
public class SessionAccountResolver {
    private final EmployeeAuthRepository employees;

    public SessionAccountResolver(EmployeeAuthRepository employees) {
        this.employees = employees;
    }

    /** The principal employeeId would get by signing in now — or empty when they could not sign in at all: the row
     * is gone, or the account is no longer active. Those are exactly the two things AuthService#login refuses, so
     * an open session ends on the same terms a new one would be denied. */
    public Optional<UserPrincipal> current(long employeeId) {
        return employees.findByEmployeeId(employeeId)
            .filter(EmployeeLoginRecord::active)
            .map(SessionAccountResolver::principalOf);
    }

    static UserPrincipal principalOf(EmployeeLoginRecord employee) {
        return principalOf(employee, employee.mustChangePassword());
    }

    /** Selects affected frontend tests, including readers outside the import graph. */
    static UserPrincipal principalOf(EmployeeLoginRecord employee, boolean mustChangePassword) {
        return new UserPrincipal(
            employee.employeeId(),
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
}
