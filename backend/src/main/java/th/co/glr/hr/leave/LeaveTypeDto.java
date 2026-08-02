package th.co.glr.hr.leave;

import java.math.BigDecimal;

/**
 * §5 company-announcement leave rules as data (V116): {@code paidDaysCap} through
 * {@code oncePerEmployment} turn what used to be hardcoded per-type branches in
 * {@code LeaveService} into columns on {@code hr.leave_type}. See V116's migration comment for the
 * seeded values per type and the decisions behind them.
 *
 * <p>{@code dayCountBasis} (V119, 2026-08-02): §5.4 MATERNITY calendar-day counting -- see
 * {@link LeaveDayCountBasis} for the announcement text and V119's migration comment for why only
 * MATERNITY is {@code CALENDAR_DAYS} (every other type, including MILITARY/ORDINATION, stays the
 * default {@code WORKING_DAYS}).
 */
public record LeaveTypeDto(
    String code,
    String nameTh,
    String nameEn,
    BigDecimal annualQuotaDays,
    boolean requiresAttachment,
    BigDecimal paidDaysCap,
    int advanceNoticeDays,
    int minServiceMonths,
    BigDecimal maxConsecutiveDays,
    boolean oncePerEmployment,
    LeaveDayCountBasis dayCountBasis
) {
}
