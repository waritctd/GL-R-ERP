package th.co.glr.hr.payroll.export;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Per-employee identity/salary-history enrichment for PayrollDetailExporter ONLY — never fed into payroll
 * math, purely display columns reproducing the two facts the accountant's workbook always showed beside each
 * employee's salary: when they started, and when/to-what their salary was last adjusted. */
public record PayrollDetailIdentityDto(
    LocalDate hireDate,
    LocalDate lastSalaryAdjustedDate,
    BigDecimal lastSalaryAdjustedNewAmount
) {
    public static final PayrollDetailIdentityDto EMPTY = new PayrollDetailIdentityDto(null, null, null);
}
