package th.co.glr.hr.leave;

import java.math.BigDecimal;

/**
 * {@code carriedInDays} (V127, §5.3.5): the amount of the PRIOR year's unused quota carried into
 * {@code year} for this leave type -- always {@code ZERO} for a type where
 * {@link LeaveTypeDto#carriesForward()} is FALSE (every type except VACATION). {@code
 * remainingDays} already has this folded in (it is derived from {@code annualQuotaDays +
 * carriedInDays - approvedDays - pendingDays}); the field is exposed separately so a caller can
 * explain WHY remaining exceeds the flat annual quota, not to be summed a second time. See {@link
 * LeaveService#balanceFor}.
 */
public record LeaveBalanceDto(
    String leaveTypeCode,
    String leaveTypeNameTh,
    String leaveTypeNameEn,
    BigDecimal annualQuotaDays,
    BigDecimal approvedDays,
    BigDecimal pendingDays,
    BigDecimal remainingDays,
    boolean requiresAttachment,
    BigDecimal carriedInDays
) {
}
