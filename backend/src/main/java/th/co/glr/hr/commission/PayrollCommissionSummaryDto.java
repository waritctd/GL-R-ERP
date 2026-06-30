package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollCommissionSummaryDto(
    LocalDate payrollMonth,
    String status,
    BigDecimal totalCommissionableBase,
    BigDecimal totalCommissionAmount,
    List<SalesRepCommissionSummaryDto> salesReps
) {}
