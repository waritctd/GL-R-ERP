package th.co.glr.hr.leave;

import java.time.LocalDate;
import java.util.List;

/** GET /api/leave/calendar-context response body -- the caller's own holiday + resolved work-schedule context
 * for [from, to], so the leave composer can show WHY LeaveDayMath's schedule/holiday-aware count differs from
 * the plain calendar span, see LeaveCalendarContextService. */
record LeaveCalendarContextDto(
    LocalDate from,
    LocalDate to,
    List<LeaveCalendarHolidayDto> holidays,
    LeaveCalendarWorkScheduleDto schedule,
    List<LocalDate> nonWorkingDates
) {
}
