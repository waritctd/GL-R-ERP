package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;

/** Pure-math coverage for LeaveDayMath, the weekday-counting logic shared by the per-month unpaid-day
 * attribution query (LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth) and the cancel-after-close
 * reversal (LeaveService#cancel). No DB needed -- these are plain date-arithmetic assertions. */
class LeaveDayMathTest {

    @Test
    void countWorkingDaysExcludesWeekends() {
        // Mon 2026-07-13 .. Fri 2026-07-17 = 5 working days.
        assertThat(LeaveDayMath.countWorkingDays(LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-17")))
            .isEqualTo(5);
        // Sat 2026-07-18 .. Sun 2026-07-19 = 0 working days.
        assertThat(LeaveDayMath.countWorkingDays(LocalDate.parse("2026-07-18"), LocalDate.parse("2026-07-19")))
            .isEqualTo(0);
        // Mon 2026-07-13 .. Mon 2026-07-20 (spans a weekend) = 6 working days.
        assertThat(LeaveDayMath.countWorkingDays(LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-20")))
            .isEqualTo(6);
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.4 MATERNITY calendar-day counting: the LeaveDayCountBasis-aware
    // overloads. WORKING_DAYS-basis calls above hit the no-basis overloads,
    // which delegate to WORKING_DAYS -- these tests are additive coverage for CALENDAR_DAYS.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void unpaidWorkingDaysByMonthOnCalendarBasisCountsAnUnpaidWeekendDayTowardTheDeduction() {
        // Mon 2026-07-13 .. Sun 2026-07-19 (7 calendar days), 5 paid -> under CALENDAR_DAYS the remaining 2 unpaid
        // days are Sat 7/18 and Sun 7/19 -- both counted, unlike WORKING_DAYS (which would never even reach a weekend
        // day to rank it).
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-19"), new BigDecimal("5.00"),
            new BigDecimal("7.00"), LeaveDayCountBasis.CALENDAR_DAYS);

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-07-01"), new BigDecimal("2.00")));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Schedule/holiday-aware working-day counting: LeaveDayMath#workingDayPredicate and
    // the predicate-accepting overloads it feeds. Pure unit coverage of the DECISION (which date
    // counts) -- LeaveScheduleHolidayAwareIntegrationTest proves the same thing survives into real
    // SQL (the employee->division/department join, the batched holiday read) through the real
    // repository, which Mockito/plain-object fakes here cannot verify.
    // ─────────────────────────────────────────────────────────────────────

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final WorkSchedule FIVE_DAY_SCHEDULE = new WorkSchedule(
        BUSINESS_ZONE, LocalTime.of(8, 30), LocalTime.of(17, 30), 5,
        EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));
    private static final WorkSchedule SIX_DAY_SCHEDULE = new WorkSchedule(
        BUSINESS_ZONE, LocalTime.of(8, 30), LocalTime.of(17, 30), 5,
        EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY));

    @Test
    void workingDayPredicateCountsASaturdayForASixDayEmployeeButNotForAFiveDayEmployeeOnTheSameDate() {
        // Both sides on ONE fixture (the same Saturday), per this repo's vacuous-fixture rule: a predicate that always
        // returned true (or always false) would pass only one of these.
        LocalDate saturday = LocalDate.parse("2026-08-08");
        WorkScheduleResolver sixDayResolver = (employeeId, divisionId, departmentId, date) -> SIX_DAY_SCHEDULE;
        WorkScheduleResolver fiveDayResolver = (employeeId, divisionId, departmentId, date) -> FIVE_DAY_SCHEDULE;

        Predicate<LocalDate> sixDayIsWorkingDay =
            LeaveDayMath.workingDayPredicate(sixDayResolver, 1L, null, null, Set.of());
        Predicate<LocalDate> fiveDayIsWorkingDay =
            LeaveDayMath.workingDayPredicate(fiveDayResolver, 2L, null, null, Set.of());

        assertThat(sixDayIsWorkingDay.test(saturday)).as("six-day schedule: Saturday counts").isTrue();
        assertThat(fiveDayIsWorkingDay.test(saturday)).as("five-day schedule: the SAME Saturday does not").isFalse();
    }

}
