package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/**
 * Manual holiday create. No {@code source} field on purpose — {@link HolidayAdminService} always
 * writes {@code COMPANY}; see {@link HolidayRepository}'s class javadoc for why that must never be
 * caller-suppliable.
 */
record HolidayCreateRequest(LocalDate holidayDate, String nameTh) {
}
