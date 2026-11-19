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
import th.co.glr.hr.common.sheet.LibreOfficeMetrics;

/**
 * The fresh deposit-notice engine (docs: pdf-generators-fresh-engine-design.md): a render MODEL ->
 * POI fill of templates/deposit_notice_template.xls (sheet "Update") -> LibreOffice PDF. Every
 * printed value is the STORED value. All cell coordinates are 0-based (row, col); the design's
 * Excel names are in the comments. All names/ids/amounts below are made up.
 */
class DepositNoticePdfRendererTest {
    private static final String TEMPLATE = "/templates/deposit_notice_template.xls";
    // Native layout (item count within the template's zone).
    private static final int ITEM0 = 12, NOTES_ROW = 36;
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

    private static void requireLibreOffice() {
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(), "LibreOffice (soffice) not installed");
    }

    @Test
    void headerBlockLandsInTheDesignCellMapAndKeepsTheLabels() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(norm(str(sh, 6, 1))).isEqualTo("บริษัท ทดสอบ จำกัด (สำนักงานใหญ่) / เลขประจำตัวผู้เสียภาษี : 0105599999999"); // B7
            assertThat(norm(str(sh, 6, 8))).isEqualTo("28 ตุลาคม 2569");                       // I7 date
            assertThat(norm(str(sh, 7, 1))).isEqualTo("99 ถนนทดสอบ แขวงทดสอบ กรุงเทพฯ 10000");  // B8 address
            assertThat(norm(str(sh, 7, 8))).isEqualTo("GLRD69999");                             // I8 doc no
            assertThat(norm(str(sh, 8, 8))).isEqualTo("QT-TEST-0001");                          // I9 reference VALUE
            assertThat(norm(str(sh, 8, 7))).startsWith("อ้างอิง");                              // H9 label kept
            assertThat(norm(str(sh, 10, 1))).isEqualTo("Project : ตึกตัวอย่าง");                // B11
        }
    }

    @Test
    void customerLineOmitsABlankBranchAndNeverPrintsNull() throws Exception {
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), null, "0105599999999", twoItems()))) {
            assertThat(norm(str(wb.getSheetAt(0), 6, 1)))
                .isEqualTo("บริษัท ทดสอบ จำกัด / เลขประจำตัวผู้เสียภาษี : 0105599999999");
        }
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สาขา 1", null, twoItems()))) {
            assertThat(str(wb.getSheetAt(0), 6, 1)).contains("บริษัท ทดสอบ จำกัด (สาขา 1)").doesNotContain("null");
        }
    }

    @Test
    void itemRowsPrintStoredValuesWithNumbersNotFormulas() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            int r = ITEM0;
            assertThat(seq(sh, r)).isEqualTo(1);                                                 // A
            assertThat(norm(str(sh, r, 1))).isEqualTo("กระเบื้องทดสอบ รุ่น AAA");                 // B
            assertThat(num(sh, r, 2)).isEqualTo(3.0);                                            // C qty
            assertThat(str(sh, r, 3)).isEqualTo("แผ่น");                                         // D unit
            assertThat(num(sh, r, 4)).isEqualTo(120.00);                                         // E unit price
            assertThat(norm(str(sh, r, 6))).isEqualTo("พิเศษ");                                  // G = the stored label, as-is (no "ลด ")
            assertThat(num(sh, r, 7)).isEqualTo(100.00);                                         // H net
            assertThat(num(sh, r, 8)).isCloseTo(299.99, within(0.0001));                         // I stored amount
            r = ITEM0 + 1;
            assertThat(seq(sh, r)).isEqualTo(2);
            assertThat(isBlank(sh, r, 6)).as("no discount -> G blank (no template 'พิเศษ')").isTrue();
            assertThat(num(sh, r, 8)).isEqualTo(111.00);
            for (int row = ITEM0; row <= ITEM0 + 1; row++)
                for (int c = 0; c <= 9; c++)
                    assertThat(str(sh, row, c)).as("(%d,%d) no formula", row, c).doesNotStartWith("FORMULA:");
        }
    }

    // A quotation's ส่วนลดพิเศษ line (qty -1, negative stored amount, no unit) prints as its own row with the stored values.
    @Test
    void aNegativeDiscountLine_printsItsStoredValuesWithAnEmptyUnit() throws Exception {
        List<DepositNoticeItemDto> items = new java.util.ArrayList<>(twoItems());
        items.add(item(3, "ส่วนลดพิเศษ 10 บาท", "-1", "", "10.00", null, "10.00", "-10.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", items))) {
            Sheet sh = wb.getSheetAt(0);
            int r = ITEM0 + 2;
            assertThat(norm(str(sh, r, 1))).isEqualTo("ส่วนลดพิเศษ 10 บาท");
            assertThat(num(sh, r, 2)).isEqualTo(-1.0);
            assertThat(isBlank(sh, r, 3)).as("empty unit cell").isTrue();
            assertThat(num(sh, r, 7)).isCloseTo(10.0, within(0.0001));
            assertThat(num(sh, r, 8)).isCloseTo(-10.0, within(0.0001));
        }
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
        }
    }

    @Test
    void preparerSignatureDateAndSheetHygiene() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(wb.getNumberOfSheets()).as("only the Update sheet").isEqualTo(1);
            assertThat(norm(str(sh, PREPARER, 1))).isEqualTo("( สมหญิง ทดสอบ )");                // B48
            assertThat(norm(str(sh, SIGDATE, 1))).isEqualTo("วันที่ 28/10/2569");
            // the template's own alignment is kept (B is the full-width description column: centring there misplaces the lines)
            try (Workbook own = openTemplate(TEMPLATE)) {
                Sheet t = own.getSheet("Update");
                assertThat(sh.getRow(PREPARER).getCell(1).getCellStyle().getAlignment())
                    .isEqualTo(t.getRow(PREPARER).getCell(1).getCellStyle().getAlignment());
                assertThat(sh.getRow(SIGDATE).getCell(1).getCellStyle().getAlignment())
                    .isEqualTo(t.getRow(SIGDATE).getCell(1).getCellStyle().getAlignment());
            }                 // B49
            assertThat(allStrings(sh)).noneMatch(s -> s.contains("มิ.ย"));
        }
        try (Workbook wb = render(model(LocalDate.of(2026, 1, 5), "สำนักงานใหญ่", "0105599999999", twoItems()))) {
            assertThat(norm(str(wb.getSheetAt(0), SIGDATE, 1))).isEqualTo("วันที่ 5/1/2569"); // d/M, no padding
        }
    }

    @Test
    void notesBlockIsTheTemplatesOwnAndPrintedOnce() throws Exception {
        List<String> expected = templateNoteLines(TEMPLATE);
        assertThat(expected).as("template ships its own note lines").anyMatch(s -> !s.isBlank());
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(rowOfExact(sh, 1, NOTES_HEADER)).isEqualTo(NOTES_ROW);
            assertThat(noteLines(sh)).isEqualTo(expected);
            assertThat(allStrings(sh).stream().filter(s -> s.contains(NOTES_HEADER) && norm(s).equals(NOTES_HEADER)))
                .as("หมายเหตุ header printed once").hasSize(1);
            String firstLine = norm(expected.get(0));
            assertThat(allStrings(sh).stream().filter(s -> norm(s).equals(firstLine))).hasSize(1);
        }
    }

    @Test
    void aLongDescriptionWrapsOntoContinuationRowsInsteadOfBeingClipped() throws Exception {
        String desc = "Porcelain tile 60x60 matte white rectified edge sample item ".repeat(7).trim();
        List<DepositNoticeItemDto> items = List.of(
            item(1, desc, "3", "แผ่น", "120.00", null, "120.00", "360.00"),
            item(2, "ยาแนวทดสอบ", "2", "ถุง", "55.50", null, "55.50", "111.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", items))) {
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
            assertThat(num(sh, ITEM0, 8)).isEqualTo(360.00);                           // numbers on the FIRST row only
            int second = ITEM0 + rows.size();
            assertThat(seq(sh, second)).as("next item follows the continuation rows").isEqualTo(2);
            assertThat(rowOfExact(sh, 1, NOTES_HEADER)).as("still within capacity: footer at native rows").isEqualTo(NOTES_ROW);
        }
    }

    @Test
    void manyItemsFlowTheFooterDownExtendThePrintAreaAndRepeatTheHeader() throws Exception {
        List<DepositNoticeItemDto> items = manyItems(40);
        List<String> expectedNotes = templateNoteLines(TEMPLATE);
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", items))) {
            Sheet sh = wb.getSheetAt(0);
            int header = rowOfExact(sh, 1, NOTES_HEADER);
            assertThat(header).as("notes header moved below all 40 item rows").isGreaterThanOrEqualTo(ITEM0 + 40);
            int delta = header - NOTES_ROW;
            assertThat(seq(sh, ITEM0 + 39)).isEqualTo(40);
            assertThat(num(sh, ITEM0 + 39, 8)).isEqualTo(40.0);
            assertThat(noteLines(sh)).isEqualTo(expectedNotes);
            assertThat(num(sh, SUBTOTAL + delta, 8)).isCloseTo(1234.56, within(0.0001));
            assertThat(num(sh, DEPOSIT + delta, 8)).isCloseTo(370.40, within(0.0001));
            assertThat(num(sh, VAT + delta, 8)).isCloseTo(25.93, within(0.0001));
            assertThat(num(sh, TOTAL + delta, 8)).isCloseTo(396.33, within(0.0001));
            assertThat(norm(str(sh, PREPARER + delta, 1))).isEqualTo("( สมหญิง ทดสอบ )");
            assertThat(norm(str(sh, SIGDATE + delta, 1))).isEqualTo("วันที่ 28/10/2569");
            assertThat(printAreaLastRow(wb)).as("print area covers the signature date row").isGreaterThanOrEqualTo(SIGDATE + delta + 1);
            assertThat(sh.getRepeatingRows()).as("repeating header rows A1:I10").isNotNull();
            assertThat(sh.getRepeatingRows().getFirstRow()).isEqualTo(0);
            assertThat(sh.getRepeatingRows().getLastRow()).isEqualTo(9);
            for (int r = ITEM0; r < ITEM0 + 40; r++)
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
            assertThat(text).contains("396.33", "370.40", "25.93", "1,234.56", "299.99", "28/10/2569");
        }
    }

    @Test
    void pdfMoneyColumnsAreWideEnoughForLargeStoredFigures() throws Exception {
        requireLibreOffice();
        DepositNoticeRenderModel m = new DepositNoticeRenderModel(LocalDate.of(2026, 10, 28), "GLRD69999",
            "บริษัท ทดสอบ จำกัด", "สำนักงานใหญ่", "0105599999999", "99 ถนนทดสอบ", "ตึกตัวอย่าง", "QT-TEST-0001",
            List.of(item(1, "ของทดสอบ", "1", "ชุด", "98765432.10", null, "98765432.10", "98765432.10")),
            new BigDecimal("98765432.10"), new BigDecimal("0.50"), new BigDecimal("49382716.05"),
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
                model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", manyItems(120))))) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            int blockPages = 0;
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(p);
                s.setEndPage(p);
                String text = s.getText(doc);
                assertThat(text).as("page %d repeats the column header", p).contains("ลำดับ");
                boolean hasNotes = text.contains(NOTES_HEADER);
                boolean hasTotal = text.contains("396.33");
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
            try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", manyItems(n)))) {
                Sheet sh = wb.getSheetAt(0);
                int header = rowOfExact(sh, 1, NOTES_HEADER);
                assertThat(header).as("n=%d notes header", n).isGreaterThanOrEqualTo(NOTES_ROW);
                int d = header - NOTES_ROW;
                assertRowsVisible(sh, ITEM0, SIGDATE + d);
                assertThat(noteLines(sh)).as("n=%d notes", n).isEqualTo(expectedNotes);
            assertThat(num(sh, SUBTOTAL + d, 8)).isCloseTo(1234.56, within(0.0001));
            assertThat(num(sh, DEPOSIT + d, 8)).isCloseTo(370.40, within(0.0001));
            assertThat(num(sh, VAT + d, 8)).isCloseTo(25.93, within(0.0001));
            assertThat(num(sh, TOTAL + d, 8)).isCloseTo(396.33, within(0.0001));
                assertThat(norm(str(sh, PREPARER + d, 1))).as("n=%d preparer", n).isEqualTo("( สมหญิง ทดสอบ )");
                assertThat(norm(str(sh, SIGDATE + d, 1))).as("n=%d signature date", n).isEqualTo("วันที่ 28/10/2569");
            }
        }
    }

    @Test
    void pdfWith25ItemsPrintsEveryTotalAndTheSignatureDate() throws Exception {
        requireLibreOffice();
        try (PDDocument doc = Loader.loadPDF(renderer.toPdf(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", manyItems(25))))) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("396.33", "370.40", "25.93", "28/10/2569");
        }
    }

    /** A null/blank unit (or no seq) prints blank, never the template's own example "แผ่น" / "1". */
    @Test
    void theItemZoneKeepsNoTemplateConstants() throws Exception {
        List<DepositNoticeItemDto> items = List.of(item(0, "ไม่มีหน่วย", "1", null, "10.00", null, "10.00", "10.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", items))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(isBlank(sh, ITEM0, 3)).as("unit D13").isTrue();
            assertThat(isBlank(sh, ITEM0, 0)).as("seq A13").isTrue();
        }
    }

    /** ผู้จัดทำ prints the CHOSEN person's signature in place of the template's baked picture, centred on the
     * underscore line (the original form's layout); with no signature there is no picture at all (the logo stays). */
    @Test
    void preparerSignature_replacesTheBakedPicture_centredOnTheLine() throws Exception {
        DepositNoticeRenderModel m = model();
        byte[] png = signaturePng(120, 40);
        try (Workbook wb = render(new DepositNoticeRenderModel(m.issueDate(), m.docNumber(), m.customerName(), m.customerBranch(),
                m.customerTaxId(), m.customerAddress(), m.projectName(), m.reference(), m.items(), m.subtotal(), m.depositPercent(),
                m.depositAmount(), m.vatAmount(), m.totalPayable(), m.preparerName(), png))) {
            Sheet sh = wb.getSheetAt(0);
            var pics = signaturePictures(sh, PREPARER);
            assertThat(pics).as("exactly one picture in the preparer block").hasSize(1);
            assertThat(pics.get(0).getPictureData().getData()).as("the uploaded image, not the template's baked one").isEqualTo(png);
            double lineCentre = LibreOfficeMetrics.TEXT_INSET_TWIPS / 20.0 + visibleCentrePt(sh, PREPARER - 1);
            assertThat(pictureCentrePt(sh, pics.get(0))).isCloseTo(lineCentre, within(0.03 * columnBPt(sh)));
            assertThat(otherPictureCount(sh, PREPARER)).as("the logo stays").isEqualTo(1);
        }
    }

    @Test
    void noPreparerSignature_printsNoPictureInThePreparerBlock() throws Exception {
        try (Workbook wb = render(model())) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(signaturePictures(sh, PREPARER)).isEmpty();
            assertThat(otherPictureCount(sh, PREPARER)).as("the logo stays").isEqualTo(1);
        }
    }

    /** The name and the date sit centred under the underscore line, short or long. */
    @Test
    void preparerNameAndDate_areCentredUnderTheLine() throws Exception {
        for (String name : List.of("สมชาย ใจดี", "วิภาวดี ศรีสมบูรณ์พูนผล")) {
            DepositNoticeRenderModel m = model();
            try (Workbook wb = render(new DepositNoticeRenderModel(m.issueDate(), m.docNumber(), m.customerName(), m.customerBranch(),
                    m.customerTaxId(), m.customerAddress(), m.projectName(), m.reference(), m.items(), m.subtotal(), m.depositPercent(),
                    m.depositAmount(), m.vatAmount(), m.totalPayable(), name))) {
                Sheet sh = wb.getSheetAt(0);
                double tol = 0.03 * columnBPt(sh);
                double lineCentre = visibleCentrePt(sh, PREPARER - 1);
                assertThat(norm(str(sh, PREPARER, 1))).isEqualTo("( " + name + " )");
                assertThat(visibleCentrePt(sh, PREPARER)).as(name).isCloseTo(lineCentre, within(tol));
                assertThat(visibleCentrePt(sh, SIGDATE)).as("date under " + name).isCloseTo(lineCentre, within(tol));
            }
        }
    }

    /** A document with no preparer prints no name: the template's own line carries a real person's name, and
     * ผู้จัดทำ must be whoever made the document (sales manager ruling 2026-11-18). */
    @Test
    void aMissingPreparerPrintsNoName() throws Exception {
        try (Workbook wb = render(new DepositNoticeRenderModel(LocalDate.of(2026, 10, 28), "GLRD69999", "บริษัท ทดสอบ จำกัด", null, "0105599999999", "99 ถนนทดสอบ", "ตึกตัวอย่าง", "QT-TEST-0001", twoItems(), STORED_SUBTOTAL, new BigDecimal("0.30"), STORED_DEPOSIT, STORED_VAT, STORED_TOTAL, null))) {
            assertThat(norm(str(wb.getSheetAt(0), PREPARER, 1))).isEmpty();
        }
    }

    /** Thai text is broken at word boundaries; a line never starts with a combining vowel or tone mark. */
    @Test
    void thaiDescriptionsWrapWithoutOrphaningCombiningMarks() throws Exception {
        String base = "กระเบื้องพอร์ซเลนเคลือบผิวมันวาวขนาดหกสิบคูณหกสิบเซนติเมตรชนิดขอบตรงคุณภาพส่งออกบรรจุกล่องละสี่แผ่นสำหรับปูพื้นภายในอาคารที่พักอาศัยและสำนักงาน";
        for (int pad = 0; pad < 40; pad++) { // shifts every cut point, so some would land mid-cluster
            String desc = "ก".repeat(pad) + base;
            List<DepositNoticeItemDto> items = List.of(item(1, desc, "1", "แผ่น", "10.00", null, "10.00", "10.00"));
            try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", items))) {
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

    /** The HTML preview shows the template's note lines (what the PDF prints), never the stored notes. */
    @Test
    void previewShowsTheTemplatesNoteLinesNotTheStoredNotes() throws Exception {
        String first = norm(templateNoteLines(TEMPLATE).get(0));
        DepositNoticeDto doc = new DepositNoticeDto(99L, 10L, "DEPOSIT_NOTICE", 1, "GLRD69999", LocalDate.of(2026, 10, 28),
            "ISSUED", "บริษัท ทดสอบ จำกัด", "0105599999999", "99 ถนนทดสอบ", "ตึกตัวอย่าง", "QT-TEST-0001", "THB",
            new BigDecimal("0.30"), STORED_SUBTOTAL, STORED_DEPOSIT, new BigDecimal("0.07"), STORED_VAT, STORED_TOTAL,
            List.of("SENTINEL-NOTE-MUST-NOT-SHOW"), true, true, "Sales", "สมหญิง ทดสอบ", null, null, null, List.of(
                item(1, "ราคาพิเศษ", "1", "ชิ้น", "120.00", "พิเศษ", "100.00", "100.00"),
                item(2, "ไม่ลด", "1", "ชิ้น", "100.00", "Net", "100.00", "100.00"),
                item(3, "ลดเปอร์เซ็นต์", "1", "ชิ้น", "398.51", "12.5%", "399.15", "399.15")));
        String html = renderer.toPreviewHtml(doc, LocalDate.of(2026, 10, 28));
        assertThat(html).doesNotContain("SENTINEL-NOTE-MUST-NOT-SHOW");
        assertThat(html).contains(first);
        // the preview prints the same discount cell as the PDF: the stored label as-is, never a computed "ลด x"
        assertThat(html).contains(">พิเศษ<").contains(">Net<").contains(">12.5%<");
        assertThat(html).as("no computed per-unit discount, no ลด prefix").doesNotContain("ลด 20.00").doesNotContain("ลด ");
    }

    /** Stored text is escaped in the preview, and a draft (no issue date) shows the given "today". */
    @Test
    void previewEscapesStoredTextAndShowsTodayForADraft() {
        DepositNoticeDto draft = new DepositNoticeDto(98L, 10L, "DEPOSIT_NOTICE", 1, null, null, "DRAFT",
            "บริษัท <i>ทดสอบ</i>", "0105599999999", "99 <u>ถนน</u>", "<b>x</b>", "QT-TEST-0001", "THB",
            new BigDecimal("0.30"), STORED_SUBTOTAL, STORED_DEPOSIT, new BigDecimal("0.07"), STORED_VAT, STORED_TOTAL,
            List.of(), false, false, "Sales", "สมหญิง ทดสอบ", null, null, null, List.of());
        String html = renderer.toPreviewHtml(draft, LocalDate.of(2026, 10, 29));
        assertThat(html).contains("29 ตุลาคม 2569");
        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x</b>");
        assertThat(html).doesNotContain("<i>ทดสอบ</i>").doesNotContain("<u>ถนน</u>");
    }

    /** The stored address already carries the branch (createDraft appended it): it prints exactly once, in B8. */
    @Test
    void aBranchInsideTheStoredAddressPrintsOnce() throws Exception {
        DepositNoticeRenderModel m = new DepositNoticeRenderModel(LocalDate.of(2026, 10, 28), "GLRD69999",
            "บริษัท ทดสอบ จำกัด", null, "0105599999999", "99 ถนนทดสอบ สำนักงานใหญ่", "ตึกตัวอย่าง", "QT-TEST-0001",
            twoItems(), STORED_SUBTOTAL, new BigDecimal("0.30"), STORED_DEPOSIT, STORED_VAT, STORED_TOTAL, "สมหญิง ทดสอบ");
        try (Workbook wb = render(m)) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(norm(str(sh, 7, 1))).isEqualTo("99 ถนนทดสอบ สำนักงานใหญ่");
            assertThat(str(sh, 6, 1)).doesNotContain("สำนักงานใหญ่");
        }
    }

    /** The ส่วนลด cell prints the stored discount_label AS-IS (it is the quotation's own ส่วนลด cell text: พิเศษ / Net /
     * N%), never a computed "ลด <unit - net>" and never a "ลด " prefix. A null/blank label (a discount line) leaves
     * G empty even when net < unit. Same font/alignment as the figures, width = E, B untouched. */
    @Test
    void theDiscountColumnPrintsTheStoredLabelAsIs() throws Exception {
        List<DepositNoticeItemDto> items = List.of(
            item(1, "special", "1", "ชิ้น", "120.00", "พิเศษ", "96.63", "96.63"),
            item(2, "net", "1", "ชิ้น", "100.00", "Net", "100.00", "100.00"),
            item(3, "percent", "1", "ชิ้น", "12345.67", "12.5%", "10000.00", "10000.00"),
            item(4, "null label, net < unit", "1", "ชิ้น", "50.00", null, "40.00", "40.00"),
            item(5, "blank label (discount line)", "-1", "", "10.00", "", "10.00", "-10.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", items)); Workbook t = openTemplate(TEMPLATE)) {
            Sheet sh = wb.getSheetAt(0);
            assertDiscountCellPrintsLikeTheFigures(sh, t.getSheet("Update"), ITEM0, "พิเศษ");
            assertDiscountCellPrintsLikeTheFigures(sh, t.getSheet("Update"), ITEM0 + 1, "Net");
            assertDiscountCellPrintsLikeTheFigures(sh, t.getSheet("Update"), ITEM0 + 2, "12.5%");
            assertThat(isBlank(sh, ITEM0 + 3, 6)).as("null label -> G blank, not a computed ลด 10.00").isTrue();
            assertThat(isBlank(sh, ITEM0 + 4, 6)).as("discount line keeps an empty ส่วนลด cell").isTrue();
            for (int r = ITEM0; r <= ITEM0 + 4; r++)
                assertThat(str(sh, r, 6)).as("row %d G", r).doesNotStartWith("ลด");
        }
    }

    /** Item 2: a description carrying the quotation's size line on a SECOND line (the "\n" the quotation-copy writes)
     * prints on its own template row under the product line, in the description column, with qty/price on row 1. */
    @Test
    void aSizeLineOnASecondDescriptionLinePrintsUnderTheProduct() throws Exception {
        String size = "ขนาด 6 cm x 24.6 cm x 8 mm (ขนาดโดยประมาณ)";
        List<DepositNoticeItemDto> items = List.of(
            item(1, "กระเบื้อง รุ่น Tribeca สี Sage green ผิว Glossy\n" + size, "3", "แผ่น", "120.00", "พิเศษ", "100.00", "300.00"),
            item(2, "ยาแนวทดสอบ", "2", "ถุง", "55.50", "Net", "55.50", "111.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", items))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(norm(str(sh, ITEM0, 1))).isEqualTo("กระเบื้อง รุ่น Tribeca สี Sage green ผิว Glossy");
            assertThat(norm(str(sh, ITEM0 + 1, 1))).isEqualTo(size);
            assertThat(num(sh, ITEM0, 8)).as("amount stays on the product row").isEqualTo(300.00);
            assertThat(isBlank(sh, ITEM0 + 1, 8)).as("the size row carries no figures").isTrue();
            assertThat(seq(sh, ITEM0 + 2)).as("the next item follows the size row").isEqualTo(2);
        }
    }

    /** One over-long Thai run with NO break opportunity must still wrap, between grapheme clusters. */
    @Test
    void anUnbreakableThaiRunWrapsBetweenClustersNeverBeforeACombiningMark() throws Exception {
        String run = "a\u0E34".repeat(70);   // letter + Thai vowel mark clusters, no line-break opportunity anywhere
        List<DepositNoticeItemDto> items = List.of(item(1, run, "1", "แผ่น", "10.00", null, "10.00", "10.00"));
        try (Workbook wb = render(model(LocalDate.of(2026, 10, 28), "สำนักงานใหญ่", "0105599999999", items))) {
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

    private static List<DepositNoticeItemDto> manyItems(int n) {
        List<DepositNoticeItemDto> items = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            items.add(item(i, "รายการทดสอบ " + i, "1", "ชิ้น", "1.00", null, "1.00", String.valueOf(i == n ? 40 : 1) + ".00"));
        return items;
    }
}
