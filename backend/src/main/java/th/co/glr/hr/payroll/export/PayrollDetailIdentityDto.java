package th.co.glr.hr.payroll.export;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Per-employee identity/salary-history enrichment for {@link PayrollDetailExporter} ONLY — never
 * fed into payroll math, purely display columns reproducing the two facts the accountant's
 * workbook always showed beside each employee's salary: when they started, and when/to-what their
 * salary was last adjusted.
 *
 * <p>{@code hireDate} is {@code hr.employee.hire_date} (วันที่เริ่มงาน). {@code
 * lastSalaryAdjustedDate}/{@code lastSalaryAdjustedNewAmount} come from the single most-recent
 * {@code hr.salary_history} row for the employee (that table's own V1 column comments: {@code
 * effective_date} = "วันที่ปรับเงิน", {@code new_amount} = "เงินใหม่") — exactly the ปรับล่าสุด
 * date/เงินเดือนใหม่ pair the accountant's sheet places next to {@code baseSalary}. {@code null}
 * for either means the employee has no salary_history row at all (e.g. seeded directly with a
 * current salary and no recorded adjustment).
 *
 * <p>{@link PayrollDetailExporter} only ever shows {@code lastSalaryAdjustedNewAmount} in its own
 * "เงินเดือนใหม่" column when {@code lastSalaryAdjustedDate} falls inside the reported payroll
 * month — otherwise a raise from years ago would read as "new" on every payslip run after it.
 */
public record PayrollDetailIdentityDto(
    LocalDate hireDate,
    LocalDate lastSalaryAdjustedDate,
    BigDecimal lastSalaryAdjustedNewAmount
) {
    public static final PayrollDetailIdentityDto EMPTY = new PayrollDetailIdentityDto(null, null, null);
}
