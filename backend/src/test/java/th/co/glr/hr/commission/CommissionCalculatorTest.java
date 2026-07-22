package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CommissionCalculatorTest {
    private final CommissionCalculator calculator = new CommissionCalculator();

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
}
