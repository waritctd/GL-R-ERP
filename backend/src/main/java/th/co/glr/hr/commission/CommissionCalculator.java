package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CommissionCalculator {
    private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.07");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;
    // Commission redesign calc-refine: the monthly TIER BASE is computed at this many decimal
    // places (not 2dp) so the single VAT-strip division doesn't reintroduce the per-receipt
    // rounding error the whole point of this slice is to remove. "8+" per the workbook
    // reconciliation; 10 leaves comfortable headroom under progressiveCommission's own internal
    // 8-decimal rate scale without ever being the limiting precision.
    private static final int TIER_BASE_SCALE = 10;

    // Commission redesign Slice A1: real policy Excel pays nothing when a rep's monthly
    // commissionable base is below this floor, even though tier 1 technically starts at 0.
    // Tier bounds/rates below the floor are unchanged — a base at or above the floor is taxed
    // exactly as before, from THB 0 up through the tiers.
    private static final BigDecimal MONTHLY_FLOOR = new BigDecimal("50000");

    /**
     * Commission redesign Slice A1 (2026-07-22): the real commission policy Excel has two more
     * columns than this calculator previously modeled — หัก ณ ที่จ่าย (withholding tax, subtracted
     * here) and รับเงินเกิน (overpayment received, added back). Both are applied at the same
     * "actualReceived" stage as the existing five deduction columns.
     *
     * <p><b>Owner confirmation needed:</b> the exact Excel formula for how these two columns
     * combine with the others could not be extracted — LibreOffice dropped the cell formulas on
     * conversion, leaving only static values behind. This sign convention (WHT subtracted,
     * overpayment added, both at this stage, before the VAT strip) is a best-effort mirror of the
     * columns' evident intent, not a verified transcription of the source formula. The
     * sales-manager review step ({@link CommissionService#updateDeductions}) is the safety net for
     * this slice — flag this for the owner to confirm against the Excel/accounting team before
     * relying on it unreviewed.
     *
     * <p>Backward compatible: when {@code withholdingTax} and {@code overpayment} are both zero
     * (or null), the result is identical to the pre-Slice-A1 five-column formula.
     */
    public InvoiceCalculation calculateInvoice(
        BigDecimal grossAmount,
        BigDecimal bankFees,
        BigDecimal suspenseVat,
        BigDecimal transportFee,
        BigDecimal cutFee,
        BigDecimal shortfall,
        BigDecimal withholdingTax,
        BigDecimal overpayment
    ) {
        BigDecimal actualReceived = money(grossAmount)
            .subtract(money(bankFees))
            .subtract(money(suspenseVat))
            .subtract(money(transportFee))
            .subtract(money(cutFee))
            .subtract(money(shortfall))
            .subtract(money(withholdingTax))
            .add(money(overpayment))
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal commissionableBase = actualReceived
            .divide(VAT_DIVISOR, MONEY_SCALE, RoundingMode.HALF_UP);
        return new InvoiceCalculation(actualReceived, commissionableBase);
    }

    /**
     * Commission redesign calc-refine (2026-07-22): the monthly TIER BASE is now built from
     * {@link #monthlyTierBase} at full precision (weighted actual-received summed across every
     * active receipt in the month, divided by VAT exactly once) rather than by summing each
     * receipt's already-2dp-rounded {@code commissionable_base} column. This method therefore no
     * longer rounds its input to 2dp before running the tier brackets -- rounding only happens
     * once, on the final total, as it always has. Callers that still pass an already-2dp value
     * (e.g. unit tests, or a single invoice's own {@code commissionableBase}) are unaffected,
     * since rounding a value that is already at 2dp is a no-op.
     */
    public BigDecimal progressiveCommission(BigDecimal monthlyCommissionableBase, List<TierConfig> tiers) {
        BigDecimal base = monthlyCommissionableBase == null ? BigDecimal.ZERO : monthlyCommissionableBase;
        if (base.signum() <= 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        if (base.compareTo(MONTHLY_FLOOR) < 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal total = BigDecimal.ZERO;
        List<TierConfig> ordered = tiers.stream()
            .sorted(Comparator.comparingInt(TierConfig::tierNumber))
            .toList();
        for (TierConfig tier : ordered) {
            BigDecimal tierAmount = taxableAmountForTier(base, tier);
            if (tierAmount.signum() <= 0) {
                continue;
            }
            BigDecimal rate = tier.ratePercent().divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP);
            total = total.add(tierAmount.multiply(rate));
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal progressiveCommission(BigDecimal monthlyCommissionableBase) {
        return progressiveCommission(monthlyCommissionableBase, TierConfig.defaults());
    }

    /**
     * Commission redesign calc-refine (2026-07-22): {@code monthlyTierBase = SUM(actual_received
     * &times; weight_multiplier) &divide; 1.07}, dividing exactly once at {@link
     * #TIER_BASE_SCALE} decimal places. {@code weightedActualReceived} is the raw, pre-division
     * sum (real cash times each receipt's weight multiplier) -- see {@link
     * CommissionRepository#sumActiveWeightedActualReceived}. This intentionally does NOT round to
     * 2dp; only {@link #progressiveCommission} rounds, and only the final total. Result is fed
     * straight into {@code progressiveCommission} for the bracket math; round to 2dp only when
     * displaying it (e.g. in a response DTO), never before.
     */
    public BigDecimal monthlyTierBase(BigDecimal weightedActualReceived) {
        BigDecimal weighted = weightedActualReceived == null ? BigDecimal.ZERO : weightedActualReceived;
        return weighted.divide(VAT_DIVISOR, TIER_BASE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal taxableAmountForTier(BigDecimal base, TierConfig tier) {
        if (tier.highRoller()) {
            return base.subtract(tier.lowerBound()).max(BigDecimal.ZERO);
        }
        BigDecimal upper = tier.upperBound();
        if (upper == null || base.compareTo(tier.lowerBound()) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal capped = base.min(upper);
        return capped.subtract(tier.lowerBound()).max(BigDecimal.ZERO);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
