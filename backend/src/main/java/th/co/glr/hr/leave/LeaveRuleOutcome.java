package th.co.glr.hr.leave;

import java.util.Map;

/** Phase A0a (structured rejection outcome): the machine-readable replacement for the plain English rejection
 * String LeaveService#autoRejectNote used to return. */
public record LeaveRuleOutcome(LeaveRuleCode code, Map<String, String> params, String messageTh) {

    /** Builds the outcome for code, resolving messageTh through LeaveRuleMessages#render. params may be null
     * (treated as Map#of()) but must otherwise carry every key code's template needs -- LeaveRuleMessages#render
     * throws IllegalArgumentException naming the code and the missing key if it does not. */
    public static LeaveRuleOutcome of(LeaveRuleCode code, Map<String, String> params) {
        Map<String, String> safeParams = params == null ? Map.of() : Map.copyOf(params);
        return new LeaveRuleOutcome(code, safeParams, LeaveRuleMessages.render(code, safeParams));
    }
}
