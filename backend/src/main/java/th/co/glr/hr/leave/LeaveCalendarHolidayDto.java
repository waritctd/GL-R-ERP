package th.co.glr.hr.leave;

import java.time.LocalDate;

/**
 * One {@code hr.holiday} row inside a {@code GET /api/leave/calendar-context} range -- the same
 * {@code (holidayDate, nameTh)} data {@code HolidayController}'s admin GET exposes via
 * {@code HolidayDto}, narrowed to what the leave composer needs (no {@code source}: BANK vs
 * COMPANY is an admin-CRUD distinction, not something an employee filing leave needs to see).
 */
record LeaveCalendarHolidayDto(LocalDate holidayDate, String nameTh) {
}
