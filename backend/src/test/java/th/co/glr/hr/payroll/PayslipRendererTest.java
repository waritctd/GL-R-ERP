package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PayslipRendererTest {
    private final PayslipRenderer renderer = new PayslipRenderer();

    @Test
    void rendersTheTwoColumnEmployerLayout() throws Exception {
        PayrollLineDto line = line();
        byte[] pdf = renderer.toPdf(line, periodFor(line));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = extractText(pdf);

        // Header + meta
        assertThat(text).contains("บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด");
        assertThat(text).contains("สลิปเงินเดือน");
        assertThat(text).contains("สมชาย ทดสอบ");
        assertThat(text).contains("GLR-42");
        assertThat(text).contains("30/มิ.ย./2569");

        // Two-column grid headers + rows
        assertThat(text).contains("รายการได้");
        assertThat(text).contains("รายการหัก");
        assertThat(text).contains("เงินเดือน");
        assertThat(text).contains("ค่าล่วงเวลา");     // overtime surfaced when non-zero
        assertThat(text).contains("ค่าคอมมิชชั่น");   // commission surfaced when non-zero
        assertThat(text).contains("ประกันสังคม");
        assertThat(text).contains("ภาษี");

        // Totals foot: รวมรายได้ − รวมรายหัก == เงินรับสุทธิ == netPay
        assertThat(text).contains("รวมรายได้");
        assertThat(text).contains("42,000.00");
        assertThat(text).contains("รวมรายหัก");
        assertThat(text).contains("10,000.00");
        assertThat(text).contains("เงินรับสุทธิ");
        assertThat(text).contains("32,000.00");
        BigDecimal earningsTotal = nz(line.grossEarnings()).add(nz(line.nonTaxableIncome()));
        assertThat(earningsTotal.subtract(line.totalDeductions())).isEqualByComparingTo(line.netPay());

        // Signature line
        assertThat(text).contains("ผู้รับเงิน");
        assertThat(text).contains("วันที่");

        // The old HR-worksheet intermediates are gone
        assertThat(text).doesNotContain("รายได้ทั้งปีประมาณการ");
        assertThat(text).doesNotContain("เงินได้สุทธิทั้งปี");
        assertThat(text).doesNotContain("รหัสรอบเงินเดือน");
        assertThat(text).doesNotContain("?????");
    }

    @Test
    void footsWithNonTaxableIncomeAndLeaveRefundCredit() throws Exception {
        // gross 30,000 + non-taxable 2,000 = earnings total 32,000
        // deductions: unpaidLeave 1,500 − refund 500 + sso 875 + กยศ 1,000 = 2,875
        // net = 30,000 − 2,875 + 2,000 = 29,125
        PayrollLineDto line = new PayrollLineDto(
            1L, 7L, "GLR-07", "ทดสอบ สอง", "AC-บัญชี", "ธ.", "1",
            money("30000.00"), money("1000.00"), money("125.00"),
            List.of(), money("0.00"), money("0.00"), money("0.00"),
            money("30000.00"), money("2000.00"), money("1.50"), money("1500.00"), money("28000.00"),
            money("17500.00"), money("875.00"), money("0.00"), money("0.00"), money("0.00"),
            money("0.00"), money("0.00"), money("0.00"), money("1000.00"), money("0.00"),
            money("0.00"), money("2875.00"), money("29125.00"), null,
            money("0.00"), money("0.00"), money("0.00"), money("0.00"),
            money("0.50"), money("500.00")
        );
        String text = extractText(renderer.toPdf(line, periodFor(line)));

        assertThat(text).contains("รายได้ไม่คิดภาษี");
        assertThat(text).contains("2,000.00");
        assertThat(text).contains("คืนหักลาย้อนหลัง");
        assertThat(text).contains("-500.00");
        assertThat(text).contains("32,000.00");   // รวมรายได้
        assertThat(text).contains("2,875.00");     // รวมรายหัก
        assertThat(text).contains("29,125.00");    // เงินรับสุทธิ

        BigDecimal earningsTotal = nz(line.grossEarnings()).add(nz(line.nonTaxableIncome()));
        assertThat(earningsTotal.subtract(line.totalDeductions())).isEqualByComparingTo(line.netPay());
    }

    private String extractText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private PayrollPeriodDto periodFor(PayrollLineDto line) {
        return new PayrollPeriodDto(
            99L,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 6, 30),
            "PROCESSED",
            OffsetDateTime.now(),
            7L,
            1,
            money("42000.00"),
            money("10000.00"),
            money("32000.00"),
            money("750.00"),
            money("500.00"),
            List.of(line)
        );
    }

    /**
     * The over-withholding notice must reach the RENDERED PDF, not merely the DTO.
     *
     * <p>Review found `excessWithheldToDate` computed correctly and consumed by nothing at the time — the only
     * signal of the branch's largest defect, visible to no one. Without it a stranded excess appears
     * on the payslip as a 0.00 ภาษี line, indistinguishable from "no tax was due this month". Asserted
     * through PDFTextStripper because that is the only thing that proves the employee can actually
     * read it: an earlier "visible on the payslip" claim was true of the string and false of the page,
     * where the text was drawn ~900pt past the right edge.
     */
    /**
     * DECEMBER: the over-withholding is final, so the payslip may state plainly that payroll cannot
     * return it and name the ภ.ง.ด.91 route.
     *
     * <p>Asserted through PDFTextStripper rather than on the DTO, deliberately: an earlier "visible on
     * the payslip" claim was true of the string and false of the page, because the text was drawn ~900pt
     * past the right edge. Reading it back out of the rendered PDF is the only thing that proves an
     * employee can see it.
     */
    @Test
    void anOverWithheldEmployeeIsToldSoOnTheFinalPayslipOfTheYear() throws Exception {
        PayrollLineDto line = lineWithExcessWithheld(new BigDecimal("17527.51"));
        String text = extractText(renderer.toPdf(line, periodForMonth(line, 12)));

        // P2 fix (Opus review, 2026-07-30): F3 re-activated a payslip line the owner forbade at spec
        // section 8: "Do not print the excess amount." The notice text moved to the owner-approved
        // wording verbatim, which states neither a figure nor "เกินภาษีที่ต้องเสียทั้งปี" -- it names
        // WHY December's withholding differs (a year-end true-up) without ever quantifying the excess.
        // excessWithheldToDate is still computed and persisted (F3's own contribution, untouched) for
        // the ภ.ง.ด.1 working papers -- only what gets PRINTED changed here.
        assertThat(text).contains("ภาษีหัก ณ ที่จ่ายเดือนธันวาคมเป็นการปรับปรุงยอดปลายปี");
        assertThat(text)
            .as("spec section 8 forbids printing the excess baht figure, even in the final period")
            .doesNotContain("17,527.51");
        assertThat(text)
            .as("the employee must be told the route to reclaim any excess, since payroll cannot return it")
            .contains("ภ.ง.ด.90")
            .contains("ภ.ง.ด.91");
    }

    /**
     * MID-YEAR: the same figure is only an estimate — later periods can still absorb it by withholding
     * less — so the payslip must NOT assert it is unrecoverable.
     *
     * <p>Review built the case that forced this: a March commission spike produced a notice from April
     * onward whose amount later periods then returned in full. Telling an employee on a payroll document
     * that a named sum "ไม่สามารถคืนผ่านระบบเงินเดือนได้" when payroll subsequently returns it is a false
     * statement they could carry onto a ภ.ง.ด.91.
     */
    @Test
    void aMidYearOverWithholdingIsReportedAsProvisionalNotFinal() throws Exception {
        PayrollLineDto line = lineWithExcessWithheld(new BigDecimal("17527.51"));
        String text = extractText(renderer.toPdf(line, periodForMonth(line, 6)));

        assertThat(text)
            .as("mid-year the figure is an estimate and must be labelled as one")
            .contains("ประมาณการ");
        // P2 fix (Opus review, 2026-07-30): spec section 8 forbids printing the excess baht figure at
        // all, in any month -- see the December test's own comment above for the full reasoning.
        assertThat(text)
            .as("spec section 8 forbids printing the excess baht figure")
            .doesNotContain("17,527.51");
        assertThat(text)
            .as("mid-year the payslip must not claim the excess is unrecoverable")
            .doesNotContain("ไม่สามารถคืนผ่านระบบเงินเดือนได้");
        assertThat(text)
            .as("nor send the employee to ภ.ง.ด.91 over a figure that may still be absorbed")
            .doesNotContain("ภ.ง.ด.91");
    }

    /** The ordinary case prints no notice at all — it must not become background noise. */
    @Test
    void aPayslipWithNoOverWithholdingCarriesNoNotice() throws Exception {
        PayrollLineDto line = lineWithExcessWithheld(BigDecimal.ZERO);
        String text = extractText(renderer.toPdf(line, periodForMonth(line, 12)));

        assertThat(text).doesNotContain("ภ.ง.ด.91");
        assertThat(text).doesNotContain("เกินภาษีที่ต้องเสียทั้งปี");
        assertThat(text).doesNotContain("ประมาณการ");
    }

    private PayrollPeriodDto periodForMonth(PayrollLineDto line, int month) {
        PayrollPeriodDto base = periodFor(line);
        LocalDate payrollMonth = LocalDate.of(2026, month, 1);
        return new PayrollPeriodDto(
            base.id(), payrollMonth, payrollMonth,
            payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()),
            payrollMonth.withDayOfMonth(payrollMonth.lengthOfMonth()),
            base.status(), base.processedAt(), base.processedById(), base.lineCount(),
            base.totalGross(), base.totalDeductions(), base.totalNet(),
            base.totalSocialSecurity(), base.totalWithholdingTax(), base.lines());
    }

    private PayrollLineDto lineWithExcessWithheld(BigDecimal excess) {
        PayrollLineDto base = line();
        return new PayrollLineDto(
            base.id(), base.employeeId(), base.employeeCode(), base.employeeName(),
            base.departmentName(), base.bankName(), base.bankAccount(), base.baseSalary(),
            base.dailyRate(), base.hourlyRate(), base.specialPays(), base.specialPayTotal(),
            base.overtimePay(), base.commissionPay(), base.grossEarnings(), base.nonTaxableIncome(),
            base.unpaidLeaveDays(), base.unpaidLeaveDeduction(), base.grossTaxableIncome(),
            base.ssoWageBase(), base.socialSecurity(), base.projectedAnnualIncome(),
            base.taxExpenseDeduction(), base.taxAllowanceTotal(), base.taxableAnnualIncome(),
            base.annualTax(), base.withholdingTax(), base.studentLoanDeduction(),
            base.legalExecutionDeduction(), base.otherPostTaxDeductions(), base.totalDeductions(),
            base.netPay(), base.calculationNote(), base.directorRemuneration(),
            base.warningLetterDeduction(), base.customerReturnDeduction(), base.otherPretaxDeduction(),
            base.leaveRefundDays(), base.leaveDeductionRefund(), base.withholdingTaxOverride(),
            base.regularTaxableIncome(), base.variableTaxableIncome(), base.regularWithholdingTax(),
            base.variableWithholdingTax(), base.bonusPay(), base.otherOneOffPay(), excess);
    }

    private PayrollLineDto line() {
        return new PayrollLineDto(
            123L,
            42L,
            "GLR-42",
            "สมชาย ทดสอบ",
            "บุคคล",
            "ธนาคาร",
            "001-234-5678",
            money("40000.00"),
            money("1333.33"),
            money("166.67"),
            List.of(new PayrollSpecialPayDto("specialPay1", "พิเศษ 1 (ค่าครองชีพ)", money("1000.00"))),
            money("1000.00"),
            money("500.00"),
            money("500.00"),
            money("42000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            money("42000.00"),
            money("15000.00"),
            money("750.00"),
            money("504000.00"),
            money("100000.00"),
            BigDecimal.ZERO,
            money("404000.00"),
            money("9500.00"),
            money("500.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            money("8750.00"),
            money("10000.00"),
            money("32000.00"),
            "calculated",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
