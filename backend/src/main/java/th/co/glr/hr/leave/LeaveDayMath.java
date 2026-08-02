package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 *
 * <p><b>§5.4 MATERNITY calendar-day counting (V119, 2026-08-02):</b> every method below now has a
 * {@link LeaveDayCountBasis}-aware overload alongside its original working-day-only signature,
 * which is UNCHANGED and simply delegates to the new overload with {@code WORKING_DAYS} -- this
 * class's Mon-Fri assumption for every leave type except MATERNITY is untouched. See {@link
 * LeaveDayCountBasis} for the announcement text this implements and {@link LeaveService#submit}/
 * {@link LeaveService#computeTotalDays} for where the basis is selected.
 */
final class LeaveDayMath {
    // Renamed from ONE_WORKING_DAY (V119): this constant is now merged per COUNTED day under
    // EITHER basis (working or calendar), not just a working day -- see the basis-aware overloads
    // below. Purely an internal name; not part of any tested/pinned public shape.
    private static final BigDecimal ONE_DAY = new BigDecimal("1.00");

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
     * §5.4 MATERNITY calendar-day counting (V119): total CALENDAR days (every day, no Mon-Fri
     * filter) in the inclusive range [startDate, endDate]. Trivial ({@code endDate - startDate +
     * 1}), but centralised here (rather than inlined in LeaveService) so every day-counting rule
     * this codebase uses lives in one place. See {@link LeaveDayCountBasis#CALENDAR_DAYS}.
     */
    static int countCalendarDays(LocalDate startDate, LocalDate endDate) {
        return (int) (ChronoUnit.DAYS.between(startDate, endDate) + 1);
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
        return unpaidWorkingDaysByMonth(startDate, endDate, paidDays, totalDays, LeaveDayCountBasis.WORKING_DAYS);
    }

    /**
     * §5.4 MATERNITY calendar-day counting (V119): basis-aware overload of {@link
     * #unpaidWorkingDaysByMonth(LocalDate, LocalDate, BigDecimal, BigDecimal)}. Behaviour for
     * {@link LeaveDayCountBasis#WORKING_DAYS} is BYTE-IDENTICAL to the 4-arg overload above (which
     * now just delegates here) -- nothing about existing working-day counting changes. {@link
     * LeaveDayCountBasis#CALENDAR_DAYS} runs the exact same chronological-rank algorithm, just
     * ranking every day in the range instead of only Mon-Fri ones -- so a weekend or (weekday)
     * holiday falling in the UNPAID tail of the range is bucketed into its month's deduction the
     * same way a working day would be. See LeaveService's decision note on why the payroll
     * deduction path (LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth) uses this same basis
     * as the leave type's quota counting, not always WORKING_DAYS.
     */
    static Map<LocalDate, BigDecimal> unpaidWorkingDaysByMonth(
            LocalDate startDate, LocalDate endDate, BigDecimal paidDays, BigDecimal totalDays,
            LeaveDayCountBasis basis) {
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
            if (basis.counts(cursor)) {
                rank++;
                if (rank > paidWholeDays) {
                    LocalDate month = cursor.withDayOfMonth(1);
                    byMonth.merge(month, ONE_DAY, BigDecimal::add);
                }
            }
            cursor = cursor.plusDays(1);
        }
        return byMonth;
    }

    static boolean isWorkingDay(LocalDate date) {
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
        return totalDaysByYear(startDate, endDate, totalDays, LeaveDayCountBasis.WORKING_DAYS);
    }

    /**
     * §5.4 MATERNITY calendar-day counting (V119): basis-aware overload. {@link
     * LeaveDayCountBasis#WORKING_DAYS} is byte-identical to the 3-arg overload above (which now
     * delegates here); {@link LeaveDayCountBasis#CALENDAR_DAYS} buckets every day in the range by
     * year, not just Mon-Fri ones -- required so a cross-year MATERNITY request (§5.4, up to 98
     * CALENDAR days) splits its calendar days per year, matching how {@link LeaveService#submit}
     * now computes {@code totalDays} for a CALENDAR_DAYS type in the first place.
     */
    static Map<Integer, BigDecimal> totalDaysByYear(
            LocalDate startDate, LocalDate endDate, BigDecimal totalDays, LeaveDayCountBasis basis) {
        Map<Integer, BigDecimal> byYear = new LinkedHashMap<>();
        if (startDate.equals(endDate)) {
            BigDecimal total = totalDays == null ? BigDecimal.ZERO : totalDays;
            byYear.put(startDate.getYear(), total);
            return byYear;
        }

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (basis.counts(cursor)) {
                byYear.merge(cursor.getYear(), ONE_DAY, BigDecimal::add);
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
        return unpaidWorkingDaysByMonthAcrossYears(startDate, endDate, perYear, LeaveDayCountBasis.WORKING_DAYS);
    }

    /**
     * §5.4 MATERNITY calendar-day counting (V119): basis-aware overload of {@link
     * #unpaidWorkingDaysByMonthAcrossYears(LocalDate, LocalDate, List)}. The SAME basis applies to
     * every year in {@code perYear} -- a leave TYPE's counting basis does not change from one
     * calendar year to the next, only the request's date range does. {@link
     * LeaveDayCountBasis#WORKING_DAYS} is byte-identical to the 3-arg overload above (which now
     * delegates here).
     */
    static Map<LocalDate, BigDecimal> unpaidWorkingDaysByMonthAcrossYears(
            LocalDate startDate, LocalDate endDate, List<LeaveQuotaYearSplit> perYear, LeaveDayCountBasis basis) {
        Map<LocalDate, BigDecimal> combined = new LinkedHashMap<>();
        for (LeaveQuotaYearSplit year : perYear) {
            LocalDate[] clipped = clipToYear(startDate, endDate, year.quotaYear());
            if (clipped == null) {
                continue;
            }
            Map<LocalDate, BigDecimal> yearly =
                unpaidWorkingDaysByMonth(clipped[0], clipped[1], year.paidDays(), year.totalDays(), basis);
            yearly.forEach((month, days) -> combined.merge(month, days, BigDecimal::add));
        }
        return combined;
    }
}
