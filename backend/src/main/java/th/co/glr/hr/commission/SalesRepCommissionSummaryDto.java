package th.co.glr.hr.commission;

import java.math.BigDecimal;

public record SalesRepCommissionSummaryDto(
    long salesRepId,
    String salesRepName,
    BigDecimal commissionableBase,
    BigDecimal commissionAmount
) {}
