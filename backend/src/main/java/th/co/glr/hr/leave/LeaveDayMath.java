package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
}
