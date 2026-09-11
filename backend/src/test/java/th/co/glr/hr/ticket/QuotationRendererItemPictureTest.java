package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.sheet.LibreOfficeMetrics;
import th.co.glr.hr.dealquotation.QuotationHtmlDocument;
import th.co.glr.hr.ticket.QuotationRenderModel.ItemPicture;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderModel.Signatories;

/**
 * GLA-75 — per-item pictures in the quotation XLS (the one row plan both the LibreOffice PDF and
 * the Chromium/HTML PDF print). Owner, 2026-09-10: "attach image like the reference photo make sure
 * the sizing appropriate like the reference picture". Two placements:
 * <ul>
 *   <li>BELOW — large, under the description, scaled to column B's width, aspect kept, height
 *       capped; the item's rows GROW so the next item (or the remark box) starts below it;</li>
 *   <li>BESIDE — a thumbnail at the right of the description cell, at most two rows, never over the
 *       จำนวน column.</li>
 * </ul>
 * An item with NO picture is covered by {@link QuotationRendererNoPictureGoldenTest}.
 */
class QuotationRendererItemPictureTest {
    private static final int ITEM_START_ROW = 9;
    private static final int COL_B = 1;
    private static final double MM_PER_PT = 25.4 / 72.0;
    private static final String FIRST_REMARK = "1. วันที่รับจำนวน 10 กันยายน 2569";
    private static final String LAST_REMARK = "8. ขอบคุณที่ใช้บริการ";

    private final QuotationRenderer renderer = new QuotationRenderer();

    @Test
    void below_sitsUnderItsOwnDescription_spansColumnB_keepsAspect_andTheNextItemStartsBelowIt() throws Exception {
        byte[] landscape = pattern(1200, 600, new Color(30, 120, 170));
        List<RenderItem> items = List.of(item(1, new ItemPicture(landscape, "image/png", "BELOW")), item(2, null));
        try (Workbook wb = render(items)) {
            Sheet sh = sheet(wb);
            HSSFClientAnchor a = anchorOf(sh, landscape);
            int main1 = mainRow(sh, 1);
            int main2 = mainRow(sh, 2);

            assertThat((int) a.getCol1()).as("picture confined to column B").isEqualTo(COL_B);
            assertThat((int) a.getCol2()).as("picture must not reach จำนวน (column C)").isEqualTo(COL_B);
            assertThat(a.getRow1()).as("BELOW starts under the item's LAST text row")
                .isGreaterThan(lastTextRow(sh, main1, main2));
            assertThat(a.getRow2()).as("the next item starts strictly below the picture").isLessThan(main2);

            double widthPt = anchorWidthPt(wb, sh, a);
            double heightPt = anchorHeightPt(sh, a);
            assertThat(heightPt / widthPt).as("aspect ratio kept").isCloseTo(0.5, within(0.01));
            // BELOW is sized to column B's width UNLESS that would exceed the 60 mm height cap
            // (QuotationRenderer#BELOW_MAX_HEIGHT_MM). Which one binds depends on the machine's
            // Thai font: column B is measured in font units, and CI's fonts-thai-tlwg makes it
            // ~530 pt where licensed Cordia New makes it ~360 pt — so in CI this 2:1 picture hits
            // the height cap (340 pt wide) and locally it fills the column. Either is correct; a
            // picture that does neither (shrunk for no reason) is the bug this guards against.
            double columnPt = columnBWidthPt(wb, sh);
            double capPt = 60.0 / 25.4 * 72.0;
            assertThat(widthPt > 0.9 * columnPt || Math.abs(heightPt - capPt) < 1.0)
                .as("fills column B less a small inset (%.1f of %.1f pt), or is height-capped at 60 mm "
                    + "(%.1f pt tall)", widthPt, columnPt, heightPt)
                .isTrue();
            // The rows the picture sits on are real, empty item rows — nothing printed over it.
            for (int r = a.getRow1(); r <= a.getRow2(); r++) {
                assertThat(text(sh, r, COL_B)).as("row %d under the picture", r).isEmpty();
                assertThat(sh.getRow(r).getCell(2) == null || sh.getRow(r).getCell(2).getCellType() != CellType.NUMERIC)
                    .as("no จำนวน printed beside a picture row %d", r).isTrue();
            }
        }
    }

    @Test
    void below_aTallPortraitPicture_isCappedAt60mm_andStillKeepsItsAspect() throws Exception {
        byte[] portrait = pattern(400, 1600, new Color(150, 60, 40));
        try (Workbook wb = render(List.of(item(1, new ItemPicture(portrait, "image/png", "BELOW"))))) {
            Sheet sh = sheet(wb);
            HSSFClientAnchor a = anchorOf(sh, portrait);
            double heightPt = anchorHeightPt(sh, a);
            assertThat(heightPt * MM_PER_PT).as("height cap").isLessThanOrEqualTo(60.5).isGreaterThan(55.0);
            assertThat(heightPt / anchorWidthPt(wb, sh, a)).isCloseTo(4.0, within(0.05));
        }
    }

