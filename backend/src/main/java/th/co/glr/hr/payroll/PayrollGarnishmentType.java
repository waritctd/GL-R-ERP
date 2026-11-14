package th.co.glr.hr.payroll;

/** Which statutory garnishment cap applies to the amount HR requests be withheld under a legal execution order,
 * researched from the Legal Execution Department manual: #SALARY. */
public enum PayrollGarnishmentType {
    SALARY,
    BONUS,
    OVERTIME_OR_DILIGENCE,
    SEVERANCE
}
