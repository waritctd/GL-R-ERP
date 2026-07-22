package th.co.glr.hr.payroll.export;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/**
 * Builds the KBank K Cash Connect Plus payroll transfer file (product code <b>PCT</b>): a fixed-width
 * CP874 file with a 178-byte {@code H} header and one 487-byte {@code D} record per employee, uploaded
 * to move salaries into employees' KBank accounts.
 *
 * <p>Layout and byte offsets are taken from the KBank Excel Template V2.5.5 macro and verified against
 * a real GL&amp;R {@code PCTNew*.txt} file (see {@code KBankPctExporterGoldenTest}). Amounts are stored
 * as satang (baht×100) with no decimal point; dates are {@code YYMMDD}; text is space-padded right and
 * numbers zero-padded left — all measured in CP874 bytes. This class only <em>renders</em> the already
 * computed {@code net_amount}; it performs no payroll math.
 */
@Component
public class KBankPctExporter {
    private static final int HEADER_WIDTH = 178;
    private static final int DETAIL_WIDTH = 487;
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd", Locale.US);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);

    /**
     * @param rows          the period's payroll lines
     * @param employer      employer/bank registration constants
     * @param effectiveDate the transfer effective date HR picked (funds move on this day). The
     *                      transaction date is set equal to it; the sample file's one-day gap between
     *                      the two is cosmetic and not required by the bank.
     */
    public byte[] export(List<PayrollExportRow> rows, AppProperties.Employer employer, LocalDate effectiveDate) {
        return export(rows, employer, effectiveDate, effectiveDate);
    }

    /**
     * Full form with a distinct transaction (file/submission) date and effective (funds-move) date —
     * the layout the real GL&amp;R file uses (txn 28th, effective 29th). Production calls the single-date
     * overload above; the golden-file test uses this to reproduce the sample byte-for-byte.
     */
    public byte[] export(List<PayrollExportRow> rows, AppProperties.Employer employer,
                         LocalDate transactionDate, LocalDate effectiveDate) {
        String txnDate = transactionDate.format(YYMMDD);
        String effDate = effectiveDate.format(YYMMDD);
        String batchRef = employer.getKbankBatchRef() == null || employer.getKbankBatchRef().isBlank()
            ? effectiveDate.format(YYYYMMDD)
            : employer.getKbankBatchRef();

        List<PayrollExportRow> payable = rows.stream()
            .filter(row -> hasBankAccount(row) && isPositive(row.netAmount()))
            .toList();

        List<byte[]> records = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        List<byte[]> details = new ArrayList<>();
        int seq = 0;
        for (PayrollExportRow row : payable) {
            seq++;
            total = total.add(row.netAmount());
            details.add(detail(row, seq, txnDate, effDate));
        }

        records.add(header(employer, batchRef, total, payable.size(), txnDate, effDate));
        records.addAll(details);
        return Cp874.file(records);
    }

    private byte[] header(AppProperties.Employer employer, String batchRef, BigDecimal total, int count,
                          String txnDate, String effDate) {
        return Cp874.record(HEADER_WIDTH,
            Cp874.bytes("H"),
            Cp874.bytes("PCT"),
            Cp874.rpad(batchRef, 16),
            Cp874.bytes("000000"),
            Cp874.spaces(14),
            account10(employer.getKbankDebitAccount()),
            Cp874.spaces(1),
            Cp874.satang(total, 15),
            Cp874.spaces(1),
            Cp874.bytes(txnDate),
            Cp874.spaces(25),
            Cp874.rpad(employer.getCompanyNameTh(), 50),
            Cp874.bytes(effDate),
            Cp874.zpad(count, 18),
            Cp874.bytes("N"),
            Cp874.spaces(5));
    }

    private byte[] detail(PayrollExportRow row, int seq, String txnDate, String effDate) {
        return Cp874.record(DETAIL_WIDTH,
            Cp874.bytes("D"),
            Cp874.zpad(seq, 6),
            Cp874.spaces(14),
            account10(row.bankAccount()),
            Cp874.spaces(1),
            Cp874.satang(row.netAmount(), 15),
            Cp874.spaces(1),
            Cp874.bytes(txnDate),
            Cp874.spaces(25),
            Cp874.rpad(row.firstNameTh(), 50),
            Cp874.bytes(effDate),
            Cp874.bytes("000"),                       // WHT record count — none
            Cp874.rpad(beneficiaryRef(row), 16),
            Cp874.spaces(50),                          // reserved
            Cp874.spaces(1),                           // advice flag — none
            Cp874.rpad("", 50),                        // fax
            Cp874.rpad("", 50),                        // email
            Cp874.bytes("0000000000.00"),              // total before VAT
            Cp874.bytes("0000000000.00"),              // total tax deducted
            Cp874.spaces(13),                          // reserved
            Cp874.rpad("", 10),                        // tax id (left blank, as in the golden file)
            Cp874.rpad("", 13),                        // personal id (left blank — no PII in bank file)
            Cp874.rpad("", 30),                        // address 1
            Cp874.rpad("", 30),                        // address 2
            Cp874.rpad("", 30),                        // address 3
            Cp874.rpad("", 30));                       // address 4
    }

    /**
     * Beneficiary reference (required, ≤16 chars, {@code [A-Za-z0-9-/_]}). GL&amp;R uses the employee's
     * lowercased English first name; falls back to the employee code so the field is never empty.
     */
    private String beneficiaryRef(PayrollExportRow row) {
        String base = row.firstNameEn() != null && !row.firstNameEn().isBlank()
            ? row.firstNameEn()
            : row.employeeCode();
        String sanitized = base == null ? "" : base.toLowerCase(Locale.US).replaceAll("[^a-z0-9/_-]", "");
        if (sanitized.isBlank()) {
            sanitized = "pay" + row.employeeId();
        }
        return sanitized.length() > 16 ? sanitized.substring(0, 16) : sanitized;
    }

    private boolean hasBankAccount(PayrollExportRow row) {
        return row.bankAccount() != null && !row.bankAccount().isBlank();
    }

    private boolean isPositive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    /** Exactly 10 digits, preserving leading zeros; a longer stored value is a data error. */
    private byte[] account10(String account) {
        String digits = account == null ? "" : account.replaceAll("\\D", "");
        return Cp874.zpad(digits, 10);
    }
}
