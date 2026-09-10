package th.co.glr.hr.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFPictureData;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * html-fidelity-spec.md §1 — extracts every metric the HTML/Chromium render needs to reproduce
 * {@code quotation_template.xls} (sheet "Update") EXACTLY, into a checked-in JSON file
 * ({@code quotation_template.metrics.json}), and the template's embedded pictures (logo + the two
 * certification badges) into checked-in PNG assets. This is a pure extraction tool — it never
 * writes back to the template.
 *
 * <p>Run it directly ({@code main}) or through {@link TemplateMetricsDumpTest} with
 * {@code -Dregenerate=true} to refresh the checked-in files after a genuine template change;
 * without that flag the test only VERIFIES the checked-in JSON still matches what dumping the XLS
 * produces today, so the JSON can never silently drift from the real template (spec §1's own
 * requirement).
 *
 * <p>Units: column widths and row heights are reported in both their native POI unit (pixels at
 * 96 dpi for columns, points for rows) and the derived millimetres the HTML grid consumes
 * directly ({@code px96 / 96 * 25.4} and {@code pt / 72 * 25.4}).
 */
public final class TemplateMetricsDump {
    private TemplateMetricsDump() {}

    static final String TEMPLATE_RESOURCE = "templates/quotation_template.xls";
    static final String SHEET_NAME = "Update";
    static final int FIRST_ROW = 0;
    static final int LAST_ROW = 47; // inclusive — template's own print area is A1:I48 (1-based)
    static final int FIRST_COL = 0;
    static final int LAST_COL = 8; // inclusive — column I

    private static final double PX96_TO_MM = 25.4 / 96.0;
    private static final double PT_TO_MM = 25.4 / 72.0;
    private static final double IN_TO_MM = 25.4;

    static ObjectNode dump(ObjectMapper mapper, InputStream xlsStream) throws IOException {
        try (Workbook wb = new HSSFWorkbook(xlsStream)) {
            Sheet sh = wb.getSheet(SHEET_NAME);
            if (sh == null) sh = wb.getSheetAt(0);

            ObjectNode root = mapper.createObjectNode();
            root.put("sheet", sh.getSheetName());
            root.set("pageSetup", pageSetup(mapper, sh));
            root.set("columns", columns(mapper, sh));
            root.set("rows", rows(mapper, sh));
            root.set("mergedRegions", mergedRegions(mapper, sh));
            root.set("cells", cells(mapper, wb, sh));
            root.set("pictures", pictures(mapper, sh));
            return root;
        }
    }

    private static ObjectNode pageSetup(ObjectMapper mapper, Sheet sh) {
        ObjectNode node = mapper.createObjectNode();
        PrintSetup ps = sh.getPrintSetup();
        // PrintSetup's numeric getters return short — cast every one to int, same reasoning as
        // #font's comment (ShortNode vs the IntNode a round-tripped JSON literal parses back as).
        node.put("paperSize", (int) ps.getPaperSize());
        node.put("landscape", ps.getLandscape());
        node.put("fitToPage", sh.getFitToPage());
        node.put("fitWidth", (int) ps.getFitWidth());
        node.put("fitHeight", (int) ps.getFitHeight());
        node.put("printSetupScalePercent", (int) ps.getScale());

        ObjectNode margins = mapper.createObjectNode();
        margins.put("topIn", sh.getMargin(PageMargin.TOP));
        margins.put("bottomIn", sh.getMargin(PageMargin.BOTTOM));
        margins.put("leftIn", sh.getMargin(PageMargin.LEFT));
        margins.put("rightIn", sh.getMargin(PageMargin.RIGHT));
        margins.put("headerIn", sh.getMargin(PageMargin.HEADER));
        margins.put("footerIn", sh.getMargin(PageMargin.FOOTER));
        margins.put("topMm", sh.getMargin(PageMargin.TOP) * IN_TO_MM);
        margins.put("bottomMm", sh.getMargin(PageMargin.BOTTOM) * IN_TO_MM);
        margins.put("leftMm", sh.getMargin(PageMargin.LEFT) * IN_TO_MM);
        margins.put("rightMm", sh.getMargin(PageMargin.RIGHT) * IN_TO_MM);
        node.set("margins", margins);

        String printArea = sh.getWorkbook().getPrintArea(sh.getWorkbook().getSheetIndex(sh));
        node.put("printArea", printArea);

        // The XLS renderer (QuotationRenderer#naturalWidthScale) computes fit-to-width from real
        // AWT font metrics at render time rather than from a static workbook field — the
        // template's OWN PrintSetup scale is not that number (Excel/LO recompute fit-to-width
        // dynamically same as we do). Record the renderer's documented approximation here as a
        // named constant so the HTML path and any comparison script share one source rather than
        // two independently maintained "~0.86" comments.
        node.put("xlsPathFitToWidthScaleApprox", 0.86);
        return node;
    }

