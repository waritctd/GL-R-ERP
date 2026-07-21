package th.co.glr.hr.specialmoney;

/** Mirrors {@code th.co.glr.hr.overtime.OvertimeEmployeeOption}: the picker the submit form uses. */
public record SpecialMoneyEmployeeOption(
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    boolean self,
    boolean directReport) {
}
