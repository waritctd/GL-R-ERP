package th.co.glr.hr.common.sheet;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import th.co.glr.hr.common.sheet.SheetPlan.Borders;
import th.co.glr.hr.common.sheet.SheetPlan.Facets;
import th.co.glr.hr.common.sheet.SheetPlan.Page;
import th.co.glr.hr.common.sheet.SheetPlan.Picture;
import th.co.glr.hr.common.sheet.SheetPlan.PlannedCell;

/**
 * Draws a {@link SheetPlan} as a self-contained HTML document whose printed pages are the
 * LibreOffice render of the same sheet: one absolutely positioned A4 {@code div.page} per plan
 * page, the content box at the plan's origin, every cell an absolutely positioned box (font,
 * alignment, wrap, shrink-to-fit) and every border a separately positioned rule of the exact
 * LibreOffice stroke width, centred on the grid line — no CSS table, no {@code border-collapse},
 * no {@code 1px}. Shared edges are resolved before drawing: an edge two cells both declare is
 * drawn once, the heavier style wins, and collinear runs of equal weight merge into one rule.
 * Pictures are inlined as {@code data:} URIs at their anchors; the sheet footer/header ("หน้า
 * &P/&N") is drawn in-page at LibreOffice's position, so the printer needs no header/footer
 * template and no page margins of its own.
 */
public final class SheetHtmlRenderer {
    private SheetHtmlRenderer() {}

    /** A classifier turning a picture into an extra CSS class ("logo", "sig-image", …) so callers
     * and tests can tell the pictures apart; may return null. */
    public interface PictureClassifier extends Function<Picture, String> {}

    public record Rendered(String html, SheetPlan plan) {
        public int pageCount() {
            return plan.pages.size();
        }
    }

    public static Rendered render(Workbook wb, Sheet sheet, PictureClassifier classifier) {
        SheetPlan plan = SheetPlan.of(wb, sheet);
        return new Rendered(render(plan, classifier), plan);
    }

    public static String render(SheetPlan plan, PictureClassifier classifier) {
        StringBuilder html = new StringBuilder(64 * 1024);
        double pageW = pt(plan.pageWidthHmm);
        double pageH = pt(plan.pageHeightHmm);
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title></title><style>")
            .append(String.format(Locale.US, "@page{size:%spt %spt;margin:0}", fmt(pageW), fmt(pageH)))
            .append("html,body{margin:0;padding:0;background:#fff}")
            .append(String.format(Locale.US, ".page{position:relative;width:%spt;height:%spt;overflow:hidden;"
                + "break-after:page;page-break-after:always}", fmt(pageW), fmt(pageH)))
            .append(".page.last{break-after:auto;page-break-after:auto}")
            .append(".c{position:absolute;display:flex;box-sizing:border-box;white-space:pre;line-height:normal;"
                + "color:#000;overflow:visible}")
            .append(".c.wrap{white-space:pre-wrap;word-break:normal;overflow-wrap:anywhere}")
            .append(".c.clip{overflow:hidden}")
            .append(".c>span{display:block;flex:none}")
            .append(".c.wrap>span{flex:0 1 auto;max-width:100%}")
            .append(".rules{position:absolute;left:0;top:0;fill:#000}")
            .append(".sheet{position:absolute;overflow:hidden}")
            .append(".fill{position:absolute}")
            .append(".p{position:absolute}")
            .append(".hf{position:absolute;white-space:pre;color:#000;line-height:normal}")
            .append("</style></head><body>");

        int total = plan.pages.size();
        for (Page page : plan.pages) {
            html.append("<div class=\"page").append(page.number() == total ? " last" : "").append("\">");
            renderPage(html, plan, page, classifier);
            html.append("</div>");
        }
        html.append(shrinkScript());
        html.append("</body></html>");
        return html.toString();
    }

    // ── one page ─────────────────────────────────────────────────────────────────────────

