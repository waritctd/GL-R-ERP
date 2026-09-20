package th.co.glr.hr.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.LibreOfficePdfConverter;

class BillingNoteRendererTest {
    private final BillingNoteRenderer renderer = new BillingNoteRenderer();

    private void requireLibreOffice() {
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(),
            "LibreOffice (soffice) not installed — skipping PDF render test");
    }

    private BillingNoteDto document() {
        return new BillingNoteDto(
            "GLR6900001-1", LocalDate.of(2026, 9, 20),
            "บริษัท ทดสอบ จำกัด", "สาขาทดสอบ", "99/1 Sukhumvit Road\nBangkok 10110",
            "0100000000019", "หมายเหตุทดสอบ", new BigDecimal("126977.20"),
            List.of(
                new BillingNoteLineRenderDto("GLR6900001-1", LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 9, 1), new BigDecimal("100000.00"), "งวดที่ 1"),
                new BillingNoteLineRenderDto("DEP6900002-1", LocalDate.of(2026, 8, 5),
                    LocalDate.of(2026, 9, 5), new BigDecimal("26977.20"), null)
            ));
    }

    @Test
    void renderUsesTheStoredDateAndLeavesAnUndatedDraftBlank() throws Exception {
        BillingNoteDto dated = document();
        BillingNoteDto undated = new BillingNoteDto(null, null, dated.customerName(), dated.customerBranch(),
            dated.customerAddress(), dated.customerTaxId(), dated.note(), dated.totalAmount(), dated.lines());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(renderer.toXlsx(dated)))) {
            assertThat(wb.getSheetAt(0).getRow(7).getCell(6).getStringCellValue())
                .isEqualTo("20 กันยายน 2569");
        }
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(renderer.toXlsx(undated)))) {
            assertThat(wb.getSheetAt(0).getRow(7).getCell(6).getStringCellValue()).isEmpty();
        }
    }

    @Test
    void xlsxOutputIsRealBiff8OleBytesNotOoxmlZip() throws Exception {
        // Same byte-format pin RemainingInvoiceRendererTest carries: WorkbookFactory.create over
        // the .xls template always returns an HSSFWorkbook, so wb.write emits OLE2/CFB (BIFF8)
        // bytes, never an OOXML ZIP — BillingNoteController serves this as
        // "application/vnd.ms-excel" + ".xls".
        byte[] xlsx = renderer.toXlsx(document());
        assertThat(xlsx).startsWith((byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1);
    }

    @Test
    void rendersWithoutFormulaErrorsAsASinglePageWithTheCorrectTotalAndBahtText() throws Exception {
        requireLibreOffice();
        byte[] xlsx = renderer.toXlsx(document());
        byte[] pdf = LibreOfficePdfConverter.convert(xlsx);

        String text;
        int pageCount;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            pageCount = doc.getNumberOfPages();
            text = new PDFTextStripper().getText(doc);
        }

        assertThat(text).doesNotContain("#VALUE!");
        assertThat(text).doesNotContain("#N/A");
        assertThat(text).doesNotContain("#REF!");
        assertThat(pageCount).isEqualTo(1);
        assertThat(text).contains("บริษัท ทดสอบ จำกัด");
        assertThat(text).contains("0100000000019");
        assertThat(text).contains("GLR6900001-1");
        assertThat(text).contains("DEP6900002-1");
        // Line amounts and the auto-calculated SUM total (100,000.00 + 26,977.20 = 126,977.20).
        assertThat(text).contains("100,000.00");
        assertThat(text).contains("26,977.20");
        assertThat(text).contains("126,977.20");
        // The computed Thai baht-text literal — the exact figure/string ThaiTextTest pins against
        // the owner's own sample sheet.
        assertThat(text).contains("หนึ่งแสนสองหมื่นหกพันเก้าร้อยเจ็ดสิบเจ็ดบาทยี่สิบสตางค์");
        assertThat(text).contains("หมายเหตุทดสอบ");
    }

    @Test
    void totalCellIsALiteralNotAFormula_soANonRecalculatingReaderCannotSeeItDisagreeWithTheBahtWords() throws Exception {
        // F4 (Opus review, GLA-99 step 3 round 1): before the fix, F27 kept the template's own
        // SUM(F12:F26) formula — reading the raw cell (as any consumer that does not open the file
        // in a real spreadsheet program and recalculate it would) sees the template's STALE cached
        // formula result, not the real total, even though B27's baht-text literal is always
        // correct. Both must now come from the same computed `total` and F27 must be a literal.
        byte[] xlsx = renderer.toXlsx(document());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            Cell totalCell = sh.getRow(26).getCell(5); // F27 (0-based row 26, col 5)
            assertThat(totalCell.getCellType()).isNotEqualTo(CellType.FORMULA);
            assertThat(totalCell.getNumericCellValue()).isEqualTo(126977.20);
        }
    }

    @Test
    void totalCellKeepsTheTemplatesOwnStyle_bordersAndFont() throws Exception {
        // S5 (Opus review, GLA-99 step 3 round 2) — the F4 fix (round 1) introduced a NEW
        // regression: it applied the freshly-created, borderless/default-font `moneyStyle` (meant
        // ONLY for the 15 blank line-amount cells) to F27 itself too, discarding the template's own
        // printed borders + Thai font on the total cell. F27 must keep whatever style the template
        // shipped with — moneyStyle is applied only to the 15 line-amount cells.
        org.apache.poi.ss.usermodel.BorderStyle expectedBottom, expectedTop, expectedLeft, expectedRight;
        String expectedFontName;
        try (java.io.InputStream tpl = new org.springframework.core.io.ClassPathResource(
                "templates/billing_note_template.xls").getInputStream();
             Workbook rawWb = WorkbookFactory.create(tpl)) {
            Sheet sh = rawWb.getSheet("Update");
            if (sh == null) sh = rawWb.getSheetAt(0);
            org.apache.poi.ss.usermodel.CellStyle style = sh.getRow(26).getCell(5).getCellStyle();
            expectedBottom = style.getBorderBottom();
            expectedTop = style.getBorderTop();
            expectedLeft = style.getBorderLeft();
            expectedRight = style.getBorderRight();
            expectedFontName = rawWb.getFontAt(style.getFontIndexAsInt()).getFontName();
        }

        byte[] xlsx = renderer.toXlsx(document());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            org.apache.poi.ss.usermodel.CellStyle rendered = sh.getRow(26).getCell(5).getCellStyle();
            assertThat(rendered.getBorderBottom()).isEqualTo(expectedBottom);
            assertThat(rendered.getBorderTop()).isEqualTo(expectedTop);
            assertThat(rendered.getBorderLeft()).isEqualTo(expectedLeft);
            assertThat(rendered.getBorderRight()).isEqualTo(expectedRight);
            assertThat(wb.getFontAt(rendered.getFontIndexAsInt()).getFontName()).isEqualTo(expectedFontName);
        }
    }

    @Test
    void moreLinesThanTheTemplateHoldsThrowsRatherThanTruncate() {
        List<BillingNoteLineRenderDto> tooMany = new ArrayList<>();
        for (int i = 0; i < BillingNoteRenderer.MAX_LINE_ROWS + 1; i++) {
            tooMany.add(new BillingNoteLineRenderDto("DOC" + i, LocalDate.now(), LocalDate.now(),
                new BigDecimal("1.00"), null));
        }
        BillingNoteDto doc = new BillingNoteDto("GLR6900001-1", LocalDate.now(), "ลูกค้า", null, null,
            null, null, new BigDecimal(tooMany.size()), tooMany);

        assertThatThrownBy(() -> renderer.toXlsx(doc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exactlyMaxLineRowsRendersWithoutThrowing() throws Exception {
        List<BillingNoteLineRenderDto> exact = new ArrayList<>();
        for (int i = 0; i < BillingNoteRenderer.MAX_LINE_ROWS; i++) {
            exact.add(new BillingNoteLineRenderDto("DOC" + i, LocalDate.now(), LocalDate.now(),
                new BigDecimal("1.00"), null));
        }
        BillingNoteDto doc = new BillingNoteDto("GLR6900001-1", LocalDate.now(), "ลูกค้า", null, null,
            null, null, new BigDecimal(exact.size()), exact);

        assertThat(renderer.toXlsx(doc)).isNotEmpty();
    }
}
