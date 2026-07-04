package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PriceCalcConfigDto(
    long configId,
    int version,
    String country,
    BigDecimal freightPerSqm,
    BigDecimal insurancePerSqm,
    BigDecimal inlandFactoryToPortPerSqm,
    BigDecimal inlandPortToWarehousePerSqm,
    BigDecimal importDutyPct,
    BigDecimal marginPct,
    boolean isCurrent,
    LocalDate effectiveFrom,
    Instant updatedAt
) {}