    private static void renderPage(StringBuilder html, SheetPlan plan, Page page, PictureClassifier classifier) {
        double originX = pt(plan.originXHmm());
        double originY = pt(plan.originYHmm(page));
        // Row top positions on THIS page (repeated rows first), 1/100 mm from the content origin.
        Map<Integer, Integer> rowTop = new HashMap<>();
        int y = 0;
        for (int r : page.rowIndices()) {
            rowTop.put(r, y);
            y += plan.rows.get(r).scaledHmm();
        }
        int pageContentHmm = y;

        // Cells live in a clipping box the size of the printed range — LibreOffice clips text
        // that spills past the print range's edge (e.g. a long reference number in column I).
        html.append(String.format(Locale.US, "<div class=\"sheet\" style=\"left:%spt;top:%spt;width:%spt;height:%spt\">",
            fmt(originX), fmt(originY), fmt(pt(plan.contentWidthHmm())), fmt(pt(pageContentHmm))));
        // Backgrounds first, then text, then rules on top (LibreOffice draws borders last).
        for (int r : page.rowIndices()) {
            for (SheetPlan.Column col : plan.columns) {
                PlannedCell cell = plan.cells.get(SheetPlan.key(r, col.index()));
                if (cell == null || cell.facets().fillHex() == null) continue;
                Box box = boxOf(plan, cell, rowTop);
                if (box == null) continue;
                html.append(String.format(Locale.US, "<div class=\"fill\" style=\"left:%spt;top:%spt;width:%spt;"
                        + "height:%spt;background:%s\"></div>",
                    fmt(box.x), fmt(box.y), fmt(box.w), fmt(box.h), cell.facets().fillHex()));
            }
        }
        for (int r : page.rowIndices()) {
            for (SheetPlan.Column col : plan.columns) {
                PlannedCell cell = plan.cells.get(SheetPlan.key(r, col.index()));
                if (cell == null || cell.text() == null || cell.text().isEmpty()) continue;
                Box box = boxOf(plan, cell, rowTop);
                if (box == null) continue;
                renderCell(html, plan, cell, box);
            }
        }
        html.append("</div>");
        for (Picture pic : plan.pictures) {
            if (!rowTop.containsKey(pic.row1())) continue;
            renderPicture(html, plan, pic, rowTop, originX, originY, classifier);
        }
        // Rules go into ONE SVG per page, as filled rects in pt: Chromium pixel-snaps CSS box
        // backgrounds to its 96-dpi layout grid (a 0.54 pt rule became 0.75 pt on a 0.75 pt
        // grid — measured), but SVG geometry keeps its floating-point coordinates in the PDF.
        html.append(String.format(Locale.US, "<svg class=\"rules\" "
                + "width=\"%spt\" height=\"%spt\" viewBox=\"0 0 %s %s\" shape-rendering=\"geometricPrecision\">",
            fmt(pt(plan.pageWidthHmm)), fmt(pt(plan.pageHeightHmm)), fmt(pt(plan.pageWidthHmm)), fmt(pt(plan.pageHeightHmm))));
        for (Rule rule : rules(plan, page, rowTop)) {
            double lw = rule.widthPt;
            if (rule.horizontal) {
                html.append(String.format(Locale.US, "<rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\"/>",
                    fmt(originX + pt(rule.from) - lw / 2), fmt(originY + pt(rule.pos) - lw / 2),
                    fmt(pt(rule.to - rule.from) + lw), fmt(lw)));
            } else {
                // (LibreOffice strokes the print range's right-hand edge twice — opaque black over
                // black, invisible; NOT mirrored: it measured 0.63/0.63 pt at 72% but 0.89 vs
                // 0.65 pt at 84%, because a rasteriser snaps a sub-pixel stroke to one pixel however
                // often it is drawn while two anti-aliased fills compound.)
                html.append(String.format(Locale.US, "<rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\"/>",
                    fmt(originX + pt(rule.pos) - lw / 2), fmt(originY + pt(rule.from) - lw / 2),
                    fmt(lw), fmt(pt(rule.to - rule.from) + lw)));
            }
        }
        html.append("</svg>");
        renderHeaderFooter(html, plan, page);
    }

    private record Box(double x, double y, double w, double h) {}

    private static Box boxOf(SheetPlan plan, PlannedCell cell, Map<Integer, Integer> rowTop) {
        Integer top = rowTop.get(cell.row());
        if (top == null) return null;
        int h = 0;
        for (int r = cell.row(); r <= cell.lastRow(); r++) {
            if (!rowTop.containsKey(r)) break;
            h += plan.rows.get(r).scaledHmm();
        }
        int left = plan.columnLeftHmm(cell.col());
        int w = 0;
        for (int c = cell.col(); c <= cell.lastCol(); c++) w += plan.column(c).scaledHmm();
        return new Box(pt(left), pt(top), pt(w), pt(h));
    }

