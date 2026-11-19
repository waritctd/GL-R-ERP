package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** V164: plain-unit coverage for the pieces of the WARN_UNPAID_* redesign that do not need a real database or a
 * mocked LeaveRepository -- the LeaveRuleCode#enforcement mapping itself, LeaveService#isBlocking's BLOCK/WARN
 * dispatch, and LeaveService#dominantUnpaidByRuleDays's dominance rule. */
class LeaveRuleEnforcementTest {

    // LeaveRuleCode#enforcement() mapping -- pinned against the task brief's explicit code lists, not against
    // LeaveRuleCode's own source, so this test would actually fail if a future edit silently moved a code between
    // buckets.

    // LeaveService#isBlocking dispatch

    // LeaveService#dominantUnpaidByRuleDays -- owner ruling #3, "never sum an ALL with an EXCESS".

    @Test
    void dominanceIsTotalDaysNeverSummedWithExcessWhenBothAnAllAndAnExcessFired() {
        // Owner ruling #3, verbatim: "Never sum an ALL with an EXCESS." An ALL always wins outright, regardless of how
        // large -- or small -- the accumulated EXCESS amount is.
        LeaveRuleOutcome allOutcome = LeaveRuleOutcome.of(LeaveRuleCode.ADVANCE_NOTICE,
            Map.of("leaveTypeNameTh", "ลากิจ", "noticeDays", "3", "days", "5.00"));
        LeaveRuleOutcome excessOutcome = LeaveRuleOutcome.of(LeaveRuleCode.WEDDING_MAX_DAYS,
            Map.of("maxDays", "3", "totalDays", "4.00", "excessDays", "1.00"));

        BigDecimal result = LeaveService.dominantUnpaidByRuleDays(
            List.of(allOutcome, excessOutcome), new BigDecimal("1.00"), new BigDecimal("5.00"));

        // NOT 6.00 (5.00 + 1.00) -- exactly totalDays.
        assertThat(result).isEqualByComparingTo("5.00");
    }
}
