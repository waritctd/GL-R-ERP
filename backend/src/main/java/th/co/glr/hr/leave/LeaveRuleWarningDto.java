package th.co.glr.hr.leave;

import java.util.Map;

/** §5 WARN_UNPAID_* gates (V164, owner-approved change, 2026-09-09): one warning that fired for a SUBMITTED
 * request but did not block it -- see LeaveRuleEnforcement/LeaveRuleCode #enforcement() for which codes these
 * are and LeaveService#autoRejectNote for how they accumulate. */
public record LeaveRuleWarningDto(String code, Map<String, String> params, String messageTh) {

    /** Builds the DTO shape from the Java-side {@link LeaveRuleOutcome} a WARN gate produced. */
    static LeaveRuleWarningDto from(LeaveRuleOutcome outcome) {
        return new LeaveRuleWarningDto(outcome.code().name(), outcome.params(), outcome.messageTh());
    }
}
