package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import th.co.glr.hr.support.WorkbookTexts;

/**
 * Sales feedback, Admin #6 (แจ้งปัญหาการใช้งาน เสนอราคา, 2026-10-06): "เลขแบบฟอร์มเอกสาร ISO
 * F-SM-002 (03) จำเป็นต้องใส่หรือไม่ // ต้องเพิ่ม". Owner the same day: "just put the ISO F-SM-002 (03)
 * as a footer on the bottom right of the page".
 *
 * <p>The number was never missing from the FORM: {@code quotation_template.xls} — the company's
 * own controlled form — carries it in the last cell of its last row (I47 in the raw file), which
 * is the bottom-right corner of the printed page. The renderer blanked that cell on every Thai
 * document ("F-SM-002 tag, not customer-facing", a decision that predates the English form) while
 * writing {@code F-SM-008 (01)} into the very same cell on English ones. These tests pin the Thai
 * document getting its own number back, in that cell, in every layout the renderer has.
 *
 * <p>"Bottom right" is asserted structurally, not by eye: the cell is in the LAST COLUMN and the
 * LAST ROW of the sheet's print area, so nothing that prints is below it or to its right; on a
 * document that runs to several pages it is on the last one, after the signature block. (What the
 * page looks like is checked on a real PDF, by hand — see the PR body. No test here opens one.)
 *
 * <p>The fixtures are {@link QuotationRendererNoPictureGoldenTest#model}'s — the same five that
 * pin every layout branch — so this cannot pass on a layout the golden test does not know about.
 */
class QuotationFormTagTest {
    private static final String THAI_FORM = "F-SM-002 (03)";
    private static final String ENGLISH_FORM = "F-SM-008 (01)";
    private static final int LAST_COLUMN = 8; // I — เป็นเงิน, the form's rightmost column

    private final QuotationRenderer renderer = new QuotationRenderer();

    // ── the Thai document carries its number ────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "legacy-shape"})
    void aThaiQuotation_printsItsIsoFormNumber_exactlyOnce(String fixture) throws Exception {
        Sheet sheet = render(fixture);
        assertThat(cellsReading(sheet, THAI_FORM))
            .as("cells reading %s on the %s document", THAI_FORM, fixture)
            .hasSize(1);
    }

    /** The owner's "bottom right of the page": last column, last row of what prints. */
    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "legacy-shape"})
    void theFormNumber_isTheBottomRightCellOfThePrintedArea(String fixture) throws Exception {
        Sheet sheet = render(fixture);
        Cell tag = theOnlyCellReading(sheet, THAI_FORM);
        AreaReference printed = printArea(sheet);

        assertThat(tag.getColumnIndex()).as("column").isEqualTo(LAST_COLUMN)
            .isEqualTo((int) printed.getLastCell().getCol());
        assertThat(tag.getRowIndex()).as("row — the last one that prints").isEqualTo(printed.getLastCell().getRow());
    }

    /** A number in a row that does not print is not on the document. */
    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "legacy-shape"})
    void theFormNumbersRow_isNotHidden(String fixture) throws Exception {
        Row row = theOnlyCellReading(render(fixture), THAI_FORM).getRow();
        assertThat(row.getZeroHeight()).as("hidden").isFalse();
        assertThat(row.getHeight()).as("height").isPositive();
    }

    /** It stands alone on its row, UNDER the signature block — it must never share a line with (and
     * so crowd, or be mistaken for part of) a signature, a date or a total. */
    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "legacy-shape"})
    void theFormNumber_standsAloneOnItsRow(String fixture) throws Exception {
        Cell tag = theOnlyCellReading(render(fixture), THAI_FORM);
        List<String> others = new ArrayList<>();
        for (Cell cell : tag.getRow()) {
            if (cell.getColumnIndex() != tag.getColumnIndex() && !text(cell).isBlank()) {
                others.add(text(cell));
            }
        }
        assertThat(others).as("other text on the form number's row").isEmpty();
    }

    /** Several pages: the number is at the foot of the document, i.e. after the last page break —
     * not stranded at the foot of page 1 while the signatures move on. */
    @Test
    void onADocumentOfSeveralPages_theFormNumberIsOnTheLastOne() throws Exception {
        Sheet sheet = render("paginated");
        int[] breaks = sheet.getRowBreaks();
        assertThat(breaks).as("the fixture really does paginate").isNotEmpty();
        assertThat(theOnlyCellReading(sheet, THAI_FORM).getRowIndex())
            .isGreaterThan(Arrays.stream(breaks).max().getAsInt());
    }

    // ── each form keeps its OWN number ──────────────────────────────────────────────────────

    /** F-SM-002 is the Thai form. The English document is F-SM-008 and must not start printing
     * the Thai number because the Thai one now prints. */
    @Test
    void anEnglishQuotation_keepsItsOwnFormNumber_andNeverCarriesTheThaiOne() throws Exception {
        Sheet sheet = render("english");
        assertThat(cellsReading(sheet, ENGLISH_FORM)).hasSize(1);
        assertThat(WorkbookTexts.of(xls("english"))).noneMatch(t -> t.contains("F-SM-002"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "legacy-shape"})
    void aThaiQuotation_neverCarriesTheEnglishFormNumber(String fixture) throws Exception {
        assertThat(WorkbookTexts.of(xls(fixture))).noneMatch(t -> t.contains("F-SM-008"));
    }

    /** Both forms print their number in the same place — one layout rule, two numbers. */
    @Test
    void theTwoForms_printTheirNumberInTheSameCell() throws Exception {
        Cell thai = theOnlyCellReading(render("single-page"), THAI_FORM);
        Cell english = theOnlyCellReading(render("english"), ENGLISH_FORM);
        assertThat(thai.getColumnIndex()).isEqualTo(english.getColumnIndex());
        // Same row too: the two fixtures differ only in language (and item count below the native
        // capacity, which does not move the footer).
        assertThat(thai.getRowIndex()).isEqualTo(english.getRowIndex());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private byte[] xls(String fixture) {
        return renderer.toXls(QuotationRendererNoPictureGoldenTest.model(fixture));
    }

    private Sheet render(String fixture) throws Exception {
        Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xls(fixture)));
        return wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
    }

    private static AreaReference printArea(Sheet sheet) {
        Workbook wb = sheet.getWorkbook();
        String area = wb.getPrintArea(wb.getSheetIndex(sheet));
        assertThat(area).as("print area").isNotNull();
        return new AreaReference(area, SpreadsheetVersion.EXCEL97);
    }

    private static Cell theOnlyCellReading(Sheet sheet, String text) {
        List<Cell> cells = cellsReading(sheet, text);
        assertThat(cells).as("cells reading %s", text).hasSize(1);
        return cells.get(0);
    }

    private static List<Cell> cellsReading(Sheet sheet, String text) {
        List<Cell> out = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (text.equals(text(cell).strip())) {
                    out.add(cell);
                }
            }
        }
        return out;
    }

    private static String text(Cell cell) {
        return cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : "";
    }
}
