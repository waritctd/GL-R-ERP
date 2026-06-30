package th.co.glr.hr.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record PayrollPeriodDto(
    Long id,
    LocalDate payrollMonth,
    LocalDate periodStart,
    LocalDate periodEnd,
    LocalDate payDate,
    String status,
    OffsetDateTime processedAt,
    Long processedById,
    int lineCount,
    BigDecimal totalGross,
    BigDecimal totalDeductions,
    BigDecimal totalNet,
    BigDecimal totalSocialSecurity,
    BigDecimal totalWithholdingTax,
    List<PayrollLineDto> lines
) {}
