package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
                new BigDecimal("9.00"), new BigDecimal("2.00")),
            new LeaveQuotaYearSplit(2027, new BigDecimal("3.00"), new BigDecimal("3.00"), BigDecimal.ZERO,
                new BigDecimal("98.00"), new BigDecimal("95.00"))
        );

        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonthAcrossYears(
            LocalDate.parse("2026-12-21"), LocalDate.parse("2027-01-05"), perYear);

        assertThat(byMonth)
            .containsExactly(Map.entry(LocalDate.parse("2026-12-01"), new BigDecimal("2.00")));
        // The critical negative assertion: January must NOT show up as unpaid, even though the naive
        // aggregate-rank reading described above would have put 2 unpaid days there instead.
        assertThat(byMonth).doesNotContainKey(LocalDate.parse("2027-01-01"));
    }

    @Test
    void unpaidWorkingDaysByMonthAcrossYearsSkipsAYearThatDoesNotIntersectTheRange() {
        // Defensive: a LeaveQuotaYearSplit entry for a year the [startDate, endDate] range does not
        // actually touch (should not happen in practice -- see clipToYear's Javadoc) is simply
        // skipped rather than throwing.
        List<LeaveQuotaYearSplit> perYear = List.of(
            new LeaveQuotaYearSplit(2025, new BigDecimal("1.00"), BigDecimal.ZERO, new BigDecimal("1.00"),
                BigDecimal.ZERO, BigDecimal.ZERO),
            new LeaveQuotaYearSplit(2026, new BigDecimal("2.00"), BigDecimal.ZERO, new BigDecimal("2.00"),
                new BigDecimal("6.00"), new BigDecimal("6.00"))
        );

        Map<LocalDate, BigDecimal> byMonth = LeaveDayMath.unpaidWorkingDaysByMonthAcrossYears(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), perYear);

        assertThat(byMonth).containsOnlyKeys(LocalDate.parse("2026-07-01"));
    }
}
