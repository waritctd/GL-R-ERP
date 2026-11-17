package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.Map;

/** Quota read model for the UI: how much of each type an employee has used, keyed by SpecialMoneyType name so
 * it serializes as a plain JSON object. It backs the "once per lifetime" guard, which must see undecided
 * requests or an employee could file the same claim twice before either was decided. */
public record SpecialMoneyUsageDto(
    long employeeId,
    int year,
    Map<String, BigDecimal> approvedAmountThisYearByType,
    Map<String, Integer> approvedCountLifetimeByType,
    Map<String, Integer> activeCountThisYearByType) {
}
