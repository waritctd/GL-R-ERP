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
 * pattern — {@code null} leaves the record's existing multiplier unchanged. 1 (the default) and 2
 * (owner-confirmed workbook policy) are safe to set; 3 is accepted by the column but its business
 * meaning is NOT confirmed — see {@code CommissionCalculator}/handoff 102. Sales has no route to
 * this field at all: the endpoint this request backs is manager/CEO-only.
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
