package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
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
     * {@code ขนาด {width} cm x {height} cm x {thickness} mm (ขนาดโดยประมาณ)} — owner ruling
     * 2026-09-12, verbatim: <i>"normalize it in the database so its the same in unit. make the
     * size cm and the thickness mm"</i>. STORAGE stays MILLIMETRES (the columns are literally
     * named {@code width_mm}/{@code height_mm}/{@code thickness_mm} — writing centimetres into a
     * {@code _mm} column would manufacture exactly the 10x-error class this branch exists to
     * remove); PRESENTATION is normalized here, in ONE place: face size divided by 10 into
     * centimetres, thickness left in millimetres.
     *
     * <p><b>Correction to the previous ("owner feedback pass 3") version of this method.</b> That
     * pass built the face size by splitting the REP'S FREE-TEXT {@code sizeText} on "x" and
     * unit-suffixing both halves "cm" unconditionally, e.g. {@code sizeLine("200x300", 9, ...)}
     * printed {@code "ขนาด 200 cm x 300 cm x 9 mm"}. That is wrong whenever the rep typed
     * millimetres — confirmed against the owner's own price-list documents, which mix cm-typed and
     * mm-typed sizes in the very same free-text column. The real product behind a typed "200x300"
     * is a 20 cm x 30 cm tile, so the old line was off by 10x on both dimensions — the identical
     * defect, one layer up, from the "200x300 read as centimetres" regression fixed in {@code
     * DealQuotationService#resolveSqmPerPiece} (200mm x 300mm is 0.06 sqm/piece, not 6.0).
     *
     * <p>The fix: derive the face size from the CATALOGUE's own {@code width_mm}/{@code height_mm}
     * — {@code catalogWidthMm}/{@code catalogHeightMm} here, unambiguous millimetres, never
     * inferred from text — resolved the SAME way {@code DealQuotationService#resolveSqmPerPiece}
     * already resolves {@code sqmPerPiece}: via {@code CatalogRepository#findSqmBasis}/{@code
     * #findSqmBases}. Divide by 10 for centimetres; {@link #format(BigDecimal)}'s {@code "#,##0.##"}
     * pattern already drops a trailing ".0" (60 cm, never 60.0 cm).
     *
     * <p><b>FALLBACK</b> — no catalogue dimensions available (no catalog link on the row, or the
     * linked row has neither dimension populated): print {@code sizeText} EXACTLY AS TYPED, unit
     * and all, un-split, un-converted. There is no way to tell which unit free text is in from the
     * text alone, so a size the rep recognises beats a confidently mangled one. Only the thickness
     * is ever unit-suffixed in this branch — thickness is always entered in millimetres regardless
     * of source, which is the one thing this method is never unsure about.
     *
     * <p>layout-spec §2: when thickness is absent there is NO separate ขนาด row at all — the size
     * already went inline on {@link #descriptionLine} — so this returns {@code null} (not an
     * empty/dangling string) and the caller must omit the row entirely.
     */
    public static String sizeLine(String sizeText, BigDecimal thicknessMm,
                                  BigDecimal catalogWidthMm, BigDecimal catalogHeightMm) {
        if (thicknessMm == null) {
            return null;
        }
        String thicknessPart = format(thicknessMm) + " mm";
        String facePart = faceSizeFromCatalogMm(catalogWidthMm, catalogHeightMm);
        if (facePart == null && !blank(sizeText)) {
            // FALLBACK: no catalogue geometry -- print exactly what the rep typed. Never split it
            // on "x", never unit-suffix it, never treat SIZE_HAS_UNIT as license to reformat it --
            // see this method's Javadoc for why guessing here was the bug.
            facePart = sizeText.trim();
        }
        if (blank(facePart)) {
            return "ขนาด " + thicknessPart + " (ขนาดโดยประมาณ)";
        }
        return "ขนาด " + facePart + " x " + thicknessPart + " (ขนาดโดยประมาณ)";
    }

    /**
     * {@code "{width} cm x {height} cm"} from the catalogue's OWN {@code width_mm}/{@code
     * height_mm} — always millimetres, never inferred from free text — divided by 10 into
     * centimetres via {@link BigDecimal#movePointLeft}, which is an exact decimal shift (no
     * division rounding to worry about).
     *
     * @return {@code null} when either dimension is missing or non-positive, so {@link #sizeLine}
     *     falls back to the rep's typed text rather than printing a bogus "0 cm x 0 cm".
     */
    private static String faceSizeFromCatalogMm(BigDecimal widthMm, BigDecimal heightMm) {
        if (widthMm == null || heightMm == null || widthMm.signum() <= 0 || heightMm.signum() <= 0) {
            return null;
        }
        return format(widthMm.movePointLeft(1)) + " cm x " + format(heightMm.movePointLeft(1)) + " cm";
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
            ? "จำนวน " + format(piecesBeforeWastage) + " แผ่น"
            : "พื้นที่ " + format(areaSqm) + " ตร.ม.ๆละ " + format(piecesPerSqm) + " แผ่น รวม "
                + format(piecesBeforeWastage) + " แผ่น";

        // Owner feedback pass 3: "ตัด '+ เผื่อ 0%' ออกทั้งหมด" -- a ZERO wastage value prints
        // NOTHING for this part, in either mode, rather than "+ เผื่อ 0%" / "+ เผื่อ 0 แผ่น".
        // Printing-only: piecesFinal itself is unaffected, it is computed upstream and simply
        // echoed below exactly as it always was.
        boolean hasWastage = wastageValue != null && wastageValue.signum() != 0;
        String wastagePart = "";
        if (hasWastage && WastageCalculator.WASTAGE_MODE_PERCENT.equals(wastageMode)) {
            wastagePart = " + เผื่อ " + format(wastageValue) + "%";
        } else if (hasWastage && WastageCalculator.WASTAGE_MODE_PIECES.equals(wastageMode)) {
            wastagePart = " + เผื่อ " + format(wastageValue) + " แผ่น";
        }

        boolean hasBox = piecesPerBox != null && piecesPerBox > 0;
        String roundingPart = hasBox ? " และปัดลงกล่อง" : "";

        String line = "(" + quantityPart + wastagePart + roundingPart + " = " + format(piecesFinal) + " แผ่น)";
        if (hasBox) {
            line += " (บรรจุ " + format(piecesPerBox) + " แผ่น/กล่อง)";
        }
        return line;
    }

    // ── quotation v3 (owner feedback pass 3, 2026-09-11) ─────────────────────────────────────

    /**
     * The SPECIAL_SQM sub-line the owner's documents carry under a ราคาพิเศษ tile row:
     * {@code (ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)}.
     *
     * <p>"ตรม" without full stops, and no space before "บาท/ตรม" beyond the single one — copied
     * from her documents rather than normalised to the "ตร.ม." this file uses elsewhere, because
     * this string is customer-facing text she has already approved in print.
     *
     * @return {@code null} when there is no ราคาพิเศษ, so the caller omits the row entirely rather
     *     than printing an empty one (same contract as {@link #sizeLine}).
     */
    public static String specialPriceLine(BigDecimal specialPriceSqm) {
        if (specialPriceSqm == null || specialPriceSqm.signum() <= 0) {
            return null;
        }
        return "(ราคาพิเศษ " + format(specialPriceSqm) + " บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)";
    }

    /**
     * The ADJUSTMENT row's derived description:
     * {@code ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569} — the rep types only the percent and
     * the date.
     *
     * <p>The date is Buddhist-era and ZERO-PADDED to {@code dd/MM}, matching the owner's own
     * QN6900704-2 ("31/07/2569") and {@code DealQuotationRenderAdapter#shortThaiDate}'s existing
     * discipline. (The v3 spec writes the placeholder as {@code d/M/BBBB}, which would render
     * "31/7/2569"; the sample document it is drawn FROM prints the padded form, so the sample
     * wins — recorded here rather than silently reinterpreted.)
     *
     * @param deadline nullable — with no date the "สำหรับการสั่งซื้อภายใน" clause is dropped
     *     entirely rather than printed with a blank or a placeholder date.
     */
    public static String adjustmentDescription(BigDecimal pct, LocalDate deadline) {
        String head = "ส่วนลดพิเศษ" + (pct == null ? "" : " " + format(pct) + "%");
        if (deadline == null) {
            return head;
        }
        return head + " สำหรับการสั่งซื้อภายใน "
            + String.format("%02d/%02d/%d", deadline.getDayOfMonth(), deadline.getMonthValue(),
                deadline.getYear() + 543);
    }

    /**
     * The FLAT baht amount to echo back on an ADJUSTMENT row, so a GET→PUT round-trip of such a
     * row survives (review fix F2). A flat adjustment has no column of its own — its figure lives
     * in {@code unit_price}/{@code amount} exactly like any other row's price — so it is recovered
     * here from the stored price rather than duplicated into the schema.
     *
     * @return {@code null} on any row that is not a flat ADJUSTMENT, including a PERCENTAGE
     *     adjustment (which round-trips through {@code adjustmentPct}). Exactly one of the two is
     *     ever non-null, which is what keeps the echoed payload legal under
     *     {@code DealQuotationService#requirePriceValidForType}'s "exactly one of percent / flat".
     */
    public static BigDecimal flatAdjustmentAmount(String lineType, BigDecimal adjustmentPct,
                                                  BigDecimal unitPrice) {
        if (!WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(lineType) || adjustmentPct != null) {
            return null;
        }
        return unitPrice;
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

    /**
     * Owner feedback pass 3: "Format ตัวเลขในคำอธิบายขอ comma ด้วย" -- thousands-grouped, no
     * decimal places (these are always whole piece/box counts), Locale.US so the separator is
     * "," and the decimal point (never reached here) would be ".". Same discipline as
     * {@link #format(BigDecimal)}, just for the {@code int} piece counts that line printed
     * ungrouped via string concatenation before this pass.
     */
    private static String format(int value) {
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }
}
