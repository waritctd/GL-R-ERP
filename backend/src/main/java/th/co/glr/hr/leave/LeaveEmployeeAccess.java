package th.co.glr.hr.leave;

public record LeaveEmployeeAccess(
    long employeeId,
    Long managerEmployeeId,
    boolean active
) {
}
