package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CommissionSimulationDto(
    LocalDate payrollMonth,
    BigDecimal actualReceived,
    BigDecimal commissionableBase,
    BigDecimal existingMonthlyBase,
    BigDecimal projectedMonthlyBase,
    BigDecimal projectedMonthlyCommission,
    BigDecimal incrementalCommission
) {}
