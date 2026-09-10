package th.co.glr.hr.common.sheet;

import java.awt.font.FontRenderContext;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * The arithmetic LibreOffice Calc applies when it prints an XLS sheet to PDF — measured out of
 * LibreOffice's own PDF vectors (2026-09-10, {@code soffice --convert-to pdf} on the quotation
 * template), not taken from documentation. {@link SheetPlan} uses these so an HTML render of the
 * SAME workbook lands on the same page geometry as the LibreOffice render:
 *
 * <ul>
 *   <li><b>Column width</b>: an XLS column width is stored in 1/256ths of the workbook default
 *       font's "0" advance. LibreOffice measures that advance in twips ({@link #charWidthTwips}),
 *       then {@code trunc(units * charWidth / 256 - 0.5)} twips per column
 *       ({@code XclTools::GetScColumnWidth}). Verified to ±1/100 mm on nine columns at three zooms.
 *       Apache POI's own {@code getColumnWidthInPixels} uses a different constant (36.56 units/px)
 *       and lands ~3% wider — never use it for LibreOffice fidelity.</li>
 *   <li><b>Zoom</b>: LibreOffice IGNORED the explicit {@code PrintSetup.scale} in every file this
 *       repo produces (the SETUP record is flagged invalid) and fits to {@code fitWidth ×
 *       fitHeight} whenever either is nonzero — regardless of the WSBOOL fit-to-page flag POI's
 *       {@code Sheet#setFitToPage} toggles. The zoom is an integer percent, the largest that fits
 *       ({@code floor(100 * min(availableWidth/contentWidth, availableHeight/contentHeight))}),
 *       computed over the print area TRIMMED to the last row/column that has a value or a visible
 *       attribute (border/fill). With both fit counts zero LibreOffice prints at 100%.</li>
 *   <li><b>Scaled lengths</b> are truncated to 1/100 mm per row/column; page margins are NOT
 *       scaled; content is centred horizontally when the sheet's flag says so.</li>
 *   <li><b>Borders</b>: THIN = 0.75 pt, MEDIUM = 1.75 pt, THICK = 2.5 pt, HAIR = 0.05 pt (LO's
 *       twips mapping of the Excel styles), all multiplied by the zoom, drawn centred on the cell
 *       boundary and extended by half the stroke at both ends; collinear runs of equal weight are
 *       one path.</li>
 *   <li><b>Fonts</b>: rendered at {@code floor(pt * zoom)} integer points (the output device
 *       truncates the font height).</li>
 * </ul>
 */
public final class LibreOfficeMetrics {
    private LibreOfficeMetrics() {}

    public static final int TWIPS_PER_INCH = 1440;
    public static final double HMM_PER_TWIP = 2540.0 / 1440.0;
    public static final double PT_PER_HMM = 72.0 / 2540.0;
    /** A4 in 1/100 mm — LibreOffice's own paper table (the only paper this app's templates use). */
    public static final int A4_WIDTH_HMM = 21000;
    public static final int A4_HEIGHT_HMM = 29700;
    /** Smallest zoom LibreOffice's fit-to-pages will pick. */
    public static final int MIN_ZOOM = 10;
    /** Text inset LibreOffice keeps between a cell edge and its text (measured 2 pt at 100%). */
    public static final int TEXT_INSET_TWIPS = 40;
    /** Fallback when the default font cannot be measured — LibreOffice's own default. */
    private static final int DEFAULT_CHAR_WIDTH_TWIPS = 110;

    /** Width in twips of "0" in the workbook's default font (font index 0), the unit an XLS column
     * width is expressed in. Measured through AWT with fractional metrics, then rounded to whole
     * twips exactly like LibreOffice's output device does. */
    public static int charWidthTwips(Workbook wb) {
        try {
            Font f = wb.getFontAt(0);
            java.awt.Font awt = new java.awt.Font(f.getFontName(), java.awt.Font.PLAIN, f.getFontHeightInPoints());
            if (!awt.getFamily().equalsIgnoreCase(f.getFontName()) && !awt.getFontName().replace(" ", "")
                    .equalsIgnoreCase(f.getFontName().replace(" ", ""))) {
                return DEFAULT_CHAR_WIDTH_TWIPS;
            }
            FontRenderContext frc = new FontRenderContext(null, true, true);
            double advancePt = awt.getStringBounds("0", frc).getWidth();
            int twips = (int) Math.round(advancePt * 20.0);
            return twips > 0 ? twips : DEFAULT_CHAR_WIDTH_TWIPS;
        } catch (RuntimeException e) {
            return DEFAULT_CHAR_WIDTH_TWIPS;
        }
    }

    /** {@code XclTools::GetScColumnWidth}: XLS 1/256-char units → twips. */
    public static int columnTwips(int widthUnits, int charWidthTwips) {
        double scWidth = (double) widthUnits * charWidthTwips / 256.0 - 0.5;
        return Math.max(0, (int) scWidth);
    }

    /**
     * LibreOffice's fit-to-pages zoom: the largest integer percent at which the content fits
     * {@code fitWidth} pages across and {@code fitHeight} pages down (a zero count leaves that
     * axis unconstrained; both zero → 100%). The ONE place this arithmetic lives —
     * {@code SheetPlan#computeZoom} reads the zoom of a finished sheet with it, and
     * {@code QuotationRenderer#insertNoSplitPageBreaks} predicts the same zoom BEFORE it places
     * the manual row breaks, so its page budget is the page LibreOffice will actually fill.
     */
    public static int fitZoomPercent(long contentWidthTwips, long contentHeightTwips,
                                     double availableWidthTwips, double availableHeightTwips,
                                     int fitWidth, int fitHeight) {
        if (fitWidth <= 0 && fitHeight <= 0) return 100;
        double ratio = 1.0;
        if (fitWidth > 0 && contentWidthTwips > 0) {
            ratio = Math.min(ratio, availableWidthTwips * fitWidth / contentWidthTwips);
        }
        if (fitHeight > 0 && contentHeightTwips > 0) {
            ratio = Math.min(ratio, availableHeightTwips * fitHeight / contentHeightTwips);
        }
        int zoom = (int) Math.floor(ratio * 100.0 + 1e-9);
        return Math.max(MIN_ZOOM, Math.min(100, zoom));
    }

    /** A twips length at {@code zoomPercent}, truncated to LibreOffice's 1/100 mm print unit. */
    public static int scaledHmm(int twips, int zoomPercent) {
        return (int) Math.floor(twips * (zoomPercent / 100.0) * HMM_PER_TWIP);
    }

    public static double hmmToPt(double hmm) {
        return hmm * PT_PER_HMM;
    }

    public static int inchesToTwips(double inches) {
        return (int) Math.round(inches * TWIPS_PER_INCH);
    }

    public static int inchesToHmm(double inches) {
        return (int) Math.round(inches * 2540.0);
    }

    /** The integer point size LibreOffice actually draws a {@code pt}-point font at, at zoom. */
    public static int fontPt(double pt, int zoomPercent) {
        return Math.max(1, (int) Math.floor(pt * zoomPercent / 100.0 + 1e-9));
    }

    /** Stroke width in points at 100% for an Excel border style. */
    public static double borderWidthPt(BorderStyle style) {
        if (style == null) return 0;
        return switch (style) {
            case NONE -> 0;
            case HAIR -> 0.05;
            case THIN, DOTTED, DASHED, DASH_DOT, DASH_DOT_DOT, SLANTED_DASH_DOT -> 0.75;
            case MEDIUM, MEDIUM_DASHED, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT -> 1.75;
            case THICK -> 2.5;
            case DOUBLE -> 1.5;
        };
    }
}
