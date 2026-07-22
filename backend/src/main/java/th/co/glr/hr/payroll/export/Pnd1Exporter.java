package th.co.glr.hr.payroll.export;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/**
 * Builds the Revenue Department ภ.ง.ด.1 withholding-tax file: one pipe-delimited line of 21 tokens per
 * employee, CP874-encoded with CRLF line endings. Layout follows the RD "Format กลาง" import spec and
 * is verified against a real GL&amp;R {@code Pnd1.txt} (see {@code Pnd1ExporterGoldenTest}).
 *
 * <p>Amounts render with two explicit decimals ({@code 150000.00}); the year is Buddhist Era (พ.ศ. =
 * Gregorian + 543) and the pay date is {@code ddMMyyyy} in พ.ศ. Only already-computed
 * {@code gross_taxable_income} and {@code withholding_tax} are rendered — no tax math here.
 *
 * <p>KNOWN GAP: the sample splits the owner across two income-type rows (types 1 &amp; 4). The ERP
 * holds one withholding figure per employee, so this emits one line per employee at income type 1
 * (40(1) salary). This is a deliberate, documented simplification, not a silent divergence.
 */
@Component
public class Pnd1Exporter {
    private static final String DEFAULT_INCOME_TYPE = "1"; // 40(1) salary
    private static final String WITHHOLDING_CONDITION = "1"; // หัก ณ ที่จ่าย

    /**
     * @param rows         the period's payroll lines (all paid employees get a line)
     * @param employer     employer registration constants (payer tax id + branch)
     * @param payrollMonth the payroll month (drives fields month + พ.ศ. year)
     * @param payDate      the date income was paid (HR-picked; defaults to the 26th)
     */
    public byte[] export(List<PayrollExportRow> rows, AppProperties.Employer employer,
                         LocalDate payrollMonth, LocalDate payDate) {
        String companyTaxId = digits(employer.getCompanyTaxId());
        String branch = employer.getPnd1Branch();
        String month = String.format(Locale.US, "%02d", payrollMonth.getMonthValue());
        String buddhistYear = Integer.toString(payrollMonth.getYear() + 543);
        // ddMMyyyy in Buddhist Era, e.g. 26 Jun 2026 -> "26062569".
        String payDateThai = String.format(Locale.US, "%02d%02d%04d",
            payDate.getDayOfMonth(), payDate.getMonthValue(), payDate.getYear() + 543);

        List<byte[]> lines = new ArrayList<>();
        int seq = 0;
        for (PayrollExportRow row : rows) {
            seq++;
            lines.add(formatLine(row, seq, DEFAULT_INCOME_TYPE, companyTaxId, branch, month, buddhistYear, payDateThai));
        }
        return Cp874.file(lines);
    }

    /**
     * Assemble one ภ.ง.ด.1 line (21 pipe-delimited tokens) as CP874 bytes. Package-private so the
     * golden-file test can drive {@code seq} and {@code incomeType} from the sample (the two values
     * the business layer decides) while still exercising the real token/decimal/date formatting.
     */
    byte[] formatLine(PayrollExportRow row, int seq, String incomeType, String companyTaxId,
                      String branch, String month, String buddhistYear, String payDateThai) {
        String line = String.join("|",
            "00",
            String.format(Locale.US, "%05d", seq),
            "0000000000000",
            companyTaxId,
            branch,
            clean(digits(row.nationalId())),
            payeeAltId(row.taxId()),
            clean(row.titleTh()),
            clean(row.firstNameTh()),
            clean(row.lastNameTh()),
            clean(row.houseNo()),
            clean(row.addressRest()),
            clean(nullToEmpty(row.postalCode())),
            month,
            buddhistYear,
            incomeType,
            payDateThai,
            "0",
            Cp874.decimal2(row.grossTaxableIncome()),
            Cp874.decimal2(row.withholdingTax()),
            WITHHOLDING_CONDITION);
        return Cp874.line(line);
    }

    /** Field 7 (payee alternate id): the legacy 10-digit tax id, or ten zeros when absent. */
    private String payeeAltId(String taxId) {
        String d = digits(taxId);
        if (d.isEmpty()) {
            return "0000000000";
        }
        if (d.length() > 10) {
            d = d.substring(0, 10);
        }
        return String.format(Locale.US, "%010d", Long.parseLong(d));
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    /** Strip the pipe delimiter from free-text fields so it can never break parsing. */
    private String clean(String value) {
        return value == null ? "" : value.replace("|", " ");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
