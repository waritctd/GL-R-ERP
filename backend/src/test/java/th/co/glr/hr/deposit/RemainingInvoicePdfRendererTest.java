package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static th.co.glr.hr.deposit.PdfRendererTestSupport.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

/**
 * The fresh remaining-invoice engine: render MODEL -> POI fill of templates/remaining_invoice_template.xls
 * (sheet "Update") -> LibreOffice PDF. Every printed value is the STORED value. Coordinates are
 * 0-based (row, col), design names in comments. All data below is made up.
 */
class RemainingInvoicePdfRendererTest {
    private static final int NET = 41, VAT = 42, GRAND = 43, PREPARER = 44, SIGDATE = 45;
    // Stored on purpose != anything a formula would compute from the lines.
    private static final BigDecimal DEPOSIT = new BigDecimal("123.45");
    private static final BigDecimal STORED_NET = new BigDecimal("287.60");    // != 410.99 - 123.45 (287.54)
    private static final BigDecimal STORED_VAT = new BigDecimal("20.14");     // != 7% of 287.60 (20.13)
    private static final BigDecimal STORED_GRAND = new BigDecimal("307.80");  // != 287.60 + 20.14

    private final RemainingInvoicePdfRenderer renderer = new RemainingInvoicePdfRenderer();

    private static RemainingInvoiceItemDto item(int seq, String desc, String qty, String unit, String price,
                                                String discount, String net, String amount) {
        return new RemainingInvoiceItemDto(seq, desc, new BigDecimal(qty), unit, new BigDecimal(price),
            discount, new BigDecimal(net), new BigDecimal(amount));
    }

    private static List<RemainingInvoiceItemDto> twoItems() {
        return List.of(
            item(1, "กระเบื้องทดสอบ รุ่น BBB", "3", "แผ่น", "120.00", "พิเศษ", "100.00", "299.99"),
            item(2, "ยาแนวทดสอบ", "2", "ถุง", "55.50", null, "55.50", "111.00"));
    }

    private static RemainingInvoiceRenderModel model(LocalDate date, BigDecimal deposit,
                                                     List<RemainingInvoiceItemDto> items) {
        return new RemainingInvoiceRenderModel(date, "GLR6999999-1", "บริษัท ทดสอบ จำกัด", "สำนักงานใหญ่",
            "0105599999999", "99 ถนนทดสอบ แขวงทดสอบ กรุงเทพฯ 10000", "ตึกตัวอย่าง", "QT-TEST-0002", "AI2600001",
            items, deposit, STORED_NET, STORED_VAT, STORED_GRAND, "สมหญิง ทดสอบ");
    }

    private static RemainingInvoiceRenderModel model() {
        return model(LocalDate.of(2026, 10, 28), DEPOSIT, twoItems());
    }

    private Workbook render(RemainingInvoiceRenderModel m) throws Exception {
        return open(renderer.toXls(m));
    }

    // A document with no VAT (an EN-quotation deal): the template's "ภาษีมูลค่าเพิ่ม 7%" row must not print at all
    // (the EN quotation form has no VAT row either). With VAT the row is printed as before.
    @Test
    void vatRow_isNotPrinted_whenTheDocumentHasNoVat_andIsPrintedOtherwise() throws Exception {
        RemainingInvoiceRenderModel noVat = new RemainingInvoiceRenderModel(LocalDate.of(2026, 10, 28), "GLR6999999-1",
            "บริษัท ทดสอบ จำกัด", "สำนักงานใหญ่", "0105599999999", "99 ถนนทดสอบ", "ตึกตัวอย่าง", "QT-TEST-0002", null,
            twoItems(), BigDecimal.ZERO, new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal("1000.00"), "สมหญิง ทดสอบ");
        try (Workbook wb = render(noVat)) {
            Sheet sh = wb.getSheet("Update");
            int d = 0;
            while (!"รวมเป็นเงิน".equals(norm(str(sh, NET + d, 7))) && d < 40) d++;
            assertThat(str(sh, VAT + d, 4)).as("VAT label").isBlank();
            assertThat(str(sh, VAT + d, 7)).as("7% rate").isBlank();
            assertThat(str(sh, VAT + d, 8)).as("VAT amount").isBlank();
        }
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheet("Update");
            int d = 0;
            while (!"รวมเป็นเงิน".equals(norm(str(sh, NET + d, 7))) && d < 40) d++;
            assertThat(norm(str(sh, VAT + d, 4))).isEqualTo("ภาษีมูลค่าเพิ่ม");
        }
    }
}
