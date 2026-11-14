package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Read-only preview of what SpecialMoneyService#ceoApproveFrom would enforce for a request evaluated RIGHT
 * NOW, computed via SpecialMoneyService#computeApprovalCeiling -- the same calculation the approval itself
 * uses, so a preview fetched and an approval submitted at the SAME instant agree exactly. */
public record SpecialMoneyApprovalPreviewDto(
    BigDecimal requestedAmount, BigDecimal eligibleAmount, LocalDate payrollMonth) {
}
