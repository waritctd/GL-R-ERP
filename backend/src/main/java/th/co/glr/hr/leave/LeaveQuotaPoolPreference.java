package th.co.glr.hr.leave;

/**
 * §5.3.5 pool choice (V161, 2026-09-03): which quota pool a leave request draws from FIRST, for a
 * leave type whose unused annual quota may carry into the immediately following year (see
 * {@link LeaveTypeDto#carriesForward()}, {@code hr.leave_carryover}, and V127). Persisted per request
 * on {@code hr.leave_request.quota_pool_preference}.
 *
 * <p>This is the REQUESTED order only, not a hard partition -- {@link
 * LeaveService#computeQuotaSplit} bounds the chosen pool by its own remaining balance and lets the
 * remainder spill into the OTHER pool when the chosen one runs out mid-request. A request is never
 * rejected merely because it does not fit inside one pool; spillover is expected and correct. See
 * {@code hr.leave_request_quota_year.carried_in_days}/{@code own_quota_days} (also V161) for what was
 * ACTUALLY charged where, which is the authoritative record once spillover is accounted for -- this
 * enum only ever describes what the requester asked for.
 *
 * <p>{@code null} (on {@link SubmitLeaveRequest#quotaPoolPreference()} /
 * {@link LeavePreviewRequest#quotaPoolPreference()}) means {@link #CARRIED_IN_FIRST} -- see
 * {@link #orDefault}. This is the §5.3.5 use-it-or-lose-it reading that MAXIMISES what the employee
 * keeps overall:
 * <ul>
 *   <li>a carry-in day expires unconditionally, un-accumulating, at the end of the year it may be
 *       used in (§5.3.5's own "...ได้ในปีต่อไปเท่านั้น" -- "...only in the following year"; see
 *       {@link LeaveService#ensureCarryoverGrant}'s non-accumulation note) -- it is worth spending
 *       BEFORE it is worth protecting.</li>
 *   <li>an unused day of THIS year's own (renewable) quota, by contrast, is itself eligible to become
 *       NEXT year's carry-in candidate if it survives to year-end -- it is worth protecting, not
 *       spending, whenever a choice exists.</li>
 * </ul>
 * Spending the about-to-expire pool first therefore preserves the maximum possible balance of the
 * renewable pool for a future carry-out. A requester with a specific reason to protect this year's
 * own quota instead (uncommon -- e.g. a known, larger claim on it planned later the same year) may
 * choose {@link #OWN_FIRST} explicitly; nothing in this codebase infers that choice automatically.
 */
public enum LeaveQuotaPoolPreference {
    CARRIED_IN_FIRST,
    OWN_FIRST;

    /** {@code null}-safe default -- see this enum's own Javadoc for why {@link #CARRIED_IN_FIRST}. */
    static LeaveQuotaPoolPreference orDefault(LeaveQuotaPoolPreference preference) {
        return preference == null ? CARRIED_IN_FIRST : preference;
    }
}
