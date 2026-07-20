package th.co.glr.hr.specialmoney;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Body for approve/reject/cancel. {@code approvedAmount} and {@code capOverrideReason} are only
 * meaningful on a CEO approval (see {@link SpecialMoneyService#ceoApprove}) -- ignored otherwise.
 */
public record ReviewSpecialMoneyRequest(
    @Size(max = 2000) String reviewerNote,
    BigDecimal approvedAmount,
    @Size(max = 2000) String capOverrideReason) {
}
