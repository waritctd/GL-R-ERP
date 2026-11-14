package th.co.glr.hr.leave;

/** §5 WARN_UNPAID_* gates (owner-approved change, 2026-09-09, V164): what LeaveService #autoRejectNote does
 * when a LeaveRuleCode gate fires. Only when NO #WARN_UNPAID_ALL fired does unpaid_by_rule_days become the MAX
 * (never the sum) of whatever #WARN_UNPAID_EXCESS codes fired. */
public enum LeaveRuleEnforcement {
    BLOCK,
    WARN_UNPAID_ALL,
    WARN_UNPAID_EXCESS
}
