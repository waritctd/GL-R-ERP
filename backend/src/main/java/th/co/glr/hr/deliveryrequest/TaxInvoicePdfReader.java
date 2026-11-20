package th.co.glr.hr.deliveryrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads the number, date and total off an issued tax invoice (an Express print-out, a text PDF) so the issuer only checks
 * them. Express prints Latin digits and document numbers, which extract reliably even when Thai glyphs do not; a scanned
 * PDF reads nothing and the issuer types the three values. The file is untrusted: only the first {@link #MAX_PAGES} pages
 * are read with a bounded stream cache (a few MB of heap, a capped scratch file, no external resources), the work runs on
 * a small bounded pool with a time limit, and every failure (an Exception) is a null, never an exception. An Error
 * (out of memory) is not swallowed in the worker; the caller of {@link #read} gets a null for it.
 */
public final class TaxInvoicePdfReader {
    static final int MAX_PAGES = 3;
    static final int MAX_TEXT_CHARS = 200_000;
    static final long TIME_LIMIT_SECONDS = 5;
    /** Stream data past this much heap spills to a scratch file; past the scratch cap the read fails (a null). */
    static final long MAX_HEAP_BYTES = 4L * 1024 * 1024;
    static final long MAX_SCRATCH_BYTES = 64L * 1024 * 1024;
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), r -> { Thread t = new Thread(r, "tax-invoice-pdf"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.AbortPolicy());

    private static final Pattern NUMBER = Pattern.compile("(?<![A-Z0-9])[A-Z]{2,3}\\d{4}-\\d{3,5}(?!\\d)");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,3}(?:,\\d{3})*\\.\\d{2}");
    /** The grand-total labels; a bare "ยอดรวม" / "จำนวนเงินรวม" is also printed above the VAT line, so it is only a weak match. */
    private static final Pattern STRONG_TOTAL = Pattern.compile("รวมทั้งสิ้น|จำนวนเงินรวมทั้งสิ้น|Grand\\s*Total", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEAK_TOTAL = Pattern.compile("ยอดรวม|จำนวนเงินรวม", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_LABEL = Pattern.compile("(?<![A-Za-z])No\\.?(?![A-Za-z])");

    private TaxInvoicePdfReader() {}

    /** Any field may be null; {@link #readOk} is true when all three were found. {@code totalWeak}: the total came from a bare
     * "ยอดรวม" or was just the largest amount, so the issuer should check it. */
    public record Read(String invoiceNumber, LocalDate invoiceDate, BigDecimal total, boolean totalWeak) {
        static final Read NOTHING = new Read(null, null, null, false);

        public boolean readOk() {
            return invoiceNumber != null && invoiceDate != null && total != null;
        }
    }

    public static Read read(byte[] pdf) {
        Future<Read> job;
        try {
            job = POOL.submit((Callable<Read>) () -> readNow(pdf));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return Read.NOTHING; // pool full: the issuer types it
        }
        try {
            return job.get(TIME_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) { // timeout, interrupt, or the worker failed (an Error inside it arrives here as its cause)
            job.cancel(true);
            return Read.NOTHING;
        }
    }

    private static Read readNow(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf, "", null, null, MemoryUsageSetting.setupMixed(MAX_HEAP_BYTES, MAX_SCRATCH_BYTES).streamCache)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(MAX_PAGES);
            String text = stripper.getText(doc);
            return parse(text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
        } catch (Exception e) {
            return Read.NOTHING; // unreadable, encrypted, over the cache cap: nothing read
        }
    }

    static Read parse(String text) {
        String[] lines = text.split("\\R");
        BigDecimal[] total = total(lines);
        return new Read(number(lines), date(lines), total[0], total[0] != null && total[1] == null);
    }

    private static String number(String[] lines) {
        String first = null;
        for (String line : lines) {
            Matcher m = NUMBER.matcher(line);
            if (m.find()) {
                if (line.contains("เลขที่") || NO_LABEL.matcher(line).find()) {
                    return m.group();
                }
                if (first == null) {
                    first = m.group();
                }
            }
        }
        return first;
    }

    private static LocalDate date(String[] lines) {
        LocalDate first = null;
        for (String line : lines) {
            Matcher m = DATE.matcher(line);
            while (m.find()) {
                LocalDate d = toDate(m);
                if (d == null) {
                    continue;
                }
                if (line.contains("วันที่")) {
                    return d;
                }
                if (first == null) {
                    first = d;
                }
            }
        }
        return first;
    }

    private static LocalDate toDate(Matcher m) {
        try {
            int year = Integer.parseInt(m.group(3));
            return LocalDate.of(year > 2400 ? year - 543 : year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {total, strong}: the LAST grand-total line's amount (strong is non-null), else the last weak-label line's, else the
     * largest amount on any line. */
    private static BigDecimal[] total(String[] lines) {
        BigDecimal strong = null;
        BigDecimal weak = null;
        BigDecimal largest = null;
        for (String line : lines) {
            BigDecimal lineMax = null;
            Matcher m = AMOUNT.matcher(line);
            while (m.find()) {
                BigDecimal v = new BigDecimal(m.group().replace(",", ""));
                lineMax = lineMax == null || v.compareTo(lineMax) > 0 ? v : lineMax;
            }
            if (lineMax == null) {
                continue;
            }
            if (STRONG_TOTAL.matcher(line).find()) {
                strong = lineMax;
            } else if (WEAK_TOTAL.matcher(line).find()) {
                weak = lineMax;
            }
            largest = largest == null || lineMax.compareTo(largest) > 0 ? lineMax : largest;
        }
        if (strong != null) {
            return new BigDecimal[] {strong, strong};
        }
        return new BigDecimal[] {weak != null ? weak : largest, null};
    }
}
