package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.Set;

/**
 * The company holiday calendar ({@code hr.holiday}, V115) — public holidays (นักขัตฤกษ์) and other
 * company-recognised days off, distinct from an employee's ordinary non-workdays (which come from
 * their {@link WorkSchedule} instead).
 *
 * <p>{@link #holidaysBetween} exists so a range read never costs one query per day: it protects the
 * same query budget {@code AttendanceDailyService#recalculateRange} documents ("three queries
 * rather than three per employee-day") — callers over a date range must use it instead of calling
 * {@link #isHoliday} per row.
 */
public interface HolidayCalendar {
    boolean isHoliday(LocalDate date);

    Set<LocalDate> holidaysBetween(LocalDate fromDate, LocalDate toDate);
}
