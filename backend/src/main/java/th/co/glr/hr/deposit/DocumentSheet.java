package th.co.glr.hr.deposit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.BreakIterator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFShape;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.core.io.ClassPathResource;
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.common.sheet.FontResolver;
import th.co.glr.hr.common.sheet.LibreOfficeMetrics;

/**
 * What {@link DepositNoticePdfRenderer} and {@link RemainingInvoicePdfRenderer} share: both fill the
 * "Update" sheet of an owner-supplied BIFF8 template with STORED values (never a template formula),
 * wrap descriptions with LibreOffice's own column model, and, when the items outgrow the template's
 * item zone, insert rows above the หมายเหตุ block and paginate (the quotation stack's technique:
 * shiftRows + extended print area + repeating header rows + page breaks that never split a block).
 */
final class DocumentSheet {
    private DocumentSheet() {}

    static final int ITEM_START = 12;          // 0-based row of the first item (row 13)
    static final int HEADER_LAST_ROW = 9;      // rows 0..9 (A1:I10) repeat on every page
    private static final int DESCRIPTION_COL = 1;   // B
    private static final int DISCOUNT_COL = 6;      // G  (the short "ลด X" label)
    private static final int AMOUNT_COL = 8;        // I
    private static final int HELPER_COL = 9;        // J  (the template's hidden-formula helper)
    private static final int LAST_COL = 8;          // print columns A..I
    private static final int MONEY_PAD_CHARS = 1;
    private static final int MONEY_UNITS_PER_CHAR = 300;
    private static final int NOTE_LINES = 6;

    /** One printed line: a real item, or the remaining invoice's deposit-deduction row (seq 0 = no number). */
    record Line(int seq, String description, BigDecimal qty, String unit, BigDecimal unitPrice,
                BigDecimal netUnitPrice, BigDecimal amount) {
        /** Column G's single total "ลด X", X = unit price - net price: only when a discount really applies (net < unit). */
        String discountText() {
            if (unitPrice == null || netUnitPrice == null || netUnitPrice.compareTo(unitPrice) >= 0) return "";
            return "ลด " + String.format(Locale.US, "%,.2f", unitPrice.subtract(netUnitPrice));
        }
    }

    /** A column's usable text width in pt, with the font its cells print in. */
    private record Measure(FontResolver.Resolved font, double sizePt, boolean bold, double limitPt) {
        double width(String s) { return FontResolver.stringWidthPt(font, s, sizePt, bold).orElse(-1); }
        boolean measurable() { return width("x") >= 0; }
    }

    static Workbook openTemplate(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return WorkbookFactory.create(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read template " + path, e);
        }
    }

