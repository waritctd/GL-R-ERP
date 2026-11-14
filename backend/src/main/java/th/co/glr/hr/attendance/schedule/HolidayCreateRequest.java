package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/** Manual holiday create. No source field on purpose — HolidayAdminService always writes COMPANY; see
 * HolidayRepository's class javadoc for why that must never be caller-suppliable. */
record HolidayCreateRequest(LocalDate holidayDate, String nameTh) {
}
