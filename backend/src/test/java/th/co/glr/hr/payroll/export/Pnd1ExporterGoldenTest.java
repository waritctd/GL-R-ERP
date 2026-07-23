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
 * Pins the ภ.ง.ด.1 line assembler to a real GL&amp;R {@code Pnd1.txt} byte-for-byte, line by line. Each
 * golden line is parsed into its tokens and fed back through {@link Pnd1Exporter#formatLine} with the
 * sample's own sequence number and income-type code, so the token order, two-decimal amounts, digit
 * cleaning, Buddhist-Era date and CP874 encoding are all verified against real bytes. (The whole-file
 * business mapping — one line per employee at income type 1 — is checked separately in
 * {@link #exportAppliesDefaultsOneLinePerEmployee()}, because the sample deliberately splits the owner
 * across two income-type rows, which the ERP does not reproduce.)
 */
class Pnd1ExporterGoldenTest {
    private final Pnd1Exporter exporter = new Pnd1Exporter();

    @Test
    void reproducesEachGoldenLineByteForByte() throws IOException {
        byte[] golden = readGolden();
        String[] lines = new String(golden, Cp874.CHARSET).split("\r\n", -1);

        int dataLines = 0;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            dataLines++;
            String[] t = line.split("\\|", -1);
            assertThat(t).as("21 tokens per line").hasSize(21);

            PayrollExportRow row = new PayrollExportRow(
                dataLines, "E" + dataLines, t[7], t[8], t[9], null,
                t[5], t[6], null, null,
                t[10], t[11], t[12],
                BigDecimal.ZERO, new BigDecimal(t[18]), new BigDecimal(t[19]), BigDecimal.ZERO, BigDecimal.ZERO);

            byte[] produced = exporter.formatLine(
                row, Integer.parseInt(t[1]), t[15], t[3], t[4], t[13], t[14], t[16]);

            assertThat(new String(produced, Cp874.CHARSET))
                .as("golden line %d", dataLines)
                .isEqualTo(line);
        }
        assertThat(dataLines).isEqualTo(31);
    }

    @Test
    void exportAppliesDefaultsOneLinePerEmployee() {
        AppProperties.Employer employer = new AppProperties.Employer();
        employer.setCompanyTaxId("0105542026329");
        employer.setPnd1Branch("0000");

        List<PayrollExportRow> rows = List.of(
            rowWithTax("3100902988046", "1002818495", "นาง", "กัลยาณี", "ฐิตญาดา", "30000.00", "2929.00"),
            rowWithTax("1103900192241", null, "นางสาว", "มาลี", "ใจดี", "18000.00", "0.00"));

        byte[] bytes = exporter.export(rows, employer, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26));
        String[] lines = new String(bytes, Cp874.CHARSET).split("\r\n", -1);

        String[] a = lines[0].split("\\|", -1);
        assertThat(a).hasSize(21);
        assertThat(a[1]).isEqualTo("00001");            // sequence
        assertThat(a[3]).isEqualTo("0105542026329");    // payer tax id
        assertThat(a[6]).isEqualTo("1002818495");       // legacy 10-digit alt id
        assertThat(a[13]).isEqualTo("06");              // month
        assertThat(a[14]).isEqualTo("2569");            // Buddhist year 2026+543
        assertThat(a[15]).isEqualTo("1");               // default income type 40(1)
        assertThat(a[16]).isEqualTo("26062569");        // pay date ddMMyyyy in พ.ศ.
        assertThat(a[18]).isEqualTo("30000.00");
        assertThat(a[19]).isEqualTo("2929.00");
        assertThat(a[20]).isEqualTo("1");

        String[] b = lines[1].split("\\|", -1);
        assertThat(b[1]).isEqualTo("00002");            // second employee, next sequence
        assertThat(b[6]).isEqualTo("0000000000");       // no tax id → ten zeros
    }

    private PayrollExportRow rowWithTax(String nationalId, String taxId, String title,
                                        String first, String last, String income, String tax) {
        return new PayrollExportRow(
            1, "E1", title, first, last, null,
            nationalId, taxId, null, null,
            "99", " ถ.เทส", "10110",
            BigDecimal.ZERO, new BigDecimal(income), new BigDecimal(tax), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private byte[] readGolden() throws IOException {
        try (var in = getClass().getResourceAsStream("/payroll-export/Pnd1.golden.txt")) {
            assertThat(in).as("golden resource present").isNotNull();
            return in.readAllBytes();
        }
    }
}
