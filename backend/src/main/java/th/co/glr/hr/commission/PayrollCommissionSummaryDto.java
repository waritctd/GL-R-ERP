package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollCommissionSummaryDto(
    LocalDate payrollMonth,
    String status,
    BigDecimal totalCommissionableBase,
    BigDecimal totalCommissionAmount,
    // Issue #405: month totals for the two new auto-computed limbs -- sum of each rep's
    // incentiveAmount/stockBonusAmount below, mirroring totalCommissionableBase/
    // totalCommissionAmount's own sum-of-reps pattern. Both are additive: this is a new field on
    // an existing DTO, not a contract removal.
    BigDecimal totalIncentiveAmount,
    BigDecimal totalStockBonusAmount,
    List<SalesRepCommissionSummaryDto> salesReps
) {}
