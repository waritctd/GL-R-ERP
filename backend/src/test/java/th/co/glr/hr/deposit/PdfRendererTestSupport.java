package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import th.co.glr.hr.common.sheet.FontResolver;
import th.co.glr.hr.common.sheet.LibreOfficeMetrics;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import th.co.glr.hr.common.sheet.FontResolver;
import th.co.glr.hr.common.sheet.LibreOfficeMetrics;

/** Shared POI readers for the deposit-notice / remaining-invoice PDF-engine tests. 0-based (row, col). */
final class PdfRendererTestSupport {
    private PdfRendererTestSupport() {}

    /** The template's own หมายเหตุ block: header + this many lines below it (both documents). */
    static final int NOTE_LINES = 6;
    static final String NOTES_HEADER = "หมายเหตุ";

    static Workbook open(byte[] xls) throws Exception {
        return WorkbookFactory.create(new ByteArrayInputStream(xls));
    }

    static Workbook openTemplate(String resource) throws Exception {
        try (InputStream in = PdfRendererTestSupport.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return WorkbookFactory.create(in);
        }
    }

    static Cell cell(Sheet sh, int r, int c) {
        Row row = sh.getRow(r);
        return row == null ? null : row.getCell(c);
    }

    /** The cell's string value; "" for blank/missing; "FORMULA:..." for a formula so equality fails loudly. */
    static String str(Sheet sh, int r, int c) {
        Cell cell = cell(sh, r, c);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case FORMULA -> "FORMULA:" + cell.getCellFormula();
            case NUMERIC -> "NUMERIC:" + cell.getNumericCellValue();
            default -> "";
        };
    }

    /** A STORED number: the cell must be NUMERIC, never a formula. */
    static double num(Sheet sh, int r, int c) {
        Cell cell = cell(sh, r, c);
        assertThat(cell).as("cell (%d,%d) exists", r, c).isNotNull();
        assertThat(cell.getCellType()).as("cell (%d,%d) must hold a stored number, not a formula/text", r, c)
            .isEqualTo(CellType.NUMERIC);
        return cell.getNumericCellValue();
    }

    static boolean isBlank(Sheet sh, int r, int c) {
        Cell cell = cell(sh, r, c);
        return cell == null || cell.getCellType() == CellType.BLANK
            || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank());
    }

    /** Seq may be written as a number or text — both are fine, the VALUE must be n. */
    static int seq(Sheet sh, int r) {
        Cell cell = cell(sh, r, 0);
        assertThat(cell).as("seq cell row %d", r).isNotNull();
        return cell.getCellType() == CellType.NUMERIC
            ? (int) cell.getNumericCellValue() : Integer.parseInt(cell.getStringCellValue().trim());
    }

    static String norm(String s) { return s.replaceAll("\\s+", " ").trim(); }

    static String stripWs(String s) { return s.replaceAll("\\s+", ""); }

    /** First row whose cell in {@code col} satisfies the test, or -1. */
    static int rowOf(Sheet sh, int col, Predicate<String> test) {
        for (int r = 0; r <= sh.getLastRowNum(); r++) {
            if (test.test(norm(str(sh, r, col)))) return r;
        }
        return -1;
    }

    static int rowOfExact(Sheet sh, int col, String text) {
        return rowOf(sh, col, text::equals);
    }

    static List<String> allStrings(Sheet sh) {
        List<String> out = new ArrayList<>();
        for (Row row : sh) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING) out.add(cell.getStringCellValue());
            }
        }
        return out;
    }

    /** The template's own note lines (column B, NOTE_LINES rows under the header) — the expected text. */
    static List<String> templateNoteLines(String resource) throws Exception {
        try (Workbook wb = openTemplate(resource)) {
            return noteLines(wb.getSheet("Update"));
        }
    }

    static List<String> noteLines(Sheet sh) {
        int header = rowOfExact(sh, 1, NOTES_HEADER);
        assertThat(header).as("หมายเหตุ header row").isGreaterThanOrEqualTo(0);
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= NOTE_LINES; i++) lines.add(str(sh, header + i, 1));
        return lines;
    }

    /** Last 1-based row of the sheet's print area (column I), e.g. "Update!$A$1:$I$49" -> 49. */
    static int printAreaLastRow(Workbook wb) {
        String area = wb.getPrintArea(0);
        assertThat(area).as("print area").isNotNull();
        Matcher m = Pattern.compile("\\$I\\$(\\d+)$").matcher(area);
        assertThat(m.find()).as("print area '%s' ends at column I", area).isTrue();
        return Integer.parseInt(m.group(1));
    }

    /** Column B's usable text width in points, measured the way LibreOffice lays it out. */
    static double columnBTextWidthPt(Sheet sh) {
        int charTwips = LibreOfficeMetrics.charWidthTwips(sh.getWorkbook());
        int colTwips = LibreOfficeMetrics.columnTwips(sh.getColumnWidth(1), charTwips);
        return (colTwips - 2 * LibreOfficeMetrics.TEXT_INSET_TWIPS) / 20.0;
    }

    /** Width in pt of a B cell's text in its own font, or -1 when no measurable font exists on this host. */
    static double textWidthPt(Sheet sh, int r, String text) {
        Cell cell = cell(sh, r, 1);
        var font = sh.getWorkbook().getFontAt(cell.getCellStyle().getFontIndexAsInt());
        var width = FontResolver.stringWidthPt(FontResolver.resolve(font.getFontName()), text,
            font.getFontHeightInPoints(), font.getBold());
        return width.isPresent() ? width.getAsDouble() : -1;
    }

    /** Rows from {@code first} while column B is non-blank and A, C..I are blank (a wrapped description). */
    static List<Integer> descriptionRows(Sheet sh, int first, int stopBefore) {
        List<Integer> rows = new ArrayList<>(List.of(first));
        for (int r = first + 1; r < stopBefore; r++) {
            boolean onlyB = !isBlank(sh, r, 1);
            for (int c = 0; c <= 8 && onlyB; c++) if (c != 1 && !isBlank(sh, r, c)) onlyB = false;
            if (!onlyB) break;
            rows.add(r);
        }
        return rows;
    }

    /** Every OLE stream of the committed template (Workbook, SummaryInformation, ...) as raw bytes. */
    static Map<String, byte[]> oleStreams(String resource) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (InputStream in = PdfRendererTestSupport.class.getResourceAsStream(resource);
             POIFSFileSystem fs = new POIFSFileSystem(in)) {
            for (Entry e : fs.getRoot()) {
                if (e instanceof DocumentEntry d) out.put(e.getName(), new DocumentInputStream(d).readAllBytes());
            }
        }
        return out;
    }

    static boolean containsText(byte[] hay, String text) {
        return indexOf(hay, text.getBytes(StandardCharsets.UTF_16LE)) >= 0
            || indexOf(hay, text.getBytes(StandardCharsets.ISO_8859_1)) >= 0;
    }

    private static int indexOf(byte[] h, byte[] n) {
        outer:
        for (int i = 0; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** BIFF record ids of the Workbook stream, in order (top level only; CONTINUEs are skipped by length). */
    static List<Integer> biffRecordIds(byte[] workbook) {
        List<Integer> ids = new ArrayList<>();
        int p = 0;
        while (p + 4 <= workbook.length) {
            ids.add((workbook[p] & 255) | ((workbook[p + 1] & 255) << 8));
            p += 4 + ((workbook[p + 2] & 255) | ((workbook[p + 3] & 255) << 8));
        }
        return ids;
    }

    /** Every row from {@code from} to {@code to} is visible (HSSF shiftRows does not carry the hidden flag). */
    static void assertRowsVisible(Sheet sh, int from, int to) {
        for (int r = from; r <= to; r++) {
            Row row = sh.getRow(r);
            assertThat(row != null && row.getZeroHeight()).as("row %d must not be hidden", r).isFalse();
        }
    }

    static int totalWidthAtoI(Sheet sh) {
        int sum = 0;
        for (int c = 0; c <= 8; c++) sum += sh.getColumnWidth(c);
        return sum;
    }

    /** Ruling 2026-10-29 (P1-b): column G prints ONE figure "ลด X" like the E/H/I figures (same font, right-aligned,
     * never shrunk) in a column exactly as wide as E, and B/the pictures keep the template's own geometry. */
    static void assertDiscountCellPrintsLikeTheFigures(Sheet out, Sheet template, int row, String label) {
        var g = out.getRow(row).getCell(6);
        var i = out.getRow(row).getCell(8);
        assertThat(norm(g.getStringCellValue())).isEqualTo(label);
        assertThat(g.getCellStyle().getShrinkToFit()).as("G must not shrink-to-fit").isFalse();
        Font gf = out.getWorkbook().getFontAt(g.getCellStyle().getFontIndexAsInt());
        Font ifont = out.getWorkbook().getFontAt(i.getCellStyle().getFontIndexAsInt());
        assertThat(gf.getFontName()).as("G font name = I's").isEqualTo(ifont.getFontName());
        assertThat(gf.getFontHeightInPoints()).as("G font size = I's").isEqualTo(ifont.getFontHeightInPoints());
        assertThat(g.getCellStyle().getAlignment()).as("G aligned like the figures").isEqualTo(HorizontalAlignment.RIGHT);
        assertThat(out.getColumnWidth(6)).as("G is as wide as E").isEqualTo(out.getColumnWidth(4));
        var width = FontResolver.stringWidthPt(FontResolver.resolve(gf.getFontName()), label, gf.getFontHeightInPoints(), gf.getBold());
        if (width.isPresent()) {
            int colTwips = LibreOfficeMetrics.columnTwips(out.getColumnWidth(6), LibreOfficeMetrics.charWidthTwips(out.getWorkbook()));
            assertThat(width.getAsDouble()).as("label width pt <= column G text width")
                .isLessThanOrEqualTo((colTwips - 2.0 * LibreOfficeMetrics.TEXT_INSET_TWIPS) / 20.0);
        }
        assertThat(out.getColumnWidth(1)).as("B keeps the template's width").isEqualTo(template.getColumnWidth(1));
        assertThat(pictureWidthsPt(out)).as("logo/signature sizes").isEqualTo(pictureWidthsPt(template));
    }

    static List<org.apache.poi.hssf.usermodel.HSSFPicture> pictures(Sheet sh) {
        List<org.apache.poi.hssf.usermodel.HSSFPicture> out = new ArrayList<>();
        for (var shape : ((org.apache.poi.hssf.usermodel.HSSFSheet) sh).getDrawingPatriarch().getChildren())
            if (shape instanceof org.apache.poi.hssf.usermodel.HSSFPicture p) out.add(p);
        out.sort(java.util.Comparator.comparingInt(p -> ((org.apache.poi.hssf.usermodel.HSSFClientAnchor) p.getAnchor()).getRow1()));
        return out;
    }

    /** {col1,row1,dx1,dy1,col2,row2,dx2,dy2} */
    static int[] anchor(org.apache.poi.hssf.usermodel.HSSFPicture p) {
        var a = (org.apache.poi.hssf.usermodel.HSSFClientAnchor) p.getAnchor();
        return new int[] {a.getCol1(), a.getRow1(), a.getDx1(), a.getDy1(), a.getCol2(), a.getRow2(), a.getDx2(), a.getDy2()};
    }

    /** The picture's simple Escher properties (BLIP__CROPFROMTOP=256, ..BOTTOM=257, ..LEFT=258, ..RIGHT=259). */
    static Map<Integer, Integer> pictureProps(org.apache.poi.hssf.usermodel.HSSFPicture p) throws Exception {
        var m = org.apache.poi.hssf.usermodel.HSSFShape.class.getDeclaredMethod("getEscherContainer");
        m.setAccessible(true);
        var opt = (org.apache.poi.ddf.EscherOptRecord) ((org.apache.poi.ddf.EscherContainerRecord) m.invoke(p))
            .getChildById(org.apache.poi.ddf.EscherOptRecord.RECORD_ID);
        Map<Integer, Integer> props = new LinkedHashMap<>();
        for (var pr : opt.getEscherProperties())
            if (pr instanceof org.apache.poi.ddf.EscherSimpleProperty sp) props.put((int) sp.getPropertyNumber(), sp.getPropertyValue());
        return props;
    }

    /** Each picture's rendered width in pt (anchor span over the column widths, LibreOffice's column model). */
    static List<Double> pictureWidthsPt(Sheet sh) {
        int charTwips = LibreOfficeMetrics.charWidthTwips(sh.getWorkbook());
        List<Double> out = new ArrayList<>();
        for (var p : pictures(sh)) {
            int[] a = anchor(p);
            double x1 = xPt(sh, charTwips, a[0], a[2]), x2 = xPt(sh, charTwips, a[4], a[6]);
            out.add(Math.round((x2 - x1) * 2) / 2.0);
        }
        return out;
    }

    private static double xPt(Sheet sh, int charTwips, int col, int dx) {
        double x = 0;
        for (int c = 0; c < col; c++) x += LibreOfficeMetrics.columnTwips(sh.getColumnWidth(c), charTwips) / 20.0;
        return x + dx / 1024.0 * LibreOfficeMetrics.columnTwips(sh.getColumnWidth(col), charTwips) / 20.0;
    }

    /** The user name stored in the workbook's WRITEACCESS record (0x005C), or null when there is none. */
    static String writeAccessUser(byte[] workbook) {
        int p = 0;
        while (p + 4 <= workbook.length) {
            int id = (workbook[p] & 255) | ((workbook[p + 1] & 255) << 8);
            int len = (workbook[p + 2] & 255) | ((workbook[p + 3] & 255) << 8);
            if (id == 0x005C) {
                int cch = (workbook[p + 4] & 255) | ((workbook[p + 5] & 255) << 8);
                boolean wide = (workbook[p + 6] & 1) != 0;
                return new String(workbook, p + 7, wide ? cch * 2 : cch,
                    wide ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1).trim();
            }
            p += 4 + len;
        }
        return null;
    }
    /** Font colour index of a cell (automatic = 32767, black = 8, 64). */
    static short fontColor(Sheet sh, int r, int c) {
        var cell = sh.getRow(r).getCell(c);
        return ((org.apache.poi.hssf.usermodel.HSSFFont) sh.getWorkbook().getFontAt(cell.getCellStyle().getFontIndexAsInt())).getColor();
    }
}
