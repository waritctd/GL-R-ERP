package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Shared POI readers for the deposit-notice / remaining-invoice PDF-engine tests. 0-based (row, col). */
final class PdfRendererTestSupport {
    private PdfRendererTestSupport() {}

    static Workbook open(byte[] xls) throws Exception {
        return WorkbookFactory.create(new ByteArrayInputStream(xls));
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

    /** {col1,row1,dx1,dy1,col2,row2,dx2,dy2} */
    static int[] anchor(org.apache.poi.hssf.usermodel.HSSFPicture p) {
        var a = (org.apache.poi.hssf.usermodel.HSSFClientAnchor) p.getAnchor();
        return new int[] {a.getCol1(), a.getRow1(), a.getDx1(), a.getDy1(), a.getCol2(), a.getRow2(), a.getDx2(), a.getDy2()};
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

    /** A small opaque PNG (a scribble on white), the stand-in for an uploaded signature. */
    static byte[] signaturePng(int w, int h) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(java.awt.Color.BLUE);
        g.drawLine(0, h - 1, w - 1, 0);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
