package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;

/**
 * Pure-math coverage for {@link LeaveDayMath}, the weekday-counting logic shared by the per-month
 * unpaid-day attribution query ({@link LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}) and
 * the cancel-after-close reversal ({@link LeaveService#cancel}). No DB needed -- these are plain
 * date-arithmetic assertions.
 */
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

    @Test
    void unpaidWorkingDaysByMonthWhollyWithinOneMonthAttributesAllUnpaidDaysToThatMonth() {
        // Mon 2026-07-13 .. Fri 2026-07-17 (5 working days), 2 paid -> 3 unpaid, all in July.
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-17"), new BigDecimal("2.00"), new BigDecimal("5.00"));

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-07-01"), new BigDecimal("3.00")));
    }

    @Test
    void unpaidWorkingDaysByMonthSplitsCorrectlyAcrossACalendarMonthBoundary() {
        // Thu 2026-07-30 .. Wed 2026-08-05: working days are Thu 7/30, Fri 7/31, Mon 8/3, Tue 8/4,
        // Wed 8/5 (Sat 8/1 + Sun 8/2 excluded) = 5 working days total, chronological order:
        // rank1=7/30, rank2=7/31, rank3=8/3, rank4=8/4, rank5=8/5.
        // paidDays=2 consumes the first 2 (both in July) -> July has 0 unpaid days, August has 3
        // (ranks 3,4,5 all fall beyond the 2 paid days and all land in August).
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-30"), LocalDate.parse("2026-08-05"), new BigDecimal("2.00"), new BigDecimal("5.00"));

        assertThat(byMonth)
            .doesNotContainKey(LocalDate.parse("2026-07-01"))
            .containsEntry(LocalDate.parse("2026-08-01"), new BigDecimal("3.00"));
    }

    @Test
    void unpaidWorkingDaysByMonthSplitsPaidPortionAcrossTheBoundaryToo() {
        // Same range as above but paidDays=4: ranks 1-4 (7/30, 7/31, 8/3, 8/4) are paid, only rank 5
        // (8/5) is unpaid -- so July still contributes 0 unpaid days (its 2 working days were both
        // paid) and August contributes exactly 1.
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-30"), LocalDate.parse("2026-08-05"), new BigDecimal("4.00"), new BigDecimal("5.00"));

        assertThat(byMonth)
            .doesNotContainKey(LocalDate.parse("2026-07-01"))
            .containsEntry(LocalDate.parse("2026-08-01"), new BigDecimal("1.00"));
    }

    @Test
    void unpaidWorkingDaysByMonthWithZeroPaidDaysMarksEveryWorkingDayUnpaid() {
        // LEAVE_WITHOUT_PAY case: paidDays=0 -> every working day in range is unpaid.
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), BigDecimal.ZERO, new BigDecimal("2.00"));

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-07-01"), new BigDecimal("2.00")));
    }

    @Test
    void unpaidWorkingDaysByMonthWithFullyPaidRangeProducesNoUnpaidEntries() {
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), new BigDecimal("5.00"), new BigDecimal("2.00"));

        assertThat(byMonth).isEmpty();
    }

    // --- Sub-day leave (2026-07-25): single-date range, may be a fractional remainder -----------

    @Test
    void unpaidWorkingDaysByMonthSubDaySingleDateOverQuotaAttributesFractionalRemainder() {
        // Half-day (0.50) leave, none of it covered by quota -> the whole 0.50 is unpaid, in that
        // day's month.
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"), new BigDecimal("0.00"), new BigDecimal("0.50"));

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-07-01"), new BigDecimal("0.50")));
    }

    @Test
    void unpaidWorkingDaysByMonthSubDaySingleDateWithinQuotaProducesNoUnpaidEntries() {
        // Half-day (0.50) leave, fully covered by quota -> nothing unpaid.
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"), new BigDecimal("0.50"), new BigDecimal("0.50"));

        assertThat(byMonth).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────
    // V118 cross-year quota fix (2026-08-02): totalDaysByYear / clipToYear /
    // unpaidWorkingDaysByMonthAcrossYears -- the day-counting logic EXTENDED (not duplicated) to
    // attribute a request's days per calendar year instead of only per calendar month.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void totalDaysByYearWhollyWithinOneYearProducesASingleEntry() {
        // Mon 2026-07-13 .. Fri 2026-07-17 = 5 working days, all in 2026.
        Map<Integer, BigDecimal> byYear = LeaveDayMath.totalDaysByYear(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-17"), new BigDecimal("5.00"));

        assertThat(byYear).containsExactly(Map.entry(2026, new BigDecimal("5.00")));
    }

    @Test
    void totalDaysByYearSplitsCorrectlyAcrossThe31DecemberToJanuaryBoundary() {
        // Thu 2026-12-31 (a working day) .. Fri 2027-01-01 (a working day too, no holiday calendar in
        // v1): 1 day in 2026, 1 day in 2027.
        Map<Integer, BigDecimal> byYear = LeaveDayMath.totalDaysByYear(
            LocalDate.parse("2026-12-31"), LocalDate.parse("2027-01-01"), new BigDecimal("2.00"));

        assertThat(byYear)
            .containsExactly(Map.entry(2026, new BigDecimal("1.00")), Map.entry(2027, new BigDecimal("1.00")));
        // Ascending-year order is a documented guarantee callers rely on (LeaveService#submit reads
        // the first entry as the request's start year).
        assertThat(byYear.keySet()).containsExactly(2026, 2027);
    }

    @Test
    void totalDaysByYearOnASingleDateAttributesTheWholeAmountToThatYear() {
        // Single-date range (whole-day or sub-day): no weekday-rank logic needed, just the one date's
        // year -- proven with a fractional (sub-day) amount, since that is the case a naive
        // working-day-count reimplementation would get wrong.
        Map<Integer, BigDecimal> byYear = LeaveDayMath.totalDaysByYear(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-13"), new BigDecimal("0.50"));

        assertThat(byYear).containsExactly(Map.entry(2026, new BigDecimal("0.50")));
    }

    @Test
    void clipToYearClipsBothEndsWhenTheRangeExtendsPastTheYearOnBothSides() {
        LocalDate[] clipped = LeaveDayMath.clipToYear(
            LocalDate.parse("2026-11-01"), LocalDate.parse("2027-03-17"), 2026);

        assertThat(clipped).containsExactly(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-31"));
    }

    @Test
    void clipToYearReturnsNullWhenTheRangeDoesNotIntersectTheYearAtAll() {
        LocalDate[] clipped = LeaveDayMath.clipToYear(
            LocalDate.parse("2027-01-01"), LocalDate.parse("2027-03-17"), 2026);

        assertThat(clipped).isNull();
    }

    @Test
    void unpaidWorkingDaysByMonthAcrossYearsComposesEachYearIndependently() {
        // The scenario that DEFINES the risk in LeaveQuotaYearSplit's Javadoc: year A (2026) is
        // capped mid-way (an unpaid TAIL near year-end), year B (2027) is fully paid from its own
        // fresh start. A single whole-request chronological-rank threshold over the AGGREGATE figures
        // would put the unpaid days at the wrong end of the whole span -- this proves the per-year
        // composition instead correctly keeps them in December.
        //
        // Full continuous range Mon 2026-12-21 .. Tue 2027-01-05, working days in chronological
        // order: 12/21,22,23,24,25,28,29,30,31 (9, all 2026), then 1/1,1/4,1/5 (3, all 2027;
        // 1/2-1/3 is a weekend, no holiday calendar in v1) -- 12 working days total.
        //
        // 2026 slice: 7 of its 9 working days paid -> unpaid = the LAST 2 chronologically, 12/30 and
        // 12/31 (both December).
        // 2027 slice: all 3 of its working days paid -> nothing unpaid.
        //
        // A NAIVE aggregate reading (paid=7+3=10 across the full 12-day span, single global rank
        // threshold) would instead mark ranks 11-12 (1/4 and 1/5, both JANUARY) as unpaid -- the
        // wrong month AND the wrong year. That divergence is exactly what this test guards against.
        List<LeaveQuotaYearSplit> perYear = List.of(
            new LeaveQuotaYearSplit(2026, new BigDecimal("9.00"), new BigDecimal("7.00"), new BigDecimal("2.00"),
                new BigDecimal("9.00"), new BigDecimal("2.00"), BigDecimal.ZERO, new BigDecimal("7.00")),
            new LeaveQuotaYearSplit(2027, new BigDecimal("3.00"), new BigDecimal("3.00"), BigDecimal.ZERO,
                new BigDecimal("98.00"), new BigDecimal("95.00"), BigDecimal.ZERO, new BigDecimal("3.00"))
        );

        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonthAcrossYears(
            LocalDate.parse("2026-12-21"), LocalDate.parse("2027-01-05"), perYear);

        assertThat(byMonth)
            .containsExactly(Map.entry(LocalDate.parse("2026-12-01"), new BigDecimal("2.00")));
        // The critical negative assertion: January must NOT show up as unpaid, even though the naive
        // aggregate-rank reading described above would have put 2 unpaid days there instead.
        assertThat(byMonth).doesNotContainKey(LocalDate.parse("2027-01-01"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.4 MATERNITY calendar-day counting (V119, 2026-08-02): the new LeaveDayCountBasis-aware
    // overloads. WORKING_DAYS-basis calls above are UNCHANGED (they hit the old no-basis overloads,
    // which now delegate to WORKING_DAYS) -- these tests are additive coverage for CALENDAR_DAYS.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void countCalendarDaysCountsEveryDayIncludingWeekends() {
        // Mon 2026-07-13 .. Sun 2026-07-19 (a full week, both weekend days included) = 7 calendar
        // days, vs. only 5 working days for the identical range (see countWorkingDaysExcludesWeekends
        // for the same Mon-Fri span) -- proves this is a genuinely different count, not a renamed
        // duplicate.
        assertThat(LeaveDayMath.countCalendarDays(LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-19")))
            .isEqualTo(7);
        assertThat(LeaveDayMath.countCalendarDays(LocalDate.parse("2026-07-18"), LocalDate.parse("2026-07-19")))
            .isEqualTo(2);
        // A 98-CALENDAR-day span (the exact §5.4 MATERNITY quota figure) -- the defect this branch
        // fixes: under the old WORKING_DAYS-only counting this same range would have counted roughly
        // 70 days (98 * 5/7), not 98.
        assertThat(LeaveDayMath.countCalendarDays(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-04-12")))
            .isEqualTo(98);
    }

    @Test
    void totalDaysByYearOnCalendarBasisCountsWeekendsThatWorkingBasisWouldExclude() {
        // THE vacuous-fixture guard: same Mon 2026-07-13 .. Sun 2026-07-19 range, asserted under BOTH
        // bases on one fixture. If this ever regressed to always using the same counting logic
        // regardless of basis, one of these two assertions would fail.
        LocalDate start = LocalDate.parse("2026-07-13");
        LocalDate end = LocalDate.parse("2026-07-19");

        Map<Integer, BigDecimal> calendarBasis =
            LeaveDayMath.totalDaysByYear(start, end, new BigDecimal("7.00"), LeaveDayCountBasis.CALENDAR_DAYS);
        Map<Integer, BigDecimal> workingBasis =
            LeaveDayMath.totalDaysByYear(start, end, new BigDecimal("5.00"), LeaveDayCountBasis.WORKING_DAYS);

        assertThat(calendarBasis).containsExactly(Map.entry(2026, new BigDecimal("7.00")));
        assertThat(workingBasis).containsExactly(Map.entry(2026, new BigDecimal("5.00")));
        // The no-basis 3-arg overload (used by every WORKING_DAYS type) must be byte-identical to
        // explicitly passing WORKING_DAYS -- pins that the delegation introduced no drift.
        assertThat(LeaveDayMath.totalDaysByYear(start, end, new BigDecimal("5.00"))).isEqualTo(workingBasis);
    }

    @Test
    void totalDaysByYearOnCalendarBasisSplitsCorrectlyAcrossThe31DecemberToJanuaryBoundaryIncludingTheWeekend() {
        // Thu 2026-12-31 .. Mon 2027-01-04: CALENDAR_DAYS counts all 5 days (Thu,Fri,Sat,Sun,Mon) --
        // 1 in 2026 (12/31), 4 in 2027 (1/1-1/4) -- including the weekend, which WORKING_DAYS would
        // have skipped entirely.
        Map<Integer, BigDecimal> byYear = LeaveDayMath.totalDaysByYear(
            LocalDate.parse("2026-12-31"), LocalDate.parse("2027-01-04"), new BigDecimal("5.00"),
            LeaveDayCountBasis.CALENDAR_DAYS);

        assertThat(byYear)
            .containsExactly(Map.entry(2026, new BigDecimal("1.00")), Map.entry(2027, new BigDecimal("4.00")));
    }

    @Test
    void unpaidWorkingDaysByMonthOnCalendarBasisCountsAnUnpaidWeekendDayTowardTheDeduction() {
        // Mon 2026-07-13 .. Sun 2026-07-19 (7 calendar days), 5 paid -> under CALENDAR_DAYS the
        // remaining 2 unpaid days are Sat 7/18 and Sun 7/19 -- both counted, unlike WORKING_DAYS
        // (which would never even reach a weekend day to rank it).
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-19"), new BigDecimal("5.00"),
            new BigDecimal("7.00"), LeaveDayCountBasis.CALENDAR_DAYS);

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-07-01"), new BigDecimal("2.00")));
    }

    @Test
    void unpaidWorkingDaysByMonthWithNoBasisArgumentIsByteIdenticalToExplicitWorkingDays() {
        // Pins that the 4-arg overload (used by every WORKING_DAYS type today) delegates to
        // WORKING_DAYS and produces an identical result to passing it explicitly -- the refactor that
        // added the basis parameter must not have changed the default path.
        LocalDate start = LocalDate.parse("2026-07-30");
        LocalDate end = LocalDate.parse("2026-08-05");
        BigDecimal paid = new BigDecimal("2.00");
        BigDecimal total = new BigDecimal("5.00");

        assertThat(LeaveDayMath.unpaidWorkingDaysByMonth(start, end, paid, total))
            .isEqualTo(LeaveDayMath.unpaidWorkingDaysByMonth(start, end, paid, total, LeaveDayCountBasis.WORKING_DAYS));
    }

    @Test
    void unpaidWorkingDaysByMonthAcrossYearsOnCalendarBasisIncludesTheYearBoundaryWeekend() {
        // Cross-year composition (V118) under CALENDAR_DAYS (V119): Thu 2026-12-31 .. Mon 2027-01-04,
        // split 2026: 1 day (12/31) fully paid; 2027: 4 days (1/1 Fri-holiday-shaped, 1/2 Sat, 1/3
        // Sun, 1/4 Mon), 1 paid -> 3 unpaid, ALL of them counted even though two are a weekend.
        List<LeaveQuotaYearSplit> perYear = List.of(
            new LeaveQuotaYearSplit(2026, new BigDecimal("1.00"), new BigDecimal("1.00"), BigDecimal.ZERO,
                new BigDecimal("98.00"), new BigDecimal("97.00"), BigDecimal.ZERO, new BigDecimal("1.00")),
            new LeaveQuotaYearSplit(2027, new BigDecimal("4.00"), new BigDecimal("1.00"), new BigDecimal("3.00"),
                new BigDecimal("98.00"), new BigDecimal("97.00"), BigDecimal.ZERO, new BigDecimal("1.00"))
        );

        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonthAcrossYears(
            LocalDate.parse("2026-12-31"), LocalDate.parse("2027-01-04"), perYear, LeaveDayCountBasis.CALENDAR_DAYS);

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2027-01-01"), new BigDecimal("3.00")));
    }

    @Test
    void unpaidWorkingDaysByMonthAcrossYearsWithNoBasisArgumentIsByteIdenticalToExplicitWorkingDays() {
        List<LeaveQuotaYearSplit> perYear = List.of(
            new LeaveQuotaYearSplit(2026, new BigDecimal("2.00"), BigDecimal.ZERO, new BigDecimal("2.00"),
                new BigDecimal("6.00"), new BigDecimal("6.00"), BigDecimal.ZERO, BigDecimal.ZERO)
        );
        LocalDate start = LocalDate.parse("2026-07-13");
        LocalDate end = LocalDate.parse("2026-07-14");

        assertThat(LeaveDayMath.unpaidWorkingDaysByMonthAcrossYears(start, end, perYear))
            .isEqualTo(LeaveDayMath.unpaidWorkingDaysByMonthAcrossYears(start, end, perYear, LeaveDayCountBasis.WORKING_DAYS));
    }

    @Test
    void unpaidWorkingDaysByMonthAcrossYearsSkipsAYearThatDoesNotIntersectTheRange() {
        // Defensive: a LeaveQuotaYearSplit entry for a year the [startDate, endDate] range does not
        // actually touch (should not happen in practice -- see clipToYear's Javadoc) is simply
        // skipped rather than throwing.
        List<LeaveQuotaYearSplit> perYear = List.of(
            new LeaveQuotaYearSplit(2025, new BigDecimal("1.00"), BigDecimal.ZERO, new BigDecimal("1.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            new LeaveQuotaYearSplit(2026, new BigDecimal("2.00"), BigDecimal.ZERO, new BigDecimal("2.00"),
                new BigDecimal("6.00"), new BigDecimal("6.00"), BigDecimal.ZERO, BigDecimal.ZERO)
        );

        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonthAcrossYears(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), perYear);

        assertThat(byMonth).containsOnlyKeys(LocalDate.parse("2026-07-01"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Schedule/holiday-aware working-day counting (2026-08-03): LeaveDayMath#workingDayPredicate and
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
        // Both sides on ONE fixture (the same Saturday), per this repo's vacuous-fixture rule: a
        // predicate that always returned true (or always false) would pass only one of these.
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

    @Test
    void workingDayPredicateExcludesASeededHolidayEvenOnASixDaySchedulesSaturday() {
        // Holiday beats schedule: a date in `holidays` never counts, regardless of what the resolved
        // WorkSchedule says about that day of week.
        LocalDate holidaySaturday = LocalDate.parse("2026-08-08");
        WorkScheduleResolver sixDayResolver = (employeeId, divisionId, departmentId, date) -> SIX_DAY_SCHEDULE;

        Predicate<LocalDate> isWorkingDay = LeaveDayMath.workingDayPredicate(
            sixDayResolver, 1L, null, null, Set.of(holidaySaturday));

        assertThat(isWorkingDay.test(holidaySaturday))
            .as("a seeded holiday is never a working day, even for a six-day schedule")
            .isFalse();
        // Vacuous-fixture guard: the very next day (Sunday, not a holiday, not in either schedule)
        // stays correctly excluded too -- proves this predicate is not just "always false".
        assertThat(isWorkingDay.test(LocalDate.parse("2026-08-07")))
            .as("Friday -- neither a holiday nor excluded by the six-day schedule -- still counts")
            .isTrue();
    }

    @Test
    void countWorkingDaysWithAPredicateCountsASixDayEmployeesSaturday() {
        WorkScheduleResolver sixDayResolver = (employeeId, divisionId, departmentId, date) -> SIX_DAY_SCHEDULE;
        Predicate<LocalDate> isWorkingDay =
            LeaveDayMath.workingDayPredicate(sixDayResolver, 1L, null, null, Set.of());

        // Mon 2026-08-03 .. Sat 2026-08-08: 6 days, ALL of them working days under a six-day schedule
        // -- the legacy Mon-Fri-only countWorkingDays(start, end) would have returned 5 for this exact
        // range (see countWorkingDaysExcludesWeekends for the equivalent Mon-Fri assertion).
        assertThat(LeaveDayMath.countWorkingDays(
            LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-08"), isWorkingDay))
            .isEqualTo(6);
    }

    @Test
    void unpaidWorkingDaysByMonthWithAPredicateExcludesASeededHolidayFromTheRanking() {
        WorkScheduleResolver fiveDayResolver = (employeeId, divisionId, departmentId, date) -> FIVE_DAY_SCHEDULE;
        LocalDate holiday = LocalDate.parse("2026-08-04"); // Tuesday
        Predicate<LocalDate> isWorkingDay =
            LeaveDayMath.workingDayPredicate(fiveDayResolver, 1L, null, null, Set.of(holiday));

        // Mon 2026-08-03 .. Wed 2026-08-05: 3 calendar weekdays, but 8/4 is a seeded holiday -- only
        // 8/3 and 8/5 rank as working days. paidDays=0 -> both unpaid, entirely in August.
        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-05"), BigDecimal.ZERO, new BigDecimal("2.00"),
            LeaveDayCountBasis.WORKING_DAYS, isWorkingDay);

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-08-01"), new BigDecimal("2.00")));
    }

    @Test
    void unpaidWorkingDaysByMonthOnCalendarBasisNeverInvokesThePredicateEvenWhenGivenAHostileOne() {
        // The double-handling guard at the pure-math level: a predicate that (wrongly) excludes
        // EVERY date must have zero effect on CALENDAR_DAYS (MATERNITY) -- proves CALENDAR_DAYS truly
        // never consults isWorkingDay, not merely that it happens to agree with a permissive one.
        Predicate<LocalDate> rejectsEveryDate = date -> {
            throw new AssertionError("CALENDAR_DAYS must never invoke isWorkingDay, but it did for " + date);
        };

        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-09"), new BigDecimal("5.00"),
            new BigDecimal("7.00"), LeaveDayCountBasis.CALENDAR_DAYS, rejectsEveryDate);

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-08-01"), new BigDecimal("2.00")));
    }
}
