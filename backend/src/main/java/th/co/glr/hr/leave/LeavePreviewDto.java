package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.util.List;

/** POST /api/leave/preview (Phase A0b dry-run) response -- see LeaveService#preview's Javadoc for the full
 * contract. true means the check genuinely ran, whether or not it found a problem. counters: always populated
 * -- see LeaveService#previewCounters. */
public record LeavePreviewDto(
    LeaveRuleOutcome blocking,
    boolean datesEvaluated,
    boolean coverageEvaluated,
    BigDecimal totalDays,
    BigDecimal paidDays,
    BigDecimal unpaidDays,
    List<LeaveQuotaYearSplit> quotaYearSplits,
    List<LeaveRuleWarningDto> ruleWarnings,
    BigDecimal unpaidByRuleDays,
    LeavePreviewCounters counters
) {
}