    static byte[] bytes(Workbook wb) {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot write workbook", e);
        }
    }

    /** The template's own หมายเหตุ lines (what both the PDF and the preview show; stored notes never print). */
    static List<String> templateNotes(String templatePath) {
        try (Workbook wb = openTemplate(templatePath)) {
            Sheet sh = wb.getSheet("Update");
            List<String> lines = new ArrayList<>();
            for (int r = 0; r <= sh.getLastRowNum(); r++) {
                Cell c = sh.getRow(r) == null ? null : sh.getRow(r).getCell(DESCRIPTION_COL);
                if (c != null && c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                    && "หมายเหตุ".equals(c.getStringCellValue().trim())) {
                    for (int i = 1; i <= NOTE_LINES; i++) {
                        Row row = sh.getRow(r + i);
                        Cell n = row == null ? null : row.getCell(DESCRIPTION_COL);
                        if (n != null && n.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                            && !n.getStringCellValue().isBlank()) lines.add(n.getStringCellValue().strip());
                    }
                    break;
                }
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── cell helpers (keep the template's style; a literal replaces any template formula) ────────

    static Cell cell(Sheet sh, int r, int c) {
        Row row = sh.getRow(r);
        if (row == null) row = sh.createRow(r);
        Cell cell = row.getCell(c);
        if (cell == null) cell = row.createCell(c);
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) cell.setBlank();
        return cell;
    }

    static void str(Sheet sh, int r, int c, String v) { cell(sh, r, c).setCellValue(v == null ? "" : v); }

    static void num(Sheet sh, int r, int c, BigDecimal v) { cell(sh, r, c).setCellValue(v == null ? 0 : v.doubleValue()); }

    /** "( name )", in the template's own cell style; a document with no preparer leaves the template's own line. */
    static void preparer(Sheet sh, int r, String name) {
        if (name != null && !name.isBlank()) str(sh, r, 1, "( " + name.trim() + " )");
    }

    /** The signature date line, in the template's own cell style. */
    static void signatureDate(Sheet sh, int r, LocalDate d) {
        str(sh, r, 1, signatureDate(d));
    }

    // ── text rules ───────────────────────────────────────────────────────────────────────────────

    /** "name (branch) / เลขประจำตัวผู้เสียภาษี : taxId", blank parts omitted. */
    static String customerLine(String name, String branch, String taxId) {
        StringBuilder sb = new StringBuilder(name == null ? "" : name.trim());
        if (branch != null && !branch.isBlank()) sb.append(" (").append(branch.trim()).append(")");
        if (taxId != null && !taxId.isBlank()) sb.append(" / เลขประจำตัวผู้เสียภาษี : ").append(taxId.trim());
        return sb.toString();
    }

    static String signatureDate(LocalDate d) {
        return d == null ? "" : "วันที่ " + d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + (d.getYear() + 543);
    }

    static String thaiDate(LocalDate d) { return ThaiText.date(d); }

    // ── description wrap (LibreOffice's column model, the same measure QuotationRenderer uses) ──

    private static Measure measureOf(Sheet sh, int col, int fontRow) {
        Font font = fontAt(sh, fontRow, col);
        int colTwips = LibreOfficeMetrics.columnTwips(sh.getColumnWidth(col), LibreOfficeMetrics.charWidthTwips(sh.getWorkbook()));
        double limit = (colTwips - 2.0 * LibreOfficeMetrics.TEXT_INSET_TWIPS) / 20.0 - 1.0; // 1pt safety
        return new Measure(FontResolver.resolve(font.getFontName()), font.getFontHeightInPoints(), font.getBold(), limit);
    }

    private static Font fontAt(Sheet sh, int row, int col) {
        return sh.getWorkbook().getFontAt(cell(sh, row, col).getCellStyle().getFontIndexAsInt());
    }

    /** Greedy wrap at Thai/Latin line-break opportunities; a lone segment wider than the column is cut between
     * characters (grapheme clusters), never between a consonant and its vowel or tone mark. */
    static List<String> wrap(Measure m, String text) {
        List<String> out = new ArrayList<>();
        Locale th = Locale.forLanguageTag("th");
        for (String para : (text == null ? "" : text).split("\\R", -1)) {
            if (!m.measurable()) { out.add(para); continue; }
            StringBuilder line = new StringBuilder();
            for (String seg : segments(BreakIterator.getLineInstance(th), para)) {
                if (m.width(line + seg.stripTrailing()) <= m.limitPt()) { line.append(seg); continue; }
                if (line.length() > 0) { out.add(line.toString().stripTrailing()); line.setLength(0); }
                if (m.width(seg.stripTrailing()) <= m.limitPt()) { line.append(seg); continue; }
                for (String g : segments(BreakIterator.getCharacterInstance(th), seg)) { // oversize segment
                    if (line.length() > 0 && m.width(line + g) > m.limitPt()) { out.add(line.toString()); line.setLength(0); }
                    line.append(g);
                }
            }
            out.add(line.toString().stripTrailing());
        }
        return out;
    }

    private static List<String> segments(BreakIterator it, String text) {
        List<String> parts = new ArrayList<>();
        it.setText(text);
        for (int start = it.first(), end = it.next(); end != BreakIterator.DONE; start = end, end = it.next())
            parts.add(text.substring(start, end));
        return parts;
    }

    // ── item zone + flow ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes {@code lines} from row {@link #ITEM_START}. If they need more rows than the template's zone
     * (down to {@code notesRow}) the footer is shifted down by the difference. Returns that shift.
     * {@code pageBlocks} receives (firstRow, rowCount) of every item for the page-break pass.
     */
    static int fillItems(Sheet sh, List<Line> lines, int notesRow, List<int[]> pageBlocks) {
        Measure descriptionColumn = measureOf(sh, DESCRIPTION_COL, ITEM_START);
        List<List<String>> wrapped = new ArrayList<>();
        int needed = 0;
        for (Line l : lines) {
            List<String> w = wrap(descriptionColumn, l.description());
            wrapped.add(w);
            needed += Math.max(1, w.size());
        }
        int zone = notesRow - ITEM_START;
        int delta = Math.max(0, needed - zone);
        if (delta > 0) insertRows(sh, notesRow, delta);
        int end = notesRow + delta;                     // first row below the zone
        for (int r = ITEM_START; r < end; r++) {        // the template's example values and formulas never print
            Row row = sh.getRow(r);
            if (row == null) continue;
            for (int c = 0; c <= HELPER_COL; c++) {
                Cell cl = row.getCell(c);
                if (cl != null) cl.setBlank();
            }
        }
        CellStyle[] itemStyles = new CellStyle[LAST_COL + 1];   // an interior item row: black figures, plain borders
        for (int c = 0; c <= LAST_COL; c++) itemStyles[c] = cell(sh, ITEM_START + 1, c).getCellStyle();
        Map<Integer, CellStyle> labelStyles = new HashMap<>();
        int r = ITEM_START;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            List<String> w = wrapped.get(i);
            int rows = Math.max(1, w.size());
            for (int k = 0; k < rows; k++) {
                if (r + k == ITEM_START) continue;
                for (int c = 0; c <= LAST_COL; c++) cell(sh, r + k, c).setCellStyle(itemStyles[c]);
            }
            if (l.seq() > 0) cell(sh, r, 0).setCellValue(l.seq());
            str(sh, r, DESCRIPTION_COL, w.isEmpty() ? "" : w.get(0));
            if (l.qty() != null) num(sh, r, 2, l.qty());
            if (l.unit() != null) str(sh, r, 3, l.unit());
            if (l.unitPrice() != null) num(sh, r, 4, l.unitPrice());
            String disc = l.discountText();
            if (!disc.isEmpty()) {
                str(sh, r, DISCOUNT_COL, disc);
                styleDiscountLabel(sh, r, labelStyles);
            }
            if (l.netUnitPrice() != null) num(sh, r, 7, l.netUnitPrice());
            if (l.amount() != null) num(sh, r, AMOUNT_COL, l.amount());
            for (int k = 1; k < w.size(); k++) str(sh, r + k, DESCRIPTION_COL, w.get(k));
            pageBlocks.add(new int[] {r, rows});
            r += rows;
        }
        return delta;
    }

    /** G's cell prints like the E/H/I figures: their font, right-aligned, never shrunk. */
    private static void styleDiscountLabel(Sheet sh, int r, Map<Integer, CellStyle> cache) {
        Cell cell = sh.getRow(r).getCell(DISCOUNT_COL);
        CellStyle base = cell.getCellStyle();
        Font figures = fontAt(sh, r, AMOUNT_COL);
        cell.setCellStyle(cache.computeIfAbsent((int) base.getIndex(), k -> {
            CellStyle st = sh.getWorkbook().createCellStyle();
            st.cloneStyleFrom(base);
            st.setFont(figures);
            st.setAlignment(HorizontalAlignment.RIGHT);
            st.setShrinkToFit(false);
            return st;
        }));
    }

    /** Inserts {@code n} item-styled rows above {@code at}, moving everything from {@code at} down (pictures too).
     * HSSF copies a row's height when shifting but not its hidden flag, so the templates must carry no hidden row
     * (PdfTemplateHygieneTest pins that). */
    private static void insertRows(Sheet sh, int at, int n) {
        Row proto = sh.getRow(at - 1);
        sh.shiftRows(at, sh.getLastRowNum(), n, true, false);
        for (int i = 0; i < n; i++) {
            Row row = sh.createRow(at + i);
            row.setHeight(proto.getHeight());
            for (Cell pc : proto) {
                if (pc.getColumnIndex() > HELPER_COL) continue;
                row.createCell(pc.getColumnIndex()).setCellStyle(pc.getCellStyle());
            }
        }
        HSSFPatriarch patriarch = ((HSSFSheet) sh).getDrawingPatriarch();
        if (patriarch == null) return;
        for (HSSFShape shape : patriarch.getChildren()) {
            if (shape.getAnchor() instanceof HSSFClientAnchor a && a.getRow1() >= at) {
                a.setRow1(a.getRow1() + n);
                a.setRow2(a.getRow2() + n);
            }
        }
    }

    /** Widens a money column so the largest printed figure never shows "###" (never narrows). */
    static void fitMoney(Sheet sh, int col, BigDecimal... values) {
        int chars = 0;
        for (BigDecimal v : values)
            if (v != null) chars = Math.max(chars, String.format(Locale.US, "%,.2f", v.abs()).length() + (v.signum() < 0 ? 1 : 0));
        int needed = (chars + MONEY_PAD_CHARS) * MONEY_UNITS_PER_CHAR;
        if (needed > sh.getColumnWidth(col)) sh.setColumnWidth(col, needed);
    }

    // ── print setup ──────────────────────────────────────────────────────────────────────────────

    /**
     * Within capacity: fit to ONE page (the template's own layout). When rows were inserted ({@code delta > 0}): print area through the signature
     * row, repeating header rows A1:I10, "หน้า &P/&N", and a page break before any item or the whole
     * footer block (notes + totals + signature) that would otherwise straddle a page.
     */
    static void finishPage(Sheet sh, int delta, int notesRow, int lastRow, List<int[]> itemBlocks) {
        sh.setColumnWidth(DISCOUNT_COL, sh.getColumnWidth(4)); // G (ส่วนลด) is exactly as wide as E (ราคา), after any money sizing
        sh.setFitToPage(true);
        sh.setAutobreaks(true);
        PrintSetup ps = sh.getPrintSetup();
        ps.setFitWidth((short) 1);
        ps.setFitHeight((short) (delta == 0 ? 1 : 0)); // within capacity: the template's own one page
        if (delta == 0) return;
        Workbook wb = sh.getWorkbook();
        wb.setPrintArea(wb.getSheetIndex(sh), 0, LAST_COL, 0, lastRow);
        sh.setRepeatingRows(CellRangeAddress.valueOf("A1:I" + (HEADER_LAST_ROW + 1)));
        sh.getFooter().setCenter("หน้า &P/&N");

        int zoom = fitZoom(sh);
        int bodyHmm = LibreOfficeMetrics.A4_HEIGHT_HMM
            - LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.TOP))
            - LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.BOTTOM));
        // headroom: the "หน้า &P/&N" footer takes space LibreOffice does not take out of the margin (measured: ~2 rows)
        int capacity = bodyHmm - rowsHmm(sh, 0, HEADER_LAST_ROW, zoom) - 3 * rowsHmm(sh, ITEM_START, ITEM_START, zoom);
        int used = 0;
        for (int[] b : itemBlocks) {
            int h = rowsHmm(sh, b[0], b[0] + b[1] - 1, zoom);
            if (used > 0 && used + h > capacity) { sh.setRowBreak(b[0] - 1); used = 0; }
            used += h;
        }
        if (used > 0 && used + rowsHmm(sh, notesRow, lastRow, zoom) > capacity) sh.setRowBreak(notesRow - 1);
    }

    private static int fitZoom(Sheet sh) {
        int charTwips = LibreOfficeMetrics.charWidthTwips(sh.getWorkbook());
        long contentTwips = 0;
        for (int c = 0; c <= LAST_COL; c++) contentTwips += LibreOfficeMetrics.columnTwips(sh.getColumnWidth(c), charTwips);
        double availableTwips = (LibreOfficeMetrics.A4_WIDTH_HMM
            - LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.LEFT))
            - LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.RIGHT))) / LibreOfficeMetrics.HMM_PER_TWIP;
        return LibreOfficeMetrics.fitZoomPercent(contentTwips, 0, availableTwips, 0, 1, 0);
    }

    private static int rowsHmm(Sheet sh, int from, int to, int zoom) {
        int sum = 0;
        for (int r = from; r <= to; r++) {
            Row row = sh.getRow(r);
            int twips = row == null ? Math.round(sh.getDefaultRowHeightInPoints() * 20f)
                : row.getZeroHeight() ? 0 : row.getHeight();
            sum += LibreOfficeMetrics.scaledHmm(twips, zoom);
        }
        return sum;
    }
}
