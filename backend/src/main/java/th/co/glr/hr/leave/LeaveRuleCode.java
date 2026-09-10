package th.co.glr.hr.leave;

/**
 * Phase A0a (structured rejection outcome): a machine-readable code for every distinct auto-reject
 * reason {@link LeaveService#autoRejectNote} can return. Before this enum existed, the only signal
 * a caller (or the frontend) had was an English prose {@code String} -- unusable for anything
 * structured (deep-linking into a policy screen, branching UI logic, translating) and wrong for a
 * Thai-primary product regardless. This enum, paired with {@link LeaveRuleOutcome} and
 * {@link LeaveRuleMessages}, is a PURE REPRESENTATION change: it does not alter which gate fires,
 * in what order, or under what threshold -- see {@code LeaveService#autoRejectNote}'s own Javadoc
 * for that ordering, which this change leaves untouched.
 *
 * <p><b>Declaration order is load-bearing documentation, not incidental.</b> It mirrors, one for
 * one, the exact order {@link LeaveService#autoRejectNote} evaluates these gates in (categorical
 * eligibility first, then request-shape, then document/timing, then finally department coverage --
 * see that method's Javadoc for the full rationale). Do not reorder these constants casually; if
 * the evaluation order in {@code autoRejectNote} ever genuinely changes, update this ordering to
 * match it in the SAME change, not as an afterthought.
 *
 * <p>{@code sectionRef} names the governing §5 announcement section (e.g. {@code "5.3.3"}) so a
 * frontend can deep-link a rejected employee straight into the relevant clause of a policy screen,
 * without hardcoding its own code-to-section table.
 *
 * <p><b>{@link #MAX_CONSECUTIVE_DAYS} is LIVE in code but currently UNREACHABLE in practice.</b>
 * {@code LeaveService#autoRejectNote} still evaluates {@code leaveType.maxConsecutiveDays() != null}
 * for every submission, but V120 set PERSONAL's {@code max_consecutive_days} to NULL (superseded by
 * {@code firstYearMaxDays} -- see that migration's comment) and no other seeded leave type carries a
 * non-NULL value today. Kept here anyway, per this phase's brief: the gate itself is not being
 * removed or pruned, only its rejection message is being restructured -- deleting the code because
 * it is dormant would be a scope change this phase does not make.
 *
 * <p><b>{@link #enforcement()} (V164, owner-approved change, 2026-09-09):</b> 10 of these 17 codes no
 * longer BLOCK the request at all -- see {@link LeaveRuleEnforcement}'s class Javadoc for the
 * BLOCK/WARN_UNPAID_ALL/WARN_UNPAID_EXCESS distinction and V164's migration comment for the owner's
 * stated reasoning per code. The mapping below is settled and owner-approved; it is not something a
 * future reader should "simplify" back to all-BLOCK or infer from declaration order -- the
 * enforcement column is independent of, and orthogonal to, the ordering invariant described above.
 */
