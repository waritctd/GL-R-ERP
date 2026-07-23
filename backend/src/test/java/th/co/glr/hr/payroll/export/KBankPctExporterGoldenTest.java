package th.co.glr.hr.payroll.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.config.AppProperties;

/**
 * Pins the KBank PCT formatter to a real GL&amp;R export file byte-for-byte. The test parses the golden
 * file's own field values back into {@link PayrollExportRow}s and asserts the formatter reproduces the
 * exact bytes — so any drift in a field offset, padding rule, satang conversion, or the CP874 encoding
 * fails the build. This is the file that actually moves salary money, so it gets the strictest test.
 */
class KBankPctExporterGoldenTest {
    private final KBankPctExporter exporter = new KBankPctExporter();

    @Test
    void reproducesTheGoldenFileByteForByte() throws IOException {
        byte[] golden = readGolden();
        String text = new String(golden, Cp874.CHARSET);
        String[] lines = text.split("\r\n", -1);
        String header = lines[0];

        AppProperties.Employer employer = new AppProperties.Employer();
        employer.setCompanyNameTh(rstrip(header.substring(98, 148)));
        employer.setKbankDebitAccount(header.substring(40, 50));
        employer.setKbankBatchRef(""); // blank → derived as the effective date yyyyMMdd (= golden batch ref)

        LocalDate transactionDate = yymmdd(header.substring(67, 73)); // 260628
        LocalDate effectiveDate = yymmdd(header.substring(148, 154)); // 260629

        List<PayrollExportRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String d = lines[i];
            if (d.isEmpty()) {
                continue; // trailing CRLF produces a final empty element
            }
            String creditAccount = d.substring(21, 31);
            long satang = Long.parseLong(d.substring(32, 47));
            String payeeNameTh = rstrip(d.substring(79, 129));
            String beneficiaryRef = rstrip(d.substring(138, 154));
            rows.add(row(i, payeeNameTh, beneficiaryRef, creditAccount, BigDecimal.valueOf(satang, 2)));
        }

        byte[] generated = exporter.export(rows, employer, transactionDate, effectiveDate);

        assertThat(generated).isEqualTo(golden);
        // Sanity on the parse itself so a broken golden read can't make the test vacuously pass.
        assertThat(rows).hasSize(30);
    }

    private PayrollExportRow row(long id, String firstNameTh, String firstNameEn, String bankAccount, BigDecimal net) {
        return new PayrollExportRow(
            id, "E" + id, null, firstNameTh, null, firstNameEn,
            null, null, null, bankAccount,
            null, null, null,
            net, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static LocalDate yymmdd(String s) {
        int year = 2000 + Integer.parseInt(s.substring(0, 2));
        int month = Integer.parseInt(s.substring(2, 4));
        int day = Integer.parseInt(s.substring(4, 6));
        return LocalDate.of(year, month, day);
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    private byte[] readGolden() throws IOException {
        try (var in = getClass().getResourceAsStream("/payroll-export/PCTNew2906.golden.txt")) {
            assertThat(in).as("golden resource present").isNotNull();
            return in.readAllBytes();
        }
    }
}
