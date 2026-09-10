package th.co.glr.hr.leave;

/**
 * §5 WARN_UNPAID_* gates (owner-approved change, 2026-09-09, V164): what {@link LeaveService
 * #autoRejectNote} does when a {@link LeaveRuleCode} gate fires. Before this enum existed, every one
 * of the 17 gates AUTO_REJECTed the request outright and AUTO_REJECTED was terminal ({@code
 * approve()} requires SUBMITTED) -- there was no exception path at all, including for gates that are
 * genuinely just missing HR data (a never-recorded {@code hire_date}) rather than an employee
 * violation. See {@link LeaveRuleCode}'s class Javadoc for which codes carry which enforcement and
 * why, and V164's migration comment for the owner's stated reasoning per code.
 *
 * <p>{@link #BLOCK} is the ORIGINAL, unchanged behaviour: {@link LeaveService#autoRejectNote}
 * short-circuits and returns AUTO_REJECTED the instant a BLOCK code fires, exactly as every code did
 * before this enum existed.
 *
 * <p>{@link #WARN_UNPAID_ALL} and {@link #WARN_UNPAID_EXCESS} both mean the SAME thing for control
 * flow -- {@code autoRejectNote} records the outcome and KEEPS EVALUATING the remaining gates (so
 * several warnings can accumulate on one request) instead of returning -- and differ only in HOW MUCH
 * of the request becomes unpaid if a human later approves it:
 * <ul>
 *   <li>{@link #WARN_UNPAID_ALL}: the entire request ({@code total_days}) is unpaid. Used for gates
 *       that are categorical (probation/min-service not yet met at all) or document/timing gates
 *       where there is no well-defined "partial" amount (a missing SICK certificate does not have a
 *       natural excess day count).
 *   <li>{@link #WARN_UNPAID_EXCESS}: only the days BEYOND the type's own cap are unpaid -- the
 *       request itself still fits the ordinary quota/paid-cap machinery up to that cap. Used for the
 *       three per-request numeric caps (wedding, first-year total, max-consecutive) where "the excess
 *       over the stated limit" is a well-defined quantity.
 * </ul>
 *
 * <p><b>Dominance rule (owner ruling, {@link LeaveService#autoRejectNote}, NOT additive):</b> if ANY
 * {@link #WARN_UNPAID_ALL} code fired on a request, {@code unpaid_by_rule_days = total_days} full
 * stop -- an {@link #WARN_UNPAID_EXCESS} firing alongside it contributes nothing extra (the whole
 * request is already unpaid). Only when NO {@link #WARN_UNPAID_ALL} fired does {@code
 * unpaid_by_rule_days} become the MAX (never the sum) of whatever {@link #WARN_UNPAID_EXCESS} codes
 * fired. An ALL is never summed with an EXCESS.
 */
public enum LeaveRuleEnforcement {
    BLOCK,
    WARN_UNPAID_ALL,
    WARN_UNPAID_EXCESS
}