    private static void renderCell(StringBuilder html, SheetPlan plan, PlannedCell cell, Box box) {
        Facets f = cell.facets();
        // LibreOffice lays the text out at the cell font's EXACT scaled size (advances of a
        // 14 pt run at 80% span 11.2 pt-worth) and only draws the glyphs at the floored integer
        // size — measured on the signature underscores (1.8% longer than an 11 pt run). The
        // exact size reproduces LibreOffice's text extents; the glyphs come out ≤ 2% larger.
        double fontPt = f.fontPt() * plan.zoomPercent / 100.0;
        double inset = pt(LibreOfficeMetrics.scaledHmm(LibreOfficeMetrics.TEXT_INSET_TWIPS, plan.zoomPercent));
        String justify = switch (f.hAlign()) {
            case LEFT -> "flex-start";
            case CENTER -> "center";
            case RIGHT -> "flex-end";
        };
        String align = switch (f.vAlign()) {
            case TOP -> "flex-start";
            case CENTER -> "center";
            case BOTTOM -> "flex-end";
        };
        StringBuilder cls = new StringBuilder("c");
        if (f.wrap()) cls.append(" wrap");
        if (f.shrink()) cls.append(" shrink");
        if (!f.wrap() && !cell.merged() && overflowBlocked(plan, cell)) cls.append(" clip");
        StringBuilder style = new StringBuilder();
        style.append(String.format(Locale.US, "left:%spt;top:%spt;width:%spt;height:%spt;", fmt(box.x),
            fmt(box.y), fmt(box.w), fmt(box.h)));
        style.append(String.format(Locale.US, "padding:%spt %spt %spt %spt;", fmt(inset), fmt(inset), fmt(inset), fmt(inset)));
        style.append("justify-content:").append(justify).append(";align-items:").append(align).append(';');
        style.append("font-family:").append(fontStack(f.fontName())).append(';');
        style.append("font-size:").append(fmt(fontPt)).append("pt;");
        if (f.bold()) style.append("font-weight:700;");
        if (f.italic()) style.append("font-style:italic;");
        if (f.underline()) style.append("text-decoration:underline;");
        html.append("<div class=\"").append(cls).append("\" style=\"").append(style).append("\"><span>")
            .append(esc(cell.text())).append("</span></div>");
    }

    /** LibreOffice lets unwrapped text spill over EMPTY neighbours and clips it at the first
     * non-empty one. The neighbour in the direction the text would spill decides. */
    private static boolean overflowBlocked(SheetPlan plan, PlannedCell cell) {
        return switch (cell.facets().hAlign()) {
            case LEFT -> neighbourHasText(plan, cell.row(), cell.col() + 1);
            case RIGHT -> neighbourHasText(plan, cell.row(), cell.col() - 1);
            case CENTER -> neighbourHasText(plan, cell.row(), cell.col() + 1) || neighbourHasText(plan, cell.row(), cell.col() - 1);
        };
    }

    private static boolean neighbourHasText(SheetPlan plan, int row, int col) {
        if (col < plan.firstCol || col > plan.lastCol) return false;
        PlannedCell n = plan.cells.get(SheetPlan.key(row, col));
        if (n != null) return n.text() != null && !n.text().isEmpty();
        // covered by a merged region whose anchor has text?
        for (CellRangeAddress m : plan.mergedRegions) {
            if (m.isInRange(row, col)) {
                PlannedCell anchor = plan.cells.get(SheetPlan.key(m.getFirstRow(), m.getFirstColumn()));
                return anchor != null && anchor.text() != null && !anchor.text().isEmpty();
            }
        }
        return false;
    }

    private static void renderPicture(StringBuilder html, SheetPlan plan, Picture pic, Map<Integer, Integer> rowTop,
                                      double originX, double originY, PictureClassifier classifier) {
        double x1 = anchorX(plan, pic.col1(), pic.dx1());
        double x2 = anchorX(plan, pic.col2(), pic.dx2());
        Double y1 = anchorY(plan, rowTop, pic.row1(), pic.dy1());
        Double y2 = anchorY(plan, rowTop, pic.row2(), pic.dy2());
        if (y1 == null || y2 == null || x2 <= x1 || y2 <= y1) return;
        String cls = classifier != null ? classifier.apply(pic) : null;
        html.append("<img class=\"p").append(cls != null && !cls.isBlank() ? " " + esc(cls) : "").append("\" src=\"data:")
            .append(esc(pic.mimeType())).append(";base64,").append(Base64.getEncoder().encodeToString(pic.data()))
            .append(String.format(Locale.US, "\" style=\"left:%spt;top:%spt;width:%spt;height:%spt\" alt=\"\">",
                fmt(originX + x1), fmt(originY + y1), fmt(x2 - x1), fmt(y2 - y1)));
    }

