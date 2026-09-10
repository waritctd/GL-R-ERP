package th.co.glr.hr.common.sheet;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * html-fidelity-spec.md §5 measurement tool: rasterises a PDF page (PDFBox, no external
 * binary) and extracts every ruled line — position, extent and stroke — from the pixels, so two
 * renders of the same sheet can be compared as the owner sees them, not as their vectors claim.
 *
 * <p>A rule is a dark, axis-aligned, strictly contiguous run at least {@link #MIN_RUN_MM} long:
 * Thai text never sustains an unbroken dark run that long, a border always does. Position and
 * stroke are ink-weighted (the coverage profile across the line integrated per row/column), so a
 * 0.54 pt line rendered as two half-grey pixels measures 0.54 pt whatever its sub-pixel phase —
 * a plain "count the dark pixels" width flips between 1 and 2 px on a 0.15 px shift, which is
 * exactly the noise a fidelity gate must not have.
 */
public final class RuleScanner {
    private RuleScanner() {}

    public static final int DPI = 110;
    public static final double PX_TO_MM = 25.4 / DPI;
    public static final double PX_TO_PT = 72.0 / DPI;
    /** Detection threshold on the grey value (0 = black): 200 catches the faint half-pixel edges
     * of a sub-pixel line, and still rejects paper. */
    public static final int DARK = 200;
    public static final double MIN_RUN_MM = 12.0;

    /** A detected rule. {@code pos} is the perpendicular coordinate (x for a vertical rule, y for
     * a horizontal one), {@code from}/{@code to} the extent along the rule, all in px. */
    public record Rule(boolean horizontal, double pos, double from, double to, double strokePx) {
        public double posMm() { return pos * PX_TO_MM; }
        public double fromMm() { return from * PX_TO_MM; }
        public double toMm() { return to * PX_TO_MM; }
        public double extentMm() { return (to - from) * PX_TO_MM; }
        public double strokePt() { return strokePx * PX_TO_PT; }
    }

    public static BufferedImage rasterize(byte[] pdf, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImageWithDPI(pageIndex, DPI, ImageType.GRAY);
        }
    }

    public static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    /** Raw grey samples. NOT {@code getRGB}: on a TYPE_BYTE_GRAY image that converts the linear
     * grey to sRGB and brightens every anti-aliased half-covered pixel (96 → 165), so a line
     * spread over two pixels would measure barely half the ink of the same line snapped to one
     * solid pixel — which is exactly how PDFBox draws LibreOffice's stroked lines versus
     * Chromium's filled rects. Raw samples keep the two comparable. */
    public static int[][] gray(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[][] g = new int[h][w];
        var raster = img.getRaster();
        boolean grayImage = raster.getNumBands() == 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (grayImage) {
                    g[y][x] = raster.getSample(x, y, 0);
                } else {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xff, gg = (rgb >> 8) & 0xff, b = rgb & 0xff;
                    g[y][x] = (r * 299 + gg * 587 + b * 114) / 1000;
                }
            }
        }
        return g;
    }

    public static List<Rule> scan(BufferedImage img) {
        int[][] g = gray(img);
        List<Rule> out = new ArrayList<>(scanVertical(g));
        out.addAll(scanHorizontal(g));
        return out;
    }

    private static List<Rule> scanVertical(int[][] g) {
        int h = g.length;
        int w = g[0].length;
        int minRun = (int) Math.round(MIN_RUN_MM / PX_TO_MM);
        List<Rule> out = new ArrayList<>();
        boolean[] taken = new boolean[w];
        for (int x = 0; x < w; x++) {
            if (taken[x]) continue;
            for (int[] run : runs(g, x, true, h, minRun)) {
                // ink profile across ±3 px, averaged along the run
                int y0 = run[0], y1 = run[1];
                double[] ink = new double[7];
                for (int d = -3; d <= 3; d++) {
                    int xx = x + d;
                    if (xx < 0 || xx >= w) continue;
                    double s = 0;
                    for (int y = y0; y <= y1; y++) s += (255 - g[y][xx]) / 255.0;
                    ink[d + 3] = s / (y1 - y0 + 1);
                }
                double[] cs = centroidAndStroke(ink, x - 3);
                // every column of the bump that formed this rule is spoken for — a two-pixel line's
                // fainter half (ink 0.29) must not be scanned again as a second, identical rule
                for (int i = (int) cs[2]; i <= (int) cs[3]; i++) if (i >= 0 && i < w) taken[i] = true;
                out.add(new Rule(false, cs[0], y0, y1 + 1, cs[1]));
            }
        }
        return out;
    }

    private static List<Rule> scanHorizontal(int[][] g) {
        int h = g.length;
        int w = g[0].length;
        int minRun = (int) Math.round(MIN_RUN_MM / PX_TO_MM);
        List<Rule> out = new ArrayList<>();
        boolean[] taken = new boolean[h];
        for (int y = 0; y < h; y++) {
            if (taken[y]) continue;
            for (int[] run : runs(g, y, false, w, minRun)) {
                int x0 = run[0], x1 = run[1];
                double[] ink = new double[7];
                for (int d = -3; d <= 3; d++) {
                    int yy = y + d;
                    if (yy < 0 || yy >= h) continue;
                    double s = 0;
                    for (int x = x0; x <= x1; x++) s += (255 - g[yy][x]) / 255.0;
                    ink[d + 3] = s / (x1 - x0 + 1);
                }
                double[] cs = centroidAndStroke(ink, y - 3);
                for (int i = (int) cs[2]; i <= (int) cs[3]; i++) if (i >= 0 && i < h) taken[i] = true;
                out.add(new Rule(true, cs[0], x0, x1 + 1, cs[1]));
            }
        }
        return out;
    }

    /** EVERY strictly contiguous run of dark pixels longer than {@code minRun} along column x
     * (vertical) or row y, as [start, end] pairs in scan order. All of them, not the longest: a
     * signature-label row carries four equal underscore runs, and reporting only the longest
     * made each engine's pick depend on a one-pixel length difference, so the comparison paired
     * ผู้พิมพ์'s underscores in one render with ผู้สั่งซื้อ's in the other. */
    private static List<int[]> runs(int[][] g, int idx, boolean vertical, int n, int minRun) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= n; i++) {
            boolean dark = i < n && (vertical ? g[i][idx] : g[idx][i]) < DARK;
            if (dark) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                if (i - start > minRun) out.add(new int[]{start, i - 1});
                start = -1;
            }
        }
        return out;
    }

    /** Ink-weighted centroid (px, pixel-centre convention +0.5), integrated stroke width (px) and
     * the bump's first/last absolute index, from a 7-sample coverage profile whose first sample
     * is at {@code firstIndex}. Only the connected bump containing the peak counts, so a
     * neighbouring rule 3 px away is excluded. */
    private static double[] centroidAndStroke(double[] ink, int firstIndex) {
        int peak = 0;
        for (int i = 1; i < ink.length; i++) if (ink[i] > ink[peak]) peak = i;
        int lo = peak, hi = peak;
        while (lo > 0 && ink[lo - 1] > 0.08 && ink[lo - 1] <= ink[lo] + 1e-9) lo--;
        while (hi < ink.length - 1 && ink[hi + 1] > 0.08 && ink[hi + 1] <= ink[hi] + 1e-9) hi++;
        double sum = 0, wsum = 0;
        for (int i = lo; i <= hi; i++) {
            sum += ink[i];
            wsum += ink[i] * (firstIndex + i + 0.5);
        }
        return new double[]{sum > 0 ? wsum / sum : firstIndex + peak + 0.5, sum, firstIndex + lo, firstIndex + hi};
    }

    public static String describe(Rule r) {
        return String.format(Locale.US, "%s at %6.2fmm  extent %6.2f..%6.2fmm (%5.1fmm)  stroke %.2fpt",
            r.horizontal() ? "hline" : "vline", r.posMm(), r.fromMm(), r.toMm(), r.extentMm(), r.strokePt());
    }
}
