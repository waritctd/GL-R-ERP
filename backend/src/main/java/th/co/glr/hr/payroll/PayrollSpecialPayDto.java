package th.co.glr.hr.payroll;

import java.math.BigDecimal;

public record PayrollSpecialPayDto(
    String key,
    String label,
    BigDecimal amount
) {}
