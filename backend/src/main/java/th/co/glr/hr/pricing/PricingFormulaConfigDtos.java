package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * BRANCH 1 of the sales pricing-formula redesign: config storage only. See
 * {@code V109__pricing_formula_config.sql} for the formula this config parameterizes and the
 * versioning model (one parent row owns three child lists; children are never versioned
 * independently). No calculation happens here or anywhere in this package as part of this branch
 * -- reading/writing {@code sales.pricing_formula_config} and its children, nothing else.
 */
public final class PricingFormulaConfigDtos {
    private PricingFormulaConfigDtos() {}

    public record PricingFormulaConfigDto(
        long formulaConfigId,
        int version,
        BigDecimal insuranceValueFactor,
        BigDecimal insuranceRate,
        BigDecimal insuranceBuffer,
        BigDecimal costBuffer,
        BigDecimal sellingBuffer,
        BigDecimal defaultMarginPct,
        BigDecimal sellingPriceRoundUpTo,
        boolean isCurrent,
        LocalDate effectiveFrom,
        Instant updatedAt,
        List<PricingFreightRateDto> freightRates,
        List<PricingDutyRateDto> dutyRates,
        List<PricingClearanceFeeDto> clearanceFees,
        // The countries a freight row may reference. Carried on the config response rather than a
        // separate endpoint: the CEO editor is the only consumer, and it already fetches this.
        // Without it the freight editor could only ever offer countries already in use, so a new
        // supplier country could never be added -- a regression on the free-text field it replaces.
        List<CountryDto> availableCountries
    ) {}

    public record CountryDto(
        String countryCode,
        String nameEn,
        String nameTh
    ) {}

    public record PricingFreightRateDto(
        long freightRateId,
        // ISO 3166-1 alpha-2. This is the join key to price_catalog.factories.country; the name
        // below is display only, resolved from price_catalog.country so the two cannot drift.
        String originCountryCode,
        String originCountryName,
        BigDecimal thicknessMinMm,
        BigDecimal thicknessMaxMm,
        BigDecimal qtyMinSqm,
        BigDecimal qtyMaxSqm,
        BigDecimal amountThb
    ) {}

    public record PricingDutyRateDto(
        long dutyRateId,
        String productType,
        String productLabel,
        BigDecimal dutyPct
    ) {}

    public record PricingClearanceFeeDto(
        long clearanceFeeId,
        BigDecimal qtyMinSqm,
        BigDecimal qtyMaxSqm,
        BigDecimal amountThb
    ) {}
}
