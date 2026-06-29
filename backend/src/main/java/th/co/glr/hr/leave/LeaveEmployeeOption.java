package th.co.glr.hr.leave;

public record LeaveEmployeeOption(
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    boolean self,
    boolean directReport
) {
}
