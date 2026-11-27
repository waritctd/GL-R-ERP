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

    /** The delivery planner: warehouse staff who are the warehouse manager. The ONE definition (trip writes, the inbound-round read). */
    public static boolean isPlanner(UserPrincipal actor, EmployeeAuthRepository employees) {
        return is(actor) && managerRecipientIds(employees).contains(actor.id());
    }

    public static boolean is(EmployeeLoginRecord employee) {
        return employee != null && employee.active() && ROLE.equals(DivisionAccessPolicy.roleFor(employee));
    }

    /**
     * The warehouse manager: active warehouse staff whose position title STARTS WITH "ผู้จัดการ" (whitespace ignored), so an
     * assistant ("ผู้ช่วยผู้จัดการ") is not one. The ONE definition; {@link #managerRecipientIds} is how a bell uses it.
     */
    public static boolean isManager(EmployeeLoginRecord employee) {
        return is(employee) && employee.positionName() != null
            && employee.positionName().replaceAll("\\s+", "").startsWith("ผู้จัดการ");
    }

    /** The warehouse manager's id(s); every active warehouse person only when nobody holds the title. */
    public static List<Long> managerRecipientIds(EmployeeAuthRepository employees) {
        List<Long> managers = employees.findAllActive().stream().filter(WarehouseStaff::isManager)
            .map(EmployeeLoginRecord::employeeId).toList();
        return managers.isEmpty() ? recipientIds(employees) : managers;
    }

    /** Employee ids of every active warehouse person, for notifications. */
    public static List<Long> recipientIds(EmployeeAuthRepository employees) {
        return employees.findAllActive().stream().filter(WarehouseStaff::is).map(EmployeeLoginRecord::employeeId).toList();
    }
}
