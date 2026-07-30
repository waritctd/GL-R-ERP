package th.co.glr.hr.payroll.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollSpecialPayDto;

/**
 * Unit test for {@link PayrollDetailExporter}, driven directly (no DB, no {@code PayrollService})
 * with hand-built {@link PayrollLineDto}/{@link PayrollDetailIdentityDto} fixtures covering both a
 * monthly and a daily-rate ({@code pay_type='D'}) employee, per-diem, meal allowance, the
 * automated-commission-vs-พิเศษ-7 distinction, and the "เงินเดือนใหม่" in-period/out-of-period rule.
 */
class PayrollDetailExporterTest {
    private final PayrollDetailExporter exporter = new PayrollDetailExporter();

    @Test
    void producesHeaderRowKnownEmployeeRowsAndCorrectTotals() throws Exception {
        PayrollPeriodDto period = new PayrollPeriodDto(
            99L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 6, 30), "PROCESSED", OffsetDateTime.now(), 7L, 2,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());

        PayrollLineDto monthly = monthlyEmployee();
        PayrollLineDto daily = dailyEmployee();

        Map<Long, PayrollDetailIdentityDto> identities = new HashMap<>();
        // Adjustment INSIDE the reported month -- must show as "เงินเดือนใหม่".
        identities.put(1L, new PayrollDetailIdentityDto(
            LocalDate.of(2020, 1, 15), LocalDate.of(2026, 6, 10), money("30000.00")));
        // Adjustment from years before the reported month -- must NOT show as "เงินเดือนใหม่".
        identities.put(2L, new PayrollDetailIdentityDto(
            LocalDate.of(2023, 3, 1), LocalDate.of(2024, 1, 1), money("10000.00")));

        byte[] bytes = exporter.export(List.of(monthly, daily), identities, period);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            Map<String, Integer> col = new HashMap<>();
            for (Cell cell : headerRow) {
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
                col.put(cell.getStringCellValue(), cell.getColumnIndex());
            }
            // Thai headers survive real UTF-8 (xlsx) -- the whole point of not routing this through Cp874.
            assertThat(col).containsKeys(
                "รหัสพนักงาน", "ชื่อ", "แผนก", "ประเภทพนักงาน", "วันที่เริ่มงาน", "วันที่ปรับล่าสุด",
                "เงินเดือน", "เงินเดือนใหม่", "อัตรารายวัน", "จำนวนวันทำงาน",
                "พิเศษ 1 (ค่าครองชีพ)", "พิเศษ 2 (ค่าเช่าบ้าน)", "ค่าอาหาร", "พิเศษ 3 (เบี้ยเลี้ยงประจำ)",
                "พิเศษ 4 (ค่าตำแหน่ง)", "พิเศษ 5 (เบี้ยขยันประจำ)", "พิเศษ 6 (ค่า GPRS)",
                "เบี้ยเลี้ยง (ตจว/ตปท) รวม", "เบี้ยเลี้ยง - ส่วนยกเว้นภาษี (ม.42)", "เบี้ยเลี้ยง - ส่วนต้องเสียภาษี",
                "ล่วงเวลา", "พิเศษ 7 (คอมมิชชั่น)", "ค่าคอมมิชชั่น (ระบบขาย)",
                "พิเศษ 8 (ทำได้ตาม KPI)", "พิเศษ 9 (เงินรางวัล/เงินช่วยเหลืออื่นๆ)",
                "รวมรายได้ที่ต้องคิดภาษี (A)",
                "หักขาดงาน", "หักตามใบเตือน", "หัก 6 (ลูกค้าคืนสินค้า)", "อื่นๆ (หักก่อนภาษี)",
                "รวมรายหักที่ต้องคิดภาษี (B)", "คงเหลือ (A-B)",
                "ภาษี", "ประกันสังคม",
                "หัก 1 (คืนเงินกู้จากบริษัท)", "หัก 3 (ทำทรัพย์สินเสียหาย สูญหาย)",
                "หัก 5 (หักค่าโทรศัพท์ส่วนที่โทรเกิน)", "หักนำส่งกรมบังคับคดี", "หัก 8 (อื่นๆ)",
                "รวมรายหักอื่นๆ (C)", "รายได้อื่นๆที่ไม่คำนวนภาษี (D)", "คงเหลือจ่ายจริง (A-B-C+D)");

