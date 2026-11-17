package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.Map;

/** The requesting employee's prior usage, pre-aggregated by the caller so the evaluator stays a pure function
 * with no repository access. The maps are counted over deliberately different status sets: amounts — status =
 * 'APPROVED' only. */
public record UsageSnapshot(
    Map<SpecialMoneyType, BigDecimal> approvedAmountThisYearByType,
    Map<SpecialMoneyType, Integer> activeCountLifetimeByType,
    Map<SpecialMoneyType, Integer> activeCountThisYearByType) {

    /** Sum of approved amounts for {@code type} within the request's calendar year. */
    public BigDecimal approvedAmountThisYear(SpecialMoneyType type) {
        return approvedAmountThisYearByType.getOrDefault(type, BigDecimal.ZERO);
    }

    /** Approved-or-in-flight request count for {@code type} across the employee's whole tenure. */
    public int activeCountLifetime(SpecialMoneyType type) {
        return activeCountLifetimeByType.getOrDefault(type, 0);
    }

    /** Approved-or-in-flight request count for {@code type} within the request's calendar year. */
    public int activeCountThisYear(SpecialMoneyType type) {
        return activeCountThisYearByType.getOrDefault(type, 0);
    }
}
