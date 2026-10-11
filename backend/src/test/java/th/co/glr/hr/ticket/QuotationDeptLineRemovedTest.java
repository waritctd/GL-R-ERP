package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Sales feedback, Admin 1d (แจ้งปัญหาการใช้งาน เสนอราคา): the ฝ่าย box — "ไม่มีดีกว่า", reps filled it
 * wrongly and some typed their own name into it; take it off the form AND the printout. Owner,
 * 2026-10-06: "remove ฝ่าย field completely".
 *
 * <p>The printed form's header has three lines top right — ฝ่าย / เลขที่อ้างอิง / หน่วยงาน (Dept. /
 * Ref. / D.Co. on the English form). These tests pin the first one being gone from EVERY document
 * this renderer produces, label and value both, and the other two being exactly where they were.
 *
 * <p>Written wrong-way-round on purpose: every fixture here CARRIES a department ("P003" — the
 * five {@link QuotationRendererNoPictureGoldenTest#model} fixtures, i.e. every layout branch plus
 * the English form and the legacy shape). A rule that merely hid the line when the value was
 * blank — which is what the renderer did before this — passes none of them.
 */
class QuotationDeptLineRemovedTest {
    private static final int HEADER_LABEL_COL = 7; // H
    private static final int HEADER_VALUE_COL = 8; // I
    private static final int DEPT_ROW = 2;         // H3 / I3
    private static final int NUMBER_ROW = 3;       // H4 / I4
    private static final int UNIT_ROW = 4;         // H5 / I5

    private final QuotationRenderer renderer = new QuotationRenderer();

    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "english", "legacy-shape"})
    void noDepartmentLabel_isPrinted_anywhereOnTheDocument(String fixture) throws Exception {
        assertThat(allText(render(fixture)))
            .as("cells on the %s document", fixture)
            .noneMatch(t -> t.strip().equals("ฝ่าย"))
            .noneMatch(t -> t.strip().equals("Dept."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "english", "legacy-shape"})
    void theDepartmentTheModelCarries_isPrintedNowhere(String fixture) throws Exception {
        assertThat(QuotationRendererNoPictureGoldenTest.model(fixture).deptCode())
            .as("the fixture really does carry a department — or this test proves nothing")
            .isEqualTo("P003");
        assertThat(allText(render(fixture))).noneMatch(t -> t.contains("P003"));
    }

    /** The slot itself: H3 and I3 are empty, not merely free of those two words. */
    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "english", "legacy-shape"})
    void theDepartmentLinesOwnCells_areEmpty(String fixture) throws Exception {
        Sheet sheet = render(fixture);
        assertThat(text(sheet, DEPT_ROW, HEADER_LABEL_COL)).as("H3 — the label").isEmpty();
        assertThat(text(sheet, DEPT_ROW, HEADER_VALUE_COL)).as("I3 — the value").isEmpty();
    }

    /** Only ฝ่าย went. The reference number and the designer / unit code beside it still print,
     * label and value, on both forms — a fix that cleared the whole block would pass the three
     * tests above. */
    @ParameterizedTest
    @ValueSource(strings = {"single-page", "one-page-scaled", "paginated", "english", "legacy-shape"})
    void theReferenceNumberAndTheUnitCode_stillPrintWithTheirLabels(String fixture) throws Exception {
        Sheet sheet = render(fixture);
        boolean english = fixture.equals("english");
        QuotationRenderModel model = QuotationRendererNoPictureGoldenTest.model(fixture);

        assertThat(text(sheet, NUMBER_ROW, HEADER_LABEL_COL)).isEqualTo(english ? "Ref." : "เลขที่อ้างอิง");
        assertThat(text(sheet, NUMBER_ROW, HEADER_VALUE_COL)).isEqualTo(model.number());
        assertThat(text(sheet, UNIT_ROW, HEADER_LABEL_COL)).isEqualTo(english ? "D.Co." : "หน่วยงาน");
        assertThat(text(sheet, UNIT_ROW, HEADER_VALUE_COL)).isEqualTo("D002");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private Sheet render(String fixture) throws Exception {
        byte[] xls = renderer.toXls(QuotationRendererNoPictureGoldenTest.model(fixture));
        Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xls));
        return wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
    }

    private static List<String> allText(Sheet sheet) {
        List<String> out = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isEmpty()) {
                    out.add(cell.getStringCellValue());
                }
            }
        }
        return out;
    }

    private static String text(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        Cell cell = r == null ? null : r.getCell(col);
        return cell != null && cell.getCellType() == CellType.STRING ? cell.getStringCellValue().strip() : "";
    }
}
