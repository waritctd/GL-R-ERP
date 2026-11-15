package th.co.glr.hr.salesboard.pipelineimport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cell → value rules for the one-time pipeline import (plan 2026-11-15 Task 4.1). Pure: no database, no POI. Every
 * parser returns either a value, an error message in Thai (the row is then skipped and the message is shown in the
 * dry run), or neither (blank, optional cell).
 */
final class PipelineFields {

    /** A parsed cell: {@code value} when it parsed, {@code error} when it did not, both null for a blank cell. */
    record Parsed<T>(T value, String error) {
        static <T> Parsed<T> ok(T value) {
            return new Parsed<>(value, null);
        }

        static <T> Parsed<T> fail(String error) {
            return new Parsed<>(null, error);
        }

        static <T> Parsed<T> blank() {
            return new Parsed<>(null, null);
        }
    }

    /** The reps' workbook wording (spec §7.1) → code, keyed with all whitespace removed. S4 has no wording: it
     * appears in no rep workbook. */
    private static final Map<String, String> STATUS_TEXT = Map.of(
        "อยู่ระหว่างเสนอสินค้า", "S1",
        "ผู้ออกแบบตกลงสเปคให้", "S2",
        "ทำใบเสนอราคาแล้ว", "S3",
        "รอผลประมูลรอผู้ซื้อ", "S5",
        "ทราบผู้ซื้อแล้ว", "S6",
        "ได้รับมัดจำแล้ว", "S7",
        "อยู่ระหว่างรอส่งสินค้า", "S8",
        "ส่งสินค้าครบแล้ว", "S9");

    private static final Pattern CODE = Pattern.compile("^[sS]0*(\\d{1,2})(?!\\d)(.*)$");

    static final String S4_REFUSAL = "S4 ไม่มีในระบบ เลือก S3 หรือ S5";

    /** "S3", "s3", "S03", "S3 ทำใบเสนอราคาแล้ว", or the long Thai text alone → "S3". */
    static Parsed<String> repCode(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            return Parsed.fail("ไม่มีสถานะ (S1–S9)");
        }
        Matcher m = CODE.matcher(text);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            if (n == 4) {
                return Parsed.fail(S4_REFUSAL);
            }
            if (n >= 1 && n <= 9) {
                return Parsed.ok("S" + n);
            }
            return Parsed.fail("สถานะ '" + text + "' ไม่มีในระบบ — ใช้ S1–S9 (ไม่มี S4)");
        }
        String code = STATUS_TEXT.get(text.replaceAll("\\s+", ""));
        if (code != null) {
            return Parsed.ok(code);
        }
        return Parsed.fail("สถานะ '" + text + "' ไม่มีในระบบ — ใช้ S1–S9 (ไม่มี S4)");
    }

    private static final Pattern THB_MARKERS = Pattern.compile("(?i)฿|thb|บาท");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final BigDecimal MAX_VALUE = new BigDecimal("999999999999.99");

    /** Deal value in THB. A numeric spreadsheet cell is taken as-is; text may carry thousands separators and a THB
     * marker (฿ / THB / บาท). Anything else — another currency, words, a negative — is an error. */
    static Parsed<BigDecimal> value(String text, Double numeric) {
        BigDecimal v;
        if (numeric != null) {
            v = BigDecimal.valueOf(numeric);
        } else {
            String t = text == null ? "" : text.strip();
            if (t.isEmpty()) {
                return Parsed.blank();
            }
            String digits = THB_MARKERS.matcher(t).replaceAll("").replace(",", "").replaceAll("\\s+", "");
            if (!PLAIN_NUMBER.matcher(digits).matches()) {
                return Parsed.fail("มูลค่า '" + t + "' ต้องเป็นตัวเลขเงินบาท เช่น 1,240,000");
            }
            v = new BigDecimal(digits);
        }
        if (v.signum() < 0 || v.compareTo(MAX_VALUE) > 0) {
            return Parsed.fail("มูลค่า '" + (text == null ? v.toPlainString() : text.strip()) + "' ไม่ถูกต้อง");
        }
        return Parsed.ok(v.setScale(2, RoundingMode.HALF_UP));
    }

    /** Win probability 0–100. A percent-formatted cell holds a fraction (0.6 = 60%). Decimals round half-up. */
    static Parsed<Integer> probability(String text, Double numeric, boolean percentFormatted) {
        BigDecimal v;
        String shown = text == null ? "" : text.strip();
        if (numeric != null) {
            v = BigDecimal.valueOf(numeric);
            if (percentFormatted) {
                v = v.movePointRight(2);
            }
        } else {
            if (shown.isEmpty()) {
                return Parsed.blank();
            }
            String digits = shown.replace("%", "").replaceAll("\\s+", "");
            if (!digits.matches("^-?\\d+(\\.\\d+)?$")) {
                return Parsed.fail("โอกาสขาย '" + shown + "' ต้องเป็นตัวเลข 0–100");
            }
            v = new BigDecimal(digits);
        }
        int rounded = v.setScale(0, RoundingMode.HALF_UP).intValueExact();
        if (rounded < 0 || rounded > 100) {
            return Parsed.fail("โอกาสขาย '" + (shown.isEmpty() ? v.toPlainString() : shown) + "' ต้องอยู่ระหว่าง 0–100");
        }
        return Parsed.ok(rounded);
    }

    private static final Pattern YEAR_FIRST = Pattern.compile("^(\\d{4})\\s*[-/ ]?\\s*Q([1-9])$");
    private static final Pattern QUARTER_FIRST = Pattern.compile("^Q([1-9])\\s*[-/ ]?\\s*(\\d{2}|\\d{4})$");

    /**
     * Expected close quarter → the V209 'YYYY-Qn' shape (Gregorian). Accepts 2026-Q4, 2026Q4, Q4/2026, Q4 26 and the
     * reps' Buddhist-era shorthand Q1/70 (= 2570 BE = 2027). Two-digit years ≥ 50 are read as BE 25xx, lower ones as
     * 20xx; four-digit years ≥ 2400 are BE. A date cell gives its own quarter.
     */
    static Parsed<String> quarter(String text, LocalDate date) {
        if (date != null) {
            return Parsed.ok(date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1));
        }
        String t = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return Parsed.blank();
        }
        Integer year = null;
        Integer q = null;
        Matcher a = YEAR_FIRST.matcher(t);
        Matcher b = QUARTER_FIRST.matcher(t);
        if (a.matches()) {
            year = Integer.valueOf(a.group(1));
            q = Integer.valueOf(a.group(2));
        } else if (b.matches()) {
            q = Integer.valueOf(b.group(1));
            String y = b.group(2);
            int n = Integer.parseInt(y);
            year = y.length() == 4 ? n : (n >= 50 ? 2500 + n : 2000 + n);
        }
        if (year == null || q < 1 || q > 4) {
            return Parsed.fail("ช่วงเวลา '" + text.strip() + "' ไม่ถูกต้อง — ใช้รูปแบบ 2026-Q4 หรือ Q4/69");
        }
        if (year >= 2400) {
            year -= 543;
        }
        if (year < 2000 || year > 2100) {
            return Parsed.fail("ช่วงเวลา '" + text.strip() + "' ไม่ถูกต้อง — ใช้รูปแบบ 2026-Q4 หรือ Q4/69");
        }
        return Parsed.ok(year + "-Q" + q);
    }

    /** Lower-case, trimmed, internal whitespace collapsed to one space — the key for matching a project title.
     * {@link PipelineImportRepository} restates the same fold in SQL. */
    static String normalise(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Trimmed text, or null when blank. */
    static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private PipelineFields() {}
}
