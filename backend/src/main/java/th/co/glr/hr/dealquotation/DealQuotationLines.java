package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Quotation v2 (direct deal quotation, V165) — the three printed-line strings per item, exactly
 * per docs/sales/quotation-v2-plan.md's "Printed lines" section. Served on {@code DealQuotationItemDto} so
 * the frontend never has to reimplement the wastage/description phrasing, and available for a
 * later renderer slice to consume directly (this slice deliberately does not touch
 * {@code QuotationRenderer} — see {@code DealQuotationRenderAdapter}).
 *
 * <p>Numbers are formatted with a plain {@code #,##0.##} pattern — grouped thousands, up to two
 * decimal places, trailing zeros dropped — using plain ASCII digits (no Thai numeral glyphs).
 * {@code java.text.DecimalFormat} is not thread-safe, so every call builds its own instance
 * rather than sharing one across this singleton-free, static-methods-only class.
 */
public final class DealQuotationLines {
    private static final java.util.regex.Pattern SIZE_HAS_UNIT =
        java.util.regex.Pattern.compile("(?i)(ซม\\.?|มม\\.?|cm\\.?|mm\\.?)\\s*$");
    private DealQuotationLines() {}

    /** {@code กระเบื้อง รุ่น {model} สี {color} ผิว {texture}}, plus {@code No.{productCode}}
     * when present. Blank components are simply omitted (never printed as "null" or an empty
     * "รุ่น "/"สี "/"ผิว " fragment).
     *
     * <p>docs/sales/quotation-v2-plan.md §"Printed lines" item 6 / layout-spec §2: when {@code thicknessMm}
     * is null there is no separate {@link #sizeLine} row at all — the size goes INLINE on this
     * line instead, between ผิว and No.{@code productCode} (e.g. {@code "กระเบื้อง รุ่น Reverso
     * Cement สี Grigio ขนาด 60x60 cm. No.BS66R13GP"}). When thickness IS present, {@link
     * #sizeLine} prints it on its own row and this line stays exactly as before. */
    public static String descriptionLine(String model, String color, String texture, String productCode,
                                         String sizeText, BigDecimal thicknessMm) {
        StringBuilder sb = new StringBuilder("กระเบื้อง");
        appendLabelled(sb, "รุ่น", model);
        appendLabelled(sb, "สี", color);
        appendLabelled(sb, "ผิว", texture);
        if (thicknessMm == null && !blank(sizeText)) {
            sb.append(" ขนาด ").append(sizeText.trim());
            // Only supply the unit when the text has none (a catalog size_raw can already say
            // "600x1200 mm"; appending " cm." to that printed "mm cm." -- review finding LOW-2).
            if (!SIZE_HAS_UNIT.matcher(sizeText.trim()).find()) sb.append(" cm.");
        }
        if (!blank(productCode)) {
            sb.append(" No.").append(productCode.trim());
        }
        return sb.toString();
    }

    private static void appendLabelled(StringBuilder sb, String label, String value) {
        if (!blank(value)) {
            sb.append(' ').append(label).append(' ').append(value.trim());
        }
    }

    /**
     * {@code ขนาด {size}x{thickness} cm. (ขนาดโดยประมาณ)} — {@code "60x120"} + thickness 2 →
     * {@code "60x120x2 cm."}. layout-spec §2: when thickness is absent there is NO separate ขนาด
     * row at all — the size already went inline on {@link #descriptionLine} — so this returns
     * {@code null} (not an empty/dangling string) and the caller must omit the row entirely.
     */
    public static String sizeLine(String sizeText, BigDecimal thicknessMm) {
        if (thicknessMm == null) {
            return null;
        }
        String size = blank(sizeText) ? "" : sizeText.trim();
        return "ขนาด " + size + "x" + format(thicknessMm) + " cm. (ขนาดโดยประมาณ)";
    }

    /**
     * The wastage/box-rounding calculation line — see docs/sales/quotation-v2-plan.md's "Printed lines"
     * section for the four variants this reproduces (AREA vs PIECES quantity mode; PERCENT vs
     * PIECES vs NONE wastage; with vs without a pieces-per-box).
     */
    public static String calculationLine(String quantityMode, BigDecimal areaSqm, BigDecimal piecesPerSqm,
                                         int piecesBeforeWastage, String wastageMode, BigDecimal wastageValue,
                                         int piecesFinal, Integer piecesPerBox) {
        String quantityPart = WastageCalculator.QUANTITY_MODE_PIECES.equals(quantityMode)
            ? "จำนวน " + piecesBeforeWastage + " แผ่น"
            : "พื้นที่ " + format(areaSqm) + " ตร.ม.ๆละ " + format(piecesPerSqm) + " แผ่น รวม " + piecesBeforeWastage + " แผ่น";

        String wastagePart = "";
        if (WastageCalculator.WASTAGE_MODE_PERCENT.equals(wastageMode)) {
            wastagePart = " + เผื่อ " + format(wastageValue) + "%";
        } else if (WastageCalculator.WASTAGE_MODE_PIECES.equals(wastageMode)) {
            wastagePart = " + เผื่อ " + format(wastageValue) + " แผ่น";
        }

        boolean hasBox = piecesPerBox != null && piecesPerBox > 0;
        String roundingPart = hasBox ? " และปัดลงกล่อง" : "";

        String line = "(" + quantityPart + wastagePart + roundingPart + " = " + piecesFinal + " แผ่น)";
        if (hasBox) {
            line += " (บรรจุ " + piecesPerBox + " แผ่น/กล่อง)";
        }
        return line;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String format(BigDecimal value) {
        if (value == null) {
            return "";
        }
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }
}
