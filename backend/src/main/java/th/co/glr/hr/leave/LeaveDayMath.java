package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leave -&gt; payroll unpaid-day deduction (2026-07-23): shared weekday-counting math used both to
 * attribute a leave request's unpaid days to the payroll month(s) it falls in ({@link
 * LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}) and to work out how many already-deducted
 * days a cancelled leave owes back when it is cancelled after payroll has processed ({@link
 * LeaveService#cancel}).
 *
 * <p><b>Company-policy caveat (needs HR/legal sign-off before this drives a real payroll run):</b>
 * weekends (Sat/Sun) never count as working days; there is no holiday calendar in v1, so a public
 * holiday inside a leave range still counts as a working day today (tracked as an out-of-scope
 * follow-up). {@code paidDays} is always treated as consumed from the request's earliest working
 * days first -- {@code hr.leave_request.paid_days}/{@code unpaid_days} are aggregate totals, not a
 * per-day flag, so chronological consumption is the only ordering they can represent, and it matches
 * the natural reading of "day N onward went unpaid".
 *
 * <p><b>Sub-day leave (2026-07-25):</b> {@link #unpaidWorkingDaysByMonth} now takes/returns
 * {@link BigDecimal} instead of {@code int}, so a fractional sub-day request (e.g. a half-day,
 * {@code 0.50}) can be attributed precisely instead of being floored to a whole day. Sub-day leave is
 * always single-day (enforced by {@code chk_leave_time_single_day}), so the single-date branch below
 * handles it directly: the whole {@code totalDays - paidDays} remainder lands in that one day's
 * month, with no rank/weekday logic needed since there is only one day it could ever land in. The
 * multi-day branch is unchanged (still whole-day-only), now simply emitting {@code BigDecimal("1.00")}
 * per unpaid weekday instead of {@code 1}.
 */
final class LeaveDayMath {
    private static final BigDecimal ONE_WORKING_DAY = new BigDecimal("1.00");

    private LeaveDayMath() {
    }

    /** Total working days (Mon-Fri) in the inclusive range [startDate, endDate]. */
    static int countWorkingDays(LocalDate startDate, LocalDate endDate) {
        int days = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (isWorkingDay(cursor)) {
                days++;
            }
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    /**
     * The unpaid portion of [startDate, endDate] beyond {@code paidDays}, bucketed by calendar month
     * (keyed by the first-of-month date).
     *
     * <p>Single-date range ({@code startDate == endDate}, covers both whole-day and sub-day single-day
     * leave): the exact remainder {@code totalDays - paidDays} (floored at zero) is attributed to that
     * day's month -- there is only one day, so no weekday-rank logic is needed, and the result may be
     * fractional (sub-day leave).
     *
     * <p>Multi-day range (always whole-day -- sub-day leave can never span more than one date): the
     * original weekday-rank logic, each unpaid working day contributing exactly {@code 1.00}, correctly
     * splitting across a calendar-month boundary.
     */
    static Map<LocalDate, BigDecimal> unpaidWorkingDaysByMonth(
            LocalDate startDate, LocalDate endDate, BigDecimal paidDays, BigDecimal totalDays) {
        Map<LocalDate, BigDecimal> byMonth = new LinkedHashMap<>();
        BigDecimal paid = paidDays == null ? BigDecimal.ZERO : paidDays;

        if (startDate.equals(endDate)) {
            BigDecimal total = totalDays == null ? BigDecimal.ZERO : totalDays;
            BigDecimal unpaid = total.subtract(paid).setScale(2, RoundingMode.HALF_UP);
            if (unpaid.signum() > 0) {
                byMonth.put(startDate.withDayOfMonth(1), unpaid);
            }
            return byMonth;
        }

        int paidWholeDays = paid.setScale(0, RoundingMode.DOWN).intValue();
        int rank = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (isWorkingDay(cursor)) {
                rank++;
                if (rank > paidWholeDays) {
                    LocalDate month = cursor.withDayOfMonth(1);
                    byMonth.merge(month, ONE_WORKING_DAY, BigDecimal::add);
                }
            }
            cursor = cursor.plusDays(1);
        }
        return byMonth;
    }

    private static boolean isWorkingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    // ─────────────────────────────────────────────────────────────────────
    // V118 cross-year quota fix (2026-08-02): a request's days may now need to be attributed to more
    // than one calendar year (§5.4 MATERNITY, up to 98 working days -- see LeaveQuotaYearSplit). The
    // methods below EXTEND this class's existing chronological weekday-counting logic to work per
    // year instead of writing a second, divergent day-counting rule -- the Mon-Fri working-day
    // assumption above is untouched.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Splits a request's total requested days by calendar year, in ascending year order. Single-date
     * range (covers both whole-day and sub-day single-day leave): the whole {@code totalDays} is
     * attributed to that one date's year. Multi-day range (always whole-day): one {@code 1.00} per
     * working day, bucketed by {@code cursor.getYear()} -- mirrors {@link #unpaidWorkingDaysByMonth}'s
     * multi-day branch exactly, just keyed by year instead of by first-of-month date. The returned
     * map's key order is guaranteed ascending (years are only ever appended in the order the cursor
     * walks through them), so a caller may safely read the first entry as the request's earliest
     * (start) year.
     */
    static Map<Integer, BigDecimal> totalDaysByYear(LocalDate startDate, LocalDate endDate, BigDecimal totalDays) {
        Map<Integer, BigDecimal> byYear = new LinkedHashMap<>();
        if (startDate.equals(endDate)) {
            BigDecimal total = totalDays == null ? BigDecimal.ZERO : totalDays;
            byYear.put(startDate.getYear(), total);
            return byYear;
        }

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (isWorkingDay(cursor)) {
                byYear.merge(cursor.getYear(), ONE_WORKING_DAY, BigDecimal::add);
            }
            cursor = cursor.plusDays(1);
        }
        return byYear;
    }

    /**
     * Clips {@code [startDate, endDate]} to the portion that falls within calendar {@code year}, as a
     * two-element {@code [clippedStart, clippedEnd]} array, or {@code null} if the range does not
     * intersect that year at all. Lets a caller holding a per-year (paidDays, totalDays) attribution
     * (see {@link LeaveQuotaYearSplit}) reuse {@link #unpaidWorkingDaysByMonth} UNMODIFIED for just
     * that year's slice of a request that spans a calendar-year boundary.
     */
    static LocalDate[] clipToYear(LocalDate startDate, LocalDate endDate, int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        LocalDate clippedStart = startDate.isAfter(yearStart) ? startDate : yearStart;
        LocalDate clippedEnd = endDate.isBefore(yearEnd) ? endDate : yearEnd;
        if (clippedStart.isAfter(clippedEnd)) {
            return null;
        }
        return new LocalDate[] {clippedStart, clippedEnd};
    }

    /**
     * Cross-year-aware composition of {@link #unpaidWorkingDaysByMonth}: calls it once per {@code
     * perYear} entry, using that year's OWN (paidDays, totalDays) attribution and that year's clipped
     * date sub-range (via {@link #clipToYear}), then merges the resulting per-month maps.
     *
     * <p>This is REQUIRED, not optional, once a single request can be granted paid days
     * independently per calendar year (see {@link LeaveQuotaYearSplit}'s Javadoc for why: a request
     * can legitimately be "paid, then unpaid" in year A and "paid" again from the start of year B --
     * a single whole-request chronological-rank threshold, which is what feeding the PARENT's
     * aggregate paidDays/totalDays into {@link #unpaidWorkingDaysByMonth} would do, cannot represent
     * that "paid resets at the boundary" shape and would misattribute which calendar days are unpaid
     * whenever an earlier year's paid cap is exhausted while a later year's is not). Used by {@code
     * LeaveService#cancel}'s cancel-after-close reversal, which (unlike the single-month payroll
     * query in {@code LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}) needs the full
     * multi-month, multi-year picture in one call.
     */
    static Map<LocalDate, BigDecimal> unpaidWorkingDaysByMonthAcrossYears(
            LocalDate startDate, LocalDate endDate, List<LeaveQuotaYearSplit> perYear) {
        Map<LocalDate, BigDecimal> combined = new LinkedHashMap<>();
        for (LeaveQuotaYearSplit year : perYear) {
            LocalDate[] clipped = clipToYear(startDate, endDate, year.quotaYear());
            if (clipped == null) {
                continue;
            }
            Map<LocalDate, BigDecimal> yearly =
                unpaidWorkingDaysByMonth(clipped[0], clipped[1], year.paidDays(), year.totalDays());
            yearly.forEach((month, days) -> combined.merge(month, days, BigDecimal::add));
        }
        return combined;
    }
}
