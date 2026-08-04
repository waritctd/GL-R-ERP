package th.co.glr.hr.leave;

import java.util.Map;

/**
 * Phase A0a (structured rejection outcome): the machine-readable replacement for the plain English
 * rejection {@code String} {@link LeaveService#autoRejectNote} used to return. {@code code} is the
 * stable, frontend-actionable identifier; {@code params} are the values that code's message
 * template needs (already caller-formatted -- see {@link LeaveRuleMessages}'s class Javadoc);
 * {@code messageTh} is the rendered Thai sentence, persisted verbatim into
 * {@code hr.leave_request.system_note} exactly where the English string used to go.
 *
 * <p>{@code params} is always non-null and immutable ({@link Map#copyOf}) -- {@link #of} normalizes
 * a {@code null} argument to {@link Map#of()} rather than ever exposing a mutable or absent map, so
 * every {@link LeaveRuleOutcome} instance is safe to persist, log, or hand to the frontend as-is.
 */
public record LeaveRuleOutcome(LeaveRuleCode code, Map<String, String> params, String messageTh) {

    /**
     * Builds the outcome for {@code code}, resolving {@code messageTh} through
     * {@link LeaveRuleMessages#render}. {@code params} may be {@code null} (treated as
     * {@link Map#of()}) but must otherwise carry every key {@code code}'s template needs --
     * {@link LeaveRuleMessages#render} throws {@link IllegalArgumentException} naming the code and
     * the missing key if it does not.
     */
    public static LeaveRuleOutcome of(LeaveRuleCode code, Map<String, String> params) {
        Map<String, String> safeParams = params == null ? Map.of() : Map.copyOf(params);
        return new LeaveRuleOutcome(code, safeParams, LeaveRuleMessages.render(code, safeParams));
    }
}
