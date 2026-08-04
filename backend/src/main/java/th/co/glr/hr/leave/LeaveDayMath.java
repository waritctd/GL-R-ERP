package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;

/**
 * Leave -&gt; payroll unpaid-day deduction (2026-07-23): shared weekday-counting math used both to
 * attribute a leave request's unpaid days to the payroll month(s) it falls in ({@link
 * LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}) and to work out how many already-deducted
 * days a cancelled leave owes back when it is cancelled after payroll has processed ({@link
 * LeaveService#cancel}).
 *
 * <p><b>Schedule/holiday-aware working-day counting (2026-08-03):</b> "is this date a working day"
 * used to be a single hardcoded Mon-Fri check ({@link #isWorkingDay(LocalDate)}), which was wrong in
 * two directions the class's own javadoc used to flag as known gaps: it treated every Saturday as a
 * non-working day even for six-day departments (คลังสินค้า/แม่บ้าน, V115/V117/V121's {@code OPS_6D}),
 * understating their unpaid-day counts; and it had no holiday calendar, so a public holiday inside a
 * leave range counted as a working day, over-counting (and over-deducting) leave across it. Every
 * method below that decides "does this date count" now takes a {@code Predicate<LocalDate>
 * isWorkingDay} built by the caller via {@link #workingDayPredicate} -- combining the employee's
 * resolved {@code WorkSchedule} (V115's {@code TieredWorkScheduleResolver}: EMPLOYEE > DEPARTMENT >
 * DIVISION > company default, effective-dated) with {@code hr.holiday} (V115's
 * {@code HolidayCalendar}). This class stays free of direct DB access: callers batch-load the
 * range's holidays once (via {@link th.co.glr.hr.attendance.schedule.HolidayCalendar#holidaysBetween})
 * before building the predicate, so a long request (e.g. an uncapped MILITARY leave, V120) never
 * costs one query per day -- the resolver itself is then called once per date inside the predicate,
 * which is a pure in-memory lookup ({@code TieredWorkScheduleResolver} caches its whole assignment
 * table for the bean's lifetime), not a query. Resolving per-date (rather than once for the whole
 * request) is deliberate: it is the only way to get a mid-request schedule reassignment right, and
 * costs nothing extra given the resolver is already O(1) DB-free. {@link LeaveDayCountBasis#CALENDAR_DAYS}
 * (MATERNITY) never invokes the predicate at all -- see that enum's javadoc for why this must not
 * change.
 *
 * <p>{@code paidDays} is always treated as consumed from the request's earliest working
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
 * <p><b>§5.4 MATERNITY calendar-day counting (V119, 2026-08-02):</b> every method below has a
 * {@link LeaveDayCountBasis}-aware overload alongside its original no-basis signature, which is
 * UNCHANGED and simply delegates to the new overload with {@code WORKING_DAYS}. See {@link
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

    /**
     * Builds the schedule/holiday-aware {@code isWorkingDay} predicate every basis-aware method
     * below takes (2026-08-03). A date counts as a working day when it is NOT in {@code holidays}
     * AND the employee's {@code WorkSchedule}, resolved for that specific date via {@code
     * scheduleResolver}, has it as a workday -- i.e. holiday beats schedule (a holiday is never a
     * working day regardless of a six-day schedule), mirroring {@code AttendanceDailyService}'s own
     * {@code schedule.isWorkday(date) && !holiday} rule for the same two collaborators.
     *
     * <p>{@code holidays} MUST be pre-loaded by the caller (via {@code
     * HolidayCalendar#holidaysBetween}) for the whole date range this predicate will be tested
     * against -- one query, not one per day. {@code scheduleResolver.resolve} is called once per
     * tested date (not batched): that is intentional, not an oversight -- see this class's javadoc
     * for why resolving per-date is both correct (respects a schedule reassignment mid-request) and
     * free of any additional query ({@code TieredWorkScheduleResolver} caches its whole assignment
     * table for the bean's lifetime).
     */
    static Predicate<LocalDate> workingDayPredicate(
            WorkScheduleResolver scheduleResolver, long employeeId, Long divisionId, Long departmentId,
            Set<LocalDate> holidays) {
        return date -> !holidays.contains(date)
            && scheduleResolver.resolve(employeeId, divisionId, departmentId, date).isWorkday(date);
    }

    /**
     * Total working days in the inclusive range [startDate, endDate], per the caller-supplied
     * {@code isWorkingDay} predicate (see {@link #workingDayPredicate}).
     */
    static int countWorkingDays(LocalDate startDate, LocalDate endDate, Predicate<LocalDate> isWorkingDay) {
        int days = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (isWorkingDay.test(cursor)) {
                days++;
            }
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    /**
     * Legacy/test convenience overload: Mon-Fri only, no holiday calendar -- the hardcoded
     * assumption this class used before it became schedule/holiday-aware (2026-08-03). Every
     * production call site now goes through the 3-arg overload above with a real predicate (see
     * {@link LeaveRepository#workingDayPredicate}); this overload survives only because
     * {@code LeaveDayMathTest} pins the pure Mon-Fri math directly, with no schedule/holiday fixture
     * to build.
     */
    static int countWorkingDays(LocalDate startDate, LocalDate endDate) {
        return countWorkingDays(startDate, endDate, LeaveDayMath::isWorkingDay);
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
     * #unpaidWorkingDaysByMonth(LocalDate, LocalDate, BigDecimal, BigDecimal)}, using the legacy
     * Mon-Fri/no-holiday predicate -- see the 6-arg overload below (now the real implementation)
     * for the schedule/holiday-aware version every production call site uses.
     */
    static Map<LocalDate, BigDecimal> unpaidWorkingDaysByMonth(
            LocalDate startDate, LocalDate endDate, BigDecimal paidDays, BigDecimal totalDays,
            LeaveDayCountBasis basis) {
        return unpaidWorkingDaysByMonth(startDate, endDate, paidDays, totalDays, basis, LeaveDayMath::isWorkingDay);
    }

    /**
     * Schedule/holiday-aware canonical implementation (2026-08-03). {@link
     * LeaveDayCountBasis#WORKING_DAYS} ranks a date only when {@code isWorkingDay} (see {@link
     * #workingDayPredicate}) says it counts for THIS employee on THIS date -- a Saturday counts for
     * a six-day-schedule employee and not for a five-day one on the identical calendar date, and a
     * seeded {@code hr.holiday} row removes a date from counting for everyone regardless of
     * schedule. {@link LeaveDayCountBasis#CALENDAR_DAYS} (MATERNITY) is UNCHANGED by this overload
     * -- it ranks every day exactly as before, never consulting {@code isWorkingDay} at all (see
     * that enum's javadoc for why this must stay true).
     */
    static Map<LocalDate, BigDecimal> unpaidWorkingDaysByMonth(
            LocalDate startDate, LocalDate endDate, BigDecimal paidDays, BigDecimal totalDays,
            LeaveDayCountBasis basis, Predicate<LocalDate> isWorkingDay) {
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
            if (basis.counts(cursor, isWorkingDay)) {
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

    /**
     * The hardcoded Mon-Fri assumption this class carried before it became schedule/holiday-aware
     * (2026-08-03). Kept only as the default predicate for the legacy overloads above (and for
     * {@code LeaveDayMathTest}'s pure-math coverage) -- every production call site now builds a
     * real predicate via {@link #workingDayPredicate} instead. Do not add a new production call
     * site against this method; it does not know about six-day schedules or {@code hr.holiday}.
     */
    static boolean isWorkingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /**
     * §5.1 SICK certificate filing-window (V124): returns the date reached by walking forward from
     * {@code from}, counting only WORKING days ({@link #isWorkingDay}, Mon-Fri -- same known
     * no-holiday-calendar limitation as the rest of this class, see the class Javadoc and CLAUDE.md;
     * NOT fixed here), until {@code workingDays} of them have been counted. {@code from} itself is
     * NEVER counted, even when it is itself a working day -- "file within N working days of X" reads
     * as N days AFTER X, not X itself as day one (mirrors the ordinary "N business days from today"
     * reading a filing deadline states). {@code workingDays <= 0} returns {@code from} unchanged
     * (defensive; every caller today passes a positive filing-window value, enforced by {@code
     * chk_leave_type_certificate_filing_window_positive}).
     */
    static LocalDate addWorkingDays(LocalDate from, int workingDays) {
        LocalDate date = from;
        int counted = 0;
        while (counted < workingDays) {
            date = date.plusDays(1);
            if (isWorkingDay(date)) {
                counted++;
            }
        }
        return date;
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
     * §5.4 MATERNITY calendar-day counting (V119): basis-aware overload, using the legacy Mon-Fri/
     * no-holiday predicate -- see the 5-arg overload below (now the real implementation) for the
     * schedule/holiday-aware version every production call site uses.
     */
    static Map<Integer, BigDecimal> totalDaysByYear(
            LocalDate startDate, LocalDate endDate, BigDecimal totalDays, LeaveDayCountBasis basis) {
        return totalDaysByYear(startDate, endDate, totalDays, basis, LeaveDayMath::isWorkingDay);
    }

    /**
     * Schedule/holiday-aware canonical implementation (2026-08-03) -- see {@link
     * #unpaidWorkingDaysByMonth(LocalDate, LocalDate, BigDecimal, BigDecimal, LeaveDayCountBasis, Predicate)}
     * for the identical {@code basis}/{@code isWorkingDay} interaction, just bucketed by year instead
     * of by month.
     */
    static Map<Integer, BigDecimal> totalDaysByYear(
            LocalDate startDate, LocalDate endDate, BigDecimal totalDays, LeaveDayCountBasis basis,
            Predicate<LocalDate> isWorkingDay) {
        Map<Integer, BigDecimal> byYear = new LinkedHashMap<>();
        if (startDate.equals(endDate)) {
            BigDecimal total = totalDays == null ? BigDecimal.ZERO : totalDays;
            byYear.put(startDate.getYear(), total);
            return byYear;
        }

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (basis.counts(cursor, isWorkingDay)) {
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
     * §5.4 MATERNITY calendar-day counting (V119): basis-aware overload, using the legacy Mon-Fri/
     * no-holiday predicate -- see the 5-arg overload below (now the real implementation) for the
     * schedule/holiday-aware version every production call site uses.
     */
    static Map<LocalDate, BigDecimal> unpaidWorkingDaysByMonthAcrossYears(
            LocalDate startDate, LocalDate endDate, List<LeaveQuotaYearSplit> perYear, LeaveDayCountBasis basis) {
        return unpaidWorkingDaysByMonthAcrossYears(startDate, endDate, perYear, basis, LeaveDayMath::isWorkingDay);
    }

    /**
     * Schedule/holiday-aware canonical implementation (2026-08-03). The SAME basis AND the SAME
     * {@code isWorkingDay} predicate apply to every year in {@code perYear} -- a leave TYPE's
     * counting basis (and an employee's resolved schedule/holiday awareness) does not change from
     * one calendar year to the next, only the request's date range does.
     */
    static Map<LocalDate, BigDecimal> unpaidWorkingDaysByMonthAcrossYears(
            LocalDate startDate, LocalDate endDate, List<LeaveQuotaYearSplit> perYear, LeaveDayCountBasis basis,
            Predicate<LocalDate> isWorkingDay) {
        Map<LocalDate, BigDecimal> combined = new LinkedHashMap<>();
        for (LeaveQuotaYearSplit year : perYear) {
            LocalDate[] clipped = clipToYear(startDate, endDate, year.quotaYear());
            if (clipped == null) {
                continue;
            }
            Map<LocalDate, BigDecimal> yearly = unpaidWorkingDaysByMonth(
                clipped[0], clipped[1], year.paidDays(), year.totalDays(), basis, isWorkingDay);
            yearly.forEach((month, days) -> combined.merge(month, days, BigDecimal::add));
        }
        return combined;
    }
}
