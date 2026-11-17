package th.co.glr.hr.leave;

import java.time.LocalDate;

/** One hr.holiday row inside a GET /api/leave/calendar-context range -- the same data HolidayController's admin
 * GET exposes via HolidayDto, narrowed to what the leave composer needs. */
record LeaveCalendarHolidayDto(LocalDate holidayDate, String nameTh) {
}