    private static double anchorX(SheetPlan plan, int col, int dx) {
        if (col > plan.lastCol) return pt(plan.contentWidthHmm());
        int left = plan.columnLeftHmm(col);
        int width = plan.column(Math.max(col, plan.firstCol)).scaledHmm();
        return pt(left + width * (dx / 1024.0));
    }

    private static Double anchorY(SheetPlan plan, Map<Integer, Integer> rowTop, int row, int dy) {
        Integer top = rowTop.get(row);
        if (top == null) {
            // anchor row below the page's rows: clamp to the bottom of the page's last row
            if (row > plan.lastRow || !rowTop.containsKey(row - 1) && row - 1 >= 0) {
                Integer prev = rowTop.get(row - 1);
                if (prev != null) return pt(prev + plan.rows.get(row - 1).scaledHmm());
            }
            return null;
        }
        return pt(top + plan.rows.get(row).scaledHmm() * (dy / 256.0));
    }

    private static void renderHeaderFooter(StringBuilder html, SheetPlan plan, Page page) {
        if (!plan.footer.isEmpty()) {
            int fontPt = LibreOfficeMetrics.fontPt(plan.defaultFontPt, plan.zoomPercent);
            double bottom = pt(plan.footerMarginHmm);
            emitHf(html, plan, page, plan.footer.left(), fontPt, "left:" + fmt(pt(plan.marginLeftHmm)) + "pt;bottom:" + fmt(bottom) + "pt");
            emitHf(html, plan, page, plan.footer.center(), fontPt, "left:0;width:100%;text-align:center;bottom:" + fmt(bottom) + "pt");
            emitHf(html, plan, page, plan.footer.right(), fontPt, "right:" + fmt(pt(plan.marginRightHmm)) + "pt;bottom:" + fmt(bottom) + "pt");
        }
        if (!plan.header.isEmpty()) {
            int fontPt = LibreOfficeMetrics.fontPt(plan.defaultFontPt, plan.zoomPercent);
            double top = pt(plan.headerMarginHmm);
            emitHf(html, plan, page, plan.header.left(), fontPt, "left:" + fmt(pt(plan.marginLeftHmm)) + "pt;top:" + fmt(top) + "pt");
            emitHf(html, plan, page, plan.header.center(), fontPt, "left:0;width:100%;text-align:center;top:" + fmt(top) + "pt");
            emitHf(html, plan, page, plan.header.right(), fontPt, "right:" + fmt(pt(plan.marginRightHmm)) + "pt;top:" + fmt(top) + "pt");
        }
    }

    private static void emitHf(StringBuilder html, SheetPlan plan, Page page, String text, int fontPt, String pos) {
        if (text == null || text.isEmpty()) return;
        String resolved = text.replace("&P", String.valueOf(page.number()))
            .replace("&N", String.valueOf(plan.pages.size()))
            .replaceAll("&[A-Z]", "");
        html.append("<div class=\"hf\" style=\"").append(pos).append(";font-family:").append(fontStack(plan.defaultFontName))
            .append(";font-size:").append(fontPt).append("pt\">").append(esc(resolved)).append("</div>");
    }

    // ── rules ────────────────────────────────────────────────────────────────────────────

    private record Rule(boolean horizontal, int pos, int from, int to, double widthPt) {}

    /** Every border edge on the page, resolved: interior edges of merged regions dropped, a
     * shared edge drawn once at the heavier of the two declared weights, collinear runs merged. */
    private static List<Rule> rules(SheetPlan plan, Page page, Map<Integer, Integer> rowTop) {
        // horizontal: keyed by y (1/100 mm) → column index → weight; vertical: x → row → weight
        Map<Integer, TreeMap<Integer, Double>> horizontal = new TreeMap<>();
        Map<Integer, TreeMap<Integer, Double>> vertical = new TreeMap<>();
        List<Integer> pageRows = page.rowIndices();
        for (int r : pageRows) {
            int top = rowTop.get(r);
            int bottom = top + plan.rows.get(r).scaledHmm();
            for (SheetPlan.Column col : plan.columns) {
                int c = col.index();
                Borders b = plan.borders.get(SheetPlan.key(r, c));
                if (b == null) continue;
                CellRangeAddress region = regionOf(plan, r, c);
                int left = plan.columnLeftHmm(c);
                int right = left + col.scaledHmm();
                if (region == null || region.getFirstRow() == r) put(horizontal, top, c, weight(plan, b.top()));
                if (region == null || region.getLastRow() == r) put(horizontal, bottom, c, weight(plan, b.bottom()));
                if (region == null || region.getFirstColumn() == c) put(vertical, left, r, weight(plan, b.left()));
                if (region == null || region.getLastColumn() == c) put(vertical, right, r, weight(plan, b.right()));
            }
        }
        List<Rule> out = new ArrayList<>();
        for (Map.Entry<Integer, TreeMap<Integer, Double>> line : horizontal.entrySet()) {
            int y = line.getKey();
            for (Run run : runs(line.getValue(), c -> plan.columnLeftHmm(c), c -> plan.column(c).scaledHmm())) {
                out.add(new Rule(true, y, run.from, run.to, run.weight));
            }
        }
        for (Map.Entry<Integer, TreeMap<Integer, Double>> line : vertical.entrySet()) {
            int x = line.getKey();
            for (Run run : runs(line.getValue(), r -> rowTop.get(r), r -> plan.rows.get(r).scaledHmm())) {
                out.add(new Rule(false, x, run.from, run.to, run.weight));
            }
        }
        return out;
    }

