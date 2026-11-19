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

    /** The over-withholding notice must reach the RENDERED PDF, not merely the DTO. Review found
     * `excessWithheldToDate` computed correctly and consumed by nothing at the time — the only signal of the
     * branch's largest defect, visible to no one. */
    /** DECEMBER: the over-withholding is final, so the payslip may state plainly that payroll cannot return it and
     * name the ภ.ง.ด.91 route. Reading it back out of the rendered PDF is the only thing that proves an employee
     * can see it. */
    @Test
    void anOverWithheldEmployeeIsToldSoOnTheFinalPayslipOfTheYear() throws Exception {
        PayrollLineDto line = lineWithExcessWithheld(new BigDecimal("17527.51"));
        String text = extractText(renderer.toPdf(line, periodForMonth(line, 12)));

        // F3 re-activated a payslip line the owner forbade at spec section 8: "Do not print the excess amount." The
        // notice text moved to the owner-approved wording verbatim, which states neither a figure nor
        // "เกินภาษีที่ต้องเสียทั้งปี".
        assertThat(text).contains("ภาษีหัก ณ ที่จ่ายเดือนธันวาคมเป็นการปรับปรุงยอดปลายปี");
        assertThat(text)
            .as("spec section 8 forbids printing the excess baht figure, even in the final period")
            .doesNotContain("17,527.51");
        assertThat(text)
            .as("the employee must be told the route to reclaim any excess, since payroll cannot return it")
            .contains("ภ.ง.ด.90")
            .contains("ภ.ง.ด.91");
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

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
