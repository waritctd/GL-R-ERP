package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code carriedInDays} (V127, §5.3.5): the amount of the PRIOR year's unused quota carried into
 * {@code year} for this leave type -- always {@code ZERO} for a type where
 * {@link LeaveTypeDto#carriesForward()} is FALSE (every type except VACATION). {@code
 * remainingDays} already has this folded in (it is derived from {@code annualQuotaDays +
 * carriedInDays - approvedDays - pendingDays}); the field is exposed separately so a caller can
 * explain WHY remaining exceeds the flat annual quota, not to be summed a second time. See {@link
 * LeaveService#balanceFor}.
 *
 * <p>{@code carriedInFromYear}/{@code carriedInExpiresOn} (Phase A0b, carry-forward provenance):
 * {@code null} whenever {@link LeaveTypeDto#carriesForward()} is FALSE -- for those types "from which
 * year" and "expires when" are meaningless questions, not just zero-valued ones. When {@code
 * carriesForward()} is TRUE, both are DERIVED, not separately queried: {@link
 * LeaveService#carryInDays} always asks {@link LeaveService#ensureCarryoverGrant} for {@code year -
 * 1} (the EARNED year), and {@code hr.leave_carryover}'s own {@code usable_year} column is always
 * {@code earned_year + 1} by construction (every {@code
 * LeaveRepository#insertCarryoverIfAbsent} call passes exactly that) -- so {@code
 * carriedInFromYear == year - 1} and {@code carriedInExpiresOn == 31 Dec of year} are the SAME
 * relationship {@code hr.leave_carryover} already encodes via {@code earned_year}/{@code
 * usable_year}, not a new one invented here. §5.3.5's non-accumulation clause ("...ได้ในปีต่อไปเท่านั้น",
 * i.e. usable in the immediately following year ONLY -- see {@link LeaveService#ensureCarryoverGrant}'s
 * Javadoc) is what makes 31 Dec of {@code year} the correct expiry: the carry-in is not valid past
 * that date regardless of whether it was ever actually consumed. No new query, no migration -- see
 * this phase's PR body for the confirmation that {@code hr.leave_carryover} already has the columns
 * this derivation relies on.
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
    BigDecimal carriedInDays,
    Integer carriedInFromYear,
    LocalDate carriedInExpiresOn
) {
}