    private record Run(int from, int to, double weight) {}

    /** Merges consecutive (by index) segments of equal weight into one run. */
    private static List<Run> runs(TreeMap<Integer, Double> segments, Function<Integer, Integer> start,
                                  Function<Integer, Integer> length) {
        List<Run> out = new ArrayList<>();
        Integer runStartIdx = null;
        int runFrom = 0;
        int runTo = 0;
        double runWeight = 0;
        Integer prevIdx = null;
        for (Map.Entry<Integer, Double> e : segments.entrySet()) {
            int idx = e.getKey();
            double w = e.getValue();
            if (w <= 0) continue;
            int from = start.apply(idx);
            int to = from + length.apply(idx);
            boolean contiguous = prevIdx != null && runStartIdx != null && to >= from
                && start.apply(prevIdx) + length.apply(prevIdx) == from && Math.abs(runWeight - w) < 1e-9;
            if (contiguous) {
                runTo = to;
            } else {
                if (runStartIdx != null) out.add(new Run(runFrom, runTo, runWeight));
                runStartIdx = idx;
                runFrom = from;
                runTo = to;
                runWeight = w;
            }
            prevIdx = idx;
        }
        if (runStartIdx != null) out.add(new Run(runFrom, runTo, runWeight));
        return out;
    }

    private static void put(Map<Integer, TreeMap<Integer, Double>> lines, int pos, int idx, double weight) {
        if (weight <= 0) return;
        TreeMap<Integer, Double> line = lines.computeIfAbsent(pos, k -> new TreeMap<>());
        line.merge(idx, weight, Math::max);
    }

    private static double weight(SheetPlan plan, BorderStyle style) {
        return LibreOfficeMetrics.borderWidthPt(style) * plan.zoomPercent / 100.0;
    }

    private static CellRangeAddress regionOf(SheetPlan plan, int r, int c) {
        for (CellRangeAddress m : plan.mergedRegions) {
            if (m.isInRange(r, c)) return m;
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /** Excel's shrink-to-fit, done by the browser before printing: scale the font of every
     * {@code .shrink} cell down until its text fits the cell's inner width. */
    private static String shrinkScript() {
        return "<script>(function(){var cs=document.querySelectorAll('.c.shrink');for(var i=0;i<cs.length;i++){"
            + "var c=cs[i],s=c.firstElementChild;if(!s)continue;var st=getComputedStyle(c);"
            + "var avail=c.clientWidth-parseFloat(st.paddingLeft)-parseFloat(st.paddingRight);"
            + "var w=s.getBoundingClientRect().width;if(w>avail&&w>0){var fs=parseFloat(getComputedStyle(s).fontSize);"
            + "s.style.fontSize=(fs*avail/w)+'px';}}})();</script>";
    }

    /** Single-quoted family names: the stack lives inside a double-quoted {@code style="…"}. */
    private static String fontStack(String name) {
        String n = name == null ? "" : name.replace("\"", "").replace("'", "");
        String fallback = switch (n) {
            case "Angsana New", "AngsanaUPC" -> "'Angsana New','AngsanaUPC',Kinnari,Norasi,serif";
            case "Cordia New", "CordiaUPC" -> "'Cordia New','CordiaUPC',Garuda,Loma,sans-serif";
            case "Browallia New", "BrowalliaUPC" -> "'Browallia New','BrowalliaUPC',Garuda,Loma,sans-serif";
            case "Tahoma" -> "Tahoma,'DejaVu Sans',sans-serif";
            default -> "sans-serif";
        };
        return "'" + n + "'," + fallback;
    }

    private static double pt(double hmm) {
        return LibreOfficeMetrics.hmmToPt(hmm);
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
