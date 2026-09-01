package th.co.glr.hr.payroll.export;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The statutory/HR payroll files HR can generate for a processed period: the two remaining CP874
 * text files ({@link #KBANK}/{@link #PND1}), plus two xlsx workbooks — {@link #PAYROLL_DETAIL}
 * (2026-07-30), a read-only per-employee breakdown reproducing the accountant's original workbook
 * layout, and {@link #SSO} (2026-08-31), which REPLACED its own CP874 .txt with the branch-per-sheet
 * workbook GL&amp;R actually files. Only KBANK and PND1 still go through {@code Cp874}.
 */
public enum PayrollExportKind {
    /** KBank K Cash Connect Plus payroll transfer file (product code PCT). */
    KBANK("kbank", "PCT", "PCT", "txt", "application/octet-stream"),
    /** Revenue Department withholding-tax submission (ภ.ง.ด.1). */
    PND1("pnd1", "PND1", "Pnd1", "txt", "application/octet-stream"),
    /**
     * Social Security Office contribution submission (สปส.1-10) — a real xlsx workbook, one sheet
     * per branch sequence. Was a CP874 fixed-width .txt until 2026-08-31; see {@code SsoExporter}'s
     * javadoc for why it changed.
     */
    SSO("sso", "SPS1-10", "SPS1-10", "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    /**
     * Detailed monthly payroll workbook (xlsx) for HR — every employee's full breakdown for the
     * period, reproducing (and extending) the accountant's original spreadsheet. Unlike the three
     * kinds above this is real UTF-8 XLSX via Apache POI, not CP874 text.
     */
    PAYROLL_DETAIL("payroll-detail", "Payroll Detail", "PayrollDetail", "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("ddMMyy", Locale.US);

    private final String slug;
    private final String label;
    private final String filePrefix;
    private final String extension;
    private final String contentType;

    PayrollExportKind(String slug, String label, String filePrefix, String extension, String contentType) {
        this.slug = slug;
        this.label = label;
        this.filePrefix = filePrefix;
        this.extension = extension;
        this.contentType = contentType;
    }

    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }

    /** MIME type for the HTTP response — {@code application/octet-stream} for the CP874 text
     * files (so the raw bytes survive the download intact), the real OOXML spreadsheet type for
     * the two xlsx workbooks ({@link #SSO}, {@link #PAYROLL_DETAIL}). */
    public String contentType() {
        return contentType;
    }

    /** Suggested download filename, e.g. {@code PCT260626.txt} for a 26 Jun transfer, or
     * {@code SPS1-10260626.xlsx} for that month's สปส.1-10 workbook. */
    public String fileName(LocalDate effectiveDate) {
        return filePrefix + effectiveDate.format(FILE_STAMP) + "." + extension;
    }

    public static PayrollExportKind fromSlug(String value) {
        for (PayrollExportKind kind : values()) {
            if (kind.slug.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown export kind: " + value);
    }
}