    private static ObjectNode columns(ObjectMapper mapper, Sheet sh) {
        ObjectNode node = mapper.createObjectNode();
        for (int c = FIRST_COL; c <= LAST_COL; c++) {
            ObjectNode col = mapper.createObjectNode();
            double px = sh.getColumnWidthInPixels(c);
            col.put("index", c);
            col.put("letter", columnLetter(c));
            col.put("widthPx96", px);
            col.put("widthMm", px * PX96_TO_MM);
            node.set(columnLetter(c), col);
        }
        return node;
    }

    private static ObjectNode rows(ObjectMapper mapper, Sheet sh) {
        ObjectNode node = mapper.createObjectNode();
        for (int r = FIRST_ROW; r <= LAST_ROW; r++) {
            Row row = sh.getRow(r);
            // Row#getHeightInPoints returns float — widen to double so re-parsed JSON (always
            // DoubleNode for a decimal literal) equals what THIS dump produces; see #font's
            // comment for why the node TYPE (not just the numeric value) has to match exactly.
            double pt = row != null ? row.getHeightInPoints() : sh.getDefaultRowHeightInPoints();
            ObjectNode rn = mapper.createObjectNode();
            rn.put("index", r);
            rn.put("heightPt", pt);
            rn.put("heightMm", pt * PT_TO_MM);
            node.set(String.valueOf(r), rn);
        }
        return node;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode mergedRegions(ObjectMapper mapper, Sheet sh) {
        var arr = mapper.createArrayNode();
        for (CellRangeAddress region : sh.getMergedRegions()) {
            ObjectNode r = mapper.createObjectNode();
            r.put("firstRow", region.getFirstRow());
            r.put("lastRow", region.getLastRow());
            r.put("firstCol", region.getFirstColumn());
            r.put("lastCol", region.getLastColumn());
            arr.add(r);
        }
        return arr;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode cells(
            ObjectMapper mapper, Workbook wb, Sheet sh) {
        var arr = mapper.createArrayNode();
        for (int r = FIRST_ROW; r <= LAST_ROW; r++) {
            Row row = sh.getRow(r);
            for (int c = FIRST_COL; c <= LAST_COL; c++) {
                Cell cell = row != null ? row.getCell(c) : null;
                CellStyle style = cell != null ? cell.getCellStyle() : sh.getColumnStyle(c);
                ObjectNode node = mapper.createObjectNode();
                node.put("row", r);
                node.put("col", c);
                node.put("text", cellText(cell));
                if (style != null) {
                    node.set("border", borders(mapper, style));
                    node.set("align", alignment(mapper, style));
                    node.set("font", font(mapper, wb, style));
                    node.put("numberFormat", style.getDataFormatString());
                } else {
                    node.putNull("border");
                    node.putNull("align");
                    node.putNull("font");
                    node.putNull("numberFormat");
                }
                arr.add(node);
            }
        }
        return arr;
    }

    private static String cellText(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        try {
            return switch (type) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> String.valueOf(cell.getNumericCellValue());
                case FORMULA -> "=" + cell.getCellFormula();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static ObjectNode borders(ObjectMapper mapper, CellStyle style) {
        ObjectNode node = mapper.createObjectNode();
        node.put("top", borderName(style.getBorderTop()));
        node.put("right", borderName(style.getBorderRight()));
        node.put("bottom", borderName(style.getBorderBottom()));
        node.put("left", borderName(style.getBorderLeft()));
        return node;
    }

    private static String borderName(BorderStyle style) {
        return style == null ? "NONE" : style.name();
    }

    private static ObjectNode alignment(ObjectMapper mapper, CellStyle style) {
        ObjectNode node = mapper.createObjectNode();
        node.put("horizontal", style.getAlignment() != null ? style.getAlignment().name() : "GENERAL");
        node.put("vertical", style.getVerticalAlignment() != null ? style.getVerticalAlignment().name() : "BOTTOM");
        node.put("wrap", style.getWrapText());
        return node;
    }

    private static ObjectNode font(ObjectMapper mapper, Workbook wb, CellStyle style) {
        Font f = wb.getFontAt(style.getFontIndexAsInt());
        ObjectNode node = mapper.createObjectNode();
        node.put("name", f.getFontName());
        // Cast every POI numeric getter that returns short/byte to int explicitly — ObjectNode
        // has short/byte-specific #put overloads that produce ShortNode/ByteNode, which are NOT
        // equal() to the IntNode a plain JSON integer literal parses back as (JsonNode#equals is
        // exact-type, not by numeric value) — that mismatch is what broke the round-trip pin the
        // first time this ran (TemplateMetricsDumpTest, "13 (NUMBER) vs 13 (NUMBER)").
        node.put("sizePt", (int) f.getFontHeightInPoints());
        node.put("bold", f.getBold());
        node.put("underline", f.getUnderline() != Font.U_NONE);
        return node;
    }

    /**
     * Every embedded picture (logo + the two certification badges) anchored on the sheet, with
     * its anchor converted to millimetres from the sheet's own top-left (spec §2's "place them as
     * data: URIs at the same anchors") and a stable, content-derived asset id
     * ({@code pic-<index>.<ext>}) so re-running the dump against an unchanged template produces
     * byte-identical file names.
     */
    private static com.fasterxml.jackson.databind.node.ArrayNode pictures(ObjectMapper mapper, Sheet sh) {
        var arr = mapper.createArrayNode();
        if (!(sh instanceof HSSFSheet hssfSheet)) return arr;
        var patriarch = hssfSheet.getDrawingPatriarch();
        if (patriarch == null) return arr;
        int idx = 0;
        for (var shape : patriarch.getChildren()) {
            if (!(shape instanceof HSSFPicture pic)) continue;
            HSSFPictureData data = pic.getPictureData();
            ClientAnchor anchor = pic.getClientAnchor();
            ObjectNode node = mapper.createObjectNode();
            String ext = data.suggestFileExtension();
            String assetId = "pic-" + idx + "." + ext;
            node.put("assetId", assetId);
            node.put("mimeType", data.getMimeType());
            node.put("anchorRow1", anchor.getRow1());
            node.put("anchorCol1", (int) anchor.getCol1()); // short → int, see #font's comment
            node.put("anchorDx1", anchor.getDx1());
            node.put("anchorDy1", anchor.getDy1());
            node.put("anchorRow2", anchor.getRow2());
            node.put("anchorCol2", (int) anchor.getCol2()); // short → int, see #font's comment
            node.put("anchorDx2", anchor.getDx2());
            node.put("anchorDy2", anchor.getDy2());
            node.put("leftMm", pixelOffsetToMm(sh, anchor.getCol1(), anchor.getDx1()));
            node.put("topMm", rowOffsetToMm(sh, anchor.getRow1(), anchor.getDy1()));
            node.put("rightMm", pixelOffsetToMm(sh, anchor.getCol2(), anchor.getDx2()));
            node.put("bottomMm", rowOffsetToMm(sh, anchor.getRow2(), anchor.getDy2()));
            arr.add(node);
            idx++;
        }
        return arr;
    }

    // EMU-to-pixel: HSSF anchors report dx/dy in 1/1024ths of the anchor cell's own width/height
    // (see QuotationRendererTest's own #startPx computation, which this mirrors) — NOT EMUs (that
    // is the XSSF convention only).
    private static double pixelOffsetToMm(Sheet sh, int col, int dx) {
        double px = 0;
        for (int c = 0; c < col; c++) px += sh.getColumnWidthInPixels(c);
        px += (dx / 1024.0) * sh.getColumnWidthInPixels(col);
        return px * PX96_TO_MM;
    }

    private static double rowOffsetToMm(Sheet sh, int row, int dy) {
        double pt = 0;
        for (int r = 0; r < row; r++) {
            Row rw = sh.getRow(r);
            pt += rw != null ? rw.getHeightInPoints() : sh.getDefaultRowHeightInPoints();
        }
        Row anchorRow = sh.getRow(row);
        float rowPt = anchorRow != null ? anchorRow.getHeightInPoints() : sh.getDefaultRowHeightInPoints();
        pt += (dy / 256.0) * rowPt;
        return pt * PT_TO_MM;
    }

    private static String columnLetter(int index) {
        return String.valueOf((char) ('A' + index));
    }

    /**
     * Writes every embedded picture found on the sheet to {@code outDir} (asset id as filename)
     * and returns the map of assetId → bytes actually written, so {@link #main} can report what
     * it extracted.
     */
    static Map<String, byte[]> extractPictures(InputStream xlsStream, Path outDir) throws IOException {
        Map<String, byte[]> written = new LinkedHashMap<>();
        try (Workbook wb = new HSSFWorkbook(xlsStream)) {
            Sheet sh = wb.getSheet(SHEET_NAME);
            if (sh == null) sh = wb.getSheetAt(0);
            if (!(sh instanceof HSSFSheet hssfSheet)) return written;
            var patriarch = hssfSheet.getDrawingPatriarch();
            if (patriarch == null) return written;
            Files.createDirectories(outDir);
            int idx = 0;
            for (var shape : patriarch.getChildren()) {
                if (!(shape instanceof HSSFPicture pic)) continue;
                HSSFPictureData data = pic.getPictureData();
                String assetId = "pic-" + idx + "." + data.suggestFileExtension();
                byte[] bytes = data.getData();
                Files.write(outDir.resolve(assetId), bytes);
                written.put(assetId, bytes);
                idx++;
            }
        }
        return written;
    }

    /** Regenerates the checked-in JSON + picture assets. Run from the backend module root. */
    public static void main(String[] args) throws IOException {
        Path repoRoot = Path.of("").toAbsolutePath();
        Path templatePath = repoRoot.resolve("src/main/resources/" + TEMPLATE_RESOURCE);
        Path jsonOut = repoRoot.resolve("src/main/resources/templates/quotation_template.metrics.json");
        // The extracted pictures are a dump for humans/diffs only: the HTML renderer takes the
        // pictures from the live workbook (SheetPlan#readPictures), never from these files, so
        // they live in the TEST resources and never reach the jar.
        Path picturesOut = repoRoot.resolve("src/test/resources/static/brand/template");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root;
        try (InputStream in = Files.newInputStream(templatePath)) {
            root = dump(mapper, in);
        }
        try (OutputStream out = Files.newOutputStream(jsonOut)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, root);
        }
        Map<String, byte[]> pics;
        try (InputStream in = Files.newInputStream(templatePath)) {
            pics = extractPictures(in, picturesOut);
        }
        System.out.println("Wrote " + jsonOut + " and " + pics.size() + " picture(s) to " + picturesOut);
    }
}
