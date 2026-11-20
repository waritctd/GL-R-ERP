package th.co.glr.hr.auth;

import java.util.List;

/**
 * The ONE definition of "warehouse staff": a person whose effective role is {@code warehouse}, any rank. The effective
 * role is what {@link DivisionAccessPolicy#roleFor} resolves (WH division, not an executive, and not an employee-tier
 * person acting as sales through the can_create_quotation grant). Notification recipients, deal-document read/upload,
 * round review and recording a delivery all ask this class, so they cannot drift apart.
 */
public final class WarehouseStaff {
    public static final String ROLE = "warehouse";

    private WarehouseStaff() {
    }

    /** The signed-in user (the session role is already the effective role). */
    public static boolean is(UserPrincipal actor) {
        return actor != null && ROLE.equals(actor.role());
    }

    public static boolean is(EmployeeLoginRecord employee) {
        return employee != null && employee.active() && ROLE.equals(DivisionAccessPolicy.roleFor(employee));
    }

    /** Employee ids of every active warehouse person, for notifications. */
    public static List<Long> recipientIds(EmployeeAuthRepository employees) {
        return employees.findAllActive().stream().filter(WarehouseStaff::is).map(EmployeeLoginRecord::employeeId).toList();
    }
}
