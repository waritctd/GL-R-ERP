package th.co.glr.hr.specialmoney;

/**
 * Mirrors {@code th.co.glr.hr.overtime.OvertimeEmployeeOption}: the picker the submit form uses.
 *
 * <p><b>{@code directReport} is always {@code false} as of 2026-08-10</b> and is retained only so
 * the wire shape (and mockApi's mirror of it) does not churn. Welfare has no submit-on-behalf: a
 * ฝ่าย manager may not file for a team member, so no option is ever flagged as one. See
 * {@link SpecialMoneyService}'s class Javadoc. Do not reintroduce a true value here without
 * reopening that decision — the frontend reads this flag to decide whether to show an on-behalf
 * picker at all.
 */
public record SpecialMoneyEmployeeOption(
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    boolean self,
    boolean directReport) {
}
