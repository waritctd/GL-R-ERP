package th.co.glr.hr.leave;

/** §5.3.5 pool choice (V161, 2026-09-03): which quota pool a leave request draws from FIRST, for a leave type
 * whose unused annual quota may carry into the immediately following year (see LeaveTypeDto#carriesForward(),
 * hr.leave_carryover, and V127). */
public enum LeaveQuotaPoolPreference {
    CARRIED_IN_FIRST,
    OWN_FIRST;

    /** {@code null}-safe default -- see this enum's own Javadoc for why {@link #CARRIED_IN_FIRST}. */
    static LeaveQuotaPoolPreference orDefault(LeaveQuotaPoolPreference preference) {
        return preference == null ? CARRIED_IN_FIRST : preference;
    }
}
