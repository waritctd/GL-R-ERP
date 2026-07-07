package th.co.glr.hr.pricing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdatePriceCalcConfigRequest(
    @NotBlank String country,
    @NotNull BigDecimal freightPerSqm,
    @NotNull BigDecimal insurancePerSqm,
    @NotNull BigDecimal inlandFactoryToPortPerSqm,
    @NotNull BigDecimal inlandPortToWarehousePerSqm,
    @NotNull BigDecimal importDutyPct,
    @NotNull BigDecimal marginPct,
    LocalDate effectiveFrom
) {}
