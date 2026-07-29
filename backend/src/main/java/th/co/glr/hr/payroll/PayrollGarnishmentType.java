package th.co.glr.hr.payroll;

/**
 * Which statutory garnishment cap applies to the amount HR requests be withheld under a legal
 * execution order (handoff section 7, docs/agent-handoffs/118_feat-payroll-classification-and-hr-
 * declarations.md), researched from the Legal Execution Department manual:
 *
 * <ul>
 *   <li>{@link #SALARY} -- เงินเดือน / ค่าจ้าง: max 30%, must leave &ge; ฿20,000 net.
 *   <li>{@link #BONUS} -- เงินโบนัส: max 50% of the bonus paid this period.
 *   <li>{@link #OVERTIME_OR_DILIGENCE} -- เบี้ยขยัน / ค่าล่วงเวลา: max 30% of overtime + diligence pay
 *       this period.
 *   <li>{@link #SEVERANCE} -- เงินตอบแทนกรณีออกจากงาน: no percentage cap, must leave &ge; ฿300,000.
 * </ul>
 *
 * <p>Defaults to {@link #SALARY} wherever unset ({@code hr.payroll_line.garnishment_type}'s V96
 * default and {@link PayrollEmployeeInputRequest#garnishmentType()}'s null-handling both agree on
 * this), which reproduces the single 30%-plus-฿20,000-floor rule the engine already applied before
 * this task existed -- see {@link PayrollCalculator}'s legacy {@code legalExecutionDeduction}
 * helper, kept byte-identical for that case.
 */
public enum PayrollGarnishmentType {
    SALARY,
    BONUS,
    OVERTIME_OR_DILIGENCE,
    SEVERANCE
}
