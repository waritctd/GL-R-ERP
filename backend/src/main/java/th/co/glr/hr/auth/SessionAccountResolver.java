package th.co.glr.hr.auth;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What an account looks like RIGHT NOW — the principal this employee would be handed if they
 * signed in at this moment.
 *
 * <p><b>Why this exists</b> (owner ruling 2026-10-06, with sales feedback #8). A session used to
 * be given its {@link UserPrincipal} once, at login, and nothing ever looked at the employee row
 * again: {@link SessionSecurityFilter} granted whatever role the session carried. The 30-minute
 * idle timeout was therefore the only thing that ever ended a stale session — and that timeout is
 * now gone (a session lasts until its owner logs out). Without a re-check, an employee HR
 * deactivates, or moves out of HR, would keep their old access for as long as they liked, on every
 * device already signed in. So {@link SessionSecurityFilter} asks this class on every request.
 *
 * <p>It is also the ONE place an employee row becomes a principal ({@link #principalOf}).
 * {@link AuthService} uses the same mapping at login and on a password change, so "what you get
 * when you sign in" and "what your open session is refreshed to" cannot drift apart.
 *
 * <p>Deliberately no caching: one primary-key lookup per request is cheap next to the request
 * itself, and a cache would turn "shut out on the very next request" into "shut out within N
 * seconds" — a window that is hard to reason about for exactly the case this exists for.
 */
@Component
public class SessionAccountResolver {
    private final EmployeeAuthRepository employees;

    public SessionAccountResolver(EmployeeAuthRepository employees) {
        this.employees = employees;
    }

    /**
     * The principal {@code employeeId} would get by signing in now — or empty when they could not
     * sign in at all: the row is gone, or the account is no longer active. Those are exactly the
     * two things {@link AuthService#login} refuses, so an open session ends on the same terms a
     * new one would be denied.
     *
     * <p>A lookup that FAILS (the database is unreachable) propagates as the exception it is. It
     * is never read as "inactive": a database hiccup must cost a failed request, not log the whole
     * company out.
     */
    public Optional<UserPrincipal> current(long employeeId) {
        return employees.findByEmployeeId(employeeId)
            .filter(EmployeeLoginRecord::active)
            .map(SessionAccountResolver::principalOf);
    }

    static UserPrincipal principalOf(EmployeeLoginRecord employee) {
        return principalOf(employee, employee.mustChangePassword());
    }

    /**
     * @param mustChangePassword passed separately because {@link AuthService#changePassword}
     *     builds the post-change principal from the record it read BEFORE writing the new hash,
     *     whose flag is by then stale.
     */
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
