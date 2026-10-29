package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static th.co.glr.hr.deposit.PdfRendererTestSupport.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.LibreOfficePdfConverter;

/**
 * The fresh remaining-invoice engine: render MODEL -> POI fill of templates/remaining_invoice_template.xls
 * (sheet "Update") -> LibreOffice PDF. Every printed value is the STORED value. Coordinates are
 * 0-based (row, col), design names in comments. All data below is made up.
 */
class RemainingInvoicePdfRendererTest {
    private static final String TEMPLATE = "/templates/remaining_invoice_template.xls";
    private static final int ITEM0 = 12, NOTES_ROW = 34;
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
            item(1, "กระเบื้องทดสอบ รุ่น BBB", "3", "แผ่น", "120.00", "ส่วนลด 20.00 ต่อหน่วย", "100.00", "299.99"),
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

    private static void requireLibreOffice() {
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(), "LibreOffice (soffice) not installed");
    }

    @Test
    void headerBlockLandsInTheDesignCellMapAndKeepsTheLabels() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(norm(str(sh, 6, 1))).isEqualTo("บริษัท ทดสอบ จำกัด (สำนักงานใหญ่) / เลขประจำตัวผู้เสียภาษี : 0105599999999"); // B7
            assertThat(norm(str(sh, 6, 7))).isEqualTo("28 ตุลาคม 2569");                       // H7 date
            assertThat(norm(str(sh, 7, 1))).isEqualTo("99 ถนนทดสอบ แขวงทดสอบ กรุงเทพฯ 10000");  // B8 address
            assertThat(norm(str(sh, 7, 7))).isEqualTo("GLR6999999-1");                          // H8 doc no
            assertThat(norm(str(sh, 8, 7))).isEqualTo("QT-TEST-0002");                          // H9 reference VALUE
            assertThat(norm(str(sh, 8, 3))).startsWith("อ้างอิง");                              // D9 label kept
            assertThat(norm(str(sh, 7, 4))).startsWith("เลขที่เอกสาร");                         // E8 label kept
            assertThat(norm(str(sh, 10, 1))).isEqualTo("Project : ตึกตัวอย่าง");                // B11
        }
    }

    @Test
    void customerLineOmitsABlankBranch() throws Exception {
        RemainingInvoiceRenderModel m = model();
        RemainingInvoiceRenderModel noBranch = new RemainingInvoiceRenderModel(m.docDate(), m.docNumber(),
            m.customerName(), null, m.customerTaxId(), m.customerAddress(), m.projectName(), m.reference(),
            m.depositReference(), m.items(), m.depositDeduction(), m.netAmount(), m.vatAmount(),
            m.grandTotal(), m.preparerName());
        try (Workbook wb = render(noBranch)) {
            assertThat(norm(str(wb.getSheetAt(0), 6, 1)))
                .isEqualTo("บริษัท ทดสอบ จำกัด / เลขประจำตัวผู้เสียภาษี : 0105599999999");
        }
    }

    @Test
    void itemRowsAndTheDepositRowPrintStoredValues() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(seq(sh, ITEM0)).isEqualTo(1);
            assertThat(norm(str(sh, ITEM0, 1))).isEqualTo("กระเบื้องทดสอบ รุ่น BBB");
            assertThat(num(sh, ITEM0, 2)).isEqualTo(3.0);
            assertThat(str(sh, ITEM0, 3)).isEqualTo("แผ่น");
            assertThat(num(sh, ITEM0, 4)).isEqualTo(120.00);
            assertThat(norm(str(sh, ITEM0, 6))).isEqualTo("ลด 20.00");
            assertThat(num(sh, ITEM0, 7)).isEqualTo(100.00);
            assertThat(num(sh, ITEM0, 8)).isCloseTo(299.99, within(0.0001));    // stored, not 3 x 100
            assertThat(seq(sh, ITEM0 + 1)).isEqualTo(2);
            assertThat(isBlank(sh, ITEM0 + 1, 6)).as("no discount -> G blank").isTrue();
            assertThat(num(sh, ITEM0 + 1, 8)).isEqualTo(111.00);

            int d = ITEM0 + 2;   // the deposit row follows the last item
            assertThat(norm(str(sh, d, 1))).isEqualTo("หัก มัดจำ AI2600001");     // B  "หัก  มัดจำ  <depositReference>"
            assertThat(num(sh, d, 2)).isEqualTo(-1.0);                           // C
            assertThat(num(sh, d, 4)).isCloseTo(123.45, within(0.0001));         // E
            assertThat(num(sh, d, 7)).isCloseTo(123.45, within(0.0001));         // H
            assertThat(num(sh, d, 8)).isCloseTo(-123.45, within(0.0001));        // I
            for (int r = ITEM0; r <= d; r++)
                for (int c = 0; c <= 9; c++)
                    assertThat(str(sh, r, c)).as("(%d,%d) no formula", r, c).doesNotStartWith("FORMULA:");
        }
    }

    @Test
    void noDepositRowWhenNothingWasDeducted() throws Exception {
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), BigDecimal.ZERO, twoItems()))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(allStrings(sh)).noneMatch(s -> s.contains("มัดจำ") && s.contains("หัก"));
            assertThat(isBlank(sh, ITEM0 + 2, 1)).isTrue();
        }
    }

    @Test
    void totalsAreTheStoredValuesAsNumbersAtTheNativeRows() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(num(sh, NET, 8)).isCloseTo(287.60, within(0.0001));       // I42
            assertThat(num(sh, VAT, 8)).isCloseTo(20.14, within(0.0001));        // I43
            assertThat(num(sh, GRAND, 8)).isCloseTo(307.80, within(0.0001));     // I44
        }
    }

    @Test
    void preparerSignatureDateAndSheetHygiene() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            assertThat(norm(str(sh, PREPARER, 1))).isEqualTo("( สมหญิง ทดสอบ )");   // B45
            assertThat(norm(str(sh, SIGDATE, 1))).isEqualTo("วันที่ 28/10/2569");
            // the template's own alignment is kept (B is the full-width description column: centring there misplaces the lines)
            try (Workbook own = openTemplate(TEMPLATE)) {
                Sheet t = own.getSheet("Update");
                assertThat(sh.getRow(PREPARER).getCell(1).getCellStyle().getAlignment())
                    .isEqualTo(t.getRow(PREPARER).getCell(1).getCellStyle().getAlignment());
                assertThat(sh.getRow(SIGDATE).getCell(1).getCellStyle().getAlignment())
                    .isEqualTo(t.getRow(SIGDATE).getCell(1).getCellStyle().getAlignment());
            }    // B46
            assertThat(allStrings(sh)).noneMatch(s -> s.contains("มิ.ย"));
        }
        try (Workbook wb = render(model(LocalDate.of(2026, 1, 5), DEPOSIT, twoItems()))) {
            assertThat(norm(str(wb.getSheetAt(0), SIGDATE, 1))).isEqualTo("วันที่ 5/1/2569");
        }
    }

    @Test
    void notesBlockIsTheTemplatesOwnAndPrintedOnce() throws Exception {
        List<String> expected = templateNoteLines(TEMPLATE);
        assertThat(expected).anyMatch(s -> !s.isBlank());
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(rowOfExact(sh, 1, NOTES_HEADER)).isEqualTo(NOTES_ROW);
            assertThat(noteLines(sh)).isEqualTo(expected);
            assertThat(allStrings(sh).stream().filter(s -> norm(s).equals(NOTES_HEADER))).hasSize(1);
            String firstLine = norm(expected.get(0));
            assertThat(allStrings(sh).stream().filter(s -> norm(s).equals(firstLine))).hasSize(1);
        }
    }

    @Test
    void aLongDescriptionWrapsOntoContinuationRowsInsteadOfBeingClipped() throws Exception {
        String desc = "Porcelain tile 60x60 matte white rectified edge sample item ".repeat(7).trim();
        List<RemainingInvoiceItemDto> items = List.of(
            item(1, desc, "3", "แผ่น", "120.00", null, "120.00", "360.00"),
            item(2, "ยาแนวทดสอบ", "2", "ถุง", "55.50", null, "55.50", "111.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), DEPOSIT, items))) {
            Sheet sh = wb.getSheetAt(0);
            List<Integer> rows = descriptionRows(sh, ITEM0, NOTES_ROW);
            assertThat(rows.size()).as("wrapped onto extra rows").isGreaterThan(1);
            StringBuilder joined = new StringBuilder();
            double limit = columnBTextWidthPt(sh);
            for (int r : rows) {
                joined.append(str(sh, r, 1));
                double w = textWidthPt(sh, r, str(sh, r, 1));
                Assumptions.assumeTrue(w >= 0, "no measurable font on this host");
                assertThat(w).as("row %d fits column B (%.1fpt)", r, limit).isLessThanOrEqualTo(limit + 0.5);
            }
            assertThat(stripWs(joined.toString())).isEqualTo(stripWs(desc));
            assertThat(num(sh, ITEM0, 8)).isEqualTo(360.00);
            int second = ITEM0 + rows.size();
            assertThat(seq(sh, second)).isEqualTo(2);
            assertThat(norm(str(sh, second + 1, 1))).as("deposit row follows the wrapped item").startsWith("หัก");
            assertThat(rowOfExact(sh, 1, NOTES_HEADER)).isEqualTo(NOTES_ROW);
        }
    }

    @Test
    void manyItemsFlowTheFooterDownExtendThePrintAreaAndRepeatTheHeader() throws Exception {
        List<String> expectedNotes = templateNoteLines(TEMPLATE);
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), DEPOSIT, manyItems(40)))) {
            Sheet sh = wb.getSheetAt(0);
            int header = rowOfExact(sh, 1, NOTES_HEADER);
            assertThat(header).as("notes header below 40 items + the deposit row").isGreaterThanOrEqualTo(ITEM0 + 41);
            int delta = header - NOTES_ROW;
            assertThat(seq(sh, ITEM0 + 39)).isEqualTo(40);
            assertThat(norm(str(sh, ITEM0 + 40, 1))).startsWith("หัก");
            assertThat(num(sh, ITEM0 + 40, 8)).isCloseTo(-123.45, within(0.0001));
            assertThat(noteLines(sh)).isEqualTo(expectedNotes);
            assertThat(num(sh, NET + delta, 8)).isCloseTo(287.60, within(0.0001));
            assertThat(num(sh, VAT + delta, 8)).isCloseTo(20.14, within(0.0001));
            assertThat(num(sh, GRAND + delta, 8)).isCloseTo(307.80, within(0.0001));
            assertThat(norm(str(sh, PREPARER + delta, 1))).isEqualTo("( สมหญิง ทดสอบ )");
            assertThat(norm(str(sh, SIGDATE + delta, 1))).isEqualTo("วันที่ 28/10/2569");
            assertThat(printAreaLastRow(wb)).isGreaterThanOrEqualTo(SIGDATE + delta + 1);
            assertThat(sh.getRepeatingRows()).isNotNull();
            assertThat(sh.getRepeatingRows().getFirstRow()).isEqualTo(0);
            assertThat(sh.getRepeatingRows().getLastRow()).isEqualTo(9);
            for (int r = ITEM0; r <= ITEM0 + 40; r++)
                for (int c = 0; c <= 9; c++)
                    assertThat(str(sh, r, c)).doesNotStartWith("FORMULA:");
        }
    }

    // ── PDF (LibreOffice) ─────────────────────────────────────────────────────────────────

    @Test
    void pdfIsOnePageWithTheStoredTotalsAndNoFormulaErrors() throws Exception {
        requireLibreOffice();
        byte[] pdf = renderer.toPdf(model());
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(text).doesNotContain("#VALUE!", "#N/A", "#REF!", "###");
            assertThat(text).contains("307.80", "287.60", "20.14", "299.99", "123.45", "28/10/2569");
        }
    }

    @Test
    void pdfMoneyColumnsAreWideEnoughForLargeStoredFigures() throws Exception {
        requireLibreOffice();
        RemainingInvoiceRenderModel m = new RemainingInvoiceRenderModel(LocalDate.of(2026, 10, 28), "GLR6999999-1",
            "บริษัท ทดสอบ จำกัด", "สำนักงานใหญ่", "0105599999999", "99 ถนนทดสอบ", "ตึกตัวอย่าง", "QT-TEST-0002",
            "AI2600001", List.of(item(1, "ของทดสอบ", "1", "ชุด", "98765432.10", null, "98765432.10", "98765432.10")),
            new BigDecimal("49382716.05"), new BigDecimal("49382716.05"),
            new BigDecimal("3456790.12"), new BigDecimal("52839506.17"), "สมหญิง ทดสอบ");
        try (PDDocument doc = Loader.loadPDF(renderer.toPdf(m))) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).doesNotContain("###").contains("98,765,432.10", "52,839,506.17");
        }
    }

    @Test
    void pdfManyItemsPaginateWithRepeatedHeaderAndAnUnsplitNotesTotalsSignatureBlock() throws Exception {
        requireLibreOffice();
        try (PDDocument doc = Loader.loadPDF(renderer.toPdf(
                model(LocalDate.of(2026, 10, 28), DEPOSIT, manyItems(120))))) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            int blockPages = 0;
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(p);
                s.setEndPage(p);
                String text = s.getText(doc);
                assertThat(text).as("page %d repeats the column header", p).contains("ลำดับ");
                boolean hasNotes = text.contains(NOTES_HEADER);
                boolean hasTotal = text.contains("307.80");
                boolean hasSignature = text.contains("28/10/2569");
                assertThat(hasNotes == hasTotal && hasTotal == hasSignature)
                    .as("notes, totals and signature date share a page (page %d)", p).isTrue();
                if (hasNotes) blockPages++;
            }
            assertThat(blockPages).isEqualTo(1);
        }
    }


    // ── phase-3 review fixes ──────────────────────────────────────────────────────────────

    /** HSSF shiftRows copies a row's height but not its hidden flag: the remaining template has a hidden row
     * that used to swallow one footer/notes row for some item counts. Every footer row must stay visible
     * and hold its text for every count around the capacity. */
    @Test
    void footerAndNotesRowsStayVisibleForEveryItemCountAroundTheCapacity() throws Exception {
        List<String> expectedNotes = templateNoteLines(TEMPLATE);
        for (int n = 20; n <= 35; n++) {
            try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), DEPOSIT, manyItems(n)))) {
                Sheet sh = wb.getSheetAt(0);
                int header = rowOfExact(sh, 1, NOTES_HEADER);
                assertThat(header).as("n=%d notes header", n).isGreaterThanOrEqualTo(NOTES_ROW);
                int d = header - NOTES_ROW;
                assertRowsVisible(sh, ITEM0, SIGDATE + d);
                assertThat(noteLines(sh)).as("n=%d notes", n).isEqualTo(expectedNotes);
            assertThat(num(sh, NET + d, 8)).isCloseTo(287.60, within(0.0001));
            assertThat(num(sh, VAT + d, 8)).isCloseTo(20.14, within(0.0001));
            assertThat(num(sh, GRAND + d, 8)).isCloseTo(307.80, within(0.0001));
                assertThat(norm(str(sh, PREPARER + d, 1))).as("n=%d preparer", n).isEqualTo("( สมหญิง ทดสอบ )");
                assertThat(norm(str(sh, SIGDATE + d, 1))).as("n=%d signature date", n).isEqualTo("วันที่ 28/10/2569");
            }
        }
    }

    @Test
    void pdfWith25ItemsPrintsEveryTotalAndTheSignatureDate() throws Exception {
        requireLibreOffice();
        try (PDDocument doc = Loader.loadPDF(renderer.toPdf(model(LocalDate.of(2026, 10, 28), DEPOSIT, manyItems(25))))) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("307.80", "287.60", "20.14", "28/10/2569");
        }
    }

    /** A null/blank unit (or no seq) prints blank, never the template's own example "แผ่น" / "1". */
    @Test
    void theItemZoneKeepsNoTemplateConstants() throws Exception {
        List<RemainingInvoiceItemDto> items = List.of(item(0, "ไม่มีหน่วย", "1", null, "10.00", null, "10.00", "10.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), DEPOSIT, items))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(isBlank(sh, ITEM0, 3)).as("unit D13").isTrue();
            assertThat(isBlank(sh, ITEM0, 0)).as("seq A13").isTrue();
        }
    }

    /** A document with no preparer leaves the template's own preparer line as it is. */
    @Test
    void aMissingPreparerLeavesTheTemplatesOwnLine() throws Exception {
        String own;
        try (Workbook t = openTemplate(TEMPLATE)) { own = norm(str(t.getSheet("Update"), PREPARER, 1)); }
        assertThat(own).as("template ships a preparer line").isNotBlank();
        try (Workbook wb = render(new RemainingInvoiceRenderModel(LocalDate.of(2026, 10, 28), "GLR6999999-1", "บริษัท ทดสอบ จำกัด", null, "0105599999999", "99 ถนนทดสอบ", "ตึกตัวอย่าง", "QT-TEST-0002", "AI2600001", twoItems(), DEPOSIT, STORED_NET, STORED_VAT, STORED_GRAND, null))) {
            assertThat(norm(str(wb.getSheetAt(0), PREPARER, 1))).isEqualTo(own);
        }
    }

    /** Thai text is broken at word boundaries; a line never starts with a combining vowel or tone mark. */
    @Test
    void thaiDescriptionsWrapWithoutOrphaningCombiningMarks() throws Exception {
        String base = "กระเบื้องพอร์ซเลนเคลือบผิวมันวาวขนาดหกสิบคูณหกสิบเซนติเมตรชนิดขอบตรงคุณภาพส่งออกบรรจุกล่องละสี่แผ่นสำหรับปูพื้นภายในอาคารที่พักอาศัยและสำนักงาน";
        for (int pad = 0; pad < 40; pad++) { // shifts every cut point, so some would land mid-cluster
            String desc = "ก".repeat(pad) + base;
            List<RemainingInvoiceItemDto> items = List.of(item(1, desc, "1", "แผ่น", "10.00", null, "10.00", "10.00"));
            try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), DEPOSIT, items))) {
                Sheet sh = wb.getSheetAt(0);
                List<Integer> rows = descriptionRows(sh, ITEM0, NOTES_ROW);
                assertThat(rows.size()).as("pad=%d wraps", pad).isGreaterThan(1);
                StringBuilder joined = new StringBuilder();
                for (int r : rows) {
                    String line = str(sh, r, 1);
                    joined.append(line);
                    int first = line.stripLeading().codePointAt(0);
                    assertThat(isThaiCombining(first)).as("pad=%d row %d starts with a combining mark", pad, r).isFalse();
                    int last = line.stripTrailing().codePointBefore(line.stripTrailing().length());
                    if (r != rows.get(rows.size() - 1))
                        assertThat(last >= 0x0E40 && last <= 0x0E44).as("pad=%d row %d ends with a leading vowel", pad, r).isFalse();
                }
                assertThat(stripWs(joined.toString())).isEqualTo(stripWs(desc));
            }
        }
    }

    private static boolean isThaiCombining(int c) {
        return c == 0x0E31 || (c >= 0x0E34 && c <= 0x0E3A) || (c >= 0x0E47 && c <= 0x0E4E);
    }

    /** Ruling P1-b: column G prints ONE total "ลด <unit - net>" whenever net < unit (the stored label text is ignored);
     * blank when net == unit or net > unit. Same font/alignment as the figures, width = E, B untouched. */
    @Test
    void theDiscountColumnPrintsOneTotalLikeTheFigures() throws Exception {
        List<RemainingInvoiceItemDto> items = List.of(
            item(1, "folded", "1", "ชิ้น", "120.00", "ส่วนลด 20 ต่อหน่วย + ส่วนลดพิเศษ 3.37 ต่อหน่วย", "96.63", "96.63"),
            item(2, "special", "1", "ชิ้น", "100.00", "ส่วนลดพิเศษ 5 ต่อหน่วย", "95.00", "95.00"),
            item(3, "no label", "1", "ชิ้น", "50.00", null, "40.00", "40.00"),
            item(4, "thousands", "1", "ชิ้น", "12345.67", null, "10000.00", "10000.00"),
            item(5, "no discount", "1", "ชิ้น", "100.00", "ส่วนลด 9 ต่อหน่วย", "100.00", "100.00"),
            item(6, "net above unit", "1", "ชิ้น", "398.51", null, "399.15", "399.15"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), DEPOSIT, items)); Workbook t = openTemplate(TEMPLATE)) {
            Sheet sh = wb.getSheetAt(0);
            assertDiscountCellPrintsLikeTheFigures(sh, t.getSheet("Update"), ITEM0, "ลด 23.37");
            assertDiscountCellPrintsLikeTheFigures(sh, t.getSheet("Update"), ITEM0 + 1, "ลด 5.00");
            assertDiscountCellPrintsLikeTheFigures(sh, t.getSheet("Update"), ITEM0 + 2, "ลด 10.00");
            assertDiscountCellPrintsLikeTheFigures(sh, t.getSheet("Update"), ITEM0 + 3, "ลด 2,345.67");
            assertThat(isBlank(sh, ITEM0 + 4, 6)).as("net == unit -> G blank").isTrue();
            assertThat(isBlank(sh, ITEM0 + 5, 6)).as("net > unit -> G blank").isTrue();
        }
    }

    /** The template's row 16 (idx 15) carries a grey font on I: the deposit row prints black like every item row,
     * wherever it lands (a wrapped description pushes it onto that row). */
    @Test
    void theDepositRowFiguresPrintBlackLikeTheItemRows() throws Exception {
        String desc = "Porcelain tile 60x60 matte white rectified edge sample item ".repeat(7).trim();
        List<RemainingInvoiceItemDto> items = List.of(item(1, desc, "3", "แผ่น", "120.00", null, "120.00", "360.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), DEPOSIT, items))) {
            Sheet sh = wb.getSheetAt(0);
            int d = rowOf(sh, 1, s -> s.startsWith("หัก"));
            assertThat(d).isGreaterThan(ITEM0);
            short itemBlack = fontColor(sh, ITEM0, 8);
            assertThat(itemBlack).as("item rows use the automatic colour").isIn((short) 32767, (short) 64, (short) 8);
            for (int c : new int[] {4, 7, 8})
                assertThat(fontColor(sh, d, c)).as("deposit row col %d font colour", c).isEqualTo(itemBlack);
        }
    }

    /** One over-long Thai run with NO break opportunity must still wrap, between grapheme clusters. */
    @Test
    void anUnbreakableThaiRunWrapsBetweenClustersNeverBeforeACombiningMark() throws Exception {
        String run = "a\u0E34".repeat(70);   // letter + Thai vowel mark clusters, no line-break opportunity anywhere
        List<RemainingInvoiceItemDto> items = List.of(item(1, run, "1", "แผ่น", "10.00", null, "10.00", "10.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), DEPOSIT, items))) {
            Sheet sh = wb.getSheetAt(0);
            List<Integer> rows = descriptionRows(sh, ITEM0, NOTES_ROW);
            assertThat(rows.size()).isGreaterThan(1);
            StringBuilder joined = new StringBuilder();
            for (int r : rows) {
                String line = str(sh, r, 1);
                joined.append(line);
                assertThat(isThaiCombining(line.codePointAt(0))).as("row %d starts with a combining mark", r).isFalse();
            }
            assertThat(joined.toString()).isEqualTo(run);
        }
    }

    private static List<RemainingInvoiceItemDto> manyItems(int n) {
        List<RemainingInvoiceItemDto> items = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            items.add(item(i, "รายการทดสอบ " + i, "1", "ชิ้น", "1.00", null, "1.00", (i == n ? 40 : 1) + ".00"));
        return items;
    }
}
