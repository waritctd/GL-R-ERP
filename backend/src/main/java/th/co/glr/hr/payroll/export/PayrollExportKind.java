package th.co.glr.hr.payroll.export;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The three statutory payroll text files HR can generate for a processed period. */
public enum PayrollExportKind {
    /** KBank K Cash Connect Plus payroll transfer file (product code PCT). */
    KBANK("kbank", "PCT", "PCT"),
    /** Revenue Department withholding-tax submission (ภ.ง.ด.1). */
    PND1("pnd1", "PND1", "Pnd1"),
    /** Social Security Office contribution submission (สปส.1-10). */
    SSO("sso", "SPS1-10", "SPS1-10");

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("ddMMyy", Locale.US);

    private final String slug;
    private final String label;
    private final String filePrefix;

    PayrollExportKind(String slug, String label, String filePrefix) {
        this.slug = slug;
        this.label = label;
        this.filePrefix = filePrefix;
    }

    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }

    /** Suggested download filename, e.g. {@code PCT2606.txt} for a 26 Jun transfer. */
    public String fileName(LocalDate effectiveDate) {
        return filePrefix + effectiveDate.format(FILE_STAMP) + ".txt";
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