public enum LeaveRuleCode {
    /** §5.6 ORDINATION: usable only once during the employee's whole employment. BLOCK: backed by the
     *  DB unique index ux_leave_once_per_employment (V116), deliberately not dropped by V164. */
    ONCE_PER_EMPLOYMENT("5.6", LeaveRuleEnforcement.BLOCK),
    /** §5.3.4: VACATION/PERSONAL are refused once a resignation has been submitted. BLOCK: protects
     *  handover, which docking pay does not achieve (owner ruling, V164). */
    RESIGNATION_GATE("5.3.4", LeaveRuleEnforcement.BLOCK),
    /** §5.2/§5.3: a prorated-first-year type (VACATION, PERSONAL) cannot be quoted without hire_date.
     *  BLOCK: missing data in HR's OWN records, not an employee violation (owner ruling, V164). */
    HIRE_DATE_MISSING_PRORATED("5.2/3", LeaveRuleEnforcement.BLOCK),
    /** §5.3: a min-service-months type cannot verify eligibility without hire_date. BLOCK: same
     *  missing-HR-data reasoning as HIRE_DATE_MISSING_PRORATED (owner ruling, V164). */
    HIRE_DATE_MISSING_MIN_SERVICE("5.3", LeaveRuleEnforcement.BLOCK),
    /** §5.3: fewer than the type's required completed months of service. WARN_UNPAID_ALL (V164, owner
     *  ruling): the request now still submits, unpaid in full if approved. */
    MIN_SERVICE_MONTHS("5.3", LeaveRuleEnforcement.WARN_UNPAID_ALL),
    /** §5.2 PERSONAL probation gate: neither confirm_date nor hire_date on file. BLOCK: missing data
     *  in HR's OWN records, not an employee violation (owner ruling, V164). */
    PROBATION_HIRE_DATE_MISSING("5.2", LeaveRuleEnforcement.BLOCK),
    /** §5.2 PERSONAL probation gate: probation not yet passed as of the request's start date.
     *  WARN_UNPAID_ALL (V164, owner ruling): the request now still submits, unpaid in full if
     *  approved. */
    PROBATION_NOT_PASSED("5.2", LeaveRuleEnforcement.WARN_UNPAID_ALL),
    /** §5.2 first-year total-days cap (under-1-year employees, effective cap = min(prorated quota,
     *  firstYearMaxDays)). WARN_UNPAID_EXCESS (V164, owner ruling): only the days beyond the
     *  effective cap are unpaid. */
    FIRST_YEAR_MAX_DAYS("5.2", LeaveRuleEnforcement.WARN_UNPAID_EXCESS),
    /** §5.2 wedding-leave cap: own marriage or a child's, at most 3 days per request.
     *  WARN_UNPAID_EXCESS (V164, owner ruling): only the days beyond the 3-day cap are unpaid. */
    WEDDING_MAX_DAYS("5.2", LeaveRuleEnforcement.WARN_UNPAID_EXCESS),
    /** §5.2 (pre-2567 wording, superseded by FIRST_YEAR_MAX_DAYS for PERSONAL): a type's own
     *  max_consecutive_days. WARN_UNPAID_EXCESS (V164, owner ruling) -- see this enum's class Javadoc
     *  and V164's migration comment: DORMANT, implemented for completeness only. */
    MAX_CONSECUTIVE_DAYS("5.2", LeaveRuleEnforcement.WARN_UNPAID_EXCESS),
    /** §5.3.3: VACATION/PERSONAL may not be taken immediately before or after one another. BLOCK:
     *  stops leave-stringing, which docking pay does not achieve (owner ruling, V164). */
    CONTIGUOUS_LEAVE_PAIR("5.3.3", LeaveRuleEnforcement.BLOCK),
    /** §5.1 SICK: a certificate was filed, but after its working-day filing deadline.
     *  WARN_UNPAID_ALL (V164, owner ruling): the request now still submits, unpaid in full if
     *  approved. */
    SICK_CERTIFICATE_WINDOW("5.1", LeaveRuleEnforcement.WARN_UNPAID_ALL),
    /** §5.1 SICK: no certificate attached, and the type allows no no-certificate tolerance.
     *  WARN_UNPAID_ALL (V164, owner ruling). */
    SICK_CERTIFICATE_REQUIRED("5.1", LeaveRuleEnforcement.WARN_UNPAID_ALL),
    /** §5.1 SICK: no certificate attached, and this month's no-certificate occasion tolerance is used
     *  up. WARN_UNPAID_ALL (V164, owner ruling). */
    SICK_NO_CERT_TOLERANCE_EXHAUSTED("5.1", LeaveRuleEnforcement.WARN_UNPAID_ALL),
    /** §5.2 PERSONAL emergency-filing exception: this month's emergency occasion allowance is used
     *  up. WARN_UNPAID_ALL (V164, owner ruling). */
    EMERGENCY_TOLERANCE_EXHAUSTED("5.2", LeaveRuleEnforcement.WARN_UNPAID_ALL),
    /** §5: the type's own advance-notice-days requirement was not met, and no emergency exception
     *  applies. WARN_UNPAID_ALL (V164, owner ruling). */
    ADVANCE_NOTICE("5", LeaveRuleEnforcement.WARN_UNPAID_ALL),
    /** §5.3.2: approving this request would leave nobody else in the department at work on some day.
     *  BLOCK: an operational rule protecting the department, not a pay rule (owner ruling, V164). */
    DEPARTMENT_COVERAGE("5.3.2", LeaveRuleEnforcement.BLOCK);

    private final String sectionRef;
    private final LeaveRuleEnforcement enforcement;

    LeaveRuleCode(String sectionRef, LeaveRuleEnforcement enforcement) {
        this.sectionRef = sectionRef;
        this.enforcement = enforcement;
    }

    /** The governing §5 announcement section, e.g. {@code "5.3.3"} -- see this enum's class Javadoc. */
    public String sectionRef() {
        return sectionRef;
    }

    /**
     * BLOCK (auto-rejects, unchanged pre-V164 behaviour) or WARN_UNPAID_ALL/WARN_UNPAID_EXCESS
     * (V164: submits normally, carrying a warning) -- see {@link LeaveRuleEnforcement}'s class
     * Javadoc for what each means and {@link LeaveService#autoRejectNote} for how it is dispatched.
     */
    public LeaveRuleEnforcement enforcement() {
        return enforcement;
    }
}
