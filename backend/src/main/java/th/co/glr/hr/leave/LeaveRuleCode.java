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
 */
public enum LeaveRuleCode {
    /** §5.6 ORDINATION: usable only once during the employee's whole employment. */
    ONCE_PER_EMPLOYMENT("5.6"),
    /** §5.3.4: VACATION/PERSONAL are refused once a resignation has been submitted. */
    RESIGNATION_GATE("5.3.4"),
    /** §5.2/§5.3: a prorated-first-year type (VACATION, PERSONAL) cannot be quoted without hire_date. */
    HIRE_DATE_MISSING_PRORATED("5.2/3"),
    /** §5.3: a min-service-months type cannot verify eligibility without hire_date. */
    HIRE_DATE_MISSING_MIN_SERVICE("5.3"),
    /** §5.3: fewer than the type's required completed months of service. */
    MIN_SERVICE_MONTHS("5.3"),
    /** §5.2 PERSONAL probation gate: neither confirm_date nor hire_date on file. */
    PROBATION_HIRE_DATE_MISSING("5.2"),
    /** §5.2 PERSONAL probation gate: probation not yet passed as of the request's start date. */
    PROBATION_NOT_PASSED("5.2"),
    /** §5.2 first-year total-days cap (under-1-year employees, effective cap = min(prorated quota, firstYearMaxDays)). */
    FIRST_YEAR_MAX_DAYS("5.2"),
    /** §5.2 wedding-leave cap: own marriage or a child's, at most 3 days per request. */
    WEDDING_MAX_DAYS("5.2"),
    /** §5.2 (pre-2567 wording, superseded by FIRST_YEAR_MAX_DAYS for PERSONAL): a type's own max_consecutive_days. */
    MAX_CONSECUTIVE_DAYS("5.2"),
    /** §5.3.3: VACATION/PERSONAL may not be taken immediately before or after one another. */
    CONTIGUOUS_LEAVE_PAIR("5.3.3"),
    /** §5.1 SICK: a certificate was filed, but after its working-day filing deadline. */
    SICK_CERTIFICATE_WINDOW("5.1"),
    /** §5.1 SICK: no certificate attached, and the type allows no no-certificate tolerance. */
    SICK_CERTIFICATE_REQUIRED("5.1"),
    /** §5.1 SICK: no certificate attached, and this month's no-certificate occasion tolerance is used up. */
    SICK_NO_CERT_TOLERANCE_EXHAUSTED("5.1"),
    /** §5.2 PERSONAL emergency-filing exception: this month's emergency occasion allowance is used up. */
    EMERGENCY_TOLERANCE_EXHAUSTED("5.2"),
    /** §5: the type's own advance-notice-days requirement was not met, and no emergency exception applies. */
    ADVANCE_NOTICE("5"),
    /** §5.3.2: approving this request would leave nobody else in the department at work on some day. */
    DEPARTMENT_COVERAGE("5.3.2");

    private final String sectionRef;

    LeaveRuleCode(String sectionRef) {
        this.sectionRef = sectionRef;
    }

    /** The governing §5 announcement section, e.g. {@code "5.3.3"} -- see this enum's class Javadoc. */
    public String sectionRef() {
        return sectionRef;
    }
}
