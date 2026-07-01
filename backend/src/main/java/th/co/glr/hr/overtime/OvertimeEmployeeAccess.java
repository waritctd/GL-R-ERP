package th.co.glr.hr.overtime;

public record OvertimeEmployeeAccess(
    long employeeId,
    Long managerEmployeeId,
    boolean active
) {
}
