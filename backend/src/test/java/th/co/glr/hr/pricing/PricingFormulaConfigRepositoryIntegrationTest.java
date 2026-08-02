package th.co.glr.hr.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.ClearanceFeeRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.CreatePricingFormulaConfigRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.DutyRateRequest;
import th.co.glr.hr.pricing.PricingFormulaConfigRequests.FreightRateRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Exercises PricingFormulaConfigRepository's versioned parent+children SQL against a real
 * PostgreSQL database -- Mockito cannot cover the CHECK constraints, the singleton
 * {@code is_current} partial unique index, or that a new version's INSERTs actually land as a
 * full, ordered snapshot.
 *
 * <p>BRANCH 1 scope: config storage only. This does not exercise any pricing calculation.
 */
class PricingFormulaConfigRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingFormulaConfigRepository formulaConfigs;

    @BeforeEach
    void wireRepository() {
        formulaConfigs = new PricingFormulaConfigRepository(jdbc);
    }

    @Test
    void findCurrent_returnsTheV109SeededConfigWithAllChildren() {
        PricingFormulaConfigDto config = formulaConfigs.findCurrent().orElseThrow();

        assertThat(config.version()).isEqualTo(1);
        assertThat(config.isCurrent()).isTrue();
        assertThat(config.insuranceValueFactor()).isEqualByComparingTo("1.150000");
        assertThat(config.insuranceRate()).isEqualByComparingTo("0.004500");
        assertThat(config.insuranceBuffer()).isEqualByComparingTo("1.070000");
        assertThat(config.costBuffer()).isEqualByComparingTo("1.070000");
        assertThat(config.sellingBuffer()).isEqualByComparingTo("1.070000");
        assertThat(config.defaultMarginPct()).isEqualByComparingTo("0.200000");
        assertThat(config.sellingPriceRoundUpTo()).isEqualByComparingTo("10.0000");

        // Italy + Spain: 4 thickness bands x (4,4,3,2) qty bands = 13 rows each. China: same
        // shape = 13 rows. 13*3 = 39.
        assertThat(config.freightRates()).hasSize(39);

        assertThat(config.dutyRates()).hasSize(2);
        assertThat(config.dutyRates()).extracting("productType").containsExactlyInAnyOrder("TILE", "GLASS_MOSAIC");
        // ก็อกน้ำ / ยาแนว are deliberately out of scope -- must not be seeded.
        assertThat(config.dutyRates()).extracting("productType").doesNotContain("FAUCET", "GROUT");

        assertThat(config.clearanceFees()).hasSize(4);
    }

    @Test
    void findCurrent_theDeliberatelyBlankFreightCellsAreAbsentNotZero() {
        PricingFormulaConfigDto config = formulaConfigs.findCurrent().orElseThrow();

        for (String country : List.of("Italy", "Spain")) {
            // [12,17)mm band4 (801+) is blank in the sheet.
            assertThat(hasFreightRow(config, country, "12", "17", "801")).isFalse();
            // [17,21)mm band3 (451-800) and band4 (801+) are blank in the sheet.
            assertThat(hasFreightRow(config, country, "17", "21", "451")).isFalse();
            assertThat(hasFreightRow(config, country, "17", "21", "801")).isFalse();
        }
        assertThat(hasFreightRow(config, "China", "12", "17", "801")).isFalse();
        assertThat(hasFreightRow(config, "China", "17", "21", "451")).isFalse();
        assertThat(hasFreightRow(config, "China", "17", "21", "801")).isFalse();

        // Sanity: a cell that IS in the sheet is present.
        assertThat(hasFreightRow(config, "China", "3", "8", "1")).isTrue();
    }

    /**
     * Defect 1 regression: the bands used to be seeded as CLOSED intervals (1-100, 101-450, ...),
     * which left no row matching a fractional value exactly at a boundary -- e.g. 100.5 sqm or
     * 7.5 mm matched nothing at all, so an ordinary fractional-sqm tile order could not be priced.
     * V109 now seeds HALF-OPEN bands [min, max) that are contiguous (band N's max == band N+1's
     * min), so every quantity/thickness value falls in EXACTLY one band -- asserting "exactly one"
     * (not "at least one") catches both a gap (falls in zero bands) and an overlap (falls in more
     * than one).
     */
    @Test
    void findCurrent_fractionalQuantityAndThicknessEachFallInExactlyOneContiguousBand() {
        PricingFormulaConfigDto config = formulaConfigs.findCurrent().orElseThrow();

        BigDecimal qty = new BigDecimal("100.5");
        BigDecimal thickness = new BigDecimal("7.5");

        long matchingClearanceBands = config.clearanceFees().stream()
            .filter(fee -> inHalfOpenBand(qty, fee.qtyMinSqm(), fee.qtyMaxSqm()))
            .count();
        assertThat(matchingClearanceBands).isEqualTo(1);

        // China [3,8)mm x [1,101)sqm is the band a 7.5mm/100.5sqm order should land in.
        long matchingFreightBands = config.freightRates().stream()
            .filter(rate -> "China".equals(rate.originCountry()))
            .filter(rate -> inHalfOpenBand(thickness, rate.thicknessMinMm(), rate.thicknessMaxMm()))
            .filter(rate -> inHalfOpenBand(qty, rate.qtyMinSqm(), rate.qtyMaxSqm()))
            .count();
        assertThat(matchingFreightBands).isEqualTo(1);

        // Also true of every distinct (country, thickness-band) combination for this quantity --
        // not just China's: exactly one qty band per thickness band matches 100.5 sqm.
        record ThicknessBand(String country, BigDecimal min, BigDecimal max) {}
        List<ThicknessBand> thicknessBands = config.freightRates().stream()
            .map(rate -> new ThicknessBand(rate.originCountry(), rate.thicknessMinMm(), rate.thicknessMaxMm()))
            .distinct()
            .toList();
        for (ThicknessBand band : thicknessBands) {
            long matches = config.freightRates().stream()
                .filter(rate -> rate.originCountry().equals(band.country())
                    && rate.thicknessMinMm().compareTo(band.min()) == 0
                    && rate.thicknessMaxMm().compareTo(band.max()) == 0)
                .filter(rate -> inHalfOpenBand(qty, rate.qtyMinSqm(), rate.qtyMaxSqm()))
                .count();
            assertThat(matches)
                .as("qty band matches for %s thickness [%s,%s)", band.country(), band.min(), band.max())
                .isEqualTo(1);
        }
    }

    private boolean inHalfOpenBand(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && (max == null || value.compareTo(max) < 0);
    }

    @Test
    void findCurrent_freightAndClearanceAreOrderedDeterministically() {
        PricingFormulaConfigDto config = formulaConfigs.findCurrent().orElseThrow();

        List<String> countries = config.freightRates().stream().map(r -> r.originCountry()).distinct().toList();
        assertThat(countries).isSorted();

        List<BigDecimal> clearanceMins = config.clearanceFees().stream().map(f -> f.qtyMinSqm()).toList();
        assertThat(clearanceMins).isSortedAccordingTo(BigDecimal::compareTo);
    }

    @Test
    void createNewVersion_incrementsVersionFlipsCurrentAndRetainsThePreviousVersionsRows() {
        PricingFormulaConfigDto original = formulaConfigs.findCurrent().orElseThrow();
        long originalId = original.formulaConfigId();
        int originalFreightCount = original.freightRates().size();

        CreatePricingFormulaConfigRequest request = new CreatePricingFormulaConfigRequest(
            new BigDecimal("1.20"), new BigDecimal("0.0050"), new BigDecimal("1.10"),
            new BigDecimal("1.10"), new BigDecimal("1.10"), new BigDecimal("0.25"),
            new BigDecimal("20"), null,
            List.of(new FreightRateRequest("Vietnam", new BigDecimal("3"), new BigDecimal("7"),
                new BigDecimal("1"), new BigDecimal("100"), new BigDecimal("70000"))),
            List.of(new DutyRateRequest("TILE", "กระเบื้อง", new BigDecimal("0.30"))),
            List.of(new ClearanceFeeRequest(new BigDecimal("1"), null, new BigDecimal("9000"))));

        PricingFormulaConfigDto updated = formulaConfigs.createNewVersion(request, null);

        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.isCurrent()).isTrue();
        assertThat(updated.formulaConfigId()).isNotEqualTo(originalId);
        assertThat(updated.insuranceValueFactor()).isEqualByComparingTo("1.20");
        assertThat(updated.freightRates()).hasSize(1);
        assertThat(updated.freightRates().get(0).originCountry()).isEqualTo("Vietnam");
        assertThat(updated.dutyRates()).hasSize(1);
        assertThat(updated.clearanceFees()).hasSize(1);

        // The previous version's rows are retained untouched, not deleted -- only is_current flips.
        Integer previousIsCurrent = jdbc.queryForObject(
            "SELECT CASE WHEN is_current THEN 1 ELSE 0 END FROM sales.pricing_formula_config WHERE formula_config_id = :id",
            java.util.Map.of("id", originalId), Integer.class);
        assertThat(previousIsCurrent).isZero();

        Integer previousFreightCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_freight_rate WHERE formula_config_id = :id",
            java.util.Map.of("id", originalId), Integer.class);
        assertThat(previousFreightCount).isEqualTo(originalFreightCount);

        // findCurrent() now returns only the new version.
        assertThat(formulaConfigs.findCurrent().orElseThrow().formulaConfigId()).isEqualTo(updated.formulaConfigId());
    }

    @Test
    void createNewVersion_calledTwiceProducesThreeGenerationsInStrictSequence() {
        CreatePricingFormulaConfigRequest request = new CreatePricingFormulaConfigRequest(
            new BigDecimal("1.15"), new BigDecimal("0.0045"), new BigDecimal("1.07"),
            new BigDecimal("1.07"), new BigDecimal("1.07"), new BigDecimal("0.2"),
            new BigDecimal("10"), null,
            List.of(new FreightRateRequest("China", new BigDecimal("3"), new BigDecimal("7"),
                new BigDecimal("1"), null, new BigDecimal("60000"))),
            List.of(new DutyRateRequest("TILE", "กระเบื้อง", new BigDecimal("0.30"))),
            List.of(new ClearanceFeeRequest(new BigDecimal("1"), null, new BigDecimal("8000"))));

        PricingFormulaConfigDto v2 = formulaConfigs.createNewVersion(request, null);
        PricingFormulaConfigDto v3 = formulaConfigs.createNewVersion(request, null);

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v3.version()).isEqualTo(3);
        assertThat(formulaConfigs.findCurrent().orElseThrow().version()).isEqualTo(3);
    }

    private boolean hasFreightRow(PricingFormulaConfigDto config, String country, String thicknessMin, String thicknessMax, String qtyMin) {
        return config.freightRates().stream().anyMatch(r ->
            r.originCountry().equals(country)
                && r.thicknessMinMm().compareTo(new BigDecimal(thicknessMin)) == 0
                && r.thicknessMaxMm().compareTo(new BigDecimal(thicknessMax)) == 0
                && r.qtyMinSqm().compareTo(new BigDecimal(qtyMin)) == 0);
    }
}
