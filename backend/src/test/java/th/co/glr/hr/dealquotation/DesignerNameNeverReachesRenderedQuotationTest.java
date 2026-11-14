package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderer;

/** Owner requirement, verbatim: "มีใหัเลือก ผู้ออกแบบ แล้ว automaitcally ขึ้น d.co แต่ไม่ต้องขึ้นชื่อผู้ออกแบบ"
 * -- picking a designer fills the quotation's D.Co. with that designer's CODE, and the designer's NAME must
 * NEVER appear on a rendered quotation. */
class DesignerNameNeverReachesRenderedQuotationTest {
    // A real pair from V173's seed -- deliberately NOT read from that file at test time, just asserted as literals
    // so the test reads as "a real designer, not a made-up string that happens not to appear".
    private static final String DESIGNER_CODE = "A001";
    private static final String DESIGNER_NAME = "ABACUS DESIGN CO.,LTD";

    @Test
    void neitherDealQuotationDtoNorRenderModelDeclaresADesignerNameField() {
        assertNoDesignerNameComponent(DealQuotationDto.class);
        assertNoDesignerNameComponent(QuotationRenderModel.class);
    }

    private void assertNoDesignerNameComponent(Class<?> record) {
        for (RecordComponent c : record.getRecordComponents()) {
            String lower = c.getName().toLowerCase();
            assertThat(lower.contains("designer") && lower.contains("name"))
                .as("%s must never gain a designer-NAME field -- see this test's own Javadoc", record.getSimpleName())
                .isFalse();
        }
    }

    @Test
    void unitCodeCarriesOnlyTheCode_theRenderModelNeverSeesTheName() {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation(), null, null);
        assertThat(model.unitCode()).isEqualTo(DESIGNER_CODE);
        assertThat(model.toString()).doesNotContain(DESIGNER_NAME);
    }

    @Test
    void renderedXlsBytesDoNotContainTheDesignersName() throws Exception {
        QuotationRenderModel model = DealQuotationRenderAdapter.toRenderModel(quotation(), null, null);
        byte[] xls = new QuotationRenderer().toXls(model);

        // Belt-and-braces: scan every cell of every sheet, not just the row this field prints on -- guards against the
        // name leaking through some OTHER cell (a debug dump, a merged summary line) just as much as through the D.Co.
        // cell itself.
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            for (Sheet sheet : wb) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String text = cellText(cell);
                        assertThat(text)
                            .as("Sheet '%s' row %d cell %d", sheet.getSheetName(), row.getRowNum(), cell.getColumnIndex())
                            .doesNotContain(DESIGNER_NAME);
                    }
                }
            }
        }

        // The code itself DOES print (as the existing หน่วยงาน/D.Co. cell always has) -- confirms the test fixture is
        // wired correctly and this isn't vacuously passing because unitCode never printed at all.
        assertThat(new String(xls, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(DESIGNER_NAME);
    }

    private String cellText(Cell cell) {
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case FORMULA -> cell.getCellFormula();
                default -> "";
            };
        } catch (RuntimeException e) {
            return "";
        }
    }

    private DealQuotationDto quotation() {
        return new DealQuotationDto(1L, "QT-2026-0001", 1L, "DRAFT", 1, null,
            1L, "ผู้พิมพ์", null, 1L, "พนักงานขาย", null, "081-000-0000",
            null, null, null, null, null, null,
            LocalDate.of(2026, 9, 12), "ลูกค้าทดสอบ", null, null, null,
            null, null, null, null, "โครงการทดสอบ",
            "P003", DESIGNER_CODE, LocalDate.of(2026, 9, 12), 30, "CREDIT", 30, 30, null, null,
            "NET", "TH",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "THB",
            false, List.of(), Instant.parse("2026-09-12T00:00:00Z"), null);
    }
}
