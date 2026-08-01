package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    @Test
    void progressiveCommission_appliesHighRollerRateAboveThreeMillion() {
        // Tiers 1-12 (0-3,000,000, all fully taxed once base exceeds 3,000,000) sum to
        // 48,750.00. V81 corrected tier 13 from 7.5000% to 3.2500%: the 500,000 excess above
        // 3,000,000 is now taxed at 3.25% = 16,250.00. Total = 65,000.00 (was 86,250.00 pre-V81).
        BigDecimal commission = calculator.progressiveCommission(new BigDecimal("3500000.00"));

        assertThat(commission).isEqualByComparingTo(new BigDecimal("65000.00"));
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
    void calculateInvoiceWithZeroWhtAndOverpayment_matchesPreSliceA1Result() {
        InvoiceCalculation withZeros = calculator.calculateInvoice(
            new BigDecimal("107000.00"), new BigDecimal("1000.00"), new BigDecimal("2000.00"),
            new BigDecimal("3000.00"), new BigDecimal("4000.00"), new BigDecimal("5000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation withNulls = calculator.calculateInvoice(
            new BigDecimal("107000.00"), new BigDecimal("1000.00"), new BigDecimal("2000.00"),
            new BigDecimal("3000.00"), new BigDecimal("4000.00"), new BigDecimal("5000.00"),
            null, null);

        assertThat(withZeros.actualReceived()).isEqualByComparingTo(new BigDecimal("92000.00"));
        assertThat(withNulls.actualReceived()).isEqualByComparingTo(new BigDecimal("92000.00"));
        assertThat(withNulls.commissionableBase()).isEqualByComparingTo(withZeros.commissionableBase());
    }

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

    // ── Slice A1: <50,000 monthly floor (written wrong-way-round: the below-floor case is the
    // assertion that matters) ──

    @Test
    void progressiveCommission_belowFiftyThousandFloor_paysZero() {
        BigDecimal commission = calculator.progressiveCommission(new BigDecimal("49999.00"));

        assertThat(commission).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void progressiveCommission_atOrAboveFiftyThousandFloor_paysNormalTierOneRate() {
        BigDecimal commission = calculator.progressiveCommission(new BigDecimal("50001.00"));

        // Tier 1 (0-250,000 @ 0.25%) still applies from THB 0, unchanged, once the floor is met.
        assertThat(commission).isEqualByComparingTo(new BigDecimal("125.00"));
    }

    // ── Slice A1 (V81): tier 13 corrected from 7.5000% to 3.2500% ──

    // ── Commission redesign calc-refine: round only the FINAL total, never the input base ──

    @Test
    void progressiveCommission_doesNotPreRoundAFullPrecisionBase_beforeRunningTheBrackets() {
        // 801,204.4999999999 sits just under the exact tie point (801,204.50) that tier 4's 1%
        // rate produces at 4,262.045 (750,000-1,000,000 bracket: 3,750.00 for tiers 1-3, plus
        // 51,204.4999999999 * 1% = 512.044999999999 -> total 4,262.044999999999). Rounding ONLY
        // that final total (this method's contract) gives 4,262.04. If this method pre-rounded
        // the input base to 2dp first (the pre-calc-refine bug), the base would round UP to
        // 801,204.50 exactly, landing precisely on the tie and giving 4,262.05 instead -- a real,
        // observable divergence, not a cosmetic one. Mutation-checked: reintroducing the
        // pre-rounding (money(monthlyCommissionableBase) instead of a plain null check) flips
        // this assertion from 4,262.04 to 4,262.05.
        BigDecimal commission = calculator.progressiveCommission(new BigDecimal("801204.4999999999"));

        assertThat(commission).isEqualByComparingTo(new BigDecimal("4262.04"));
    }

    @Test
    void progressiveCommission_aboveThreeMillion_usesCorrectedTier13Rate() {
        // Base = 3,200,000: tiers 1-12 (0-3,000,000, all fully taxed) sum to 48,750.00, then the
        // 200,000 excess above 3,000,000 is taxed at the corrected 3.25% = 6,500.00.
        // Total = 48,750.00 + 6,500.00 = 55,250.00.
        BigDecimal commission = calculator.progressiveCommission(new BigDecimal("3200000.00"));

        assertThat(commission).isEqualByComparingTo(new BigDecimal("55250.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Issue #405 — monthlyIncentive: ladder boundaries (highest threshold reached wins, NOT
    // cumulative/pro-rated), plus the explicit "no 80,000 row" and empty/null-input guards.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void monthlyIncentive_justBelowFirstThreshold_paysZero() {
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("2999999.99"), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void monthlyIncentive_atFirstThreshold_pays15000() {
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("3000000.00"), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    @Test
    void monthlyIncentive_justBelowSecondThreshold_staysAt15000() {
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("3999999.99"), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    @Test
    void monthlyIncentive_atSecondThreshold_pays25000() {
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("4000000.00"), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(new BigDecimal("25000.00"));
    }

    @Test
    void monthlyIncentive_justBelowThirdThreshold_staysAt25000() {
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("5999999.99"), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(new BigDecimal("25000.00"));
    }

    @Test
    void monthlyIncentive_atThirdThreshold_pays50000() {
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("6000000.00"), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    void monthlyIncentive_atFourthThreshold_pays65000() {
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("8000000.00"), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(new BigDecimal("65000.00"));
    }

    @Test
    void monthlyIncentive_wellAboveFourthThreshold_staysAt65000_noEightyThousandRow() {
        // The workbook's second "8.00 ล้าน -> 80,000" row is superseded and must NOT exist -- a
        // base of 12,000,000 pays 65,000, the same as exactly 8,000,000.
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("12000000.00"), INCENTIVE_LADDER);

        assertThat(incentive).isEqualByComparingTo(new BigDecimal("65000.00"));
    }

    @Test
    void monthlyIncentive_emptyLadder_paysZero() {
        // An empty (or mis-seeded, i.e. any payroll month before 2026-08-01) ladder must mean
        // ZERO -- never an invented default table (deliberately unlike TierConfig.defaults()).
        BigDecimal incentive = calculator.monthlyIncentive(new BigDecimal("9000000.00"), List.of());

        assertThat(incentive).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void monthlyIncentive_nullOrNegativeBase_paysZero() {
        assertThat(calculator.monthlyIncentive(null, INCENTIVE_LADDER)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(calculator.monthlyIncentive(new BigDecimal("-100.00"), INCENTIVE_LADDER)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Issue #405 — stockSaleBonus: STEPPED, not a percentage; disabled/null config; negative
    // receipts.
    // ─────────────────────────────────────────────────────────────────────────────────────

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
        // Stepped: the ฿50,000 remainder above two whole ฿100,000 blocks earns nothing. A naive
        // 1% read of ฿250,000 would give ฿2,500 -- that is NOT this rule.
        BigDecimal bonus = calculator.stockSaleBonus(new BigDecimal("250000.00"), ENABLED_STOCK_BONUS);

        assertThat(bonus).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(bonus).isNotEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    void stockSaleBonus_disabledConfig_paysZero() {
        StockBonusConfig disabled = new StockBonusConfig(false, LocalDate.of(2026, 8, 1), new BigDecimal("100000.00"), new BigDecimal("1000.00"));

        BigDecimal bonus = calculator.stockSaleBonus(new BigDecimal("250000.00"), disabled);

        assertThat(bonus).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void stockSaleBonus_nullConfig_paysZero() {
        BigDecimal bonus = calculator.stockSaleBonus(new BigDecimal("250000.00"), null);

        assertThat(bonus).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void stockSaleBonus_negativeReceipts_paysZero() {
        // e.g. a month where a CLAWBACK's negative actual_received outweighs the stock-linked
        // sales -- the clamp-at-zero-before-flooring rule.
        BigDecimal bonus = calculator.stockSaleBonus(new BigDecimal("-50000.00"), ENABLED_STOCK_BONUS);

        assertThat(bonus).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void stockSaleBonus_defaultDisabledSeedConfig_paysZero() {
        // The literal V108 seed row: enabled = FALSE. Proves the "ships config-gated OFF" claim
        // against the exact config shape production starts with.
        StockBonusConfig seeded = new StockBonusConfig(false, LocalDate.of(2026, 8, 1), new BigDecimal("100000.00"), new BigDecimal("1000.00"));

        BigDecimal bonus = calculator.stockSaleBonus(new BigDecimal("1000000.00"), seeded);

        assertThat(bonus).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Issue #405 — the four-rep workbook reconciliation table, to the satang. Each rep's tier
    // base and tier commission are asserted first (proving the existing tier math is untouched),
    // then the incentive on top, then the combined total the issue's own table specifies.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void workbookReconciliation_chanida_belowFloorIncentiveThreshold_noIncentive() {
        BigDecimal tierBase = new BigDecimal("1373688.69");
        BigDecimal tierCommission = calculator.progressiveCommission(tierBase);
        BigDecimal incentive = calculator.monthlyIncentive(tierBase, INCENTIVE_LADDER);

        assertThat(tierCommission).isEqualByComparingTo(new BigDecimal("11230.33"));
        assertThat(incentive).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tierCommission.add(incentive)).isEqualByComparingTo(new BigDecimal("11230.33"));
    }

    @Test
    void workbookReconciliation_suwannee_belowFloorIncentiveThreshold_noIncentive() {
        BigDecimal tierBase = new BigDecimal("559711.64");
        BigDecimal tierCommission = calculator.progressiveCommission(tierBase);
        BigDecimal incentive = calculator.monthlyIncentive(tierBase, INCENTIVE_LADDER);

        assertThat(tierCommission).isEqualByComparingTo(new BigDecimal("2322.84"));
        assertThat(incentive).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tierCommission.add(incentive)).isEqualByComparingTo(new BigDecimal("2322.84"));
    }

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
}
