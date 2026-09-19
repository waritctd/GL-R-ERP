package th.co.glr.hr.pricingcosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingClearanceFeeDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingDutyRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFreightRateDto;

/**
 * Fast, DB-free unit tests for {@link PricingFormulaEngine} — every band-edge case and every
 * arithmetic step of V109's formula, hand-verified. No Spring context, no Postgres: every method
 * under test here takes its config as an explicit argument, so a hand-built fixture list is enough
 * (mirrors a subset of V109's own seeded freight/clearance/duty data so the numbers are directly
 * cross-checkable against {@code V109__pricing_formula_config.sql}).
 *
 * <p>Real-DB coverage of the SEEDED config (that the migration actually inserts) flowing through
 * the full {@link LandedCostCalculator} pipeline lives in
 * {@code LandedCostCalculatorFormulaIntegrationTest}.
 */
class PricingFormulaEngineTest {
    private final PricingFormulaEngine engine = new PricingFormulaEngine(null);

    // ── Freight band selection — half-open [min, max), both axes ────────────────────────────

    /** Mirrors V109's own Italy [8,12)mm row exactly: qty bands [1,101)/[101,451)/[451,801)/[801,NULL).
     * V151 ("one canonical country, so the freight lookup can join"): {@code originCountryCode} is
     * the ISO 3166-1 alpha-2 join key ("IT"); {@code originCountryName} is display-only (this
     * fixture is DB-free, so it is hand-filled here rather than joined from {@code
     * price_catalog.country} the way the real repository resolves it). */
    private List<PricingFreightRateDto> italyFreightRates() {
        return List.of(
            new PricingFreightRateDto(1L, "IT", "อิตาลี", bd("3"), bd("8"), bd("1"), bd("101"), bd("80000")),
            new PricingFreightRateDto(2L, "IT", "อิตาลี", bd("8"), bd("12"), bd("1"), bd("101"), bd("50000")),
            new PricingFreightRateDto(3L, "IT", "อิตาลี", bd("8"), bd("12"), bd("101"), bd("451"), bd("80000")),
            new PricingFreightRateDto(4L, "IT", "อิตาลี", bd("8"), bd("12"), bd("451"), bd("801"), bd("90000")),
            new PricingFreightRateDto(5L, "IT", "อิตาลี", bd("8"), bd("12"), bd("801"), null, bd("100000"))
        );
    }

    @Test
    void freightQtyBand_minIsInclusive_101FallsInSecondBandNotFirst() {
        // 101 is the SECOND band's min and the FIRST band's max — max-exclusive means it must
        // land in [101,451), never [1,101).
        PricingFreightRateDto match = engine.selectFreightRate(italyFreightRates(), "IT", bd("10"), bd("101"), "test");
        assertThat(match.amountThb()).isEqualByComparingTo("80000");
        assertThat(match.qtyMinSqm()).isEqualByComparingTo("101");
    }

    @Test
    void freightQtyBand_justBelowMax_staysInFirstBand() {
        PricingFreightRateDto match = engine.selectFreightRate(italyFreightRates(), "IT", bd("10"), bd("100.99"), "test");
        assertThat(match.amountThb()).isEqualByComparingTo("50000");
    }

    @Test
    void freightQtyBand_exactMin_1FallsInFirstBand() {
        PricingFreightRateDto match = engine.selectFreightRate(italyFreightRates(), "IT", bd("10"), bd("1"), "test");
        assertThat(match.amountThb()).isEqualByComparingTo("50000");
    }

    @Test
    void freightQtyBand_nullMaxCatchesEverythingAboveTheTopBoundary() {
        PricingFreightRateDto atBoundary = engine.selectFreightRate(italyFreightRates(), "IT", bd("10"), bd("801"), "test");
        assertThat(atBoundary.amountThb()).isEqualByComparingTo("100000");
        PricingFreightRateDto wayAbove = engine.selectFreightRate(italyFreightRates(), "IT", bd("10"), bd("999999999"), "test");
        assertThat(wayAbove.amountThb()).isEqualByComparingTo("100000");
        // And the band just below the NULL-max band's start is still the PREVIOUS (finite) band.
        PricingFreightRateDto justBelow = engine.selectFreightRate(italyFreightRates(), "IT", bd("10"), bd("800.99"), "test");
        assertThat(justBelow.amountThb()).isEqualByComparingTo("90000");
    }

