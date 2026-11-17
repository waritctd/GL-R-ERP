package th.co.glr.hr.common.sheet;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The arithmetic LibreOffice Calc applies when it prints an XLS sheet to PDF — measured out of LibreOffice's
 * own PDF vectors (2026-09-10, soffice --convert-to pdf on the quotation template), not taken from
 * documentation. */
public final class LibreOfficeMetrics {
    private LibreOfficeMetrics() {}

    private static final Logger log = LoggerFactory.getLogger(LibreOfficeMetrics.class);

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

    /** Width in twips of the workbook's default font's widest digit (font index 0), the unit an XLS column width is
     * expressed in — measured on the font THIS HOST resolves that family to, not the family the workbook names. */
    public static int charWidthTwips(Workbook wb) {
        try {
            Font f = wb.getFontAt(0);
            String family = f.getFontName();
            double sizePt = f.getFontHeightInPoints();
            boolean bold = f.getBold();
            String key = family + "|" + sizePt + "|" + bold;
            Integer cached = CHAR_WIDTH_TWIPS.get(key);
            if (cached != null) return cached;
            int twips = measureCharWidthTwips(family, sizePt, bold);
            CHAR_WIDTH_TWIPS.put(key, twips);
            return twips;
        } catch (RuntimeException e) {
            log.warn("Column unit: could not read the workbook default font ({}); using LibreOffice's fallback {} twips",
                e.toString(), DEFAULT_CHAR_WIDTH_TWIPS);
            return DEFAULT_CHAR_WIDTH_TWIPS;
        }
    }

    private static final java.util.Map<String, Integer> CHAR_WIDTH_TWIPS = new java.util.concurrent.ConcurrentHashMap<>();

    private static int measureCharWidthTwips(String family, double sizePt, boolean bold) {
        FontResolver.Resolved resolved = FontResolver.resolve(family);
        java.util.OptionalDouble advance = FontResolver.maxDigitAdvancePt(resolved, sizePt, bold);
        if (advance.isEmpty()) {
            log.warn("Column unit: '{}' {}pt resolves to '{}' but no measurable font could be loaded; "
                + "using LibreOffice's fallback {} twips (the HTML render may not match LibreOffice's geometry)",
                family, sizePt, resolved.family(), DEFAULT_CHAR_WIDTH_TWIPS);
            return DEFAULT_CHAR_WIDTH_TWIPS;
        }
        int twips = (int) Math.round(advance.getAsDouble() * 20.0);
        if (twips <= 0) return DEFAULT_CHAR_WIDTH_TWIPS;
        log.info("Column unit: workbook default font '{}' {}pt{} -> {} '{}'{}: widest digit {} pt = {} twips",
            family, sizePt, bold ? " bold" : "", resolved.viaFontconfig() ? "fontconfig" : "java.awt",
            resolved.family(), resolved.file() != null ? " (" + resolved.file() + ")" : "",
            String.format(java.util.Locale.US, "%.3f", advance.getAsDouble()), twips);
        return twips;
    }

    /** {@code XclTools::GetScColumnWidth}: XLS 1/256-char units → twips. */
    public static int columnTwips(int widthUnits, int charWidthTwips) {
        double scWidth = (double) widthUnits * charWidthTwips / 256.0 - 0.5;
        return Math.max(0, (int) scWidth);
    }

    /** LibreOffice's fit-to-pages zoom: the largest integer percent at which the content fits fitWidth pages across
     * and fitHeight pages down (a zero count leaves that axis unconstrained; both zero → 100%). */
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
