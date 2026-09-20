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

    // ── Thai baht-text (GLA-99 step 3, ใบวางบิล) ──────────────────────────────────────────────

    private static final String[] THAI_DIGITS = {
        "ศูนย์", "หนึ่ง", "สอง", "สาม", "สี่", "ห้า", "หก", "เจ็ด", "แปด", "เก้า"
    };
    // Index = place within a 6-digit group: 0=หน่วย (ones), 1=สิบ, 2=ร้อย, 3=พัน, 4=หมื่น, 5=แสน.
    private static final String[] THAI_PLACES = { "", "สิบ", "ร้อย", "พัน", "หมื่น", "แสน" };

    /**
     * The amount in Thai baht-text, e.g. {@code หนึ่งแสนสองหมื่นหกพันเก้าร้อยเจ็ดสิบเจ็ดบาทยี่สิบสตางค์}
     * for 126,977.20 or {@code ศูนย์บาทถ้วน} for zero -- the string a ใบวางบิล (billing note, GLA-99
     * step 3) prints under its total, matching the style the owner's own reference form
     * ({@code ฟอร์มใบวางบิล.xls}) reads via its {@code _xlfn.BAHTTEXT} formula cell. Computed in Java
     * rather than left as a spreadsheet formula so the printed text is correct regardless of which
     * program later opens/recalculates the file -- the same reasoning every other renderer in this
     * codebase already applies to computed display text (e.g. {@link #date(LocalDate)} itself).
     *
     * <p>Two irregular readings this implements: the ones digit reads {@code เอ็ด} instead of
     * {@code หนึ่ง} whenever the number being read is not simply {@code 1} on its own (11 =
     * {@code สิบเอ็ด}, 101 = {@code หนึ่งร้อยเอ็ด}, 1,000,001 = {@code หนึ่งล้านเอ็ด}, 11,000,000 =
     * {@code สิบเอ็ดล้าน} -- decided by whether anything has already been written before that final
     * digit is reached, checked once globally so it is correct both WITHIN a six-digit group and
     * ACROSS a ล้าน group boundary -- F1's own fix, GLA-99 step 3 round 1 review: the ones-digit
     * check used to also require being in the number's OWN final group, so a non-final group's own
     * ones digit -- e.g. the "11" of 11,000,000 -- always read หนึ่ง instead of เอ็ด); the tens
     * digit drops its own {@code หนึ่ง} prefix ({@code สิบ}, never {@code หนึ่งสิบ}) and reads
     * {@code ยี่สิบ} instead of {@code สองสิบ}. Magnitudes at or above one million are chunked into
     * groups of six digits, each read the same way and followed by AS MANY {@code ล้าน} as the
     * group's own magnitude level above the base group calls for (one for 10^6..10^11, two for
     * 10^12..10^17, and so on -- also fixed by F1: a group two or more levels up used to get only
     * one {@code ล้าน} regardless, dropping one whenever an intervening group was entirely zero,
     * e.g. 1,000,000,000,000 read {@code หนึ่งล้าน} instead of {@code หนึ่งล้านล้าน}) -- the standard
     * Thai reading for large numbers (never expected in practice for a billing note, but not
     * artificially capped either).
     *
     * @return {@code "-"} for a {@code null} amount. A negative amount is read with a leading
     *     {@code ลบ}, though a billing note's own total is never negative in practice.
     */
    public static String bahtText(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        BigDecimal scaled = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long wholeBaht = scaled.longValue();
        int satang = scaled.subtract(BigDecimal.valueOf(wholeBaht))
            .movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();

        StringBuilder sb = new StringBuilder();
        if (amount.signum() < 0) {
            sb.append("ลบ");
        }
        sb.append(readNumber(wholeBaht)).append("บาท");
        if (satang == 0) {
            sb.append("ถ้วน");
        } else {
            // Satang is its own two-digit group (0-99) -- the ones-digit เอ็ด rule still applies
            // relative to satang's OWN reading (e.g. 21 สตางค์ = ยี่สิบเอ็ดสตางค์), independent of
            // whatever came before "บาท".
            sb.append(readNumber(satang)).append("สตางค์");
        }
        return sb.toString();
    }

    /** Reads a non-negative whole number, chunked into groups of six digits. Each group above the
     * base (rightmost) one is followed by AS MANY {@code ล้าน} as its own magnitude level calls for
     * -- {@code numChunks - 1 - chunk} of them, so a group representing 10^12 (two levels above the
     * base) gets {@code ล้านล้าน}, not one {@code ล้าน} regardless of how far above the base it
     * sits (F1's own fix -- see {@link #bahtText}'s own Javadoc) -- shared by the baht and satang
     * halves of {@link #bahtText}. */
    private static String readNumber(long value) {
        if (value == 0) {
            return THAI_DIGITS[0];
        }
        String digits = Long.toString(value);
        int totalLen = digits.length();
        int numChunks = (totalLen + 5) / 6;
        // Left-pad to a whole number of 6-digit chunks so each chunk can be sliced uniformly.
        String padded = "0".repeat(numChunks * 6 - totalLen) + digits;

        StringBuilder sb = new StringBuilder();
        for (int chunk = 0; chunk < numChunks; chunk++) {
            String group = padded.substring(chunk * 6, chunk * 6 + 6);
            appendGroup(sb, group);
            if (group.chars().anyMatch(ch -> ch != '0')) {
                // 0 for the base (rightmost) group -- "ล้าน".repeat(0) is "", so this is a no-op
                // there without needing a separate isLastChunk guard.
                sb.append("ล้าน".repeat(numChunks - 1 - chunk));
            }
        }
        return sb.toString();
    }

    /** Appends one 6-digit group's reading onto {@code sb}. The เอ็ด rule ({@link #bahtText}'s own
     * Javadoc) checks "has anything already been written [anywhere in {@code sb} so far]" for the
     * group's OWN ones digit -- correct both within this group (e.g. the "1" after "สิบ" in
     * 11's own group) and across a ล้าน boundary (e.g. the "1" after a preceding group's own
     * "...ล้าน", as in 1,000,001), because {@code sb} already carries everything read before this
     * point either way. F1's own fix: this used to only apply within the number's OWN FINAL group,
     * so a non-final group's ones digit (e.g. the second "1" of 11,000,000's own "000011" group)
     * always read หนึ่ง instead of เอ็ด. */
    private static void appendGroup(StringBuilder sb, String group) {
        for (int i = 0; i < 6; i++) {
            int digit = group.charAt(i) - '0';
            if (digit == 0) {
                continue;
            }
            int place = 5 - i; // 0=ones .. 5=แสน, matching THAI_PLACES
            if (place == 0) {
                sb.append(digit == 1 && sb.length() > 0 ? "เอ็ด" : THAI_DIGITS[digit]);
            } else if (place == 1) {
                // Tens place: never "หนึ่งสิบ", and "2" reads "ยี่สิบ" not "สองสิบ".
                if (digit == 1) {
                    sb.append(THAI_PLACES[1]);
                } else if (digit == 2) {
                    sb.append("ยี่").append(THAI_PLACES[1]);
                } else {
                    sb.append(THAI_DIGITS[digit]).append(THAI_PLACES[1]);
                }
            } else {
                sb.append(THAI_DIGITS[digit]).append(THAI_PLACES[place]);
            }
        }
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
