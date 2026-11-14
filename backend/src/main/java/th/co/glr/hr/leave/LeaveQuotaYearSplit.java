package th.co.glr.hr.leave;

import java.math.BigDecimal;

/** Per-calendar-year quota attribution for a single leave request (V118 cross-year quota fix, 2026-08-02). Both
 * are always ZERO for a leave type where LeaveTypeDto#carriesForward() is FALSE -- there is only ever one pool
 * for such a type, and it is ownQuotaDays. */
public record LeaveQuotaYearSplit(
    int quotaYear,
    BigDecimal totalDays,
    BigDecimal paidDays,
    BigDecimal unpaidDays,
    BigDecimal quotaRemainingBefore,
    BigDecimal quotaRemainingAfter,
    BigDecimal carriedInDays,
    BigDecimal ownQuotaDays,
    BigDecimal unpaidByRuleDays
) {
}
