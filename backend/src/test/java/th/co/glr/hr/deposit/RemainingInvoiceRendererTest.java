package th.co.glr.hr.deposit;

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
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.LibreOfficePdfConverter;

class RemainingInvoiceRendererTest {
    private final RemainingInvoiceRenderer renderer = new RemainingInvoiceRenderer();

    private void requireLibreOffice() {
        // toXlsx() output is converted to PDF via LibreOffice for this test; skip (don't fail)
        // where it isn't installed — same policy as the repo's Testcontainers-on-Docker gating.
        // CI installs libreoffice-calc.
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(),
            "LibreOffice (soffice) not installed — skipping PDF render test");
    }

    @Test
    void xlsxOutputIsRealBiff8OleBytesNotOoxmlZip() throws Exception {
        // Pins the byte format and the advertised content type together:
        // DepositNoticeController's /tickets/{id}/remaining-invoice/file endpoint serves this
        // exact output as "application/vnd.ms-excel" + ".xls".
        // WorkbookFactory.create(templates/remaining_invoice_template.xls) returns an
        // HSSFWorkbook, so wb.write(out) always emits OLE2/Compound File Binary bytes
        // ([MS-CFB], BIFF8 .xls) — never the ZIP "PK\x03\x04" local-file-header magic an OOXML
        // .xlsx would start with. If this ever drifts to XSSF, this assertion catches it before
        // the response headers do (or don't).
        byte[] xlsx = renderer.toXlsx(document());

        assertThat(xlsx).startsWith((byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1);
    }

    @Test
    void rendersWithoutFormulaErrorsAsASinglePageWithTheCorrectTotal() throws Exception {
        requireLibreOffice();
        // Regression test for the bugs this branch fixed: the renderer used to leave #REF!/
        // #VALUE! in the amount cells. Item 66 × 979.40 = 64,640.40; deposit 32,320.20 is
        // deducted → remainder 32,320.20; VAT 7% = 2,262.41; total payable = 34,582.61.
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
        // customerAddress fixture value — plain Latin text, no whitespace-flattening needed.
        assertThat(text).contains("99/1 Sukhumvit Road");
        // Item amount, deducted deposit, and final total must all appear as numbers.
        assertThat(text).contains("64,640.40");
        assertThat(text).contains("32,320.20");
        assertThat(text).contains("2,262.41");
        assertThat(text).contains("34,582.61");
    }

    /**
     * The branch's own reference sample (owner-supplied, matches the real template's formulas
     * exactly): 46 แผ่น × 806.83 = 37,114.18; deposit 18,557.09 (ref AI2600145) deducted →
     * remainder 18,557.09; I43 stores the UNROUNDED VAT 1298.9963 (18557.09 * 0.07), which the
     * template's own cell format displays as 1,299.00 — this test evaluates the formula with POI
     * directly (no LibreOffice needed) and checks the raw numeric value, not a rendered string, so
     * it also pins that the stored figure is genuinely unrounded until display.
     */
    @Test
    void referenceSampleFormulasEvaluateToTheOwnerSuppliedTotals() throws Exception {
        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            "GLRI69099",
            LocalDate.of(2026, 9, 1),
            "QT-2026-0099",
            "AI2600145",
            "บริษัท ตัวอย่าง จำกัด",
            "สำนักงานใหญ่",
            "1 ถนนตัวอย่าง",
            "0105500000000",
            "โครงการตัวอย่าง",
            new BigDecimal("18557.09"),
            List.of("หมายเหตุทดสอบ 1", "หมายเหตุทดสอบ 2"),
            List.of(new RemainingInvoiceItemDto(
                1, "กระเบื้อง 60x60", new BigDecimal("46"), "แผ่น",
                new BigDecimal("806.83"), null, new BigDecimal("806.83"), new BigDecimal("37114.18")
            ))
        );

        byte[] xlsx = renderer.toXlsx(doc);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();

            // I42 = SUM(I11:I41) = 37114.18 (item) - 18557.09 (deposit) = 18557.09.
            assertThat(round2(eval.evaluate(cell(sh, 41, 8)).getNumberValue())).isEqualTo(18557.09);
            // I43 = I42 * 0.07 — the RAW value is unrounded (1298.9963); rounds to 1,299.00 for display.
            double vatRaw = eval.evaluate(cell(sh, 42, 8)).getNumberValue();
            assertThat(vatRaw).isCloseTo(1298.9963, org.assertj.core.data.Offset.offset(0.0001));
            assertThat(round2(vatRaw)).isEqualTo(1299.00);
            // I44 = I42 + I43 = 18557.09 + 1298.9963 = 19856.0863, rounds to 19,856.09.
            double totalRaw = eval.evaluate(cell(sh, 43, 8)).getNumberValue();
            assertThat(round2(totalRaw)).isEqualTo(19856.09);

            // Item row present at row 13 (0-based 12).
            assertThat(cell(sh, 12, 1).getStringCellValue()).isEqualTo("กระเบื้อง 60x60");
            assertThat(cell(sh, 12, 2).getNumericCellValue()).isEqualTo(46.0);
            // Deposit row directly after (row 14, 0-based 13): B-column label cites depositReference,
            // never doc.reference() (the quotation/PO reference, a separate field — see
            // RemainingInvoiceDto's own header comment for the bug this fixes).
            assertThat(cell(sh, 13, 1).getStringCellValue()).isEqualTo("หัก  มัดจำ  AI2600145");

            // Header cells: B7 = "<name> (<branch>) / เลขประจำตัวผู้เสียภาษี : <taxId>", H9 = reference,
            // H7 = Thai-formatted date.
            assertThat(cell(sh, 6, 1).getStringCellValue())
                .isEqualTo("บริษัท ตัวอย่าง จำกัด (สำนักงานใหญ่) / เลขประจำตัวผู้เสียภาษี : 0105500000000");
            assertThat(cell(sh, 8, 7).getStringCellValue()).isEqualTo("QT-2026-0099");
            assertThat(cell(sh, 6, 7).getStringCellValue()).isEqualTo("1 กันยายน 2569");

            // Selected notes rendered numbered into B36/B37 (0-based rows 35/36); the template's
            // OWN static example lines (rows 38-41, 0-based 37-40) must be cleared, not left stale.
            assertThat(cell(sh, 35, 1).getStringCellValue()).isEqualTo("1. หมายเหตุทดสอบ 1");
            assertThat(cell(sh, 36, 1).getStringCellValue()).isEqualTo("2. หมายเหตุทดสอบ 2");
            for (int r = 37; r <= 40; r++) {
                Cell c = sh.getRow(r) != null ? sh.getRow(r).getCell(1) : null;
                boolean blank = c == null || c.getCellType() == CellType.BLANK
                    || (c.getCellType() == CellType.STRING && c.getStringCellValue().isEmpty());
                assertThat(blank).as("row %d col B should be cleared, not stale example text", r + 1).isTrue();
            }
            // The หมายเหตุ header itself (B35, 0-based 34) is untouched static text.
            assertThat(cell(sh, 34, 1).getStringCellValue()).isEqualTo("หมายเหตุ");
        }
    }

    /**
     * Fills every one of the 22 available item/deposit-deduction rows (rows 13-34, 1-based) and
     * asserts nothing spills past row 34 into the หมายเหตุ header at row 35 — the exact bug the
     * old {@code MAX_ITEM_ROWS = 28} constant caused (it blanked, and could write, past row 34).
     */
    @Test
    void fillsExactlyToCapacityWithoutSpillingIntoTheNotesBlock() throws Exception {
        List<RemainingInvoiceItemDto> items = new ArrayList<>();
        // MAX_ITEM_ROWS - 1 items, leaving exactly one row for the deposit deduction.
        for (int i = 1; i <= RemainingInvoiceRenderer.MAX_ITEM_ROWS - 1; i++) {
            items.add(new RemainingInvoiceItemDto(
                i, "Item " + i, BigDecimal.ONE, "แผ่น",
                BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN));
        }
        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            "GLRI69100", LocalDate.of(2026, 9, 1), "REF", "DEP-REF",
            "Customer", null, "Address", null, "Project",
            new BigDecimal("5"), List.of(), items);

        byte[] xlsx = renderer.toXlsx(doc);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            // Last item at 0-based row 12 + (21-1) = 32 (row 33, 1-based); deposit row at 33 (row 34).
            int lastItemRow = 12 + (RemainingInvoiceRenderer.MAX_ITEM_ROWS - 1) - 1;
            int depositRow = lastItemRow + 1;
            assertThat(cell(sh, lastItemRow, 1).getStringCellValue())
                .isEqualTo("Item " + (RemainingInvoiceRenderer.MAX_ITEM_ROWS - 1));
            assertThat(cell(sh, depositRow, 1).getStringCellValue()).isEqualTo("หัก  มัดจำ  DEP-REF");
            // Row 35 (0-based 34) — the หมายเหตุ header — must be untouched, not overwritten by an
            // item/deposit row spilling over.
            assertThat(cell(sh, 34, 1).getStringCellValue()).isEqualTo("หมายเหตุ");
        }
    }

    /** Never silently truncates — see RemainingInvoiceRenderer's own class Javadoc: this is a
     * defensive fail-safe behind DepositNoticeService's own capacity gate, not the primary one. */
    @Test
    void throwsRatherThanTruncatingWhenOverCapacity() {
        List<RemainingInvoiceItemDto> items = new ArrayList<>();
        for (int i = 1; i <= RemainingInvoiceRenderer.MAX_ITEM_ROWS + 1; i++) {
            items.add(new RemainingInvoiceItemDto(
                i, "Item " + i, BigDecimal.ONE, "แผ่น", BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN));
        }
        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            "GLRI69101", LocalDate.now(), null, null, "Customer", null, null, null, null,
            BigDecimal.ZERO, List.of(), items);

        assertThatThrownBy(() -> renderer.toXlsx(doc)).isInstanceOf(IllegalStateException.class);
    }

    /** Finding 2a: every item row (and the deposit row) must share row 13's own CellStyle per
     * column A-I — otherwise the template's own leftover per-row styling shows through (amounts
     * printing grey from ~row 16, a negative deposit amount printing "-18,557.09" instead of the
     * accounting format's "(18,557.09)"). Fills all 22 rows (21 items + 1 deposit row) so every
     * row in the zone is checked, not just the first couple. */
    @Test
    void everyItemAndDepositRowSharesRow13sCellStyle() throws Exception {
        List<RemainingInvoiceItemDto> items = new ArrayList<>();
        for (int i = 1; i <= RemainingInvoiceRenderer.MAX_ITEM_ROWS - 1; i++) {
            items.add(new RemainingInvoiceItemDto(
                i, "Item " + i, BigDecimal.ONE, "แผ่น", BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN));
        }
        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            "GLRI69102", LocalDate.of(2026, 9, 1), "REF", "DEP-REF",
            "Customer", null, "Address", null, "Project",
            new BigDecimal("5"), List.of(), items);

        byte[] xlsx = renderer.toXlsx(doc);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            int templateRow = 12; // 0-based row 13
            for (int c = 0; c <= 8; c++) {
                int templateStyleIndex = cell(sh, templateRow, c).getCellStyle().getIndex();
                for (int r = templateRow; r < templateRow + RemainingInvoiceRenderer.MAX_ITEM_ROWS; r++) {
                    Row row = sh.getRow(r);
                    Cell c1 = row != null ? row.getCell(c) : null;
                    assertThat(c1).as("row %d col %d exists", r + 1, c).isNotNull();
                    assertThat((int) c1.getCellStyle().getIndex())
                        .as("row %d col %d style should match row 13's", r + 1, c)
                        .isEqualTo(templateStyleIndex);
                }
            }
        }
    }

    /** Finding 2c: a note selection that would need more than MAX_NOTE_LINES physical lines must
     * refuse (defensive fail-safe; DepositNoticeService gates this first in normal use — see its
     * own notes-capacity check mirrored via wrapNotes). */
    @Test
    void throwsRatherThanOverflowingTheNotesBlock() {
        List<String> longNotes = new ArrayList<>();
        for (int i = 1; i <= RemainingInvoiceRenderer.MAX_NOTE_LINES + 1; i++) {
            // Each note is deliberately longer than NOTE_LINE_CHAR_BUDGET so it needs its own line
            // and cannot be packed with a neighbour — guarantees rowsNeeded > MAX_NOTE_LINES.
            longNotes.add("บรรทัดหมายเหตุที่ยาวมากเป็นพิเศษเพื่อให้ล้นบรรทัดของมันเองแน่นอน หมายเลข " + i);
        }
        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            "GLRI69103", LocalDate.now(), null, null, "Customer", null, null, null, null,
            BigDecimal.ZERO, longNotes, List.of());

        assertThatThrownBy(() -> renderer.toXlsx(doc)).isInstanceOf(IllegalStateException.class);
    }

    /** Finding 2b: wrapNotes/renderer aside, the discount label written into column G must never
     * exceed the defensive character budget regardless of how it was constructed — proven here by
     * feeding a long, unshortened label directly (as a legacy deposit-notice item's own
     * discountLabel might carry) and asserting the written cell is clamped, not overflowing. */
    @Test
    void discountLabelLongerThanColumnGIsClamped() throws Exception {
        RemainingInvoiceItemDto item = new RemainingInvoiceItemDto(
            1, "Item", BigDecimal.ONE, "แผ่น", BigDecimal.TEN,
            "ส่วนลดพิเศษสำหรับลูกค้าเก่าที่สั่งซื้อจำนวนมากเป็นประจำ", BigDecimal.TEN, BigDecimal.TEN);
        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            "GLRI69104", LocalDate.of(2026, 9, 1), "REF", null,
            "Customer", null, "Address", null, "Project", BigDecimal.ZERO, List.of(), List.of(item));

        byte[] xlsx = renderer.toXlsx(doc);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            String written = cell(sh, 12, 6).getStringCellValue();
            assertThat(written.length()).isLessThanOrEqualTo(11);
        }
    }

    /** Finding 2 (this branch's own fix): a realistic short discount label — the exact shape
     * {@code itemsFromQuotationForRemainingInvoice} writes — must render UNCLAMPED and must sit in
     * a column G actually wide enough to hold it, proven by measuring the real rendered column
     * width (via POI, the same units {@code toXlsx}'s own widening code reads) rather than trusting
     * the string alone. Regression pin for the bug: "ลด 43.17" is 8 characters, comfortably under
     * even the OLD DISCOUNT_LABEL_CHAR_BUDGET=10, yet clipped in the actual PDF because the column
     * itself — not the string length — was too narrow. */
    @Test
    void shortDiscountLabelIsNotClampedAndColumnGIsWideEnoughToHoldIt() throws Exception {
        RemainingInvoiceItemDto item = new RemainingInvoiceItemDto(
            1, "Item", BigDecimal.TEN, "แผ่น", new BigDecimal("100"),
            "ลด 43.17", new BigDecimal("56.83"), new BigDecimal("568.30"));
        RemainingInvoiceDto doc = new RemainingInvoiceDto(
            "GLRI69105", LocalDate.of(2026, 9, 1), "REF", null,
            "Customer", null, "Address", null, "Project", BigDecimal.ZERO, List.of(), List.of(item));

        byte[] xlsx = renderer.toXlsx(doc);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(cell(sh, 12, 6).getStringCellValue()).isEqualTo("ลด 43.17"); // not clamped
            // Column G must now be at least as wide as column E/H (the money columns) — the fix
            // widens it to match rather than leaving it at the template's original, too-narrow width.
            assertThat(sh.getColumnWidth(6)).isGreaterThanOrEqualTo(sh.getColumnWidth(4));
        }
    }

    /** wrapNotes itself: a short note fits on one line unwrapped; a long one splits onto
     * continuation lines with no repeated number prefix (matches the template's own pre-authored
     * example, where note 2 spans 3 physical lines). */
    @Test
    void wrapNotesSplitsLongNotesOntoContinuationLines() {
        List<String> notes = List.of(
            "สั้น",
            "คำอธิบายยาวมากที่ต้องการหลายบรรทัดเพื่อให้พอดีกับความกว้างของคอลัมน์ B ในแบบฟอร์มใบแจ้งหนี้ส่วนที่เหลือทั้งหมดนี้"
        );

        List<String> lines = RemainingInvoiceRenderer.wrapNotes(notes);

        assertThat(lines.get(0)).isEqualTo("1. สั้น");
        assertThat(lines.size()).isGreaterThan(2); // note 2 alone needs more than one line
        assertThat(lines.get(1)).startsWith("2. ");
        // No continuation line repeats a number prefix.
        for (int i = 2; i < lines.size(); i++) {
            assertThat(lines.get(i)).doesNotStartWith("2.");
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private Cell cell(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        assertThat(row).as("row %d exists", rowIdx + 1).isNotNull();
        Cell c = row.getCell(colIdx);
        assertThat(c).as("cell at row %d col %d exists", rowIdx + 1, colIdx).isNotNull();
        return c;
    }

    private RemainingInvoiceDto document() {
        return new RemainingInvoiceDto(
            "GLRI69001",
            LocalDate.of(2026, 7, 5),
            "REF-1",
            "REF-1",
            "บริษัท เอซีเอ็มอี จำกัด",
            null,
            "99/1 Sukhumvit Road",
            "0100000000000",
            "โครงการโชว์รูม",
            new BigDecimal("32320.20"),
            List.of(),
            List.of(new RemainingInvoiceItemDto(
                1,
                "Tile 60x60",
                new BigDecimal("66"),
                "pcs",
                new BigDecimal("979.40"),
                null,
                new BigDecimal("979.40"),
                new BigDecimal("64640.40")
            ))
        );
    }
}
