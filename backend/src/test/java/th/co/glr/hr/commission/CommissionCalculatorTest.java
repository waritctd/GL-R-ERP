package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CommissionCalculatorTest {
    private final CommissionCalculator calculator = new CommissionCalculator();

    // Issue #405: the ladder as seeded by V108 (effective 2026-08-01) -- no 80,000 row.
    private static final List<IncentiveTierConfig> INCENTIVE_LADDER = List.of(
        new IncentiveTierConfig(1, new BigDecimal("3000000.00"), new BigDecimal("15000.00"), LocalDate.of(2026, 8, 1)),
        new IncentiveTierConfig(2, new BigDecimal("4000000.00"), new BigDecimal("25000.00"), LocalDate.of(2026, 8, 1)),
        new IncentiveTierConfig(3, new BigDecimal("6000000.00"), new BigDecimal("50000.00"), LocalDate.of(2026, 8, 1)),
        new IncentiveTierConfig(4, new BigDecimal("8000000.00"), new BigDecimal("65000.00"), LocalDate.of(2026, 8, 1))
    );

    private static final StockBonusConfig ENABLED_STOCK_BONUS =
        new StockBonusConfig(true, LocalDate.of(2026, 8, 1), new BigDecimal("100000.00"), new BigDecimal("1000.00"));

    // Columns: case name, base, expected commission. The expected value is compared with isEqualByComparingTo, so
    // "0" is the same check as the BigDecimal.ZERO the single-case test used.
    @ParameterizedTest(name = "progressiveCommission_{0}")
    @CsvSource({
        // Tiers 1-12 (0-3,000,000, all fully taxed once base exceeds 3,000,000) sum to 48,750.00. V81 corrected tier
        // 13 from 7.5000% to 3.2500%: the 500,000 excess above 3,000,000 is now taxed at 3.25% = 16,250.00. Total =
        // 65,000.00 (was 86,250.00 pre-V81).
        "appliesHighRollerRateAboveThreeMillion,         3500000.00,        65000.00",
        // Slice A1: <50,000 monthly floor (written wrong-way-round: the below-floor case is the assertion that
        // matters)
        "belowFiftyThousandFloor_paysZero,               49999.00,          0",
        // Tier 1 (0-250,000 @ 0.25%) still applies from THB 0, unchanged, once the floor is met.
        "atOrAboveFiftyThousandFloor_paysNormalTierOneRate, 50001.00,       125.00",
        // Commission redesign calc-refine: round only the FINAL total, never the input base.
        // 801,204.4999999999 sits just under the exact tie point (801,204.50) that tier 4's 1% rate produces at
        // 4,262.045 (750,000-1,000,000 bracket: 3,750.00 for tiers 1-3, plus 51,204.4999999999 * 1% = 512.044999999999
        // -> total 4,262.044999999999).
        "doesNotPreRoundAFullPrecisionBase_beforeRunningTheBrackets, 801204.4999999999, 4262.04",
        // Slice A1 (V81): tier 13 corrected from 7.5000% to 3.2500%.
        // Base = 3,200,000: tiers 1-12 (0-3,000,000, all fully taxed) sum to 48,750.00, then the 200,000 excess above
        // 3,000,000 is taxed at the corrected 3.25% = 6,500.00. Total = 48,750.00 + 6,500.00 = 55,250.00.
        "aboveThreeMillion_usesCorrectedTier13Rate,      3200000.00,        55250.00",
    })
    void progressiveCommission_brackets(String caseName, String base, String expected) {
        BigDecimal commission = calculator.progressiveCommission(new BigDecimal(base));

        assertThat(commission).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void calculateInvoiceDeductsAllFeesBeforeVatStrip() {
        InvoiceCalculation calculation = calculator.calculateInvoice(
            new BigDecimal("107000.00"),
            new BigDecimal("1000.00"),
            new BigDecimal("2000.00"),
            new BigDecimal("3000.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("5000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );

        assertThat(calculation.actualReceived()).isEqualByComparingTo(new BigDecimal("92000.00"));
        assertThat(calculation.commissionableBase()).isEqualByComparingTo(new BigDecimal("85981.31"));
    }

    // ── Slice A1: withholding tax / overpayment (backward compatibility + sign convention) ──

    @Test
    void calculateInvoiceWithWithholdingTax_reducesActualReceivedAndBase() {
        InvoiceCalculation baseline = calculator.calculateInvoice(
            new BigDecimal("107000.00"), new BigDecimal("1000.00"), new BigDecimal("2000.00"),
            new BigDecimal("3000.00"), new BigDecimal("4000.00"), new BigDecimal("5000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation withWht = calculator.calculateInvoice(
            new BigDecimal("107000.00"), new BigDecimal("1000.00"), new BigDecimal("2000.00"),
            new BigDecimal("3000.00"), new BigDecimal("4000.00"), new BigDecimal("5000.00"),
            new BigDecimal("2140.00"), BigDecimal.ZERO);

        assertThat(withWht.actualReceived()).isEqualByComparingTo(new BigDecimal("89860.00"));
        assertThat(withWht.actualReceived()).isLessThan(baseline.actualReceived());
        assertThat(withWht.commissionableBase()).isLessThan(baseline.commissionableBase());
    }

    @Test
    void calculateInvoiceWithOverpayment_increasesActualReceivedAndBase() {
        InvoiceCalculation baseline = calculator.calculateInvoice(
            new BigDecimal("107000.00"), new BigDecimal("1000.00"), new BigDecimal("2000.00"),
            new BigDecimal("3000.00"), new BigDecimal("4000.00"), new BigDecimal("5000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation withOverpayment = calculator.calculateInvoice(
            new BigDecimal("107000.00"), new BigDecimal("1000.00"), new BigDecimal("2000.00"),
            new BigDecimal("3000.00"), new BigDecimal("4000.00"), new BigDecimal("5000.00"),
            BigDecimal.ZERO, new BigDecimal("1000.00"));

        assertThat(withOverpayment.actualReceived()).isEqualByComparingTo(new BigDecimal("93000.00"));
        assertThat(withOverpayment.actualReceived()).isGreaterThan(baseline.actualReceived());
        assertThat(withOverpayment.commissionableBase()).isGreaterThan(baseline.commissionableBase());
    }

    // Columns: case name, base, expected incentive. The expected value is compared with isEqualByComparingTo, so
    // "0" is the same check as the BigDecimal.ZERO the single-case tests used.
    @ParameterizedTest(name = "monthlyIncentive_{0}")
    @CsvSource({
        "justBelowFirstThreshold_paysZero,                 2999999.99,  0",
        "atFirstThreshold_pays15000,                       3000000.00,  15000.00",
        "justBelowSecondThreshold_staysAt15000,            3999999.99,  15000.00",
        "atSecondThreshold_pays25000,                      4000000.00,  25000.00",
        "justBelowThirdThreshold_staysAt25000,             5999999.99,  25000.00",
        "atThirdThreshold_pays50000,                       6000000.00,  50000.00",
        "atFourthThreshold_pays65000,                      8000000.00,  65000.00",
        // The workbook's second "8.00 ล้าน -> 80,000" row is superseded and must NOT exist -- a base of 12,000,000
        // pays 65,000, the same as exactly 8,000,000.
        "wellAboveFourthThreshold_staysAt65000_noEightyThousandRow, 12000000.00, 65000.00",
    })
    void monthlyIncentive_ladderBoundaries(String caseName, String base, String expected) {
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal(base), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(new BigDecimal(expected));
    }

    @Test
    void monthlyIncentive_nullOrNegativeBase_paysZero() {
        assertThat(calculator.monthlyIncentive(null, INCENTIVE_LADDER)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calculator.monthlyIncentive(new BigDecimal("-100.00"), INCENTIVE_LADDER)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Issue #405 — stockSaleBonus: STEPPED, not a percentage; disabled/null config; negative receipts.

    @Test
    void stockSaleBonus_justBelowOneBlock_paysZero() {
        BigDecimal bonus = calculator.stockSaleBonus(new BigDecimal("99999.99"), ENABLED_STOCK_BONUS);

        assertThat(bonus).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void stockSaleBonus_exactlyOneBlock_pays1000() {
        BigDecimal bonus = calculator.stockSaleBonus(new BigDecimal("100000.00"), ENABLED_STOCK_BONUS);

        assertThat(bonus).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void stockSaleBonus_twoAndAHalfBlocks_paysTwoWholeBlocksNotTwoThousandFiveHundred() {
        // Stepped: the ฿50,000 remainder above two whole ฿100,000 blocks earns nothing. A naive 1% read of ฿250,000
        // would give ฿2,500 -- that is NOT this rule.
        BigDecimal bonus = calculator.stockSaleBonus(new BigDecimal("250000.00"), ENABLED_STOCK_BONUS);

        assertThat(bonus).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(bonus).isNotEqualByComparingTo(new BigDecimal("2500.00"));
    }

    // Issue #405 — the four-rep workbook reconciliation table, to the satang. Each rep's tier base and tier
    // commission are asserted first (proving the existing tier math is untouched), then the incentive on top, then
    // the combined total the issue's own table specifies.

    @Test
    void workbookReconciliation_jennet_reachesFirstIncentiveThreshold() {
        BigDecimal tierBase = new BigDecimal("3246381.33");
        BigDecimal tierCommission = calculator.progressiveCommission(tierBase);
        BigDecimal incentive = calculator.monthlyIncentive(tierBase, INCENTIVE_LADDER);

        assertThat(tierCommission).isEqualByComparingTo(new BigDecimal("56757.39"));
        assertThat(incentive).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(tierCommission.add(incentive)).isEqualByComparingTo(new BigDecimal("71757.39"));
    }

    @Test
    void workbookReconciliation_praphatsorn_reachesSecondIncentiveThreshold() {
        BigDecimal tierBase = new BigDecimal("5051807.61");
        BigDecimal tierCommission = calculator.progressiveCommission(tierBase);
        BigDecimal incentive = calculator.monthlyIncentive(tierBase, INCENTIVE_LADDER);

        assertThat(tierCommission).isEqualByComparingTo(new BigDecimal("115433.75"));
        assertThat(incentive).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(tierCommission.add(incentive)).isEqualByComparingTo(new BigDecimal("140433.75"));
    }

    // V148 — itemDerivedWeight: per-item stock-commission weighting, the pure blending math.

    @Test
    void itemDerivedWeight_singleFullyStockedItem_twoX_yieldsExactlyTwo() {
        // The brief's own sanity check: a single fully-stock item at x2 must yield effectiveWeight = 2, independent of
        // the item's absolute qty/price.
        List<CommissionCalculator.ItemStockWeightInput> items = List.of(
            new CommissionCalculator.ItemStockWeightInput(
                new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("500.0000"), null, 2));

        Optional<BigDecimal> weight = calculator.itemDerivedWeight(items, new BigDecimal("100000.00"));

        assertThat(weight).isPresent();
        assertThat(weight.get()).isEqualByComparingTo("2");
    }

    @Test
    void itemDerivedWeight_halfStockCoverage_twoX_yieldsOneAndAHalf_notTheFullTwo() {
        // The brief's own worked example: a half-stock line at x2 earns 1.5, not 2.
        List<CommissionCalculator.ItemStockWeightInput> items = List.of(
            new CommissionCalculator.ItemStockWeightInput(
                new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("500.0000"), null, 2));

        Optional<BigDecimal> weight = calculator.itemDerivedWeight(items, new BigDecimal("100000.00"));

        assertThat(weight).isPresent();
        assertThat(weight.get()).isEqualByComparingTo("1.5");
    }

    @Test
    void itemDerivedWeight_wrongWayRound_zeroStockShare_storedThreeX_contributesOnlyOneX() {
        // The owner's own workbook case: a row marked *3 but sourced against an import request (qtyFromStock = 0) must
        // NOT be credited — only stock-sourced quantity ever earns weight. Empty is what lets that control go on
        // working for a ticket like this one.
        List<CommissionCalculator.ItemStockWeightInput> items = List.of(
            new CommissionCalculator.ItemStockWeightInput(
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("500.0000"), null, 3));

        Optional<BigDecimal> weight = calculator.itemDerivedWeight(items, new BigDecimal("100000.00"));

        assertThat(weight).isEmpty();
    }

    @Test
    void itemDerivedWeight_mixedStockAndImportLines_matchesTheBriefsOwnOneSixExample() {
        // A deal mixing a fully-stock x2 line (60 units) with a fully-import line (40 units, never weighted): (60*2 +
        // 40*1) / 100 = 1.6 — the brief's own worked figure.
        List<CommissionCalculator.ItemStockWeightInput> items = List.of(
            new CommissionCalculator.ItemStockWeightInput(
                new BigDecimal("60.00"), new BigDecimal("60.00"), new BigDecimal("1000.0000"), null, 2),
            new CommissionCalculator.ItemStockWeightInput(
                new BigDecimal("40.00"), BigDecimal.ZERO, new BigDecimal("1000.0000"), null, 1));

        Optional<BigDecimal> weight = calculator.itemDerivedWeight(items, new BigDecimal("100000.00"));

        assertThat(weight).isPresent();
        assertThat(weight.get()).isEqualByComparingTo("1.6");
    }

    // Characterisation tests -- multi-item, value-weighted allocation. They characterise, they do not certify
    // correctness.

    /** Golden pin of the money math the pending-approval / item-weight feature must only CALL, never change. */
    @Test
    void goldenFixedInputs_outputsUnchanged() {
        List<CommissionCalculator.ItemStockWeightInput> items = List.of(
            // stock-covered x2 line priced by approvedPrice
            new CommissionCalculator.ItemStockWeightInput(
                new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("1000.00"), null, 2),
            // non-stock line priced by the proposedPrice fallback
            new CommissionCalculator.ItemStockWeightInput(
                new BigDecimal("5"), BigDecimal.ZERO, null, new BigDecimal("500.00"), 1),
            // fully stock-covered x3 line (approvedPrice wins over proposedPrice)
            new CommissionCalculator.ItemStockWeightInput(
                new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("3000.00"), new BigDecimal("2500.00"), 3));

        // (14000 + 2500 + 18000) / 18500 = 1.864865 (6dp, HALF_UP)
        assertThat(calculator.itemDerivedWeight(items, new BigDecimal("123456.78")))
            .contains(new BigDecimal("1.864865"));

        String[][] golden = {
            // weightedActualReceived, monthlyTierBase, progressiveCommission(TierConfig.defaults())
            {"99999.99", "93457.9345794393", "233.64"},
            {"250000.00", "233644.8598130841", "584.11"},
            {"1000000.00", "934579.4392523364", "5595.79"},
            {"3210987.65", "3000923.0373831776", "48780.00"},
            {"8000000.00", "7476635.5140186916", "194240.65"},
        };
        for (String[] row : golden) {
            BigDecimal base = calculator.monthlyTierBase(new BigDecimal(row[0]));
            assertThat(base).as("monthlyTierBase(%s)", row[0]).isEqualTo(new BigDecimal(row[1]));
            assertThat(calculator.progressiveCommission(base, TierConfig.defaults()))
                .as("progressiveCommission(base of %s)", row[0]).isEqualTo(new BigDecimal(row[2]));
        }
        assertThat(calculator.progressiveCommission(calculator.monthlyTierBase(BigDecimal.ZERO), TierConfig.defaults()))
            .isEqualByComparingTo("0.00");
    }

    // Manager TEAM OVERRIDE (V199): (companyBase - threshold) x rate% ────────────────────── Source: accountant's
    // nine monthly workbooks, sheet อัตราค่าคอม K2 "ยอดรับเงิน(ไม่รวม Vat) - 3 ล้าน *0.075%".

    private static final TeamOverrideConfig WORKBOOK_TEAM_OVERRIDE = new TeamOverrideConfig(
        true, new BigDecimal("3000000.00"), new BigDecimal("0.0750"),
        List.of(new TeamOverrideRecipient(142L, "recipient-a"), new TeamOverrideRecipient(47L, "recipient-b")));

    @Test
    void teamOverride_reproducesAllSevenWorkbookMonthsToTheSatang() {
        String[][] workbook = {
            // month, company ex-VAT receipts (workbook), override the accountant paid
            {"Feb", "4682135.09", "1261.60"},
            {"Mar", "6311454.21", "2483.59"},
            {"Apr", "5708277.47", "2031.21"},
            {"May", "7984067.69", "3738.05"},
            {"Jun", "11032569.26", "6024.43"},
            {"Jul", "5482682.42", "1862.01"},
            {"Aug", "6615086.80", "2711.32"},
        };
        for (String[] row : workbook) {
            assertThat(calculator.teamOverride(new BigDecimal(row[1]), WORKBOOK_TEAM_OVERRIDE))
                .as("teamOverride(%s = %s)", row[0], row[1])
                .isEqualByComparingTo(new BigDecimal(row[2]));
        }
    }

    @Test
    void teamOverride_baseExactlyAtThreshold_isZero() {
        assertThat(calculator.teamOverride(new BigDecimal("3000000.00"), WORKBOOK_TEAM_OVERRIDE))
            .isEqualByComparingTo("0.00");
    }

    @Test
    void teamOverride_oneSatangAboveThreshold_roundsHalfUpNotDown() {
        // 0.01 x 0.075% = 0.0000075 -> 0.00 at 2dp: a tiny excess correctly rounds to nothing...
        assertThat(calculator.teamOverride(new BigDecimal("3000000.01"), WORKBOOK_TEAM_OVERRIDE))
            .isEqualByComparingTo("0.00");
        // ...while 100.00 over pays exactly 0.075 -> HALF_UP to 0.08 (banker's/DOWN would give 0.07).
        assertThat(calculator.teamOverride(new BigDecimal("3000100.00"), WORKBOOK_TEAM_OVERRIDE))
            .isEqualByComparingTo("0.08");
    }

}
