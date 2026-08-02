package th.co.glr.hr.leave;

import java.time.LocalDate;

/**
 * §5.4 MATERNITY calendar-day counting (V119, 2026-08-02): which days of a leave request's
 * [startDate, endDate] range count toward its total/paid/unpaid days.
 *
 * <p>{@link #WORKING_DAYS} (today's behaviour for every leave type except MATERNITY): Mon-Fri
 * only. This is LeaveDayMath's existing Mon-Fri assumption, UNCHANGED by this enum -- it is known
 * to be wrong for six-day departments, which is a separate, later concern owned by a different
 * branch.
 *
 * <p>{@link #CALENDAR_DAYS} (MATERNITY only, per the governing announcement "วันเวลาทำงาน และ
 * การหยุดงาน" §5.4: "การนับวันลาเพื่อคลอดบุตรให้นับรวมวันหยุดประจำสัปดาห์ วันหยุดตามประเพณี ที่มี
 * ระหว่างวันลาการคลอดบุตรด้วย" -- the counting of maternity leave days shall include weekly
 * holidays and traditional holidays that fall during the leave period): every day in the range
 * counts. There is no lookup against {@code hr.holiday} here, and none is needed -- "every day
 * counts" already includes any traditional/public holiday that happens to fall on a weekday by
 * construction, the same way it already includes Sat/Sun.
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
        boolean counts(LocalDate date) {
            return LeaveDayMath.isWorkingDay(date);
        }
    },
    CALENDAR_DAYS {
        @Override
        boolean counts(LocalDate date) {
            return true;
        }
    };

    abstract boolean counts(LocalDate date);
}
