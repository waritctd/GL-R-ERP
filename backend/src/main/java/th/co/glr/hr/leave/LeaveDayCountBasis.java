package th.co.glr.hr.leave;

import java.time.LocalDate;
import java.util.function.Predicate;

/**
 * §5.4 MATERNITY calendar-day counting (V119, 2026-08-02): which days of a leave request's
 * [startDate, endDate] range count toward its total/paid/unpaid days.
 *
 * <p>{@link #WORKING_DAYS} (every leave type except MATERNITY): delegates to the caller-supplied
 * {@code isWorkingDay} predicate -- see {@link #counts(LocalDate, Predicate)}. Until the
 * schedule/holiday-aware branch (2026-08-03), that predicate was always {@link
 * LeaveDayMath#isWorkingDay(LocalDate)} (a hardcoded Mon-Fri assumption, wrong for six-day
 * departments and blind to {@code hr.holiday}); callers now pass a predicate built from the
 * employee's resolved {@code WorkSchedule} and the holiday calendar (see
 * {@link LeaveDayMath#workingDayPredicate}), so this enum itself carries no day-of-week
 * assumption any more.
 *
 * <p>{@link #CALENDAR_DAYS} (MATERNITY only, per the governing announcement "วันเวลาทำงาน และ
 * การหยุดงาน" §5.4: "การนับวันลาเพื่อคลอดบุตรให้นับรวมวันหยุดประจำสัปดาห์ วันหยุดตามประเพณี ที่มี
 * ระหว่างวันลาการคลอดบุตรด้วย" -- the counting of maternity leave days shall include weekly
 * holidays and traditional holidays that fall during the leave period): every day in the range
 * counts, UNCONDITIONALLY -- the {@code isWorkingDay} predicate is never even invoked here. There
 * is no lookup against {@code hr.holiday} for this basis, and none is needed -- "every day counts"
 * already includes any traditional/public holiday that happens to fall on a weekday by
 * construction, the same way it already includes Sat/Sun. This is deliberate: MATERNITY must not
 * "double-handle" the schedule/holiday awareness added elsewhere in this class -- it already
 * counted holidays and six-day-schedule Saturdays as consumed leave days by definition, before
 * either fix existed.
 *
 * <p>Selected per {@code hr.leave_type.day_count_basis} (V119), exposed via
 * {@link LeaveTypeDto#dayCountBasis()}, and consumed by {@link LeaveDayMath}'s basis-aware
 * overloads of {@code totalDaysByYear}/{@code unpaidWorkingDaysByMonth}/
 * {@code unpaidWorkingDaysByMonthAcrossYears}, plus {@link LeaveService#computeTotalDays} for the
 * whole-day total itself.
 */
public enum LeaveDayCountBasis {
    WORKING_DAYS {
        @Override
        boolean counts(LocalDate date, Predicate<LocalDate> isWorkingDay) {
            return isWorkingDay.test(date);
        }
    },
    CALENDAR_DAYS {
        @Override
        boolean counts(LocalDate date, Predicate<LocalDate> isWorkingDay) {
            return true;
        }
    };

    /**
     * @param isWorkingDay schedule/holiday-aware "is this date a working day for this employee"
     *                     predicate (see {@link LeaveDayMath#workingDayPredicate}); ignored by
     *                     {@link #CALENDAR_DAYS}, which never consults it.
     */
    abstract boolean counts(LocalDate date, Predicate<LocalDate> isWorkingDay);
}
