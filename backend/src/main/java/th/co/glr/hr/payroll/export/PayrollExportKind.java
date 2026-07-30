package th.co.glr.hr.payroll.export;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The statutory/HR payroll files HR can generate for a processed period: the three legacy CP874
 * text files (KBank/PND1/SSO), plus the {@link #PAYROLL_DETAIL} xlsx report added alongside them
 * (2026-07-30) — a read-only, per-employee breakdown reproducing the accountant's original
 * workbook layout. See {@code PayrollDetailExporter} for that one; the other three still go
 * through {@code Cp874}, unaffected by this addition.
 */
public enum PayrollExportKind {
    /** KBank K Cash Connect Plus payroll transfer file (product code PCT). */
    KBANK("kbank", "PCT", "PCT", "txt", "application/octet-stream"),
    /** Revenue Department withholding-tax submission (ภ.ง.ด.1). */
    PND1("pnd1", "PND1", "Pnd1", "txt", "application/octet-stream"),
    /** Social Security Office contribution submission (สปส.1-10). */
    SSO("sso", "SPS1-10", "SPS1-10", "txt", "application/octet-stream"),
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
     * {@link #PAYROLL_DETAIL}. */
    public String contentType() {
        return contentType;
    }

    /** Suggested download filename, e.g. {@code PCT2606.txt} for a 26 Jun transfer, or
     * {@code PayrollDetail2606.xlsx} for the detail workbook. */
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