            Row monthlyRow = sheet.getRow(1);
            assertThat(monthlyRow.getCell(col.get("รหัสพนักงาน")).getStringCellValue()).isEqualTo("EMP-001");
            assertThat(monthlyRow.getCell(col.get("ชื่อ")).getStringCellValue()).isEqualTo("สมชาย ใจดี");
            assertThat(monthlyRow.getCell(col.get("ประเภทพนักงาน")).getStringCellValue()).isEqualTo("รายเดือน");
            assertThat(monthlyRow.getCell(col.get("เงินเดือน")).getNumericCellValue()).isEqualTo(30000.00);
            // Adjustment fell inside June 2026 -> shows as เงินเดือนใหม่.
            assertThat(monthlyRow.getCell(col.get("เงินเดือนใหม่")).getNumericCellValue()).isEqualTo(30000.00);
            assertThat(monthlyRow.getCell(col.get("พิเศษ 1 (ค่าครองชีพ)")).getNumericCellValue()).isEqualTo(1000.00);
            assertThat(monthlyRow.getCell(col.get("พิเศษ 2 (ค่าเช่าบ้าน)")).getNumericCellValue()).isEqualTo(2000.00);
            assertThat(monthlyRow.getCell(col.get("พิเศษ 7 (คอมมิชชั่น)")).getNumericCellValue()).isEqualTo(700.00);
            // Automated sales commission is a DISTINCT figure from the พิเศษ 7 slot above.
            assertThat(monthlyRow.getCell(col.get("ค่าคอมมิชชั่น (ระบบขาย)")).getNumericCellValue()).isEqualTo(2500.00);
            assertThat(monthlyRow.getCell(col.get("ค่าอาหาร")).getNumericCellValue()).isEqualTo(600.00);
            assertThat(monthlyRow.getCell(col.get("รวมรายหักที่ต้องคิดภาษี (B)")).getNumericCellValue())
                .as("B = unpaidLeaveDeduction(0) + warningLetterDeduction(100) + customerReturnDeduction(50) + otherPretaxDeduction(25)")
                .isEqualTo(175.00);
            assertThat(monthlyRow.getCell(col.get("คงเหลือ (A-B)")).getNumericCellValue()).isEqualTo(44000.00 - 175.00);
            assertThat(monthlyRow.getCell(col.get("รวมรายหักอื่นๆ (C)")).getNumericCellValue())
                .as("C = studentLoanDeduction(1000) + legalExecutionDeduction(0) + otherPostTaxDeductions(200)")
                .isEqualTo(1200.00);
            assertThat(monthlyRow.getCell(col.get("คงเหลือจ่ายจริง (A-B-C+D)")).getNumericCellValue())
                .as("netPay verbatim, NOT recomputed from A-B-C+D")
                .isEqualTo(40000.00);
            // หัก 3 / หัก 5 have no dedicated system column -- always blank, never fabricated.
            assertThat(monthlyRow.getCell(col.get("หัก 3 (ทำทรัพย์สินเสียหาย สูญหาย)")).getCellType())
                .isEqualTo(CellType.BLANK);
            assertThat(monthlyRow.getCell(col.get("หัก 5 (หักค่าโทรศัพท์ส่วนที่โทรเกิน)")).getCellType())
                .isEqualTo(CellType.BLANK);

