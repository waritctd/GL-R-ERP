package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/** One {@code hr.holiday} row for the admin CRUD surface. {@code source} is {@code BANK} or {@code COMPANY}. */
record HolidayDto(LocalDate holidayDate, String nameTh, String source) {
    static HolidayDto from(HolidayRepository.HolidayRow row) {
        return new HolidayDto(row.holidayDate(), row.nameTh(), row.source());
    }
}
