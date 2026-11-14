package th.co.glr.hr.specialmoney;

/** Mirrors th.co.glr.hr.overtime.OvertimeEmployeeOption: the picker the submit form uses. directReport is
 * always false as of 2026-08-10 and is retained only so the wire shape (and mockApi's mirror of it) does not
 * churn. */
public record SpecialMoneyEmployeeOption(
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    boolean self,
    boolean directReport) {
}
