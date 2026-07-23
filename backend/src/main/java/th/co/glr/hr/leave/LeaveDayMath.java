package th.co.glr.hr.leave;

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
 */
final class LeaveDayMath {
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
     * The working days in [startDate, endDate] beyond the first {@code paidDays} chronological
     * working days -- i.e. the unpaid ones -- bucketed by calendar month (keyed by the first-of-month
     * date). A leave spanning two calendar months splits correctly: each unpaid working day is
     * attributed to the month it actually falls in, not to the month of {@code startDate}.
     */
    static Map<LocalDate, Integer> unpaidWorkingDaysByMonth(LocalDate startDate, LocalDate endDate, int paidDays) {
        Map<LocalDate, Integer> byMonth = new LinkedHashMap<>();
        int rank = 0;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (isWorkingDay(cursor)) {
                rank++;
                if (rank > paidDays) {
                    LocalDate month = cursor.withDayOfMonth(1);
                    byMonth.merge(month, 1, Integer::sum);
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
