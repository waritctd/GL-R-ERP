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

    /**
     * Whether the calendar has ANY row at all for {@code year} — distinct from {@link #isHoliday},
     * which answers "is this particular date a holiday". {@code hr.holiday} ships with ZERO seed
     * rows (see V115's Javadoc: HR populates it once the BOT list is available), so
     * {@code isHoliday(date)} alone cannot tell "this date genuinely is not a holiday" apart from
     * "nobody has loaded this year's holidays yet" — both return {@code false}.
     *
     * <p>Exists for callers that must react differently to those two cases — e.g. {@code
     * OvertimeService#resolveDayTypeSubmitNote}, which flags EVERY submission for a year the
     * calendar has no data for at all, regardless of what {@code SubmitOvertimeRequest.dayType}
     * claimed (the DERIVATION is what's unverified there, not the claim), and separately refuses
     * outright a HOLIDAY claim the calendar CAN actively disprove once it does have data for the
     * year. Do not use this for money: it never participates in {@code
     * OvertimeService#deriveDayType}, which stays exactly "does {@link #isHoliday} say yes" — an
     * unloaded calendar still correctly resolves to WORKDAY, the conservative direction.
     */
    boolean hasHolidaysForYear(int year);
}
