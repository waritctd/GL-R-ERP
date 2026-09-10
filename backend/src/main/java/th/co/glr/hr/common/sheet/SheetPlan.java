package th.co.glr.hr.common.sheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * The ONE row plan both PDF engines print from. It is read straight off the POI sheet the XLS
 * engine ({@code QuotationRenderer#toXls}) has already written — every row (index, height),
 * column (width), merged region, cell (evaluated + formatted text, font, alignment, borders,
 * fill), picture anchor and the print setup — and turned into LibreOffice's page geometry with
 * {@link LibreOfficeMetrics}: integer zoom, 1/100 mm scaled sizes, page assignment (repeat rows,
 * manual row breaks, overflow breaks), horizontal centring. {@link SheetHtmlRenderer} then draws
 * exactly this plan. There is no second copy of the quotation's row arithmetic anywhere: whatever
 * the XLS path cloned, shifted, merged or blanked is what the HTML path prints, by construction.
 *
 * <p>Generic on purpose (html-fidelity-spec.md §4): any template this app already fills with POI
 * (deposit notice, invoice, …) renders through the same plan with no per-form descriptor.
 */
public final class SheetPlan {
    public enum HAlign { LEFT, CENTER, RIGHT }
    public enum VAlign { TOP, CENTER, BOTTOM }

    /** Border styles of one cell's four edges (never null — NONE when absent). */
    public record Borders(BorderStyle top, BorderStyle right, BorderStyle bottom, BorderStyle left) {
        static final Borders NONE = new Borders(BorderStyle.NONE, BorderStyle.NONE, BorderStyle.NONE, BorderStyle.NONE);

        boolean anyVisible() {
            return top != BorderStyle.NONE || right != BorderStyle.NONE
                || bottom != BorderStyle.NONE || left != BorderStyle.NONE;
        }
    }

    public record Facets(String fontName, double fontPt, boolean bold, boolean italic, boolean underline,
                         HAlign hAlign, boolean generalAlign, VAlign vAlign, boolean wrap, boolean shrink,
                         Borders borders, String fillHex) {}

    /** One rendered cell. Covered cells of a merged region are NOT planned — the region's top-left
     * cell carries {@code lastRow}/{@code lastCol} instead. {@code text} is the evaluated, number-
     * formatted display string (formulas evaluated with POI, falling back to the cached result). */
    public record PlannedCell(int row, int col, int lastRow, int lastCol, String text, boolean numeric, Facets facets) {
        public boolean merged() {
            return lastRow > row || lastCol > col;
        }
    }

    public record PlannedRow(int index, int twips, int scaledHmm) {}

    public record Column(int index, int twips, int scaledHmm) {}

    /** An HSSF picture anchor: dx in 1/1024ths of the anchor column's width, dy in 1/256ths of the
     * anchor row's height (both engines — LibreOffice and POI — read them that way). */
    public record Picture(byte[] data, String mimeType, int row1, int dy1, int col1, int dx1,
                          int row2, int dy2, int col2, int dx2) {}

    /** Rows printed on one page, in order; the first {@code repeatedRowCount} are the sheet's
     * repeating title rows (LibreOffice prints them, pictures included, on every page after the
     * first). */
    public record Page(int number, List<Integer> rowIndices, int repeatedRowCount) {}

    public record HeaderFooter(String left, String center, String right) {
        boolean isEmpty() {
            return (left == null || left.isEmpty()) && (center == null || center.isEmpty())
                && (right == null || right.isEmpty());
        }
    }

    private static final int A4_WIDTH_HMM = LibreOfficeMetrics.A4_WIDTH_HMM;
    private static final int A4_HEIGHT_HMM = LibreOfficeMetrics.A4_HEIGHT_HMM;
    private static final int MIN_ZOOM = LibreOfficeMetrics.MIN_ZOOM;

    public final int firstRow;
    public final int lastRow;
    public final int firstCol;
    public final int lastCol;
    public final int charWidthTwips;
    public final int zoomPercent;
    public final int pageWidthHmm;
    public final int pageHeightHmm;
    public final int marginLeftHmm;
    public final int marginRightHmm;
    public final int marginTopHmm;
    public final int marginBottomHmm;
    public final int headerMarginHmm;
    public final int footerMarginHmm;
    public final boolean horizontallyCentered;
    public final boolean verticallyCentered;
    public final String defaultFontName;
    public final double defaultFontPt;
    public final HeaderFooter header;
    public final HeaderFooter footer;
    /** Columns firstCol..lastCol. */
    public final List<Column> columns;
    /** Rows firstRow..lastRow (index → row). */
    public final Map<Integer, PlannedRow> rows;
    /** Planned cells (row, col) → cell; covered merged cells absent. */
    public final Map<Long, PlannedCell> cells;
    /** Border facets of EVERY cell in range, covered ones included (a merged region's perimeter is
     * drawn from its perimeter cells' own edges — the Excel/LibreOffice model). */
    public final Map<Long, Borders> borders;
    public final List<CellRangeAddress> mergedRegions;
    public final List<Picture> pictures;
    public final List<Page> pages;

    private SheetPlan(Builder b) {
        this.firstRow = b.firstRow;
        this.lastRow = b.lastRow;
        this.firstCol = b.firstCol;
        this.lastCol = b.lastCol;
        this.charWidthTwips = b.charWidthTwips;
        this.zoomPercent = b.zoom;
        this.pageWidthHmm = b.pageWidthHmm;
        this.pageHeightHmm = b.pageHeightHmm;
        this.marginLeftHmm = b.marginLeftHmm;
        this.marginRightHmm = b.marginRightHmm;
        this.marginTopHmm = b.marginTopHmm;
        this.marginBottomHmm = b.marginBottomHmm;
        this.headerMarginHmm = b.headerMarginHmm;
        this.footerMarginHmm = b.footerMarginHmm;
        this.horizontallyCentered = b.hCenter;
        this.verticallyCentered = b.vCenter;
        this.defaultFontName = b.defaultFontName;
        this.defaultFontPt = b.defaultFontPt;
        this.header = b.header;
        this.footer = b.footer;
        this.columns = Collections.unmodifiableList(b.columns);
        this.rows = Collections.unmodifiableMap(b.rows);
        this.cells = Collections.unmodifiableMap(b.cells);
        this.borders = Collections.unmodifiableMap(b.borders);
        this.mergedRegions = Collections.unmodifiableList(b.merged);
        this.pictures = Collections.unmodifiableList(b.pictures);
        this.pages = Collections.unmodifiableList(b.pages);
    }

    public static long key(int row, int col) {
        return ((long) row << 20) | col;
    }

    public Column column(int index) {
        return columns.get(index - firstCol);
    }

    /** Total scaled content width, 1/100 mm. */
    public int contentWidthHmm() {
        int w = 0;
        for (Column c : columns) w += c.scaledHmm();
        return w;
    }

    /** Left edge of column {@code col} relative to the content origin, 1/100 mm. */
    public int columnLeftHmm(int col) {
        int x = 0;
        for (Column c : columns) {
            if (c.index() == col) return x;
            x += c.scaledHmm();
        }
        return x;
    }

    public int availableWidthHmm() {
        return pageWidthHmm - marginLeftHmm - marginRightHmm;
    }

    public int availableHeightHmm() {
        return pageHeightHmm - marginTopHmm - marginBottomHmm;
    }

    /** X of the content origin on the page (left margin, plus centring), 1/100 mm. */
    public int originXHmm() {
        int x = marginLeftHmm;
        if (horizontallyCentered) x += Math.max(0, (availableWidthHmm() - contentWidthHmm()) / 2);
        return x;
    }

    public int pageContentHeightHmm(Page page) {
        int h = 0;
        for (int r : page.rowIndices()) h += rows.get(r).scaledHmm();
        return h;
    }

    public int originYHmm(Page page) {
        int y = marginTopHmm;
        if (verticallyCentered) y += Math.max(0, (availableHeightHmm() - pageContentHeightHmm(page)) / 2);
        return y;
    }

    // ── building ─────────────────────────────────────────────────────────────────────────

    public static SheetPlan of(Workbook wb, Sheet sheet) {
        return new Builder(wb, sheet).build();
    }

    private static final class Builder {
        final Workbook wb;
        final Sheet sh;
        int firstRow, lastRow, firstCol, lastCol;
        int charWidthTwips;
        int zoom;
        int pageWidthHmm, pageHeightHmm;
        int marginLeftHmm, marginRightHmm, marginTopHmm, marginBottomHmm, headerMarginHmm, footerMarginHmm;
        boolean hCenter, vCenter;
        String defaultFontName;
        double defaultFontPt;
        HeaderFooter header, footer;
        final List<Column> columns = new ArrayList<>();
        final Map<Integer, PlannedRow> rows = new HashMap<>();
        final Map<Long, PlannedCell> cells = new HashMap<>();
        final Map<Long, Borders> borders = new HashMap<>();
        final List<CellRangeAddress> merged = new ArrayList<>();
        final List<Picture> pictures = new ArrayList<>();
        final List<Page> pages = new ArrayList<>();
        final DataFormatter formatter = new DataFormatter(Locale.US);
        FormulaEvaluator evaluator;

        Builder(Workbook wb, Sheet sh) {
            this.wb = wb;
            this.sh = sh;
        }

        SheetPlan build() {
            try {
                evaluator = wb.getCreationHelper().createFormulaEvaluator();
            } catch (RuntimeException e) {
                evaluator = null;
            }
            Font defaultFont = wb.getFontAt(0);
            defaultFontName = defaultFont.getFontName();
            defaultFontPt = defaultFont.getFontHeightInPoints();
            charWidthTwips = LibreOfficeMetrics.charWidthTwips(wb);

            readPageSetup();
            readRange();
            readMerges();
            readColumnsAndRows();
            readCellsAndBorders();
            trimToUsedArea();
            columns.removeIf(c -> c.index() > lastCol);
            rows.keySet().removeIf(r -> r > lastRow);
            cells.keySet().removeIf(k -> (int) (k >> 20) > lastRow || (int) (k & 0xFFFFF) > lastCol);
            computeZoom();
            scale();
            readPictures();
            paginate();
            return new SheetPlan(this);
        }

        private void readPageSetup() {
            PrintSetup ps = sh.getPrintSetup();
            // Only A4 (paper 9) is used by this app's templates; everything else falls back to
            // A4 too rather than guessing a paper table.
            pageWidthHmm = ps.getLandscape() ? A4_HEIGHT_HMM : A4_WIDTH_HMM;
            pageHeightHmm = ps.getLandscape() ? A4_WIDTH_HMM : A4_HEIGHT_HMM;
            marginLeftHmm = LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.LEFT));
            marginRightHmm = LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.RIGHT));
            marginTopHmm = LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.TOP));
            marginBottomHmm = LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.BOTTOM));
            headerMarginHmm = LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.HEADER));
            footerMarginHmm = LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.FOOTER));
            hCenter = sh.getHorizontallyCenter();
            vCenter = sh.getVerticallyCenter();
            header = new HeaderFooter(sh.getHeader().getLeft(), sh.getHeader().getCenter(), sh.getHeader().getRight());
            footer = new HeaderFooter(sh.getFooter().getLeft(), sh.getFooter().getCenter(), sh.getFooter().getRight());
        }

        private void readRange() {
            firstRow = 0;
            lastRow = Math.max(0, sh.getLastRowNum());
            firstCol = 0;
            lastCol = 0;
            for (int r = 0; r <= lastRow; r++) {
                Row row = sh.getRow(r);
                if (row != null) lastCol = Math.max(lastCol, row.getLastCellNum() - 1);
            }
            String area = wb.getPrintArea(wb.getSheetIndex(sh));
            if (area != null && !area.isBlank()) {
                try {
                    AreaReference ref = new AreaReference(area, SpreadsheetVersion.EXCEL97);
                    firstRow = ref.getFirstCell().getRow();
                    lastRow = ref.getLastCell().getRow();
                    firstCol = ref.getFirstCell().getCol();
                    lastCol = ref.getLastCell().getCol();
                } catch (RuntimeException ignored) {
                    // unparsable print area — keep the sheet extent
                }
            }
        }

        private void readMerges() {
            for (CellRangeAddress m : sh.getMergedRegions()) {
                if (m.getLastRow() < firstRow || m.getFirstRow() > lastRow
                    || m.getLastColumn() < firstCol || m.getFirstColumn() > lastCol) continue;
                merged.add(m);
            }
        }

        private void readColumnsAndRows() {
            for (int c = firstCol; c <= lastCol; c++) {
                int twips = sh.isColumnHidden(c) ? 0
                    : LibreOfficeMetrics.columnTwips(sh.getColumnWidth(c), charWidthTwips);
                columns.add(new Column(c, twips, 0));
            }
            for (int r = firstRow; r <= lastRow; r++) {
                Row row = sh.getRow(r);
                int twips;
                if (row == null) twips = Math.round(sh.getDefaultRowHeightInPoints() * 20f);
                else if (row.getZeroHeight()) twips = 0;
                else twips = row.getHeight();
                rows.put(r, new PlannedRow(r, twips, 0));
            }
        }

        private CellRangeAddress regionOf(int r, int c) {
            for (CellRangeAddress m : merged) {
                if (m.isInRange(r, c)) return m;
            }
            return null;
        }

        private void readCellsAndBorders() {
            for (int r = firstRow; r <= lastRow; r++) {
                Row row = sh.getRow(r);
                for (int c = firstCol; c <= lastCol; c++) {
                    Cell cell = row != null ? row.getCell(c) : null;
                    CellStyle style = cell != null ? cell.getCellStyle() : sh.getColumnStyle(c);
                    Borders b = style != null ? bordersOf(style) : Borders.NONE;
                    borders.put(key(r, c), b);
                    CellRangeAddress region = regionOf(r, c);
                    if (region != null && (region.getFirstRow() != r || region.getFirstColumn() != c)) {
                        continue; // covered by a merged region — its top-left cell carries the content
                    }
                    int lastR = region != null ? Math.min(region.getLastRow(), lastRow) : r;
                    int lastC = region != null ? Math.min(region.getLastColumn(), lastCol) : c;
                    String text = "";
                    boolean numeric = false;
                    if (cell != null) {
                        CellType type = cell.getCellType();
                        if (type == CellType.FORMULA) {
                            CellType resultType = null;
                            try {
                                if (evaluator != null) {
                                    CellValue v = evaluator.evaluate(cell);
                                    resultType = v != null ? v.getCellType() : null;
                                    text = formatter.formatCellValue(cell, evaluator);
                                }
                            } catch (RuntimeException e) {
                                resultType = null;
                            }
                            if (resultType == null) {
                                try {
                                    resultType = cell.getCachedFormulaResultType();
                                    text = formatter.formatCellValue(cell);
                                } catch (RuntimeException e) {
                                    text = "";
                                }
                            }
                            numeric = resultType == CellType.NUMERIC;
                        } else if (type == CellType.NUMERIC) {
                            numeric = true;
                            try {
                                text = formatter.formatCellValue(cell);
                            } catch (RuntimeException e) {
                                text = String.valueOf(cell.getNumericCellValue());
                            }
                        } else if (type == CellType.STRING) {
                            text = cell.getStringCellValue();
                        } else if (type == CellType.BOOLEAN) {
                            text = cell.getBooleanCellValue() ? "TRUE" : "FALSE";
                        }
                    }
                    if (numeric && style != null) text = padForSkipTokens(text, style.getDataFormatString());
                    Facets facets = style != null ? facetsOf(style, b, numeric)
                        : new Facets(defaultFontName, defaultFontPt, false, false, false, HAlign.LEFT, true,
                            VAlign.BOTTOM, false, false, b, null);
                    cells.put(key(r, c), new PlannedCell(r, c, lastR, lastC, text, numeric, facets));
                }
            }
        }

        /** Excel's {@code _x} format token reserves the width of {@code x} — LibreOffice draws it
         * as a space ("8,640.00 " for {@code #,##0.00_ }); POI's DataFormatter drops it, which
         * shifts every right-aligned amount by a space width. Re-add one space per trailing (and
         * leading) skip token of the section that applies to the value. */
        static String padForSkipTokens(String text, String format) {
            if (text == null || text.isEmpty() || format == null || format.isEmpty()) return text;
            String[] sections = format.split(";", -1);
            boolean negative = text.trim().startsWith("-");
            String section = negative && sections.length > 1 ? sections[1] : sections[0];
            int firstDigit = -1;
            int lastDigit = -1;
            for (int i = 0; i < section.length(); i++) {
                char ch = section.charAt(i);
                if (ch == '\\' || ch == '_' || ch == '*') { i++; continue; }
                if (ch == '0' || ch == '#' || ch == '?' || ch == '@') {
                    if (firstDigit < 0) firstDigit = i;
                    lastDigit = i;
                }
            }
            if (lastDigit < 0) return text;
            int trailing = 0;
            for (int i = lastDigit + 1; i < section.length(); i++) {
                if (section.charAt(i) == '_' && i + 1 < section.length()) { trailing++; i++; }
            }
            int leading = 0;
            for (int i = 0; i < firstDigit; i++) {
                if (section.charAt(i) == '_' && i + 1 < section.length()) { leading++; i++; }
            }
            return " ".repeat(leading) + text + " ".repeat(trailing);
        }

        private Borders bordersOf(CellStyle style) {
            return new Borders(nz(style.getBorderTop()), nz(style.getBorderRight()),
                nz(style.getBorderBottom()), nz(style.getBorderLeft()));
        }

        private static BorderStyle nz(BorderStyle s) {
            return s == null ? BorderStyle.NONE : s;
        }

        private Facets facetsOf(CellStyle style, Borders b, boolean numeric) {
            Font font;
            try {
                font = wb.getFontAt(style.getFontIndex());
            } catch (RuntimeException e) {
                font = wb.getFontAt(0);
            }
            HorizontalAlignment ha = style.getAlignment();
            boolean general = ha == null || ha == HorizontalAlignment.GENERAL;
            HAlign h;
            if (general) h = numeric ? HAlign.RIGHT : HAlign.LEFT;
            else h = switch (ha) {
                case CENTER, CENTER_SELECTION -> HAlign.CENTER;
                case RIGHT -> HAlign.RIGHT;
                default -> HAlign.LEFT;
            };
            VerticalAlignment va = style.getVerticalAlignment();
            VAlign v = va == VerticalAlignment.TOP ? VAlign.TOP
                : (va == VerticalAlignment.CENTER || va == VerticalAlignment.JUSTIFY || va == VerticalAlignment.DISTRIBUTED)
                    ? VAlign.CENTER : VAlign.BOTTOM;
            String fill = null;
            if (style.getFillPattern() == FillPatternType.SOLID_FOREGROUND) {
                fill = hex(style.getFillForegroundColorColor());
            }
            return new Facets(font.getFontName(), font.getFontHeightInPoints(), font.getBold(), font.getItalic(),
                font.getUnderline() != Font.U_NONE, h, general, v, style.getWrapText(), style.getShrinkToFit(),
                b, fill);
        }

        private static String hex(Color color) {
            if (color instanceof org.apache.poi.hssf.util.HSSFColor hssf) {
                short[] rgb = hssf.getTriplet();
                if (rgb == null) return null;
                String s = String.format(Locale.ROOT, "#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
                return "#ffffff".equals(s) ? null : s; // white = paper, never drawn
            }
            return null;
        }

        /** LibreOffice prints (and fits) the print range trimmed to the last row/column holding a
         * value or a visible attribute — measured: adding a value one row below the last used row
         * changed the fit zoom from 72% to 71%, a value outside the print area did not. */
        private void trimToUsedArea() {
            int usedRow = firstRow;
            int usedCol = firstCol;
            for (PlannedCell cell : cells.values()) {
                boolean hasValue = cell.text() != null && !cell.text().isEmpty();
                if (hasValue) {
                    usedRow = Math.max(usedRow, cell.lastRow());
                    usedCol = Math.max(usedCol, cell.lastCol());
                }
            }
            for (Map.Entry<Long, Borders> e : borders.entrySet()) {
                int r = (int) (e.getKey() >> 20);
                int c = (int) (e.getKey() & 0xFFFFF);
                PlannedCell pc = cells.get(e.getKey());
                boolean visible = e.getValue().anyVisible() || (pc != null && pc.facets().fillHex() != null);
                if (visible) {
                    usedRow = Math.max(usedRow, r);
                    usedCol = Math.max(usedCol, c);
                }
            }
            lastRow = Math.min(lastRow, usedRow);
            lastCol = Math.min(lastCol, usedCol);
        }

        /** Measured, not documented: LibreOffice takes the fit-to-pages intent from the WSBOOL bit
         * POI exposes as {@code Sheet#getAutobreaks()} (POI and LibreOffice disagree on the byte
         * order of that record) — true → fit to {@code fitWidth × fitHeight} and IGNORE the
         * explicit scale; false → the explicit scale and ignore the fit counts. Confirmed on the
         * template-derived sheets (autobreaks true, scale 71 → rendered fit 72%) and the owner's
         * hand-filled workbook (autobreaks false, scale 86, fit 1×1 → rendered 86%, two pages). */
        private void computeZoom() {
            PrintSetup ps = sh.getPrintSetup();
            int fitW = Math.max(0, ps.getFitWidth());
            int fitH = Math.max(0, ps.getFitHeight());
            boolean fit = sh.getAutobreaks();
            if (!fit) {
                int scale = ps.getScale();
                zoom = scale <= 0 ? 100 : Math.max(MIN_ZOOM, Math.min(400, scale));
                return;
            }
            long contentW = 0;
            for (Column c : columns) contentW += c.twips();
            long contentH = 0;
            for (PlannedRow r : rows.values()) contentH += r.twips();
            double availW = (pageWidthHmm - marginLeftHmm - marginRightHmm) / LibreOfficeMetrics.HMM_PER_TWIP;
            double availH = (pageHeightHmm - marginTopHmm - marginBottomHmm) / LibreOfficeMetrics.HMM_PER_TWIP;
            zoom = LibreOfficeMetrics.fitZoomPercent(contentW, contentH, availW, availH, fitW, fitH);
        }

        private void scale() {
            for (int i = 0; i < columns.size(); i++) {
                Column c = columns.get(i);
                columns.set(i, new Column(c.index(), c.twips(), LibreOfficeMetrics.scaledHmm(c.twips(), zoom)));
            }
            for (Map.Entry<Integer, PlannedRow> e : rows.entrySet()) {
                PlannedRow r = e.getValue();
                e.setValue(new PlannedRow(r.index(), r.twips(), LibreOfficeMetrics.scaledHmm(r.twips(), zoom)));
            }
        }

        private void readPictures() {
            if (!(sh instanceof HSSFSheet hssf)) return;
            var patriarch = hssf.getDrawingPatriarch();
            if (patriarch == null) return;
            for (var shape : patriarch.getChildren()) {
                if (!(shape instanceof HSSFPicture pic)) continue;
                HSSFClientAnchor a = pic.getClientAnchor();
                if (a == null || pic.getPictureData() == null) continue;
                pictures.add(new Picture(pic.getPictureData().getData(), pic.getPictureData().getMimeType(),
                    a.getRow1(), a.getDy1(), a.getCol1(), a.getDx1(), a.getRow2(), a.getDy2(), a.getCol2(), a.getDx2()));
            }
        }

        /** LibreOffice's page fill: rows in order, a new page after a manual row break or when the
         * next row would overflow the page body (paper minus margins — the header/footer bands
         * live inside the margins); every page after the first starts with the repeating rows. */
        private void paginate() {
            int bodyHmm = pageHeightHmm - marginTopHmm - marginBottomHmm;
            Set<Integer> breakAfter = new HashSet<>();
            for (int b : sh.getRowBreaks()) breakAfter.add(b);
            List<Integer> repeat = new ArrayList<>();
            CellRangeAddress rr = sh.getRepeatingRows();
            if (rr != null) {
                for (int r = Math.max(rr.getFirstRow(), firstRow); r <= Math.min(rr.getLastRow(), lastRow); r++) {
                    repeat.add(r);
                }
            }
            int repeatHmm = 0;
            for (int r : repeat) repeatHmm += rows.get(r).scaledHmm();

            List<Integer> current = new ArrayList<>();
            int used = 0;
            int bodyRowsOnPage = 0;
            int repeatedOnPage = 0;
            int prev = -1;
            for (int r = firstRow; r <= lastRow; r++) {
                int h = rows.get(r).scaledHmm();
                boolean mustBreak = prev >= 0 && breakAfter.contains(prev);
                boolean overflow = bodyRowsOnPage > 0 && used + h > bodyHmm;
                if (bodyRowsOnPage > 0 && (mustBreak || overflow)) {
                    pages.add(new Page(pages.size() + 1, current, repeatedOnPage));
                    current = new ArrayList<>();
                    used = 0;
                    bodyRowsOnPage = 0;
                    repeatedOnPage = 0;
                    boolean afterRepeat = !repeat.isEmpty() && r > repeat.get(repeat.size() - 1);
                    if (afterRepeat) {
                        current.addAll(repeat);
                        used = repeatHmm;
                        repeatedOnPage = repeat.size();
                    }
                }
                current.add(r);
                used += h;
                bodyRowsOnPage++;
                prev = r;
            }
            if (!current.isEmpty() || pages.isEmpty()) {
                pages.add(new Page(pages.size() + 1, current, repeatedOnPage));
            }
        }
    }
}
