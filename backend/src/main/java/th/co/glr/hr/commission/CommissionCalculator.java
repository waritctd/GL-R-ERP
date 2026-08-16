package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CommissionCalculator {
    private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.07");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;
    // V148 (per-item stock-commission weighting): storage precision for the frozen blended
    // weight -- see sales.commission_record.effective_weight_multiplier's migration comment
    // (NUMERIC(9,6)) for why a fractional weight needs more than 2dp. Rounded ONLY at the very
    // end of itemDerivedWeight, matching this class's existing "round once, at the final figure"
    // discipline (monthlyTierBase/progressiveCommission never round an intermediate).
    private static final int ITEM_WEIGHT_SCALE = 6;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal THREE = new BigDecimal("3");
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

    /**
     * Issue #405, ข้อ 12: flat monthly INCENTIVE lookup against the rep's FULL-PRECISION monthly
     * tier base — the comparison uses {@code monthlyTierBase} as-is, the same "don't pre-round"
     * discipline {@link #progressiveCommission} follows for its own base argument. Highest
     * threshold reached wins; NOT cumulative, NOT pro-rated. ZERO at 2dp when {@code ladder} is
     * null/empty (an empty ladder must mean zero — see {@link IncentiveTierConfig}), the base is
     * null/&le;0, or the base is below every threshold in the ladder.
     */
    public BigDecimal monthlyIncentive(BigDecimal monthlyTierBase, List<IncentiveTierConfig> ladder) {
        BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal base = monthlyTierBase == null ? BigDecimal.ZERO : monthlyTierBase;
        if (base.signum() <= 0 || ladder == null || ladder.isEmpty()) {
            return zero;
        }
        IncentiveTierConfig winner = null;
        for (IncentiveTierConfig tierConfig : ladder) {
            if (base.compareTo(tierConfig.thresholdBase()) < 0) {
                continue;
            }
            if (winner == null || tierConfig.thresholdBase().compareTo(winner.thresholdBase()) > 0) {
                winner = tierConfig;
            }
        }
        return winner == null ? zero : winner.incentiveAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Issue #405: the STOCK_BONUS (พิเศษขายของในสต๊อค) rule — STEPPED, not a percentage:
     * {@code floor(stockReceipts / blockAmount)} whole blocks, each worth {@code bonusPerBlock}.
     * A partial block earns nothing — e.g. ฿250,000 at a ฿100,000 block pays ฿2,000 (two whole
     * blocks), NOT ฿2,500 (a naive 1% read of the remainder too). ZERO when {@code config} is
     * null, not {@link StockBonusConfig#enabled()}, or {@code stockReceipts} &le; 0 — this last
     * check is also where the "clamp stockReceipts at &ge; 0 before the floor division" rule
     * from the issue lives: a negative (e.g. CLAWBACK-only) receipts figure floors to the same
     * zero blocks a clamped-to-0 value would, so no separate clamp is needed before this call.
     * Ships config-gated OFF ({@link StockBonusConfig#disabled()} / the V108 seed row), so this
     * returns zero for every real payroll run until the CEO enables it.
     */
    public BigDecimal stockSaleBonus(BigDecimal stockReceipts, StockBonusConfig config) {
        BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal receipts = stockReceipts == null ? BigDecimal.ZERO : stockReceipts;
        if (config == null || !config.enabled() || receipts.signum() <= 0) {
            return zero;
        }
        BigDecimal blocks = receipts.divide(config.blockAmount(), 0, RoundingMode.FLOOR);
        return blocks.multiply(config.bonusPerBlock()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * V148 (per-item stock-commission weighting). Blends each line's own STOCK-EARNED weight into
     * ONE record-level effective weight, cash-weighted by each item's own share of the deal's
     * total item value -- the model:
     * <pre>
     *   itemValue_i        = qty_i &times; price_i        (price_i = COALESCE(approvedPrice_i, proposedPrice_i, 0))
     *   stockFraction_i    = qtyFromStock_i / qty_i        (0 when qty_i = 0)
     *   effectiveWeight_i  = 1 + (weightMultiplier_i - 1) &times; stockFraction_i
     *   blendedWeight      = &Sigma;(itemValue_i &times; effectiveWeight_i) / &Sigma;(itemValue_i)
     * </pre>
     * Only STOCK-sourced quantity ever earns credit above 1x -- an import-sourced remainder on the
     * SAME line is deliberately never weighted: {@code stockFraction_i < 1} pulls {@code
     * effectiveWeight_i} toward 1, never all the way to {@code weightMultiplier_i}, for a
     * partially-stocked line. This is exactly how the owner's own workbook treats a row marked
     * {@code *3} that was actually sourced against an import request: not credited, because the
     * stock-sourced share of that line was zero.
     *
     * <p><b>Algebraic simplification (division-free per item, exact until the single final
     * division).</b> Substituting {@code itemValue_i = qty_i * price_i} into {@code itemValue_i *
     * effectiveWeight_i} and cancelling {@code qty_i} gives, for {@code qty_i != 0}:
     * <pre>
     *   itemValue_i * effectiveWeight_i = price_i * (qty_i + (weightMultiplier_i - 1) * qtyFromStock_i)
     * </pre>
     * -- an EXACT identity, no rounding. For {@code qty_i == 0}: the V54 CHECK ({@code 0 &le;
     * qty_from_stock &le; qty}) guarantees {@code qtyFromStock_i} is also 0 in that case, so the
     * same formula evaluates to {@code price_i * (0 + (n-1)*0) = 0} -- exactly what {@code
     * itemValue_i (= 0) * effectiveWeight_i (defined as 1 for qty_i = 0)} would also give. No
     * {@code qty_i == 0} special case is needed: it falls out of the algebra plus the DB-enforced
     * invariant, which is why {@code weightedContribution} below is computed from this closed form
     * rather than dividing per item.
     *
     * <p><b>Also algebraically eliminated:</b> the brief's formula routes through {@code
     * allocatedCash_i = actualReceived * itemValue_i / totalItemValue} and only then {@code
     * SUM(allocatedCash_i * effectiveWeight_i) / actualReceived}. {@code actualReceived} cancels
     * out of that two-step form exactly (provided it is nonzero), leaving the same {@code
     * SUM(itemValue_i * effectiveWeight_i) / totalItemValue} this method computes directly -- so
     * {@code actualReceived} never appears in the arithmetic below, only in the initial "is there
     * anything to allocate" guard (a blended weight is undefined, and moot -- {@code 0 * anything
     * = 0} -- when there is no cash to weight).
     *
     * <p><b>Edge cases, each returning {@link Optional#empty()}</b> (the frozen column stays
     * {@code null}, and payroll falls back to the record's plain {@code weightMultiplier} exactly
     * as before this feature -- see {@code sales.commission_record.effective_weight_multiplier}'s
     * migration comment):
     * <ul>
     *   <li>{@code items} null/empty -- nothing to derive a weight from (every unlinked/manual
     *       commission never reaches this method at all -- see {@code
     *       CommissionService#computeItemDerivedWeight}).</li>
     *   <li>{@code actualReceived} null or &le; 0 -- undefined (the brief's own formula divides by
     *       it) and operationally moot (a non-positive receipt contributes nothing to the weighted
     *       base regardless of weight).</li>
     *   <li>{@code SUM(itemValue) &le; 0} -- every item has qty 0, or every item's price is null
     *       on both {@code approvedPrice} and {@code proposedPrice} (the fallback chosen for this
     *       task: an item with NO price ever set contributes ZERO to both the numerator and
     *       denominator, as if it did not exist for weighting purposes, rather than being treated
     *       as an error).</li>
     *   <li><b>The computed blend equals exactly 1</b> -- i.e. NO item carries genuine stock-earned
     *       credit (every item with positive {@code itemValue} has either {@code weightMultiplier
     *       = 1} or {@code qtyFromStock = 0}). Reviewer finding (2026-08-16), a real defect that
     *       shipped and was caught before merge: without this case, an ORDINARY ticket -- every
     *       item at the column DEFAULT of 1x, the overwhelmingly common case -- still returns a
     *       concrete, non-empty {@code Optional.of(1.000000)} (the loop above computes {@code
     *       weightedContribution == totalItemValue} exactly whenever every item's {@code
     *       effectiveWeight_i = 1}, so the division is {@code X/X = 1} exactly, no rounding
     *       involved). {@link CommissionService#submit}/{@code #createFromDeal} would then freeze
     *       that non-null {@code 1.000000} onto EVERY ticket-linked commission, and {@link
     *       CommissionRepository#sumActiveWeightedActualReceived}'s {@code
     *       COALESCE(effective_weight_multiplier, weight_multiplier)} always prefers a non-null
     *       frozen value -- so the pre-existing, still-rendered {@code weightMultiplier} manager
     *       control ({@link CommissionService#updateDeductions}, V82) would silently stop
     *       affecting payroll for any ticket-linked sale, the exact class of dead-control-on-a-
     *       money-path bug CLAUDE.md exists to prevent. Returning {@link Optional#empty()} here
     *       instead means an untouched deal freezes NULL, exactly like a pre-V148 row, and the
     *       manager's existing override goes on working precisely as it did before this feature.
     *       Mathematically equivalent to "no item has a genuine stock-weighted credit": every
     *       {@code effectiveWeight_i} for an item with positive {@code itemValue_i} is provably
     *       {@code &ge; 1} for well-formed (non-negative) input, so a weighted average of them
     *       equals exactly 1 if and only if every one of them individually equals 1 -- there is no
     *       cancelling combination that reaches exactly 1 by coincidence.</li>
     * </ul>
     *
     * <p>The result is clamped to {@code [1, 3]} before the equals-1 check above, defensively --
     * for well-formed data (non-negative qty/price, weightMultiplier &isin; {1,2,3}) the weighted
     * average is already provably within this range, so the clamp is a no-op; it exists only so a
     * malformed input (e.g. a negative price, which this column has no CHECK against) can never
     * produce a value that would trip {@code effective_weight_multiplier}'s own CHECK constraint
     * at insert time. Clamping BEFORE the equals-1 check is deliberate: a pathological input that
     * clamps up to exactly 1 (e.g. a negative price dragging the raw blend below 1) is exactly as
     * "no genuine signal" as a natural 1 is, and falling back to the plain {@code weightMultiplier}
     * is the safer behaviour for degenerate data either way.
     */
    public Optional<BigDecimal> itemDerivedWeight(List<ItemStockWeightInput> items, BigDecimal actualReceived) {
        if (items == null || items.isEmpty() || actualReceived == null || actualReceived.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal totalItemValue = BigDecimal.ZERO;
        BigDecimal weightedContribution = BigDecimal.ZERO;
        for (ItemStockWeightInput item : items) {
            BigDecimal qty = item.qty() == null ? BigDecimal.ZERO : item.qty();
            BigDecimal qtyFromStock = item.qtyFromStock() == null ? BigDecimal.ZERO : item.qtyFromStock();
            BigDecimal price = itemPrice(item);
            totalItemValue = totalItemValue.add(qty.multiply(price));
            BigDecimal stockCreditedQty = qty.add(
                BigDecimal.valueOf(item.weightMultiplier() - 1).multiply(qtyFromStock));
            weightedContribution = weightedContribution.add(price.multiply(stockCreditedQty));
        }
        if (totalItemValue.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal blended = weightedContribution.divide(totalItemValue, ITEM_WEIGHT_SCALE, RoundingMode.HALF_UP);
        BigDecimal clamped = blended.max(ONE).min(THREE);
        // Reviewer finding (2026-08-16): exactly 1 means no item carried genuine stock-earned
        // credit -- freeze nothing, so COALESCE falls back to the still-live weightMultiplier
        // control instead of a redundant, control-defeating 1.000000. See this method's own
        // Javadoc for the full defect this closes.
        if (clamped.compareTo(ONE) == 0) {
            return Optional.empty();
        }
        return Optional.of(clamped);
    }

    /**
     * {@code approvedPrice} is nullable on {@code sales.ticket_item} (the column has always
     * allowed NULL); {@code proposedPrice} is the chosen fallback for THIS task ("decide and
     * document the fallback" per the brief) -- an item that reached stock declaration without ever
     * having a price approved still usually has the import-proposed one. Both null: price 0, so
     * the item contributes nothing to either the numerator or denominator of {@link
     * #itemDerivedWeight}.
     */
    private BigDecimal itemPrice(ItemStockWeightInput item) {
        if (item.approvedPrice() != null) {
            return item.approvedPrice();
        }
        if (item.proposedPrice() != null) {
            return item.proposedPrice();
        }
        return BigDecimal.ZERO;
    }

    /**
     * Minimal, decoupled input shape for {@link #itemDerivedWeight} -- deliberately NOT {@code
     * th.co.glr.hr.ticket.TicketItemDto} (a 34-field record from a different package), so this
     * calculator stays dependency-free and trivially unit-testable with plain constructed values.
     * {@code qty} is ALWAYS the piece-count quantity, regardless of {@code unitBasis} ('PIECE' vs
     * 'SQM') -- deliberately never {@code qtySqm} -- the same convention {@link
     * CommissionRepository#sumActiveStockActualReceived}'s own {@code
     * SUM(qty_from_stock)/SUM(qty)} and the V54 {@code qty_from_stock <= qty} CHECK already use
     * for this exact ratio; this does not invent a second, diverging quantity convention for the
     * same table.
     *
     * @param weightMultiplier the item's own 1/2/3 stock-commission weight
     *                         ({@code sales.ticket_item.weight_multiplier}, V148)
     */
    public record ItemStockWeightInput(
        BigDecimal qty,
        BigDecimal qtyFromStock,
        BigDecimal approvedPrice,
        BigDecimal proposedPrice,
        int weightMultiplier
    ) {}
}
