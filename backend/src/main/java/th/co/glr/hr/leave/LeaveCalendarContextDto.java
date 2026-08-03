package th.co.glr.hr.leave;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/leave/calendar-context} response body -- the caller's own holiday + resolved
 * work-schedule context for {@code [from, to]}, so the leave composer can show WHY
 * {@code LeaveDayMath}'s schedule/holiday-aware count differs from the plain calendar span
 * ("why is this 3 days and not 5"), see {@code LeaveCalendarContextService}.
 *
 * @param from           echoes the validated request range (inclusive)
 * @param to             echoes the validated request range (inclusive)
 * @param holidays       {@code hr.holiday} rows inside {@code [from, to]} -- same data
 *                       {@code HolidayController}'s admin GET exposes, narrowed to this caller's
 *                       own read (no role gate; every authenticated employee may see the calendar
 *                       their own leave is counted against)
 * @param schedule       the caller's own resolved schedule, evaluated at {@code from} -- for
 *                       DISPLAY only (see {@code LeaveRepository#resolveOwnSchedule}'s javadoc)
 * @param nonWorkingDates every date in {@code [from, to]} that is EITHER a holiday OR a
 *                       non-workday per the caller's resolved schedule -- built by running the
 *                       SAME {@code LeaveRepository#workingDayPredicate} that
 *                       {@code LeaveDayMath} itself uses to count paid/unpaid days, not a
 *                       reimplementation. This is the field the composer actually marks dates
 *                       with.
 */
record LeaveCalendarContextDto(
    LocalDate from,
    LocalDate to,
    List<LeaveCalendarHolidayDto> holidays,
    LeaveCalendarWorkScheduleDto schedule,
    List<LocalDate> nonWorkingDates
) {
}
