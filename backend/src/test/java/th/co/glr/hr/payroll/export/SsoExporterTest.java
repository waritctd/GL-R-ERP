package th.co.glr.hr.payroll.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.config.AppProperties;

/**
 * Structural checks for the SSO สปส.1-10 formatter. There is no golden sample (the user validates the
 * real bytes on the SSO e-service), so this asserts the invariants that must hold regardless of the
 * unconfirmed byte conventions: 135-byte records, header totals equal to the sum of details, insured
 * count matches, and zero-contribution employees (directors) are excluded.
 */
class SsoExporterTest {
    private final SsoExporter exporter = new SsoExporter();

    private AppProperties.Employer employer() {
        AppProperties.Employer e = new AppProperties.Employer();
        e.setCompanyNameTh("บริษัท ทดสอบ จำกัด");
        e.setSsoEmployerAccount("0123456789");
        e.setSsoBranch("000000");
        e.setSsoRatePercent("5");
        return e;
    }

    @Test
    void everyRecordIs135BytesAndDirectorsExcluded() {
        List<PayrollExportRow> rows = List.of(
            insured("1111111111111", "17500.00", "875.00"),
            insured("2222222222222", "15000.00", "750.00"),
            director("3333333333333")); // socialSecurity 0 → excluded

        byte[] bytes = exporter.export(rows, employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26));
        String[] lines = new String(bytes, Cp874.CHARSET).split("\r\n", -1);

        // header + 2 insured details + trailing empty
        assertThat(lines[0]).startsWith("1");
        long detailCount = List.of(lines).stream().filter(l -> l.startsWith("2")).count();
        assertThat(detailCount).isEqualTo(2);
        for (String line : lines) {
            if (!line.isEmpty()) {
                assertThat(line.getBytes(Cp874.CHARSET)).as("record 135 bytes").hasSize(135);
            }
        }
    }

    @Test
    void headerTotalsAreTheSumOfDetails() {
        List<PayrollExportRow> rows = List.of(
            insured("1111111111111", "17500.00", "875.00"),
            insured("2222222222222", "15000.00", "750.00"));

        byte[] bytes = exporter.export(rows, employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26));
        String header = new String(bytes, Cp874.CHARSET).split("\r\n", -1)[0];

        // Offsets: 1(type)+10(acct)+6(branch)+6(payDate)+4(period)+45(name)+4(rate) = 76.
        // insured count (6) at 76..82
        assertThat(header.substring(76, 82)).isEqualTo("000002");
        // total wage satang (15) at 82..97 = (17500+15000)*100 = 3,250,000
        assertThat(header.substring(82, 97)).isEqualTo("000000003250000");
        // total contribution (14) at 97..111 = employee + employer = 325,000
        assertThat(header.substring(97, 111)).isEqualTo("00000000325000");
        // employee portion (12) at 111..123 = (875+750)*100 = 162,500
        assertThat(header.substring(111, 123)).isEqualTo("000000162500");
        // employer portion (12) at 123..135 matches employee (§33)
        assertThat(header.substring(123, 135)).isEqualTo("000000162500");
    }

    private PayrollExportRow insured(String nationalId, String wage, String contribution) {
        return new PayrollExportRow(
            1, "E1", "นาย", "ทดสอบ", "ระบบ", null,
            nationalId, null, nationalId, null,
            null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal(wage), new BigDecimal(contribution));
    }

    private PayrollExportRow director(String nationalId) {
        return new PayrollExportRow(
            2, "E2", "นาย", "ผู้", "บริหาร", null,
            nationalId, null, nationalId, null,
            null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
