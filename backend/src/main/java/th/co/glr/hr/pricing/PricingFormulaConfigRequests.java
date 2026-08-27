package th.co.glr.hr.pricing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request shapes for {@link PricingFormulaConfigController}'s POST -- creates a new version. */
public final class PricingFormulaConfigRequests {
    private PricingFormulaConfigRequests() {}

    public record CreatePricingFormulaConfigRequest(
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal insuranceValueFactor,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal insuranceRate,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal insuranceBuffer,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal costBuffer,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal sellingBuffer,
        @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true) BigDecimal defaultMarginPct,
        // Must be strictly positive -- it's the rounding step SP is rounded up to; zero would make
        // RoundUp() a division by zero downstream, in the engine branches build on top of this config.
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal sellingPriceRoundUpTo,
        LocalDate effectiveFrom,
        @NotEmpty @Valid List<FreightRateRequest> freightRates,
        @NotEmpty @Valid List<DutyRateRequest> dutyRates,
        @NotEmpty @Valid List<ClearanceFeeRequest> clearanceFees
    ) {}

    public record FreightRateRequest(
        // ISO 3166-1 alpha-2, must exist in price_catalog.country -- the FK rejects anything else,
        // which is the point: a free-text country is what made the freight lookup never match.
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "ต้องเป็นรหัสประเทศ 2 ตัวอักษร")
        String originCountryCode,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal thicknessMinMm,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal thicknessMaxMm,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal qtyMinSqm,
        BigDecimal qtyMaxSqm,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal amountThb
    ) {}

    public record DutyRateRequest(
        @NotBlank String productType,
        @NotBlank String productLabel,
        @NotNull @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "1", inclusive = true) BigDecimal dutyPct
    ) {}

    public record ClearanceFeeRequest(
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal qtyMinSqm,
        BigDecimal qtyMaxSqm,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal amountThb
    ) {}
}
