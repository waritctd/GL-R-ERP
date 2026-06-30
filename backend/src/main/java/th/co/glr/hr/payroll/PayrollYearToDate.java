package th.co.glr.hr.payroll;

import java.math.BigDecimal;

public record PayrollYearToDate(
    BigDecimal taxableIncome,
    BigDecimal socialSecurity,
    BigDecimal withholdingTax
) {
    public static PayrollYearToDate empty() {
        return new PayrollYearToDate(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
