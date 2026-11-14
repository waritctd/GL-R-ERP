package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.Set;

/** The company holiday calendar. */
public interface HolidayCalendar {
    boolean isHoliday(LocalDate date);

    Set<LocalDate> holidaysBetween(LocalDate fromDate, LocalDate toDate);

    /** Whether the calendar has ANY row at all for year — distinct from #isHoliday, which answers "is this
     * particular date a holiday". */
    boolean hasHolidaysForYear(int year);
}
