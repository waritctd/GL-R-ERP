package th.co.glr.hr.payroll.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

    /**
     * The defect this file exists to pin: the branch code lives only in the header, so employees
     * spread over two branches must produce two header blocks. One block for everyone is what the
     * e-service rejects with "จำนวนสาขาที่เลือกคือ 2 จำนวนสาขาที่พบในไฟล์คือ 1".
     *
     * <p>Asserted wrong-way-round as well as right-way: each header's totals must cover ONLY its own
     * branch. A block that silently carried the whole-file totals would still be 2 blocks and would
     * still pass a naive "there are two headers" check.
     */
    @Test
    void eachBranchGetsItsOwnHeaderBlockTotallingOnlyItsOwnEmployees() {
        List<PayrollExportRow> rows = List.of(
            insured("1111111111111", "17500.00", "875.00", "110001"),
            insured("2222222222222", "15000.00", "750.00", null),      // null → employer default 000000
            insured("3333333333333", "10000.00", "500.00", "000000"),
            insured("4444444444444", "12000.00", "600.00", "110001"));

        List<String> lines = records(exporter.export(
            rows, employer(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 3)));

        List<String> headers = lines.stream().filter(l -> l.startsWith("1")).toList();
        assertThat(headers).as("one header per branch").hasSize(2);
        // Ascending branch order, so the file is deterministic regardless of employee ordering.
        assertThat(headers.get(0).substring(11, 17)).isEqualTo("000000");
        assertThat(headers.get(1).substring(11, 17)).isEqualTo("110001");

        // 000000: the null-branch row + the explicit one = 2 people, wage 25,000, employee 1,250.
        assertThat(headers.get(0).substring(76, 82)).isEqualTo("000002");
        assertThat(headers.get(0).substring(82, 97)).isEqualTo("000000002500000");
        assertThat(headers.get(0).substring(111, 123)).isEqualTo("000000125000");
        // 110001: 2 people, wage 29,500, employee 1,475 — NOT the file-wide 54,500 / 2,725.
        assertThat(headers.get(1).substring(76, 82)).isEqualTo("000002");
        assertThat(headers.get(1).substring(82, 97)).isEqualTo("000000002950000");
        assertThat(headers.get(1).substring(111, 123)).isEqualTo("000000147500");

        // Details follow their own header, in caller order, and no row is dropped or duplicated.
        assertThat(lines).containsExactly(
            headers.get(0),
            detailFor(lines, "2222222222222"),
            detailFor(lines, "3333333333333"),
            headers.get(1),
            detailFor(lines, "1111111111111"),
            detailFor(lines, "4444444444444"));
        for (String line : lines) {
            assertThat(line.getBytes(Cp874.CHARSET)).as("record 135 bytes").hasSize(135);
        }
    }

    /**
     * Grouping must not turn "nobody insured" into a zero-record file — before the branch split this
     * produced one empty header, and a period where every employee is a director still should.
     */
    @Test
    void noInsuredEmployeeStillProducesOneEmptyHeader() {
        List<String> lines = records(exporter.export(
            List.of(director("3333333333333")), employer(),
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26)));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).startsWith("1");
        assertThat(lines.get(0).substring(11, 17)).isEqualTo("000000");
        assertThat(lines.get(0).substring(76, 82)).as("insured count").isEqualTo("000000");
        assertThat(lines.get(0).getBytes(Cp874.CHARSET)).hasSize(135);
    }

    /** An installation that has never assigned branches must stay byte-identical to before V122. */
    @Test
    void allEmployeesUnassignedStillProducesExactlyOneBlockAtTheEmployerBranch() {
        List<PayrollExportRow> rows = List.of(
            insured("1111111111111", "17500.00", "875.00", null),
            insured("2222222222222", "15000.00", "750.00", null));

        List<String> lines = records(exporter.export(
            rows, employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26)));

        assertThat(lines.stream().filter(l -> l.startsWith("1")).toList()).hasSize(1);
        assertThat(lines.get(0).substring(11, 17)).isEqualTo("000000");
    }

    /**
     * Golden reconciliation, built from GL&amp;R's actual June 2026 สปส.1-10/1 filing: two branches,
     * 16 + 11 insured, the REAL wage/contribution pairs (amounts are not personally identifying and
     * the ground truth explicitly authorises using them — see the PR body). Every national id below
     * is synthetic ("9" + a sequence number); none corresponds to a real employee, and no name from
     * the real filing appears anywhere in this file.
     *
     * <p>This is the test that proves the whole change: Defect 2 (ค่าจ้าง must be the uncapped wage —
     * every wage figure here is the real uncapped amount, several above the ฿17,500 cap) and Defect 3
     * (per-person rounding — the ฿11,250 row's raw ฿562.50 contribution must file as ฿563) both have
     * to be right at once for these block totals to land on the SSO's own reconciled figures.
     */
    @Test
    void juneGoldenReconciliationTwoBranchesRealAmountsSyntheticIdentities() {
        // {wageGross, contribution*100} for branch 000000 (16 insured) -- contribution kept as
        // baht*100 so the table stays integral; converted to BigDecimal("x.xx") in row(...) below.
        int[][] branch000000 = {
            {50369, 87500}, {124849, 87500}, {24400, 87500}, {20000, 87500}, {30000, 87500},
            {20850, 87500}, {35257, 87500}, {34738, 87500}, {21589, 87500}, {23400, 87500},
            {30875, 87500}, {42502, 87500}, {23738, 87500}, {12000, 60000}, {46600, 87500},
            {33903, 87500}
        };
        // Branch 110001 (11 insured). Row 0 (wage 11,250) is the ฿562.50 -> ฿563 rounding case.
        int[][] branch110001 = {
            {11250, 56250}, {45000, 87500}, {10000, 50000}, {20700, 87500}, {23620, 87500},
            {28500, 87500}, {32100, 87500}, {25500, 87500}, {18000, 87500}, {24500, 87500},
            {16000, 80000}
        };

        List<PayrollExportRow> rows = new ArrayList<>();
        int seq = 0;
        for (int[] pair : branch000000) {
            rows.add(row(++seq, "000000", pair[0], pair[1]));
        }
        String roundingRowId = syntheticId(seq + 1); // first row of branch 110001, wage 11,250
        for (int[] pair : branch110001) {
            rows.add(row(++seq, "110001", pair[0], pair[1]));
        }

        List<String> lines = records(exporter.export(
            rows, employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26)));
        List<String> headers = lines.stream().filter(l -> l.startsWith("1")).toList();
        assertThat(headers).as("one header block per branch").hasSize(2);
        assertThat(headers.get(0).substring(11, 17)).isEqualTo("000000");
        assertThat(headers.get(1).substring(11, 17)).isEqualTo("110001");

        // 000000: 16 insured, ค่าจ้าง 575,070.00, เงินสมทบ (employee) 13,725.00 -- reconciles to the
        // real สปส.1-10/1 receipt exactly.
        assertThat(headers.get(0).substring(76, 82)).as("000000 insured count").isEqualTo("000016");
        assertThat(headers.get(0).substring(82, 97)).as("000000 total wage")
            .isEqualTo("000000057507000");
        assertThat(headers.get(0).substring(111, 123)).as("000000 employee contribution")
            .isEqualTo("000001372500");

        // 110001: 11 insured, ค่าจ้าง 255,170.00, เงินสมทบ (employee) 8,863.00.
        assertThat(headers.get(1).substring(76, 82)).as("110001 insured count").isEqualTo("000011");
        assertThat(headers.get(1).substring(82, 97)).as("110001 total wage")
            .isEqualTo("000000025517000");
        assertThat(headers.get(1).substring(111, 123)).as("110001 employee contribution")
            .isEqualTo("000000886300");

        // The ฿562.50-raw row must be FILED as ฿563 (มาตรา 46 วรรคท้าย), not ฿562.
        String roundedDetail = detailFor(lines, roundingRowId);
        assertThat(roundedDetail.substring(96, 108)).as("562.50 -> 563").isEqualTo("000000056300");

        for (String line : lines) {
            assertThat(line.getBytes(Cp874.CHARSET)).as("record 135 bytes").hasSize(135);
        }
    }

    /**
     * Wrong-way-round rounding, per มาตรา 46 วรรคท้าย: exactly .50 rounds UP, just-under-.50 rounds
     * DOWN. The three contributions below are chosen so the SUM-OF-ROUNDED-PER-PERSON total (563 +
     * 562 + 301 = 1,426) differs from the ROUNDED-SUM-OF-RAW total (562.50 + 562.49 + 300.50 =
     * 1,425.49 -> 1,425) — a fixture where the two DON'T differ would let a "round the total instead
     * of each row" bug pass this test by accident.
     */
    @Test
    void contributionRoundsPerPersonNotOnTheBlockTotal() {
        List<PayrollExportRow> rows = List.of(
            insured("1111111111111", "11250.00", "562.50"), // exactly .50 -> rounds UP to 563
            insured("2222222222222", "11249.80", "562.49"), // just under .50 -> rounds DOWN to 562
            insured("3333333333333", "6010.00", "300.50"));  // exactly .50 -> rounds UP to 301

        List<String> lines = records(exporter.export(
            rows, employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26)));
        String header = lines.get(0);

        assertThat(detailFor(lines, "1111111111111").substring(96, 108)).isEqualTo("000000056300");
        assertThat(detailFor(lines, "2222222222222").substring(96, 108)).isEqualTo("000000056200");
        assertThat(detailFor(lines, "3333333333333").substring(96, 108)).isEqualTo("000000030100");

        // 1,426.00, the sum of the ROUNDED per-person amounts -- NOT 1,425.00, the rounded sum of the
        // raw 1,425.49 total. A bug that rounds the total instead of each row fails this assertion
        // while (coincidentally) the header count/wage assertions above would still pass.
        assertThat(header.substring(111, 123)).as("employee portion = sum of rounded rows")
            .isEqualTo("000000142600");
    }

    /**
     * Defect 2: ค่าจ้าง must be the wage actually paid, uncapped -- not the ฿17,500 capped
     * {@code sso_wage_base} tax/contribution math uses. The point of this test is the assertion that
     * the wage field is NOT 17,500.
     */
    @Test
    void wageFieldIsTheUncappedGrossNotTheCappedBase() {
        List<PayrollExportRow> rows = List.of(
            uncapped("1111111111111", "124849.00", "17500.00", "875.00", null));

        List<String> lines = records(exporter.export(
            rows, employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26)));
        String detail = detailFor(lines, "1111111111111");

        assertThat(detail.substring(82, 96)).as("ค่าจ้าง must be the uncapped 124,849, not 17,500")
            .isEqualTo("00000012484900");
        assertThat(detail.substring(82, 96)).isNotEqualTo("00000001750000");
        assertThat(detail.substring(96, 108)).as("เงินสมทบ still capped at 875").isEqualTo("000000087500");
    }

    /** ssoWageGross == null (rows processed before V123) falls back to ssoWageBase. */
    @Test
    void ssoWageGrossNullFallsBackToSsoWageBase() {
        List<PayrollExportRow> rows = List.of(
            insured("1111111111111", "15000.00", "750.00")); // ssoWageGross null via this helper

        List<String> lines = records(exporter.export(
            rows, employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26)));
        String detail = detailFor(lines, "1111111111111");

        assertThat(detail.substring(82, 96)).isEqualTo("00000001500000");
    }

    /**
     * เลขประจำตัวประกันสังคม keys on the NATIONAL ID (owner ruling 2026-08-03), not social_security_no.
     * The two ids below deliberately DIFFER — with a fixture where they match (as every other fixture
     * in this file has them), this assertion passes under either preference and proves nothing.
     */
    @Test
    void detailKeysOnNationalIdNotSocialSecurityNo() {
        PayrollExportRow row = new PayrollExportRow(
            1, "E1", "นาย", "ทดสอบ", "ระบบ", null,
            "9000000000001", null, "9000000000002", null, // nationalId != socialSecurityNo
            null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("15000.00"), null, new BigDecimal("750.00"), null);

        List<String> lines = records(exporter.export(
            List.of(row), employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26)));
        String detail = lines.stream().filter(l -> l.startsWith("2")).findFirst().orElseThrow();

        assertThat(detail.substring(1, 14)).as("must be the national id").isEqualTo("9000000000001");
        assertThat(detail.substring(1, 14)).as("must NOT be social_security_no")
            .isNotEqualTo("9000000000002");
    }

    /**
     * Falls back to social_security_no only when there is no national id on file — so an employee
     * missing a national id is still keyed, rather than filed under 13 zeros.
     */
    @Test
    void detailFallsBackToSocialSecurityNoWhenNationalIdIsMissing() {
        PayrollExportRow row = new PayrollExportRow(
            1, "E1", "นาย", "ทดสอบ", "ระบบ", null,
            null, null, "9000000000002", null, // no national id
            null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("15000.00"), null, new BigDecimal("750.00"), null);

        List<String> lines = records(exporter.export(
            List.of(row), employer(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 26)));
        String detail = lines.stream().filter(l -> l.startsWith("2")).findFirst().orElseThrow();

        assertThat(detail.substring(1, 14)).isEqualTo("9000000000002");
    }

    /** Synthetic national id: "9" + a 12-digit zero-padded sequence number. Never a real Thai id. */
    private String syntheticId(int seq) {
        return "9" + String.format("%012d", seq);
    }

    private PayrollExportRow row(int seq, String branch, int wageGross, int contributionCentibaht) {
        BigDecimal gross = new BigDecimal(wageGross).setScale(2);
        BigDecimal base = gross.min(new BigDecimal("17500.00"));
        BigDecimal contribution = new BigDecimal(contributionCentibaht).movePointLeft(2);
        return uncapped(syntheticId(seq), gross.toPlainString(), base.toPlainString(),
            contribution.toPlainString(), branch);
    }

    private List<String> records(byte[] bytes) {
        return List.of(new String(bytes, Cp874.CHARSET).split("\r\n", -1)).stream()
            .filter(l -> !l.isEmpty())
            .toList();
    }

    private String detailFor(List<String> lines, String nationalId) {
        return lines.stream()
            .filter(l -> l.startsWith("2") && l.substring(1, 14).equals(nationalId))
            .reduce((a, b) -> {
                throw new AssertionError("duplicate detail for " + nationalId);
            })
            .orElseThrow(() -> new AssertionError("no detail for " + nationalId));
    }

    private PayrollExportRow insured(String nationalId, String wage, String contribution) {
        return insured(nationalId, wage, contribution, null);
    }

    /**
     * ssoWageGross deliberately left {@code null} — every pre-existing test at this arity predates
     * V123 and exercises the null-fallback path (ค่าจ้าง falls back to {@code ssoWageBase}, i.e. the
     * {@code wage} param here), which is correct for these fixtures since none of them involve a
     * capped employee. See {@link #uncapped} for an explicit-gross fixture and {@code
     * ssoWageGrossNullFallsBackToSsoWageBase} for a test that pins this behaviour directly.
     */
    private PayrollExportRow insured(String nationalId, String wage, String contribution, String branch) {
        return new PayrollExportRow(
            1, "E1", "นาย", "ทดสอบ", "ระบบ", null,
            nationalId, null, nationalId, null,
            null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal(wage), null, new BigDecimal(contribution), branch);
    }

    /** Explicit ssoWageGross (uncapped) distinct from ssoWageBase (capped) — see the uncapped-wage test. */
    private PayrollExportRow uncapped(
        String nationalId, String wageGross, String wageBase, String contribution, String branch
    ) {
        return new PayrollExportRow(
            1, "E1", "นาย", "ทดสอบ", "ระบบ", null,
            nationalId, null, nationalId, null,
            null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal(wageBase), new BigDecimal(wageGross), new BigDecimal(contribution), branch);
    }

    private PayrollExportRow director(String nationalId) {
        return new PayrollExportRow(
            2, "E2", "นาย", "ผู้", "บริหาร", null,
            nationalId, null, nationalId, null,
            null, null, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, null, BigDecimal.ZERO, null);
    }
}
