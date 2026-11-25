package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static th.co.glr.hr.deposit.PdfRendererTestSupport.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

/**
 * The fresh deposit-notice engine (docs: pdf-generators-fresh-engine-design.md): a render MODEL ->
 * POI fill of templates/deposit_notice_template.xls (sheet "Update") -> LibreOffice PDF. Every
 * printed value is the STORED value. All cell coordinates are 0-based (row, col); the design's
 * Excel names are in the comments. All names/ids/amounts below are made up.
 */
class DepositNoticePdfRendererTest {
    private static final int SUBTOTAL = 43, DEPOSIT = 44, VAT = 45, TOTAL = 46, PREPARER = 47, SIGDATE = 48;
    private static final BigDecimal STORED_SUBTOTAL = new BigDecimal("1234.56");   // != 299.99 (the item)
    private static final BigDecimal STORED_DEPOSIT = new BigDecimal("370.40");     // != 30% of 1234.56
    private static final BigDecimal STORED_VAT = new BigDecimal("25.93");          // != 7% of 370.40
    private static final BigDecimal STORED_TOTAL = new BigDecimal("396.33");       // != 370.40 + 25.93

    private final DepositNoticePdfRenderer renderer = new DepositNoticePdfRenderer();

    private static DepositNoticeItemDto item(int seq, String desc, String qty, String unit, String price,
                                             String discount, String net, String amount) {
        return new DepositNoticeItemDto(seq, seq, desc, new BigDecimal(qty), unit, new BigDecimal(price),
            discount, new BigDecimal(net), new BigDecimal(amount));
    }

    private static List<DepositNoticeItemDto> twoItems() {
        return List.of(
            // stored amount 299.99 != qty 3 x net 100.00
            item(1, "กระเบื้องทดสอบ รุ่น AAA", "3", "แผ่น", "120.00", "พิเศษ", "100.00", "299.99"),
            item(2, "ยาแนวทดสอบ", "2", "ถุง", "55.50", null, "55.50", "111.00"));
    }

    private static DepositNoticeRenderModel model(LocalDate date, String branch, String taxId,
                                                  List<DepositNoticeItemDto> items) {
        return new DepositNoticeRenderModel(date, "GLRD69999", "บริษัท ทดสอบ จำกัด", branch, taxId,
            "99 ถนนทดสอบ แขวงทดสอบ กรุงเทพฯ 10000", "ตึกตัวอย่าง", "QT-TEST-0001", items,
            STORED_SUBTOTAL, new BigDecimal("0.30"), STORED_DEPOSIT, STORED_VAT, STORED_TOTAL, "สมหญิง ทดสอบ");
    }

    private static DepositNoticeRenderModel model() {
        return model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", twoItems());
    }

    private Workbook render(DepositNoticeRenderModel m) throws Exception {
        return open(renderer.toXls(m));
    }

    @Test
    void totalsAreTheStoredValuesAsNumbersAtTheNativeRows() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(num(sh, SUBTOTAL, 8)).isCloseTo(1234.56, within(0.0001));   // I44
            assertThat(num(sh, DEPOSIT, 7)).isCloseTo(0.30, within(0.0001));       // H45 deposit %
            assertThat(num(sh, DEPOSIT, 8)).isCloseTo(370.40, within(0.0001));     // I45
            assertThat(num(sh, VAT, 8)).isCloseTo(25.93, within(0.0001));          // I46
            assertThat(num(sh, TOTAL, 8)).isCloseTo(396.33, within(0.0001));       // I47
            assertThat(sh.getRow(8).getCell(1).getStringCellValue()).isBlank();     // no ออกในนาม line without one
        }
        // ออกในนาม (feedback #74): its own line under ที่อยู่ (B9); เรียน stays the customer
        DepositNoticeRenderModel m = model();
        DepositNoticeRenderModel named = new DepositNoticeRenderModel(m.issueDate(), m.docNumber(), m.customerName(),
            m.customerBranch(), m.customerTaxId(), m.customerAddress(), m.projectName(), m.reference(), m.items(), m.subtotal(),
            m.depositPercent(), m.depositAmount(), m.vatAmount(), m.totalPayable(), m.preparerName(), null,
            "นายตัวอย่าง ทดสอบ  โทร 0800000000");
        try (Workbook wb = render(named)) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getRow(8).getCell(1).getStringCellValue()).isEqualTo("ออกในนาม : นายตัวอย่าง ทดสอบ  โทร 0800000000");
            assertThat(sh.getRow(6).getCell(1).getStringCellValue()).startsWith("บริษัท ทดสอบ จำกัด");
        }
    }
}
