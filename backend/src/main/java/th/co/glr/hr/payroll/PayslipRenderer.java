package th.co.glr.hr.payroll;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.PdfDocumentWriter;

@Component
public class PayslipRenderer {
    private static final String[] THAI_MONTHS = {
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    };

    public byte[] toPdf(PayrollLineDto line, PayrollPeriodDto period) {
        try (PdfDocumentWriter pdf = new PdfDocumentWriter()) {
            PDFont regular = pdf.loadFont(PdfDocumentWriter.FONT_REGULAR);
            PDFont bold = pdf.loadFont(PdfDocumentWriter.FONT_BOLD);

            pdf.text(bold, 15, "บริษัท จี แอล แอนด์ อาร์ จำกัด");
            pdf.text(regular, 10, "ใบแจ้งเงินเดือน / Payslip");
            pdf.gap(8);
            pdf.text(bold, 13, "สลิปเงินเดือน " + thaiMonth(period.payrollMonth()));
            pdf.text(regular, 10, "รหัสรอบเงินเดือน: " + nullSafe(period.id()));
            pdf.text(regular, 10, "วันที่จ่าย: " + thaiDate(period.payDate()));
            pdf.gap(10);

            pdf.text(bold, 11, "ข้อมูลพนักงาน");
            pdf.text(regular, 10, "ชื่อพนักงาน: " + nullSafe(line.employeeName()) + "  รหัส: " + nullSafe(line.employeeCode()));
            pdf.text(regular, 10, "แผนก: " + nullSafe(line.departmentName(), "-"));
            pdf.text(regular, 10, "ธนาคาร: " + nullSafe(line.bankName(), "-") + "  เลขที่บัญชี: " + nullSafe(line.bankAccount(), "-"));
            pdf.gap(10);

            pdf.text(bold, 11, "รายได้");
            pdf.text(regular, 10, "เงินเดือน: " + fmt2(line.baseSalary()) + " บาท");
            pdf.text(regular, 10, "อัตรารายวัน: " + fmt2(line.dailyRate()) + " บาท  อัตรารายชั่วโมง: " + fmt2(line.hourlyRate()) + " บาท");
            for (PayrollSpecialPayDto item : line.specialPays() == null ? java.util.List.<PayrollSpecialPayDto>of() : line.specialPays()) {
                pdf.text(regular, 9, nullSafe(item.label()) + ": " + fmt2(item.amount()) + " บาท");
            }
            pdf.text(regular, 10, "เงินพิเศษรวม: " + fmt2(line.specialPayTotal()) + " บาท");
            pdf.text(regular, 10, "ค่าล่วงเวลา: " + fmt2(line.overtimePay()) + " บาท");
            pdf.text(regular, 10, "ค่าคอมมิชชั่น: " + fmt2(line.commissionPay()) + " บาท");
            if (nonZero(line.directorRemuneration())) {
                pdf.text(regular, 10, "ค่าตอบแทนกรรมการ: " + fmt2(line.directorRemuneration()) + " บาท");
            }
            pdf.text(regular, 10, "รายได้ไม่คิดภาษี: " + fmt2(line.nonTaxableIncome()) + " บาท");
            pdf.text(bold, 11, "รายได้รวม: " + fmt2(line.grossEarnings()) + " บาท");
            pdf.gap(10);

            pdf.text(bold, 11, "เงินหักและภาษี");
            pdf.text(regular, 10, "วันลาไม่รับค่าจ้าง: " + fmt2(line.unpaidLeaveDays()) + " วัน  หัก: " + fmt2(line.unpaidLeaveDeduction()) + " บาท");
            if (nonZero(line.warningLetterDeduction())) {
                pdf.text(regular, 10, "หักตามใบเตือน: " + fmt2(line.warningLetterDeduction()) + " บาท");
            }
            if (nonZero(line.customerReturnDeduction())) {
                pdf.text(regular, 10, "หักลูกค้าคืนสินค้า: " + fmt2(line.customerReturnDeduction()) + " บาท");
            }
            if (nonZero(line.otherPretaxDeduction())) {
                pdf.text(regular, 10, "หักอื่น ๆ ก่อนภาษี: " + fmt2(line.otherPretaxDeduction()) + " บาท");
            }
            pdf.text(regular, 10, "รายได้คิดภาษี: " + fmt2(line.grossTaxableIncome()) + " บาท");
            pdf.text(regular, 10, "ฐานประกันสังคม: " + fmt2(line.ssoWageBase()) + " บาท  ประกันสังคม: " + fmt2(line.socialSecurity()) + " บาท");
            pdf.text(regular, 10, "รายได้ทั้งปีประมาณการ: " + fmt2(line.projectedAnnualIncome()) + " บาท");
            pdf.text(regular, 10, "ค่าใช้จ่าย: " + fmt2(line.taxExpenseDeduction()) + " บาท  ค่าลดหย่อนรวม: " + fmt2(line.taxAllowanceTotal()) + " บาท");
            pdf.text(regular, 10, "เงินได้สุทธิทั้งปี: " + fmt2(line.taxableAnnualIncome()) + " บาท  ภาษีทั้งปี: " + fmt2(line.annualTax()) + " บาท");
            pdf.text(regular, 10, "ภาษีหัก ณ ที่จ่ายงวดนี้: " + fmt2(line.withholdingTax()) + " บาท");
            pdf.text(regular, 10, "หัก กยศ.: " + fmt2(line.studentLoanDeduction()) + " บาท");
            pdf.text(regular, 10, "หักอายัดกรมบังคับคดี: " + fmt2(line.legalExecutionDeduction()) + " บาท");
            pdf.text(regular, 10, "หักอื่น ๆ หลังภาษี: " + fmt2(line.otherPostTaxDeductions()) + " บาท");
            pdf.text(bold, 11, "เงินหักรวม: " + fmt2(line.totalDeductions()) + " บาท");
            pdf.gap(10);

            pdf.text(bold, 13, "เงินโอนสุทธิ: " + fmt2(line.netPay()) + " บาท");
            if (line.calculationNote() != null && !line.calculationNote().isBlank()) {
                pdf.gap(8);
                pdf.text(bold, 10, "หมายเหตุ");
                pdf.text(regular, 9, line.calculationNote());
            }

            return pdf.toBytes();
        } catch (IOException e) {
            throw new RuntimeException("Payslip PDF render failed: " + e.getMessage(), e);
        }
    }

    private String thaiMonth(LocalDate date) {
        if (date == null) {
            return "";
        }
        return THAI_MONTHS[date.getMonthValue() - 1] + " " + (date.getYear() + 543);
    }

    private String thaiDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        return date.getDayOfMonth() + " " + thaiMonth(date);
    }

    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean nonZero(BigDecimal value) {
        return value != null && value.signum() != 0;
    }

    private String fmt2(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return String.format(Locale.US, "%,.2f", value);
    }
}
