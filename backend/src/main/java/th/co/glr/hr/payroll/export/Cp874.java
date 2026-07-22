package th.co.glr.hr.payroll.export;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Byte-level text building for the Thai statutory payroll files (KBank PCT, PND1, SSO สปส.1-10).
 *
 * <p>These bank/government formats are fixed-width in <b>bytes</b> under the legacy Thai code page
 * CP874 (a.k.a. TIS-620 / windows-874), <b>not</b> UTF-8 — one Thai character is one byte. Writing
 * the file as UTF-8 would push every field after a Thai name out of alignment and the bank/agency
 * would reject the file. All padding/truncation here is therefore measured on the CP874-encoded byte
 * array, and the whole file is encoded once, with CRLF line endings (including a trailing CRLF) and
 * no BOM.
 */
public final class Cp874 {
    /** CP874 / TIS-620 / windows-874 — the Thai ANSI code page KBank + RD + SSO expect. */
    public static final Charset CHARSET = Charset.forName("x-windows-874");
    private static final byte SPACE = 0x20;
    private static final byte ZERO = '0';
    private static final byte[] CRLF = {'\r', '\n'};

    private Cp874() {}

    /** Encode a string to CP874 bytes. Characters absent from CP874 become '?' (still one byte). */
    public static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(CHARSET);
    }

    /**
     * Right-pad (text convention): encode to CP874, then pad on the right with ASCII spaces to
     * exactly {@code width} bytes, truncating on the right if longer. Safe to truncate on any byte
     * boundary because every CP874 character is a single byte.
     */
    public static byte[] rpad(String value, int width) {
        return pad(bytes(value), width, SPACE, /* padLeft= */ false, /* truncateLeft= */ false);
    }

    /**
     * Left-pad with '0' (numeric convention): the string must already be the digits to place. Longer
     * than {@code width} is a programming error (an amount/id that would overflow its column) and
     * throws rather than silently corrupting the record.
     */
    public static byte[] zpad(String digits, int width) {
        byte[] raw = bytes(digits);
        if (raw.length > width) {
            throw new IllegalArgumentException(
                "value '" + digits + "' (" + raw.length + " bytes) overflows numeric width " + width);
        }
        return pad(raw, width, ZERO, /* padLeft= */ true, /* truncateLeft= */ false);
    }

    /** Left-pad a non-negative long with '0' to {@code width}. */
    public static byte[] zpad(long value, int width) {
        if (value < 0) {
            throw new IllegalArgumentException("negative value not allowed in fixed-width numeric field: " + value);
        }
        return zpad(Long.toString(value), width);
    }

    /**
     * A baht amount as satang (baht × 100), left-zero-padded to {@code width}. e.g. 145,000.00 →
     * {@code "000000014500000"} at width 15. Used by KBank PCT (amounts carry no decimal point).
     */
    public static byte[] satang(BigDecimal baht, int width) {
        long satang = baht.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return zpad(satang, width);
    }

    /** A baht amount with two explicit decimals, e.g. {@code "150000.00"} (PND1 income/tax fields). */
    public static String decimal2(BigDecimal baht) {
        return baht.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code n} ASCII spaces — for reserved/separator regions. */
    public static byte[] spaces(int n) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, SPACE);
        return out;
    }

    /**
     * Join records into a file: every record followed by CRLF, including the last (the golden KBank
     * and PND1 files both end with a trailing CRLF).
     */
    public static byte[] file(List<byte[]> records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] record : records) {
            out.writeBytes(record);
            out.writeBytes(CRLF);
        }
        return out.toByteArray();
    }

    /** Build one fixed-width record from segments and assert its exact byte length. */
    public static byte[] record(int expectedWidth, byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] segment : segments) {
            out.writeBytes(segment);
        }
        byte[] result = out.toByteArray();
        if (result.length != expectedWidth) {
            throw new IllegalStateException(
                "fixed-width record is " + result.length + " bytes, expected " + expectedWidth);
        }
        return result;
    }

    /** Encode an already-assembled variable-width line (e.g. PND1 pipe-delimited) to CP874 bytes. */
    public static byte[] line(String assembled) {
        return bytes(assembled);
    }

    private static byte[] pad(byte[] raw, int width, byte padByte, boolean padLeft, boolean truncateLeft) {
        if (raw.length == width) {
            return raw;
        }
        if (raw.length > width) {
            byte[] cut = new byte[width];
            System.arraycopy(raw, truncateLeft ? raw.length - width : 0, cut, 0, width);
            return cut;
        }
        byte[] out = new byte[width];
        int padLen = width - raw.length;
        if (padLeft) {
            java.util.Arrays.fill(out, 0, padLen, padByte);
            System.arraycopy(raw, 0, out, padLen, raw.length);
        } else {
            System.arraycopy(raw, 0, out, 0, raw.length);
            java.util.Arrays.fill(out, raw.length, width, padByte);
        }
        return out;
    }
}
