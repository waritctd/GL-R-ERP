package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.util.List;

/**
 * POST /api/leave/preview (Phase A0b dry-run) response -- see {@link LeaveService#preview}'s Javadoc
 * for the full contract. Nothing on this record implies a value {@link LeaveService#submit} would
 * necessarily reproduce later: real-world state (quota usage, colleagues' own requests, today's
 * date) can change between a preview and the eventual submission.
 *
 * <p>{@code blocking}: the same {@link LeaveRuleOutcome} {@link LeaveService#submit} would persist
 * as {@code system_note}/{@code system_note_code}/{@code system_note_params} on an AUTO_REJECTED row
 * -- {@code null} means no blocking gate was found AMONG THE GATES ACTUALLY EVALUATED (see {@code
 * datesEvaluated}/{@code coverageEvaluated} below before reading {@code null} as "approved").
 *
 * <p>{@code datesEvaluated}: {@code false} when the request had no startDate/endDate yet, so only
 * {@link LeaveService#eligibilityRuleOutcome}'s categorical checks ran -- every date-dependent gate
 * (max-consecutive-days, wedding cap, contiguous VACATION/PERSONAL, SICK certificate window, advance
 * notice, department coverage) was NOT evaluated, and {@code totalDays}/{@code paidDays}/{@code
 * unpaidDays} are {@code null} and {@code quotaYearSplits} is empty.
 *
 * <p>{@code coverageEvaluated}: {@code false} whenever {@link
 * LeaveService#departmentCoverageRuleOutcome} did not actually run for this call -- QUICK {@link
 * LeavePreviewDepth}, a dateless preview ({@code datesEvaluated == false}), an earlier gate already
 * blocking first, or the pre-existing emergency-filing exception (which skips department coverage
 * entirely, even under FULL depth -- see {@code LeaveService.AutoRejectResult}'s Javadoc). {@code
 * true} means the check genuinely ran, whether or not it found a problem.
 *
 * <p>{@code counters}: always populated -- see {@link LeaveService#previewCounters}.
 */
public record LeavePreviewDto(
    LeaveRuleOutcome blocking,
    boolean datesEvaluated,
    boolean coverageEvaluated,
    BigDecimal totalDays,
    BigDecimal paidDays,
    BigDecimal unpaidDays,
    List<LeaveQuotaYearSplit> quotaYearSplits,
    LeavePreviewCounters counters
) {
}
