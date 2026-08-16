package th.co.glr.hr.commission;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Slice A2: the sales-manager review step may now edit any invoice input (not just the three
 * deduction fields it always could), so long as every call carries a {@code reason} — no field is
 * optional-without-justification. {@code null} on any amount field leaves that field unchanged
 * (see {@code CommissionService#valueOrExisting}); the final commission amount is never set
 * directly here — it is always recomputed by {@link CommissionCalculator} from whatever the stored
 * fields end up being after this update.
 *
 * <p>Commission redesign calc-refine (2026-07-22): {@code weightMultiplier} is the same
 * pattern — {@code null} leaves the record's existing multiplier unchanged. 1 (the default), 2,
 * and 3 are all owner-confirmed workbook policy (3x confirmed 2026-08-16, per the V148 per-item
 * stock-commission weighting task — see that migration's own header for the correction of
 * record). Sales has no route to this field at all: the endpoint this request backs is
 * manager/CEO-only.
 *
 * <p>V148 (per-item stock-commission weighting): this RECORD-level field is now the FALLBACK a
 * commission falls back to only when it has no frozen {@code effective_weight_multiplier} (an
 * unlinked/manual commission, or a linked one whose ticket had no priced/stock-covered items at
 * creation time) -- see {@code CommissionRecord#effectiveWeight()} and {@code
 * CommissionRepository#sumActiveWeightedActualReceived}'s {@code COALESCE}. Setting this field on
 * a record that already carries a frozen per-item weight has no effect on any money figure; it
 * remains editable here for the fallback case and so a reviewer always has SOME way to record an
 * intended weight even before per-item granularity applies.
 */
public record UpdateCommissionDeductionsRequest(
    @DecimalMin("0.00") BigDecimal grossAmount,
    @DecimalMin("0.00") BigDecimal bankFees,
    @DecimalMin("0.00") BigDecimal suspenseVat,
    @DecimalMin("0.00") BigDecimal transportFee,
    @DecimalMin("0.00") BigDecimal cutFee,
    @DecimalMin("0.00") BigDecimal shortfall,
    @DecimalMin("0.00") BigDecimal withholdingTax,
    @DecimalMin("0.00") BigDecimal overpayment,
    @Min(1) @Max(3) Integer weightMultiplier,
    @NotBlank String reason
) {}
