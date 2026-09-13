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
        return descriptionLine(WastageCalculator.DOCUMENT_LANGUAGE_TH, model, color, texture, productCode,
            sizeText, thicknessMm);
    }

    /**
     * Owner ruling 2026-09-13 (1): the ENGLISH document prints the same description, word for
     * word translated, with every component the Thai line carries and in the same order —
     * {@code Tile Model {model} Color {color} Finish {texture}}, the inline {@code Size {size} cm.}
     * when there is no thickness, then {@code No.{productCode}}. Any language other than
     * {@code EN} (including null) is the Thai line, byte-for-byte what the six-argument overload
     * above always returned.
     */
    public static String descriptionLine(String documentLanguage, String model, String color, String texture,
                                         String productCode, String sizeText, BigDecimal thicknessMm) {
        boolean en = english(documentLanguage);
        StringBuilder sb = new StringBuilder(en ? "Tile" : "กระเบื้อง");
        appendLabelled(sb, en ? "Model" : "รุ่น", model);
        appendLabelled(sb, en ? "Color" : "สี", color);
        appendLabelled(sb, en ? "Finish" : "ผิว", texture);
        if (thicknessMm == null && !blank(sizeText)) {
            sb.append(en ? " Size " : " ขนาด ").append(sizeText.trim());
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
        return sizeLine(WastageCalculator.DOCUMENT_LANGUAGE_TH, sizeText, thicknessMm, catalogWidthMm,
            catalogHeightMm);
    }

    /** Owner ruling 2026-09-13 (1): {@code Size {w} cm x {h} cm x {t} mm (approx.)} on an English
     * document — the same face-size resolution (catalogue mm first, the rep's typed text as the
     * fallback) and the same null-when-no-thickness contract as the Thai line. */
    public static String sizeLine(String documentLanguage, String sizeText, BigDecimal thicknessMm,
                                  BigDecimal catalogWidthMm, BigDecimal catalogHeightMm) {
        boolean en = english(documentLanguage);
        String label = en ? "Size " : "ขนาด ";
        String approx = en ? " (approx.)" : " (ขนาดโดยประมาณ)";
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
            return label + thicknessPart + approx;
        }
        return label + facePart + " x " + thicknessPart + approx;
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
        return calculationLine(WastageCalculator.DOCUMENT_LANGUAGE_TH, quantityMode, areaSqm, piecesPerSqm,
            piecesBeforeWastage, wastageMode, wastageValue, piecesFinal, piecesPerBox);
    }

    /**
     * Owner ruling 2026-09-13 (1) — the English calculation line, clause for clause the Thai one:
     * <pre>
     *   (Area 300 sqm @ 16.39 pcs/sqm = 4,917 pcs + 5% allowance, rounded up to full boxes = 5,180 pcs) (20 pcs/box)
     *   (Quantity 200 pcs + 10 pcs allowance, rounded up to full boxes = 220 pcs) (20 pcs/box)
     *   (Area 10 sqm @ 2 pcs/sqm = 20 pcs + 10% allowance = 22 pcs)          -- no pieces-per-box
     * </pre>
     * The zero-wastage omission and the grouped number format are shared with the Thai line, so
     * the two cannot drift apart on anything but the words.
     */
    public static String calculationLine(String documentLanguage, String quantityMode, BigDecimal areaSqm,
                                         BigDecimal piecesPerSqm, int piecesBeforeWastage, String wastageMode,
                                         BigDecimal wastageValue, int piecesFinal, Integer piecesPerBox) {
        if (english(documentLanguage)) {
            return englishCalculationLine(quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage, wastageMode,
                wastageValue, piecesFinal, piecesPerBox);
        }
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

    private static String englishCalculationLine(String quantityMode, BigDecimal areaSqm, BigDecimal piecesPerSqm,
                                                 int piecesBeforeWastage, String wastageMode,
                                                 BigDecimal wastageValue, int piecesFinal, Integer piecesPerBox) {
        return englishCalculationLine(quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage, wastageMode,
            wastageValue, piecesFinal, piecesPerBox, null);
    }

    /**
     * {@code boxes} non-null = the English PER-SQM variant (owner decision 2026-09-13): the same
     * area/wastage derivation, closed with the box count the printed quantity is computed from
     * instead of the "(N pcs/box)" tail — that fact moves to {@link #boxLine}, which prints right
     * under it:
     * <pre>
     *   (Area 300 sqm @ 16.39 pcs/sqm = 4,917 pcs + 5% allowance, rounded up to full boxes = 5,180 pcs = 259 boxes)
     *   (1 box = 20 pcs = 0.61 sqm)
     * </pre>
     */
    private static String englishCalculationLine(String quantityMode, BigDecimal areaSqm, BigDecimal piecesPerSqm,
                                                 int piecesBeforeWastage, String wastageMode,
                                                 BigDecimal wastageValue, int piecesFinal, Integer piecesPerBox,
                                                 Integer boxes) {
        String quantityPart = WastageCalculator.QUANTITY_MODE_PIECES.equals(quantityMode)
            ? "Quantity " + format(piecesBeforeWastage) + " pcs"
            : "Area " + format(areaSqm) + " sqm @ " + format(piecesPerSqm) + " pcs/sqm = "
                + format(piecesBeforeWastage) + " pcs";
        boolean hasWastage = wastageValue != null && wastageValue.signum() != 0;
        String wastagePart = "";
        if (hasWastage && WastageCalculator.WASTAGE_MODE_PERCENT.equals(wastageMode)) {
            wastagePart = " + " + format(wastageValue) + "% allowance";
        } else if (hasWastage && WastageCalculator.WASTAGE_MODE_PIECES.equals(wastageMode)) {
            wastagePart = " + " + format(wastageValue) + " pcs allowance";
        }
        boolean hasBox = piecesPerBox != null && piecesPerBox > 0;
        String roundingPart = hasBox ? ", rounded up to full boxes" : "";
        if (boxes != null) {
            return "(" + quantityPart + wastagePart + roundingPart + " = " + format(piecesFinal) + " pcs = "
                + format(boxes) + (boxes == 1 ? " box)" : " boxes)");
        }
        String line = "(" + quantityPart + wastagePart + roundingPart + " = " + format(piecesFinal) + " pcs)";
        if (hasBox) {
            line += " (" + format(piecesPerBox) + " pcs/box)";
        }
        return line;
    }

    /**
     * The English per-sqm box sub-line, exactly her QN6900933's shape: {@code (1 box = 28 pcs =
     * 0.6 sqm)}, {@code (1 box = 66 pcs = 0.495 sqm)} — the box area as the supplier states it,
     * trailing zeros dropped (up to the column's 6 decimals).
     */
    public static String boxLine(Integer piecesPerBox, BigDecimal sqmPerBox) {
        if (piecesPerBox == null || piecesPerBox <= 0 || sqmPerBox == null || sqmPerBox.signum() <= 0) {
            return null;
        }
        DecimalFormat area = new DecimalFormat("#,##0.######", DecimalFormatSymbols.getInstance(Locale.US));
        return "(1 box = " + format(piecesPerBox) + " pcs = " + area.format(sqmPerBox) + " sqm)";
    }

    /** What a TILE row prints in the quantity/unit cells and under its description, decided in ONE
     * place for both the stored-row mapping and the calculate-line preview. */
    public record TilePrint(String calculationLine, BigDecimal quantity, String unit, String subLine) {}

    /**
     * The TILE row's calculation line, printed quantity, unit and sub-line.
     *
     * <ul>
     *   <li><b>English per-sqm</b> ({@link WastageCalculator#isEnglishPerSqm}) with box data:
     *       quantity = {@link WastageCalculator#sqmQuantityFromBoxes}, unit {@code SQM}, the
     *       box-count calculation line and {@link #boxLine}.</li>
     *   <li>Everything else: {@link #calculationLine}, the stored quantity (pieces), the language's
     *       tile unit ({@link #printedTileUnit}) and {@link #specialPriceLine}. For Thai this is
     *       byte-for-byte what the row always printed.</li>
     * </ul>
     *
     * <p>An English per-sqm row WITHOUT box data cannot be written (the service refuses it), so
     * reaching the second branch for one means a hand-edited row; it prints pieces rather than
     * inventing an area.
     */
    public static TilePrint tilePrint(String documentLanguage, String priceMode, String quantityMode,
                                      BigDecimal areaSqm, BigDecimal piecesPerSqm, int piecesBeforeWastage,
                                      String wastageMode, BigDecimal wastageValue, int piecesFinal,
                                      Integer piecesPerBox, Integer boxes, BigDecimal sqmPerBox,
                                      BigDecimal storedQuantity, String storedUnit, BigDecimal specialPriceSqm) {
        if (WastageCalculator.isEnglishPerSqm(documentLanguage, priceMode) && boxes != null
            && piecesPerBox != null && piecesPerBox > 0 && sqmPerBox != null && sqmPerBox.signum() > 0) {
            return new TilePrint(
                englishCalculationLine(quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage, wastageMode,
                    wastageValue, piecesFinal, piecesPerBox, boxes),
                WastageCalculator.sqmQuantityFromBoxes(boxes, sqmPerBox), TILE_UNIT_SQM,
                boxLine(piecesPerBox, sqmPerBox));
        }
        return new TilePrint(
            calculationLine(documentLanguage, quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage, wastageMode,
                wastageValue, piecesFinal, piecesPerBox),
            storedQuantity, printedTileUnit(documentLanguage, storedUnit),
            specialPriceLine(documentLanguage, specialPriceSqm));
    }

    /** The tile row's printed unit: "แผ่น" on the Thai form, "PCS" on the English one (owner ruling
     * 2026-09-13 (1)). */
    public static String tileUnit(String documentLanguage) {
        return english(documentLanguage) ? TILE_UNIT_EN : TILE_UNIT_TH;
    }

    /**
     * The unit a stored TILE row prints, resolved at READ time so every existing English quotation
     * picks it up (ruling 5). Thai returns the stored {@code raw_unit} untouched, null included —
     * byte-for-byte the value the mapping always passed through. English maps the Thai tile unit
     * (and a missing one) to "PCS" and leaves any other stored unit alone.
     */
    public static String printedTileUnit(String documentLanguage, String storedUnit) {
        if (!english(documentLanguage)) {
            return storedUnit;
        }
        return storedUnit == null || TILE_UNIT_TH.equals(storedUnit.trim()) ? TILE_UNIT_EN : storedUnit;
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
        return specialPriceLine(WastageCalculator.DOCUMENT_LANGUAGE_TH, specialPriceSqm);
    }

    /**
     * ⚠️ English: ALWAYS {@code null}. The Thai sub-line states a VAT-inclusive baht price, which
     * is false on a USD/no-VAT document. The English per-sqm row prints {@link #boxLine} in this
     * slot instead — see {@link #tilePrint}.
     */
    public static String specialPriceLine(String documentLanguage, BigDecimal specialPriceSqm) {
        if (english(documentLanguage)) {
            return null;
        }
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
        return adjustmentDescription(WastageCalculator.DOCUMENT_LANGUAGE_TH, pct, deadline);
    }

    /**
     * Owner ruling 2026-09-13 (4): {@code Special discount 3% for orders placed by July 31, 2026}
     * on an English document — Gregorian year and the month NAME, the same {@code "MMMM d, yyyy"}
     * / {@code Locale.US} shape as {@code QuotationRenderer#englishDate}, so a JVM with a Thai
     * default locale cannot turn the month Thai. With no deadline: {@code Special discount 3%}.
     */
    public static String adjustmentDescription(String documentLanguage, BigDecimal pct, LocalDate deadline) {
        if (english(documentLanguage)) {
            String head = "Special discount" + (pct == null ? "" : " " + format(pct) + "%");
            return deadline == null ? head
                : head + " for orders placed by "
                    + deadline.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US));
        }
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
    /**
     * The description an ADJUSTMENT row prints, resolved at READ time (ruling 5: every existing
     * English quotation, no migration). The stored {@code description} was composed at WRITE time,
     * in Thai, whatever the document's language.
     *
     * <ul>
     *   <li>Thai: the stored text, untouched — byte-for-byte what was always printed.</li>
     *   <li>English, a PERCENT adjustment: always system-composed, so it is re-derived in English.</li>
     *   <li>English, a FLAT adjustment: re-derived only when the stored text is exactly the
     *       system's own Thai composition (the rep typed no wording of their own). A rep's own
     *       wording is printed as they typed it — it is theirs, in whatever language.</li>
     * </ul>
     */
    public static String printedAdjustmentDescription(String documentLanguage, String storedDescription,
                                                      BigDecimal pct, LocalDate deadline) {
        if (!english(documentLanguage)) {
            return storedDescription;
        }
        if (pct != null || blank(storedDescription)
            || storedDescription.equals(adjustmentDescription(WastageCalculator.DOCUMENT_LANGUAGE_TH, null, deadline))) {
            return adjustmentDescription(documentLanguage, pct, deadline);
        }
        return storedDescription;
    }

    public static BigDecimal flatAdjustmentAmount(String lineType, BigDecimal adjustmentPct,
                                                  BigDecimal unitPrice) {
        if (!WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(lineType) || adjustmentPct != null) {
            return null;
        }
        return unitPrice;
    }

    private static final String TILE_UNIT_TH = "แผ่น";
    private static final String TILE_UNIT_EN = "PCS";
    /** The English per-sqm tile unit (owner decision 2026-09-13). */
    public static final String TILE_UNIT_SQM = "SQM";

    private static boolean english(String documentLanguage) {
        return WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(documentLanguage);
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
