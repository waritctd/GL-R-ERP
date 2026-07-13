package th.co.glr.hr.pricing;

import java.math.BigDecimal;

public record PriceBreakdownItemDto(
    long itemId,
    String brand,
    String model,
    String factory,
    String rawCurrency,
    BigDecimal fxRate,
    BigDecimal sqmPerPiece,
    BigDecimal goodsCostPerSqm,
    BigDecimal freightPerSqm,
    BigDecimal insurancePerSqm,
    BigDecimal cifPerSqm,
    BigDecimal importDutyPerSqm,
    BigDecimal inlandPerSqm,
    BigDecimal landedCostPerSqm,
    BigDecimal marginPct,
    BigDecimal sellPricePerSqm,
    BigDecimal calcedCostPerPiece,
    BigDecimal calcedPricePerPiece,
    int configVersion
) {}
