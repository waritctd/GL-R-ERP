package th.co.glr.hr.leave;

/** POST /api/leave/preview (Phase A0b): how much of the gate chain LeaveService#preview should run. See
 * LeaveService#preview's Javadoc for the full rationale. */
public enum LeavePreviewDepth {
    /** Skips LeaveService#departmentCoverageRuleOutcome -- the one gate that fans out over every active department
     * colleague's schedule and leave spans. Intended for a debounced as-the-user-types call (e.g. re-checking
     * after every date-field keystroke), where that fan-out cost cannot be paid on every call. */
    QUICK,
    /** Runs every gate {@link LeaveService#submit} runs, in the same order. The default depth. */
    FULL
}