    @Test
    void below_onTheLastItem_pushesTheFooterDown_andTheEightRowRemarkBoxStaysIntact() throws Exception {
        byte[] picture = pattern(900, 700, new Color(60, 140, 90));
        try (Workbook wb = render(List.of(item(1, null), item(2, new ItemPicture(picture, "image/png", "BELOW"))))) {
            Sheet sh = sheet(wb);
            HSSFClientAnchor a = anchorOf(sh, picture);
            int firstRemark = rowWithText(sh, FIRST_REMARK);
            assertThat(firstRemark).as("remark box starts below the last item's picture").isGreaterThan(a.getRow2());
            assertRemarkBoxIntact(sh);
        }
    }

    @Test
    void beside_isAThumbnailAtTheRightOfTheDescriptionCell_atMostTwoRows_neverOverQty() throws Exception {
        byte[] thumb = pattern(600, 300, new Color(200, 170, 40));
        List<RenderItem> items = List.of(item(1, new ItemPicture(thumb, "image/png", "BESIDE")), item(2, null));
        try (Workbook wb = render(items)) {
            Sheet sh = sheet(wb);
            HSSFClientAnchor a = anchorOf(sh, thumb);
            int main1 = mainRow(sh, 1);
            assertThat((int) a.getCol1()).isEqualTo(COL_B);
            assertThat((int) a.getCol2()).as("never over the จำนวน column").isEqualTo(COL_B);
            assertThat(a.getDx1()).as("at the RIGHT of the description cell").isGreaterThan(600);
            assertThat(a.getRow1()).as("beside the item's first row").isEqualTo(main1);
            assertThat(a.getRow2()).as("about two text rows tall, no more").isLessThanOrEqualTo(main1 + 1);
            assertThat(a.getRow2()).isLessThan(mainRow(sh, 2));
            assertThat(anchorHeightPt(sh, a) / anchorWidthPt(wb, sh, a)).isCloseTo(0.5, within(0.02));
            assertThat(sh.getRow(main1).getCell(2).getCellType()).as("จำนวน still printed").isEqualTo(CellType.NUMERIC);
        }
    }

    @Test
    void beside_wrapsTheDescriptionNarrower_soTextNeverRunsUnderTheThumbnail() throws Exception {
        // ASCII so the char budget is unambiguous: fits the plain 62-char line, not the narrower one.
        String longLine = "Porcelain tile model Aurora Statuario Bianco Polished 60x60";
        assertThat(longLine.length()).isBetween(52, 62);
        byte[] thumb = pattern(300, 300, new Color(90, 90, 200));
        RenderItem plain = new RenderItem(null, List.of(longLine), BigDecimal.ONE, "แผ่น", new BigDecimal("100"),
            "Net", new BigDecimal("100"), new BigDecimal("100"));
        RenderItem beside = new RenderItem(null, List.of(longLine), BigDecimal.ONE, "แผ่น", new BigDecimal("100"),
            "Net", new BigDecimal("100"), new BigDecimal("100"), new ItemPicture(thumb, "image/png", "BESIDE"));
        int plainRows;
        try (Workbook wb = render(List.of(plain, item(2, null)))) {
            plainRows = mainRow(sheet(wb), 2) - mainRow(sheet(wb), 1);
        }
        try (Workbook wb = render(List.of(beside, item(2, null)))) {
            Sheet sh = sheet(wb);
            int main1 = mainRow(sh, 1);
            int textRows = lastTextRow(sh, main1, mainRow(sh, 2)) - main1 + 1;
            assertThat(textRows).as("beside a thumbnail the same line wraps onto more rows").isGreaterThan(plainRows);
            HSSFClientAnchor a = anchorOf(sh, thumb);
            for (int r = main1; r <= a.getRow2(); r++) {
                assertThat(text(sh, r, COL_B).length()).as("line beside the thumbnail, row %d", r)
                    .isLessThan(longLine.length());
            }
        }
    }

