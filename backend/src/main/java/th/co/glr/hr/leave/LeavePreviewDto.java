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
 * <p>{@code ruleWarnings}/{@code unpaidByRuleDays} (V164 follow-up, 2026-09-09): the SAME
 * §5 WARN_UNPAID_* signal {@link LeaveRequestDto#ruleWarnings}/{@link
 * LeaveRequestDto#unpaidByRuleDays} carry on a SUBMITTED request -- see {@link LeaveRuleWarningDto}
 * and {@link LeaveRuleCode#enforcement()}. Before this field existed, a warning was only ever visible
 * AFTER submitting; this closes that gap so the live pre-submit check the frontend runs against this
 * endpoint can show the same "this will be unpaid" sentence before the employee commits.
 *
 * <p><b>{@code ruleWarnings} is NEVER {@code null}</b> (empty {@link List} for the common case, same
 * convention as {@code LeaveRequestDto#ruleWarnings}), but it is deliberately EMPTY -- not merely
 * unpopulated -- whenever {@code datesEvaluated} is {@code false}. A dateless preview cannot know
 * {@code totalDays} yet (the caller has not chosen dates), and every WARN_UNPAID_* message template
 * renders "how many days would be unpaid" as part of its sentence -- see {@link
 * LeaveRuleMessages}'s {@code WARN_UNPAID_TAIL}. Substituting a placeholder {@code 0} there would
 * read as a real, false answer ("วันลา 0 วันจะไม่ได้รับค่าจ้าง"), not an honest "unknown" -- so this
 * endpoint reports NO warnings at all for a dateless call rather than one with a fabricated day
 * count. {@link LeaveService#eligibilityRuleOutcome} may still find a WARN gate in that case (e.g.
 * PROBATION_NOT_PASSED) -- only its {@code .blocking()} half is surfaced to a dateless caller (always
 * {@code null} for a WARN code), consistent with {@code datesEvaluated == false}'s existing contract
 * that no date-dependent verdict is available yet. Once dates are supplied ({@code datesEvaluated ==
 * true}), this mirrors {@link LeaveService#submit}'s own {@code AutoRejectResult#warnings()} exactly
 * -- see {@link LeaveService#preview}'s Javadoc for the "identical gate chain, identical arguments"
 * guarantee that makes this safe to trust before submitting.
 *
 * <p>{@code unpaidByRuleDays} follows the same dateless-vs-dated split: {@code null} whenever {@code
 * datesEvaluated} is {@code false} (unknown, not zero -- the same reading {@code totalDays}/{@code
 * paidDays}/{@code unpaidDays} already give a dateless preview), and {@link
 * LeaveService#dominantUnpaidByRuleDays}'s actual result (never {@code null}, {@link
 * BigDecimal#ZERO} when no WARN gate fired) once dates are supplied -- the identical figure {@link
 * LeaveService#submit} would persist to {@code hr.leave_request.unpaid_by_rule_days} for the SAME
 * request.
 *
 * <p>A BLOCKing gate (non-null {@code blocking}) always reports an EMPTY {@code ruleWarnings} --
 * {@link LeaveService.AutoRejectResult}'s own dominance rule discards any WARN accumulated before the
 * BLOCK fired (a BLOCK wins outright), so this DTO never has both a non-null {@code blocking} and a
 * non-empty {@code ruleWarnings} at once.
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
    List<LeaveRuleWarningDto> ruleWarnings,
    BigDecimal unpaidByRuleDays,
    LeavePreviewCounters counters
) {
}
