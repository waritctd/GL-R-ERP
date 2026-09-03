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
 *
 * <p>{@code carriedInRemainingDays}/{@code ownQuotaRemainingDays} (V161, §5.3.5 pool choice,
 * 2026-09-03): the SAME combined {@code remainingDays} figure above, split by pool -- how much of
 * THIS year's carry-in grant / THIS year's own annual quota specifically remains, after every
 * ACTIVE-status ({@code SUBMITTED}/{@code APPROVED}) request's already-recorded {@code
 * carried_in_days}/{@code own_quota_days} (see {@link LeaveQuotaYearSplit}). Exposed so the leave
 * composer can render a real, per-pool-aware choice ({@link
 * SubmitLeaveRequest#quotaPoolPreference()}) instead of the single merged figure -- e.g. only offer
 * the choice at all when {@code carriedInRemainingDays > 0}. Deliberately ADDITIVE: {@code
 * remainingDays} above keeps its existing meaning and computation (the combined {@code annualQuotaDays
 * + carriedInDays - approvedDays - pendingDays}, unchanged by this addition) -- do not repurpose it.
 * {@code carriedInRemainingDays + ownQuotaRemainingDays} equals {@code remainingDays} in the common
 * case but CAN exceed it: {@code remainingDays}' own {@code used} figure sums a request's whole {@code
 * total_days} (see {@link LeaveRepository#sumUsedDays}), which includes any UNPAID days beyond what
 * quota covered, while the two pool figures here sum only {@code carried_in_days}/{@code
 * own_quota_days} -- the strictly-smaller amount that actually consumed a pool (see {@link
 * LeaveQuotaYearSplit}'s Javadoc). Both are always {@code ZERO}/{@code annualQuotaDays} respectively
 * (i.e. {@code carriedInRemainingDays} is always {@code ZERO}) for a type where {@link
 * LeaveTypeDto#carriesForward()} is FALSE.
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
    LocalDate carriedInExpiresOn,
    BigDecimal carriedInRemainingDays,
    BigDecimal ownQuotaRemainingDays
) {
}