    @Test
    void freightThicknessBand_minIsInclusive_8FallsInSecondBandNotFirst() {
        // 8 is band2's min and band1's max — must land in [8,12), never [3,8).
        PricingFreightRateDto match = engine.selectFreightRate(italyFreightRates(), "IT", bd("8"), bd("50"), "test");
        assertThat(match.amountThb()).isEqualByComparingTo("50000");
    }

    @Test
    void freightThicknessBand_justBelowMax_staysInFirstBand() {
        PricingFreightRateDto match = engine.selectFreightRate(italyFreightRates(), "IT", bd("7.99"), bd("50"), "test");
        assertThat(match.amountThb()).isEqualByComparingTo("80000");
    }

    /** V151's whole point: the country match is now EXACT on the canonical ISO code, no fuzzy
     * matching — "Italy" (a display name, what the pre-V151 free-text column held) must NOT match
     * the "IT" rows, exactly as much a miss as an unrelated code like "VN". */
    @Test
    void freight_countryCodeMustMatchExactly_displayNameIsNotAFallback() {
        assertThatThrownBy(() -> engine.selectFreightRate(italyFreightRates(), "Italy", bd("10"), bd("50"), "รายการที่ 7"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    @Test
    void freight_missingCountry_throwsRatherThanReturningZero() {
        assertThatThrownBy(() -> engine.selectFreightRate(italyFreightRates(), "VN", bd("10"), bd("50"), "รายการที่ 42"))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(422);
                assertThat(e.getMessage()).contains("รายการที่ 42");
            });
    }

    @Test
    void freight_thicknessBeyondEverySeededBand_throwsRatherThanReturningZero() {
        assertThatThrownBy(() -> engine.selectFreightRate(italyFreightRates(), "IT", bd("25"), bd("50"), "รายการที่ 9"))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(422);
                assertThat(e.getMessage()).contains("รายการที่ 9");
            });
    }

    @Test
    void freight_overlappingBands_throwsInsteadOfSilentlyPickingOne() {
        List<PricingFreightRateDto> corrupted = List.of(
            new PricingFreightRateDto(1L, "IT", "อิตาลี", bd("8"), bd("12"), bd("1"), bd("101"), bd("50000")),
            new PricingFreightRateDto(2L, "IT", "อิตาลี", bd("8"), bd("12"), bd("50"), bd("150"), bd("999999"))
        );
        assertThatThrownBy(() -> engine.selectFreightRate(corrupted, "IT", bd("10"), bd("60"), "test"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(422));
    }

    // ── Clearance fee band selection — half-open [min, max), qty only ───────────────────────

    /** Mirrors V109's own seeded clearance ladder exactly. */
    private List<PricingClearanceFeeDto> clearanceFees() {
        return List.of(
            new PricingClearanceFeeDto(1L, bd("1"), bd("101"), bd("8000")),
            new PricingClearanceFeeDto(2L, bd("101"), bd("451"), bd("12000")),
            new PricingClearanceFeeDto(3L, bd("451"), bd("801"), bd("15000")),
            new PricingClearanceFeeDto(4L, bd("801"), null, bd("20000"))
        );
    }

    @Test
    void clearanceBand_minIsInclusive_101FallsInSecondBandNotFirst() {
        PricingClearanceFeeDto match = engine.selectClearanceFee(clearanceFees(), bd("101"), "test");
        assertThat(match.amountThb()).isEqualByComparingTo("12000");
    }

    @Test
    void clearanceBand_justBelowMax_staysInFirstBand() {
        PricingClearanceFeeDto match = engine.selectClearanceFee(clearanceFees(), bd("100.99"), "test");
        assertThat(match.amountThb()).isEqualByComparingTo("8000");
    }

    @Test
    void clearanceBand_nullMaxCatchesEverythingAboveTheTopBoundary() {
        assertThat(engine.selectClearanceFee(clearanceFees(), bd("801"), "test").amountThb())
            .isEqualByComparingTo("20000");
        assertThat(engine.selectClearanceFee(clearanceFees(), bd("50000000"), "test").amountThb())
            .isEqualByComparingTo("20000");
    }

    @Test
    void clearance_qtyBelowEveryBandsMinimum_throwsRatherThanReturningZero() {
        // Every seeded band starts at min=1 — a qty of 0 (or negative) matches none of them.
        assertThatThrownBy(() -> engine.selectClearanceFee(clearanceFees(), bd("0"), "shipment ABC"))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(422);
                assertThat(e.getMessage()).contains("shipment ABC");
            });
    }

    // ── Duty rate selection — exact match ────────────────────────────────────────────────────

    private List<PricingDutyRateDto> dutyRates() {
        return List.of(
            new PricingDutyRateDto(1L, "TILE", "กระเบื้อง", bd("0.300000")),
            new PricingDutyRateDto(2L, "GLASS_MOSAIC", "โมเสคแก้ว", bd("0.100000"))
        );
    }

    @Test
    void duty_tileDefault_is30Percent() {
        assertThat(engine.selectDutyRate(dutyRates(), "TILE", "test").dutyPct()).isEqualByComparingTo("0.3");
    }

    @Test
    void duty_glassMosaicOverride_is10PercentNot30() {
        // The owner's own worked example: โมเสคแก้ว must NOT be taxed at TILE's 30%.
        assertThat(engine.selectDutyRate(dutyRates(), "GLASS_MOSAIC", "test").dutyPct()).isEqualByComparingTo("0.1");
    }

    @Test
    void duty_unknownProductType_throwsRatherThanReturningZero() {
        assertThatThrownBy(() -> engine.selectDutyRate(dutyRates(), "SANITARY_WARE", "รายการที่ 5"))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(422);
                assertThat(e.getMessage()).contains("รายการที่ 5");
            });
    }

    // ── Pure arithmetic — hand-computed ──────────────────────────────────────────────────────

    /** i = C x insurance_value_factor x insurance_rate x insurance_buffer, V109's own worked
     * constants: 1.15 x 0.0045 x 1.07. Hand-computed: 10000 x 1.15 = 11500; x 0.0045 = 51.75;
     * x 1.07 = 55.3725. */
    @Test
    void insurance_handComputed() {
        BigDecimal result = engine.insurance(bd("10000.0000"), config(bd("1.15"), bd("0.0045"), bd("1.07"), bd("1.07"), bd("1.07")));
        assertThat(result).isEqualByComparingTo("55.3725");
    }

    /** duty amount = cif x dutyPct. Hand-computed: 60055.3725 x 0.30 = 18016.61175, HALF_UP to
     * 4dp (the digit immediately after is exactly 5) rounds AWAY FROM ZERO: 18016.6118. */
    @Test
    void dutyAmount_handComputed_halfUpRoundsExactMidpointUp() {
        BigDecimal result = engine.dutyAmount(bd("60055.3725"), bd("0.30"));
        assertThat(result).isEqualByComparingTo("18016.6118");
    }

    /** TC = [(cif + duty) x cost_buffer] + clearance. Hand-computed: (60055.3725 + 18016.6118) =
     * 78071.9843; x 1.07 = 83537.023201, rounds to 83537.0232; + 8000.0000 = 91537.0232. */
    @Test
    void totalLandedCost_handComputed() {
        BigDecimal result = engine.totalLandedCost(bd("60055.3725"), bd("18016.6118"), bd("1.07"), bd("8000.0000"));
        assertThat(result).isEqualByComparingTo("91537.0232");
    }

    /** UC = TC / Q. Hand-computed: 91537.0232 / 100 = 915.370232 (exact, terminating). */
    @Test
    void unitCostPerSqm_handComputed() {
        BigDecimal result = engine.unitCostPerSqm(bd("91537.0232"), bd("100"));
        assertThat(result).isEqualByComparingTo("915.370232");
    }

    // ── Selling price rounding — owner ruling 2026-09-19 (Phase 2 CEO pricing): SP is now
    // HALF_UP 2dp, no longer rounded UP to the nearest selling_price_round_up_to. The old
    // "round-up to nearest ฿10" cases below are REPLACED, not merely renamed — their own old
    // assertions (130.0000/200.0000/190.0000/200.0000) no longer hold under the new rule. ──────

    /** cost=100, margin=0.20, buffer=1.07: raw = 100 x 1.20 x 1.07 = 128.4, which is already
     * exactly 2dp — HALF_UP 2dp leaves it unchanged (no more bump to the ฿130 multiple the old
     * round-up rule gave it). Exercises the full three-factor multiplication (cost x margin x
     * buffer) before the rounding step, unlike the isolated-input tests below. */
    @Test
    void sellingPrice_costMarginAndBufferAllApplied_roundsHalfUpToTwoDp() {
        BigDecimal result = engine.sellingPrice(bd("100"), bd("0.20"), bd("1.07"));
        assertThat(result).isEqualByComparingTo("128.40");
    }

    /** The OLD round-up brief's own worked example, now under the NEW rule: ฿191.96 in (margin=0,
     * buffer=1, so the raw figure equals exactly 191.96) stays ฿191.96 — it no longer bumps to
     * ฿200. This is the owner's own stated correction (2026-09-19): "฿191.96 no longer becomes
     * ฿200; it prints as ฿191.96." */
    @Test
    void sellingPrice_19196_noLongerRoundsUpTo200_stays19196() {
        BigDecimal result = engine.sellingPrice(bd("191.96"), BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(result).isEqualByComparingTo("191.96");
    }

    /** HALF_UP at the 2dp boundary: a third-decimal exactly at 5 rounds up (191.965 -> 191.97). */
    @Test
    void sellingPrice_halfUpAtThirdDecimal_roundsUp() {
        BigDecimal result = engine.sellingPrice(bd("191.965"), BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(result).isEqualByComparingTo("191.97");
    }

    /** Companion: just under the halfway point rounds down (191.964 -> 191.96) — HALF_UP is not
     * "always up", only exact halves are. */
    @Test
    void sellingPrice_justBelowHalfUpAtThirdDecimal_roundsDown() {
        BigDecimal result = engine.sellingPrice(bd("191.964"), BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(result).isEqualByComparingTo("191.96");
    }

    /** The owner's own worked example (2026-09-19, Phase 2 CEO pricing UAT): UC per แผ่น
     * 1,061.20 x margin 20% x selling_buffer 1.07 must print as a 2-decimal figure, NOT the old
     * round-up-to-฿10 result of ฿1,370 — raw = 1061.20 x 1.20 x 1.07 = 1,362.5808, HALF_UP 2dp =
     * ฿1,362.58. {@code selling_price_round_up_to} is not passed to {@link
     * PricingFormulaEngine#sellingPrice} at all any more — it has no effect on this figure. */
    @Test
    void sellingPrice_ownersWorkedExample_uc1061_20_neverRoundsUpTo1370() {
        BigDecimal result = engine.sellingPrice(bd("1061.20"), bd("0.20"), bd("1.07"));
        assertThat(result).isEqualByComparingTo("1362.58");
        assertThat(result).isNotEqualByComparingTo("1370");
    }

    private PricingFormulaConfigDto config(BigDecimal ivf, BigDecimal ir, BigDecimal ib, BigDecimal costBuffer,
                                           BigDecimal sellingBuffer) {
        return new PricingFormulaConfigDto(1L, 1, ivf, ir, ib, costBuffer, sellingBuffer, bd("0.20"), bd("10"),
            true, java.time.LocalDate.now(), java.time.Instant.now(), List.of(), List.of(), List.of(), List.of());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
