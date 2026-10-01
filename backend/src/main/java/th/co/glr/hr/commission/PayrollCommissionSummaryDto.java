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
    // Manager team override (V196): sum of each rep's teamOverrideAmount, and the company-wide
    // UNWEIGHTED ex-VAT base (2dp) the override was computed from -- NULL when no override applies
    // (disabled, no generation yet, or no recipients), so the UI hides it rather than showing 0.00.
    BigDecimal totalTeamOverrideAmount,
    BigDecimal companyCommissionableBase,
    List<SalesRepCommissionSummaryDto> salesReps
) {}
