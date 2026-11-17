package th.co.glr.hr.leave;

import java.time.LocalDate;
import java.util.function.Predicate;

/** §5.4 MATERNITY calendar-day counting: which days of a leave request's [startDate, endDate] range count
 * toward its total/paid/unpaid days. #WORKING_DAYS: delegates to the caller-supplied isWorkingDay predicate --
 * see #counts. */
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

    /** Selects affected frontend tests, including readers outside the import graph. */
    abstract boolean counts(LocalDate date, Predicate<LocalDate> isWorkingDay);
}
