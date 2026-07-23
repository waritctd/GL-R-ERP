package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
        Map<LocalDate, Integer> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-17"), 2);

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-07-01"), 3));
    }

    @Test
    void unpaidWorkingDaysByMonthSplitsCorrectlyAcrossACalendarMonthBoundary() {
        // Thu 2026-07-30 .. Wed 2026-08-05: working days are Thu 7/30, Fri 7/31, Mon 8/3, Tue 8/4,
        // Wed 8/5 (Sat 8/1 + Sun 8/2 excluded) = 5 working days total, chronological order:
        // rank1=7/30, rank2=7/31, rank3=8/3, rank4=8/4, rank5=8/5.
        // paidDays=2 consumes the first 2 (both in July) -> July has 0 unpaid days, August has 3
        // (ranks 3,4,5 all fall beyond the 2 paid days and all land in August).
        Map<LocalDate, Integer> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-30"), LocalDate.parse("2026-08-05"), 2);

        assertThat(byMonth)
            .doesNotContainKey(LocalDate.parse("2026-07-01"))
            .containsEntry(LocalDate.parse("2026-08-01"), 3);
    }

    @Test
    void unpaidWorkingDaysByMonthSplitsPaidPortionAcrossTheBoundaryToo() {
        // Same range as above but paidDays=4: ranks 1-4 (7/30, 7/31, 8/3, 8/4) are paid, only rank 5
        // (8/5) is unpaid -- so July still contributes 0 unpaid days (its 2 working days were both
        // paid) and August contributes exactly 1.
        Map<LocalDate, Integer> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-30"), LocalDate.parse("2026-08-05"), 4);

        assertThat(byMonth)
            .doesNotContainKey(LocalDate.parse("2026-07-01"))
            .containsEntry(LocalDate.parse("2026-08-01"), 1);
    }

    @Test
    void unpaidWorkingDaysByMonthWithZeroPaidDaysMarksEveryWorkingDayUnpaid() {
        // LEAVE_WITHOUT_PAY case: paidDays=0 -> every working day in range is unpaid.
        Map<LocalDate, Integer> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), 0);

        assertThat(byMonth).containsExactly(Map.entry(LocalDate.parse("2026-07-01"), 2));
    }

    @Test
    void unpaidWorkingDaysByMonthWithFullyPaidRangeProducesNoUnpaidEntries() {
        Map<LocalDate, Integer> byMonth = LeaveDayMath.unpaidWorkingDaysByMonth(
            LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-14"), 5);

        assertThat(byMonth).isEmpty();
    }
}