            Row dailyRow = sheet.getRow(2);
            assertThat(dailyRow.getCell(col.get("รหัสพนักงาน")).getStringCellValue()).isEqualTo("EMP-002");
            assertThat(dailyRow.getCell(col.get("ประเภทพนักงาน")).getStringCellValue()).isEqualTo("รายวัน");
            assertThat(dailyRow.getCell(col.get("อัตรารายวัน")).getNumericCellValue()).isEqualTo(450.00);
            assertThat(dailyRow.getCell(col.get("จำนวนวันทำงาน")).getNumericCellValue()).isEqualTo(25.00);
            assertThat(dailyRow.getCell(col.get("เบี้ยเลี้ยง (ตจว/ตปท) รวม")).getNumericCellValue()).isEqualTo(1000.00);
            assertThat(dailyRow.getCell(col.get("เบี้ยเลี้ยง - ส่วนยกเว้นภาษี (ม.42)")).getNumericCellValue()).isEqualTo(800.00);
            assertThat(dailyRow.getCell(col.get("เบี้ยเลี้ยง - ส่วนต้องเสียภาษี")).getNumericCellValue()).isEqualTo(200.00);
            // Adjustment predates June 2026 -> must NOT read as "new this month".
            assertThat(dailyRow.getCell(col.get("เงินเดือนใหม่")).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(dailyRow.getCell(col.get("หักนำส่งกรมบังคับคดี")).getNumericCellValue()).isEqualTo(300.00);
            assertThat(dailyRow.getCell(col.get("คงเหลือจ่ายจริง (A-B-C+D)")).getNumericCellValue()).isEqualTo(11500.00);

            int totalsRowIdx = sheet.getLastRowNum();
            assertThat(totalsRowIdx).isEqualTo(3);
            Row totalsRow = sheet.getRow(totalsRowIdx);
            assertThat(totalsRow.getCell(0).getStringCellValue()).contains("รวมทั้งหมด").contains("2");
            assertThat(totalsRow.getCell(col.get("เงินเดือน")).getNumericCellValue())
                .as("totals row must equal the sum of the two data rows")
                .isEqualTo(30000.00 + 11250.00);
            assertThat(totalsRow.getCell(col.get("พิเศษ 7 (คอมมิชชั่น)")).getNumericCellValue()).isEqualTo(700.00);
            assertThat(totalsRow.getCell(col.get("ค่าคอมมิชชั่น (ระบบขาย)")).getNumericCellValue()).isEqualTo(2500.00);
            assertThat(totalsRow.getCell(col.get("หัก 1 (คืนเงินกู้จากบริษัท)")).getNumericCellValue()).isEqualTo(1000.00);
            assertThat(totalsRow.getCell(col.get("คงเหลือจ่ายจริง (A-B-C+D)")).getNumericCellValue())
                .isEqualTo(40000.00 + 11500.00);
            // เงินเดือนใหม่ has one blank + one populated row -> totals sum only the populated one.
            assertThat(totalsRow.getCell(col.get("เงินเดือนใหม่")).getNumericCellValue()).isEqualTo(30000.00);
        }
    }

    private PayrollLineDto monthlyEmployee() {
        List<PayrollSpecialPayDto> specialPays = List.of(
            special("specialPay1", money("1000.00")),
            special("specialPay2", money("2000.00")),
            special("specialPay3", money("300.00")),
            special("specialPay4", money("400.00")),
            special("specialPay5", money("500.00")),
            special("specialPay6", money("600.00")),
            special("specialPay7", money("700.00")),
            special("specialPay8", money("800.00")),
            special("specialPay9", money("900.00")));
        return new PayrollLineDto(
            1L, 1L, "EMP-001", "สมชาย ใจดี", "บัญชี", "กสิกรไทย", "1112223334",
            money("30000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            specialPays, money("7200.00"), money("1500.00"), money("2500.00"),
            money("45000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            money("44000.00"), money("15000.00"), money("750.00"),
            money("528000.00"), money("100000.00"), money("60000.00"), money("368000.00"),
            money("9200.00"), money("766.67"),
            money("1000.00"), BigDecimal.ZERO, money("200.00"),
            money("2716.67"), money("40000.00"), "ทดสอบ",
            BigDecimal.ZERO, money("100.00"), money("50.00"), money("25.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            money("5000.00"), money("300.00"), BigDecimal.ZERO,
            money("44000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            money("766.67"), BigDecimal.ZERO,
            false, "SALARY",
            money("600.00"), BigDecimal.ZERO, BigDecimal.ZERO, null,
            money("50.00"), null, "M");
    }

    private PayrollLineDto dailyEmployee() {
        List<PayrollSpecialPayDto> zeros = List.of(
            special("specialPay1", BigDecimal.ZERO), special("specialPay2", BigDecimal.ZERO),
            special("specialPay3", BigDecimal.ZERO), special("specialPay4", BigDecimal.ZERO),
            special("specialPay5", BigDecimal.ZERO), special("specialPay6", BigDecimal.ZERO),
            special("specialPay7", BigDecimal.ZERO), special("specialPay8", BigDecimal.ZERO),
            special("specialPay9", BigDecimal.ZERO));
        return new PayrollLineDto(
            2L, 2L, "EMP-002", "วิภา ขยันดี", "คลังสินค้า", "ไทยพาณิชย์", "5556667778",
            money("11250.00"), money("450.00"), BigDecimal.ZERO,
            zeros, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            money("12250.00"), money("500.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            money("12250.00"), money("11250.00"), money("562.50"),
            money("147000.00"), money("100000.00"), money("60000.00"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, money("300.00"), BigDecimal.ZERO,
            money("862.50"), money("11500.00"), null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            false, null,
            BigDecimal.ZERO, money("800.00"), money("200.00"), "FLAT_RATE_S42_2",
            BigDecimal.ZERO, money("25.00"), "D");
    }

    private PayrollSpecialPayDto special(String key, BigDecimal amount) {
        return new PayrollSpecialPayDto(key, key, amount);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
