package th.co.glr.hr.leave;

import java.util.Map;

/**
 * §5 WARN_UNPAID_* gates (V164, owner-approved change, 2026-09-09): one warning that fired for a
 * SUBMITTED request but did not block it -- see {@link LeaveRuleEnforcement}/{@link LeaveRuleCode
 * #enforcement()} for which codes these are and {@link LeaveService#autoRejectNote} for how they
 * accumulate. {@code code} is the {@link LeaveRuleCode} enum name as a plain {@code String} --
 * matching {@link LeaveRequestDto#systemNoteCode}'s existing precedent (a stable, frontend-actionable
 * identifier that does not require the frontend to depend on the Java enum type) -- {@code params}
 * are the values that code's message template needed, and {@code messageTh} is the already-rendered
 * Thai sentence (see {@link LeaveRuleMessages}), persisted verbatim so a historical row's warning text
 * never drifts even if the template wording changes later.
 *
 * <p>Serialized into {@code hr.leave_request.rule_warnings} (a jsonb array) by {@link
 * LeaveRepository#recordRuleWarnings} and read back by {@link LeaveRepository#mapRequest} into
 * {@link LeaveRequestDto#ruleWarnings} -- see that column's V164 migration comment. Distinct from
 * {@code systemNoteCode}/{@code systemNoteParams} (V131), which carry the single BLOCK reason behind
 * an AUTO_REJECTED request -- a SUBMITTED request can carry a non-empty {@code ruleWarnings} list and
 * never carries a non-null {@code systemNoteCode}.
 */
public record LeaveRuleWarningDto(String code, Map<String, String> params, String messageTh) {

    /** Builds the DTO shape from the Java-side {@link LeaveRuleOutcome} a WARN gate produced. */
    static LeaveRuleWarningDto from(LeaveRuleOutcome outcome) {
        return new LeaveRuleWarningDto(outcome.code().name(), outcome.params(), outcome.messageTh());
    }
}
