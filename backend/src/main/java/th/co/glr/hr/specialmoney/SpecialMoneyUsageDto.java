package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Quota read model for the UI: how much of each type an employee has used, keyed by {@link
 * SpecialMoneyType} name so it serializes as a plain JSON object.
 */
public record SpecialMoneyUsageDto(
    long employeeId,
    int year,
    Map<String, BigDecimal> approvedAmountThisYearByType,
    Map<String, Integer> approvedCountLifetimeByType) {
}