    @Test
    void paginated_aPictureNeverStraddlesAPageBreak_andEveryPictureReachesTheHtmlEngine() throws Exception {
        List<RenderItem> items = new ArrayList<>();
        List<byte[]> pictures = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            byte[] pic = pattern(800 + i * 10, 500, new Color(20 + i * 10, 100, 200 - i * 10));
            pictures.add(pic);
            items.add(item(i, new ItemPicture(pic, "image/png", i % 3 == 0 ? "BESIDE" : "BELOW")));
        }
        QuotationRenderModel model = model(items);
        byte[] xls = renderer.toXls(model);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            Sheet sh = sheet(wb);
            int[] breaks = sh.getRowBreaks();
            assertThat(breaks).as("fixture must actually paginate").isNotEmpty();
            for (int i = 0; i < pictures.size(); i++) {
                HSSFClientAnchor a = anchorOf(sh, pictures.get(i));
                int main = mainRow(sh, i + 1);
                for (int b : breaks) {
                    // setRowBreak(b) starts a new page at row b + 1: a break inside [main, row2)
                    // would put the item's first row and its picture's bottom on different pages.
                    assertThat(b >= main && b < a.getRow2())
                        .as("page break after row %d splits item %d (rows %d..%d)", b, i + 1, main, a.getRow2())
                        .isFalse();
                }
            }
            assertRemarkBoxIntact(sh);
        }
        // Manual breaks alone are not the whole pagination: a block judged to fit that does NOT fit
        // is split by the engine's own AUTOMATIC break, which getRowBreaks() never shows. The HTML
        // engine's page plan (the Chromium path) is the real page assignment, so check against it:
        // each item's first row, and its picture's first and last rows, sit on ONE page.
        var rendered = QuotationHtmlDocument.rendered(xls, model);
        assertThat(rendered.pageCount()).as("fixture must actually paginate").isGreaterThan(1);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            Sheet sh = sheet(wb);
            for (int i = 0; i < pictures.size(); i++) {
                HSSFClientAnchor a = anchorOf(sh, pictures.get(i));
                int main = mainRow(sh, i + 1);
                int page = pageOf(rendered.plan(), main);
                assertThat(pageOf(rendered.plan(), a.getRow1())).as("item %d picture top", i + 1).isEqualTo(page);
                assertThat(pageOf(rendered.plan(), a.getRow2())).as("item %d picture bottom", i + 1).isEqualTo(page);
            }
        }
        // A picture whose rows fell on two pages would be dropped by the HTML engine
        // (SheetHtmlRenderer#anchorY returns null) — so all must be present, each exactly once.
        assertThat(countOccurrences(rendered.html(), "class=\"p item-image\"")).isEqualTo(pictures.size());
    }

    /** The page whose OWN (non-repeated) rows include {@code row}. */
    private static int pageOf(th.co.glr.hr.common.sheet.SheetPlan plan, int row) {
        for (var page : plan.pages) {
            List<Integer> own = page.rowIndices().subList(page.repeatedRowCount(), page.rowIndices().size());
            if (own.contains(row)) return page.number();
        }
        throw new AssertionError("row " + row + " is on no page");
    }

    /** The LibreOffice PDF path: both placements reach the PDF as image XObjects. */
    @Test
    void libreOfficePdf_carriesBothPlacements() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(th.co.glr.hr.common.LibreOfficePdfConverter.isAvailable(),
            "LibreOffice not installed on this machine");
        QuotationRenderer libreOffice = new QuotationRenderer(); // default engine = LibreOffice
        int without = xObjects(libreOffice.toPdf(model(List.of(item(1, null), item(2, null)))));
        int with = xObjects(libreOffice.toPdf(model(List.of(
            item(1, new ItemPicture(pattern(900, 450, new Color(30, 120, 170)), "image/png", "BELOW")),
            item(2, new ItemPicture(pattern(300, 300, new Color(170, 120, 30)), "image/png", "BESIDE"))))));
        assertThat(with).isEqualTo(without + 2);
    }

    private static int xObjects(byte[] pdf) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            int count = 0;
            for (var page : doc.getPages()) {
                for (var ignored : page.getResources().getXObjectNames()) count++;
            }
            return count;
        }
    }

    @Test
    void jpegPictures_areEmbeddedAsJpeg() throws Exception {
        byte[] jpeg = jpeg(640, 480);
        try (Workbook wb = render(List.of(item(1, new ItemPicture(jpeg, "image/jpeg", "BELOW"))))) {
            HSSFPicture pic = pictureOf(sheet(wb), jpeg);
            assertThat(pic.getPictureData().getMimeType()).isEqualTo("image/jpeg");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Workbook render(List<RenderItem> items) throws IOException {
        return WorkbookFactory.create(new ByteArrayInputStream(renderer.toXls(model(items))));
    }

    private static Sheet sheet(Workbook wb) {
        return wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
    }

    static QuotationRenderModel model(List<RenderItem> items) {
        List<String> remarks = List.of(
            FIRST_REMARK, "2. มัดจำ 30% ของยอดรวม", "3. ส่วนที่เหลือเครดิต 30 วัน",
            "4. สินค้าจากไทย-สต็อก ส่งภายใน 30-45 วัน", "5. ราคานี้ยืนราคา 30 วัน",
            "6. หมายเหตุ", "7. โปรดตรวจสอบรายการ", LAST_REMARK);
        return new QuotationRenderModel(
            LocalDate.of(2026, 9, 10), "QT-2026-0099", "P003", "D002", "Sales/สมชาย ใจดี T.081-234-5678",
            "คุณลูกค้า   /   Test Customer Co., Ltd.", "โทร. 02-000-0000", "Showroom V2 Project", items, remarks,
            new Signatories("จินตนา", "จุฑาทิพ", "ผึ้ง", "สมหญิง ใจดี", null, null, null, null, null),
            true, "TH", "THB");
    }

    static RenderItem item(int i, ItemPicture picture) {
        return new RenderItem(null,
            List.of("กระเบื้อง รุ่น Model " + i + " สี White ผิว Matte", "ขนาด 60x60 cm. หนา 10 มม."),
            BigDecimal.valueOf(37), "แผ่น", new BigDecimal("450.00"), "Net",
            new BigDecimal("450.00"), new BigDecimal("16650.00"), picture);
    }

    /** The row whose column A carries sequence number {@code seq} (v2 always numbers items). */
    private static int mainRow(Sheet sh, int seq) {
        for (int r = ITEM_START_ROW; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            Cell c = row == null ? null : row.getCell(0);
            if (c != null && c.getCellType() == CellType.NUMERIC && c.getNumericCellValue() == seq) return r;
        }
        throw new AssertionError("no item row numbered " + seq);
    }

    private static int lastTextRow(Sheet sh, int from, int toExclusive) {
        int last = from;
        for (int r = from; r < toExclusive; r++) {
            if (!text(sh, r, COL_B).isEmpty()) last = r;
        }
        return last;
    }

    private static int rowWithText(Sheet sh, String value) {
        for (int r = 0; r <= sh.getLastRowNum(); r++) {
            if (value.equals(text(sh, r, COL_B))) return r;
        }
        throw new AssertionError("no row reads " + value);
    }

    /** The 8 remark lines print on 8 CONSECUTIVE rows (QuotationRenderer#REMARK_HEAD_ROWS). */
    private static void assertRemarkBoxIntact(Sheet sh) {
        int first = rowWithText(sh, FIRST_REMARK);
        assertThat(rowWithText(sh, LAST_REMARK)).as("8-row remark box").isEqualTo(first + 7);
        assertThat(text(sh, first + 1, COL_B)).startsWith("2.");
    }

    private static String text(Sheet sh, int r, int c) {
        Row row = sh.getRow(r);
        Cell cell = row == null ? null : row.getCell(c);
        return cell != null && cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : "";
    }

    private static HSSFPicture pictureOf(Sheet sh, byte[] data) {
        HSSFPicture found = null;
        for (var shape : ((HSSFSheet) sh).getDrawingPatriarch().getChildren()) {
            if (shape instanceof HSSFPicture pic && Arrays.equals(pic.getPictureData().getData(), data)) {
                assertThat(found).as("picture embedded once").isNull();
                found = pic;
            }
        }
        assertThat(found).as("item picture embedded").isNotNull();
        return found;
    }

    private static HSSFClientAnchor anchorOf(Sheet sh, byte[] data) {
        return pictureOf(sh, data).getClientAnchor();
    }

    private static double columnBWidthPt(Workbook wb, Sheet sh) {
        int twips = LibreOfficeMetrics.columnTwips(sh.getColumnWidth(COL_B), LibreOfficeMetrics.charWidthTwips(wb));
        return twips / 20.0;
    }

    private static double anchorWidthPt(Workbook wb, Sheet sh, HSSFClientAnchor a) {
        return (a.getDx2() - a.getDx1()) / 1024.0 * columnBWidthPt(wb, sh);
    }

    private static double anchorHeightPt(Sheet sh, HSSFClientAnchor a) {
        return offsetPt(sh, a.getRow2(), a.getDy2()) - offsetPt(sh, a.getRow1(), a.getDy1());
    }

    private static double offsetPt(Sheet sh, int row, int dy) {
        double sum = 0;
        for (int r = 0; r < row; r++) sum += heightPt(sh, r);
        return sum + heightPt(sh, row) * dy / 256.0;
    }

    private static double heightPt(Sheet sh, int r) {
        Row row = sh.getRow(r);
        return row != null ? row.getHeightInPoints() : sh.getDefaultRowHeightInPoints();
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    /** A flat colour with a simple tile grid — a stand-in for a pattern/mosaic picture. */
    static byte[] pattern(int width, int height, Color colour) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        int tile = Math.max(8, Math.min(width, height) / 8);
        for (int x = 0; x < width; x += tile) g.drawLine(x, 0, x, height);
        for (int y = 0; y < height; y += tile) g.drawLine(0, y, width, y);
        g.dispose();
        return encode(img, "png");
    }

    private static byte[] jpeg(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 80, 40));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return encode(img, "jpg");
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
