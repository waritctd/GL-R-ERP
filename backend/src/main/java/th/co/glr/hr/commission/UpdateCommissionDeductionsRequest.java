package th.co.glr.hr.commission;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** Slice A2: the sales-manager review step may now edit any invoice input (not just the three deduction fields
 * it always could), so long as every call carries a reason — no field is optional-without-justification. */
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
