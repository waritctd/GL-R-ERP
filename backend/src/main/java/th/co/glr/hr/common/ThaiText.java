package th.co.glr.hr.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;

/**
 * Shared Thai-reader-facing formatting for notification email copy (see
 * {@code th.co.glr.hr.notification.NotificationEmailService}) -- dates, date ranges, money and
 * durations rendered the way a Thai reader expects, not as raw Java {@code toString()} output.
 *
 * <p><b>Duplication this deliberately does NOT clean up:</b> the {@code d MONTH (year+543)} Thai-date
 * algorithm {@link #date(LocalDate)} implements already exists, byte-for-byte identical, in three PDF
 * renderers -- {@code th.co.glr.hr.ticket.QuotationRenderer#thaiDate} (~line 560), {@code
 * th.co.glr.hr.deposit.DepositNoticeRenderer#thaiDate} (~line 214) and {@code
 * th.co.glr.hr.deposit.RemainingInvoiceRenderer#thaiDate} (~line 148). Those three render PDFs/XLS
 * documents and carry their own tests; unifying them with this class is left as a follow-up, not done
 * as a side effect of this notification-copy fix. This class's {@link #date(LocalDate)} matches their
 * output exactly.
 *
 * <p>A {@code final} utility class of static methods only -- never instantiated.
 */
public final class ThaiText {
    private static final String[] THAI_MONTHS = {
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    };
    /** U+2013 EN DASH -- see {@link #dateRange(LocalDate, LocalDate)}'s Javadoc for spacing rules. */
    private static final String EN_DASH = "–";

    private ThaiText() {
    }

    /**
     * {@code d MONTH (year+543)}, e.g. 9 สิงหาคม 2569 -- no leading zero on the day. Matches {@code
     * QuotationRenderer#thaiDate}/{@code DepositNoticeRenderer#thaiDate}/{@code
     * RemainingInvoiceRenderer#thaiDate} exactly (see this class's own Javadoc).
     *
     * @return {@code ""} for a {@code null} date, mirroring the null-guard the three PDF renderers
     *     above already use ahead of this same formula.
     */
    public static String date(LocalDate d) {
        if (d == null) {
            return "";
        }
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    /**
     * A Thai-reader date range for a notification body.
     *
     * <ul>
     *   <li>{@code from.equals(to)}, or {@code to == null} -- a single date: {@link #date(LocalDate)}.
     *   <li>Same month <b>and</b> year -- compact form, day range then one month/year:
     *       {@code 9–12 สิงหาคม 2569} (EN DASH, no surrounding spaces).
     *   <li>Otherwise -- full form, each side its own day+month, year stated once at the end:
     *       {@code 30 กรกฎาคม – 2 สิงหาคม 2569} (EN DASH, one space each side). Deliberately verbatim
     *       per the approved copy: the FROM side does not repeat the year even when {@code from} and
     *       {@code to} fall in different years -- this is the same two-way split the approved copy
     *       specifies, not a third cross-year case.
     * </ul>
     *
     * @param from must be non-null -- every caller in this codebase has a required start date.
     */
    public static String dateRange(LocalDate from, LocalDate to) {
        if (to == null || from.equals(to)) {
            return date(from);
        }
        if (from.getYear() == to.getYear() && from.getMonthValue() == to.getMonthValue()) {
            return from.getDayOfMonth() + EN_DASH + date(to);
        }
        return dayMonth(from) + " " + EN_DASH + " " + date(to);
    }

    /** {@code d MONTH}, no year -- the FROM side of a cross-month/cross-year {@link #dateRange}. */
    private static String dayMonth(LocalDate d) {
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1];
    }

    /**
     * {@code MONTH (year+543)}, e.g. สิงหาคม 2569 -- the whole-month counterpart to {@link
     * #date(LocalDate)}, for a report titled by month rather than by day (e.g.
     * {@code AttendanceMonthlySummaryExporter}'s "สรุปเวลาทำงานประจำเดือน" title line). Reuses the
     * same {@link #THAI_MONTHS} table {@link #date(LocalDate)}/{@link #dayMonth(LocalDate)} do, so a
     * future month-name edit cannot land in one and not the other.
     *
     * @return {@code ""} for a {@code null} month, mirroring {@link #date(LocalDate)}'s null-guard.
     */
    public static String monthYear(YearMonth month) {
        if (month == null) {
            return "";
        }
        return THAI_MONTHS[month.getMonthValue() - 1] + " " + (month.getYear() + 543);
    }

    /**
     * Grouped-thousands baht amount, with the {@code .00} dropped for a whole number -- {@code 1,500}
     * for 1500.00, {@code 1,500.50} for 1500.50. Never prepends a currency symbol: the approved copy
     * always follows this with the literal word {@code บาท}.
     *
     * <p>Builds a fresh format string per call rather than a shared {@code DecimalFormat} field --
     * {@code DecimalFormat} is not thread-safe and this class's callers (notification-sending
     * services) are Spring singletons, the same reasoning {@code LorYor01Renderer#money} documents.
     * {@link Locale#US} is pinned explicitly so the "," grouping / "." decimal separators this format
     * relies on do not depend on the JVM's default locale.
     *
     * @return {@code "-"} for a {@code null} amount.
     */
    public static String money(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        boolean whole = scaled.remainder(BigDecimal.ONE).signum() == 0;
        return whole
            ? String.format(Locale.US, "%,d", scaled.longValueExact())
            : String.format(Locale.US, "%,.2f", scaled);
    }

    /**
     * A duration in whole hours plus leftover minutes -- {@code 3 ชม.} for exactly 180 minutes,
     * {@code 3 ชม. 30 นาที} for 210, {@code 45 นาที} for anything under an hour.
     *
     * @return {@code "-"} for zero or a negative minute count.
     */
    public static String hours(int minutes) {
        if (minutes <= 0) {
            return "-";
        }
        int wholeHours = minutes / 60;
        int remainderMinutes = minutes % 60;
        if (wholeHours == 0) {
            return remainderMinutes + " นาที";
        }
        if (remainderMinutes == 0) {
            return wholeHours + " ชม.";
        }
        return wholeHours + " ชม. " + remainderMinutes + " นาที";
    }
}
