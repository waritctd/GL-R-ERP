package th.co.glr.hr.overtime;

public record OvertimeEmployeeOption(
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    boolean self,
    boolean directReport
) {
}
