package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The requesting employee's prior approved usage, pre-aggregated by the caller (from {@code
 * hr.special_money_request} where {@code status = 'APPROVED'}) so the evaluator stays a pure
 * function with no repository access.
 */
public record UsageSnapshot(
    Map<SpecialMoneyType, BigDecimal> approvedAmountThisYearByType,
    Map<SpecialMoneyType, Integer> approvedCountLifetimeByType) {

    /** Sum of approved amounts for {@code type} within the current calendar year. */
    public BigDecimal approvedAmountThisYear(SpecialMoneyType type) {
        return approvedAmountThisYearByType.getOrDefault(type, BigDecimal.ZERO);
    }

    /** Count of approved requests for {@code type} across the employee's whole tenure. */
    public int approvedCountLifetime(SpecialMoneyType type) {
        return approvedCountLifetimeByType.getOrDefault(type, 0);
    }
}
