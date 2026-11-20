package th.co.glr.hr.payroll.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pins the ภ.ง.ด.1 line assembler to a real GL&R Pnd1.txt byte-for-byte, line by line. */
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
                BigDecimal.ZERO, new BigDecimal(t[18]), new BigDecimal(t[19]), BigDecimal.ZERO, null, BigDecimal.ZERO, null);

            byte[] produced = exporter.formatLine(
                row, Integer.parseInt(t[1]), t[15], t[3], t[4], t[13], t[14], t[16]);

            assertThat(new String(produced, Cp874.CHARSET))
                .as("golden line %d", dataLines)
                .isEqualTo(line);
        }
        assertThat(dataLines).isEqualTo(31);
    }

    private byte[] readGolden() throws IOException {
        try (var in = getClass().getResourceAsStream("/payroll-export/Pnd1.golden.txt")) {
            assertThat(in).as("golden resource present").isNotNull();
            return in.readAllBytes();
        }
    }
}
