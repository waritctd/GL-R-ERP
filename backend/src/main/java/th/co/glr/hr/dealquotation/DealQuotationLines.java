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
    /** The unit-token vocabulary the shared grammar recognises — shared by {@link #SIZE_HAS_UNIT}
     * and {@link #TWO_DIMENSIONS} so the two can never drift the way they did before 2026-09-16
     * (review finding F4: {@code SIZE_HAS_UNIT} was missing the middle-dot Thai forms {@code ซ.ม.}/
     * {@code ม.ม.} that {@code TWO_DIMENSIONS} already accepted, so {@code descriptionLine} printed
     * a millimetre size labelled "cm." on a customer document). */
    private static final String UNIT_ALTERNATION = "cm\\.?|mm\\.?|ซ\\.?ม\\.?|ม\\.?ม\\.?";

    private static final java.util.regex.Pattern SIZE_HAS_UNIT =
        java.util.regex.Pattern.compile("(?i)(" + UNIT_ALTERNATION + ")\\s*$");

    /**
     * ⚠️ SHARED GRAMMAR (2026-09-16, owner complaint re-reported 2026-09-16, "แก้ขนาด/รหัสสินค้าเอง
     * แต่ PDF ยังใช้ค่าเดิม"): this pattern and the frontend's {@code quotationMeta.js#SIZE_PATTERN}
     * MUST stay identical. Before 2026-09-16 they had quietly drifted — this one had no per-number
     * unit, no decimal-comma, no mm unit, no trailing free text, while the frontend's already
     * tolerated a third dimension — so a size the rep typed could recompute แผ่น/ตร.ม. on the
     * screen (frontend parsed it) while the printed PDF still showed the linked catalogue's size
     * (this one didn't parse it, so {@link #sizeLine} fell back to "unparseable, keep the
     * catalogue"). The vector table pinning both sides is in {@code DealQuotationLinesTest} and
     * {@code quotationMeta.test.js} — same inputs, same {width, height, unit} result — so the two
     * can never drift apart again without a red test.
     *
     * <p>One number, one separator ({@code x}/{@code X}/{@code ×}/{@code *}, optional surrounding
     * spaces), a second number, an optional THIRD {@code separator number} (thickness — e.g.
     * "30x60x1"; ignored, never a second dimension), then optional trailing free text in
     * parentheses (e.g. "(หนา 9)"; ignored). Numbers accept a decimal POINT OR COMMA ("29,7" is
     * 29.7 — this column has no thousands separators to confuse it with). Each of the first two
     * numbers may carry its OWN trailing unit token, OR a single one may follow the second number
     * (which is how "the unit once at the end" and "unit after the second number" collapse into the
     * same grammar position when there is no third dimension): {@code cm}/{@code cm.}/{@code
     * mm}/{@code mm.} (English, case-insensitive) or {@code ซม}/{@code ซม.}/{@code ซ.ม.}
     * (Thai centimetres) / {@code มม}/{@code มม.}/{@code ม.ม.} (Thai millimetres) — see {@link
     * #unitFamily}. Anything else — a spelled-out shape, a lone number, letters before/between the
     * numbers, a non-numeric third token — fails the match and yields {@code null} rather than a
     * guess.
     *
     * <p>Group 1 = width digits, group 2 = width's own unit token (or {@code null}), group 3 =
     * height digits, group 4 = height's own unit token (or {@code null}). See {@link
     * #parseTwoDimensions} for how the two unit groups resolve to one {@link SizeUnit}.
     */
    private static final java.util.regex.Pattern TWO_DIMENSIONS = java.util.regex.Pattern.compile(
        "(?i)^\\s*(\\d+(?:[.,]\\d+)?)\\s*(" + UNIT_ALTERNATION + ")?\\s*"
        + "[x×*]\\s*(\\d+(?:[.,]\\d+)?)\\s*(" + UNIT_ALTERNATION + ")?\\s*"
        + "(?:[x×*]\\s*\\d+(?:[.,]\\d+)?\\s*(?:" + UNIT_ALTERNATION + ")?\\s*)?"
        + "(?:\\([^)]*\\))?\\s*$");

    /** The unit a typed size pair states — {@code null} reads as "unspecified" (today's ambiguous
     * rule: compare the catalogue in BOTH cm and mm readings; see {@link #matchesCatalogFaceSize}).
     * An explicit unit is load-bearing: known "60x120 typed as cm vs a 60x120 MILLIMETRE catalogue
     * row" ambiguity aside (a real, tiny tile that genuinely reads "60x120mm" — nothing in free text
     * can distinguish that from "60x120cm" typed with the unit omitted; this grammar does not try),
     * an EXPLICIT unit token means only that reading is checked, never the other — so "300x600mm"
     * against a 60x60cm/600x600mm catalogue row reads as a DIFFERENT tile (300mm x 600mm), never as
     * "matches once you also allow the cm reading". */
    enum SizeUnit { CM, MM }

    /** A parsed {@code {width, height}} pair plus its resolved unit — {@code null} unit means
     * "unspecified" (see {@link SizeUnit}). Digits only, never rounded or unit-converted here. */
    record ParsedSize(BigDecimal width, BigDecimal height, SizeUnit unit) {}

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
     * <p><b>REFINEMENT (prod QT-2026-0034-1, quotation_id=32, 2026-09-15).</b> The catalogue-first
     * rule above was meant for a rep typing the SAME tile's size in an unreliable unit (the
     * "200x300" example is genuinely a 200mm x 300mm tile). It was never meant to override a rep
     * who edited the ขนาด field to a genuinely DIFFERENT size on a row that still happens to carry
     * a catalogue link — that bug printed the linked catalogue's 600x600mm dims on two lines the
     * rep had retyped to "30x60" and "3x60". So: when {@code sizeText} parses into exactly two
     * positive numbers (tolerating spaces; {@code x}/{@code X}/{@code ×}/{@code *} as the
     * separator; a trailing {@code cm}/{@code ซม.}/{@code mm}/{@code มม.} unit; decimals like
     * "6x24.6") AND that pair equals the catalogue's own face size — compared with {@link
     * BigDecimal#compareTo}, order-insensitive, in EITHER centimetres or millimetres (so "120x60"
     * matches a 600x1200mm catalogue row) — the catalogue dims print exactly as before (this is
     * what keeps the 200x300 regression above, and every other existing case, green). Only when
     * the typed pair parses AND matches NEITHER unit does the rep's typed text win, printed
     * verbatim via the FALLBACK below — never unit-guessed. Blank or unparseable {@code sizeText}
     * (spelled-out shapes, three-number typos, anything the regex does not recognise as two plain
     * numbers) is unchanged: catalogue dims when present, the FALLBACK otherwise.
     *
     * <p>This is a pure render-time rule — {@code sizeText}/{@code catalogWidthMm}/{@code
     * catalogHeightMm} are re-read from the row's live catalogue link on every load (see {@code
     * DealQuotationRepository#toItemDto}, ~line 1242) — so it also repairs already-saved documents
     * like QT-2026-0034-1 the next time they are rendered, with no data migration.
     *
     * <p><b>FALLBACK</b> — no catalogue dimensions available (no catalog link on the row, or the
     * linked row has neither dimension populated), OR the typed size parses but matches neither the
     * catalogue's cm nor mm face size: print {@code sizeText} EXACTLY AS TYPED, unit and all,
     * un-split, un-converted. There is no way to tell which unit free text is in from the text
     * alone, so a size the rep recognises beats a confidently mangled one. Only the thickness is
     * ever unit-suffixed in this branch — thickness is always entered in millimetres regardless of
     * source, which is the one thing this method is never unsure about.
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
     * document — the same face-size resolution (catalogue mm first, unless the rep typed a
     * genuinely different size — see the Thai overload's Javadoc "REFINEMENT" section — with the
     * rep's typed text as the ultimate fallback) and the same null-when-no-thickness contract as
     * the Thai line. */
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
        if (facePart != null) {
            ParsedSize typed = parseTwoDimensions(sizeText);
            if (typed != null && !matchesCatalogFaceSize(typed, catalogWidthMm, catalogHeightMm)) {
                // The rep typed a genuinely DIFFERENT size than the row's linked catalogue tile --
                // print exactly what they typed rather than the (wrong-for-this-line) catalogue
                // dims. See this method's Javadoc "REFINEMENT" section -- bug prod QT-2026-0034-1.
                facePart = sizeText.trim();
            }
        } else if (!blank(sizeText)) {
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
     * The shared size grammar — see {@link #TWO_DIMENSIONS}'s Javadoc for the full spec (separators,
     * per-number/trailing units, decimal comma, ignored third dimension, ignored trailing
     * parenthetical). Package-private (not {@code private}) so {@code DealQuotationLinesTest} can
     * pin the shared vector table directly against the parse RESULT, not just against {@link
     * #sizeLine}'s printed string.
     *
     * @return the parsed {@link ParsedSize}, or {@code null} when {@code sizeText} is blank, does
     *     not match the grammar, or either number is not strictly positive.
     */
    static ParsedSize parseTwoDimensions(String sizeText) {
        if (blank(sizeText)) {
            return null;
        }
        java.util.regex.Matcher m = TWO_DIMENSIONS.matcher(sizeText.trim());
        if (!m.matches()) {
            return null;
        }
        BigDecimal a = new BigDecimal(m.group(1).replace(',', '.'));
        BigDecimal b = new BigDecimal(m.group(3).replace(',', '.'));
        if (a.signum() <= 0 || b.signum() <= 0) {
            return null;
        }
        SizeUnit widthUnit = unitFamily(m.group(2));
        SizeUnit heightUnit = unitFamily(m.group(4));
        // Unit resolution: an explicit unit on EITHER number wins outright. When both numbers carry
        // one and they genuinely conflict (a shape no real rep types, and not in the vector table --
        // e.g. "30cm x 60mm") there is no sane single reading, so this falls back to "unspecified"
        // rather than silently preferring one side.
        SizeUnit unit = widthUnit != null ? widthUnit : heightUnit;
        if (widthUnit != null && heightUnit != null && widthUnit != heightUnit) {
            unit = null;
        }
        return new ParsedSize(a, b, unit);
    }

    /** {@code cm}/{@code cm.}/{@code ซม}/{@code ซม.}/{@code ซ.ม.} → {@link SizeUnit#CM};
     * {@code mm}/{@code mm.}/{@code มม}/{@code มม.}/{@code ม.ม.} → {@link SizeUnit#MM};
     * {@code null} (no unit token captured) → {@code null} ("unspecified"). */
    private static SizeUnit unitFamily(String token) {
        if (token == null) {
            return null;
        }
        String t = token.toLowerCase(Locale.ROOT);
        if (t.startsWith("cm")) {
            return SizeUnit.CM;
        }
        if (t.startsWith("mm")) {
            return SizeUnit.MM;
        }
        // Thai: ซ (cm) and ม (mm) never share a leading character, so a plain startsWith is
        // unambiguous -- "ซม."/"ซม"/"ซ.ม." all start with ซ; "มม."/"มม"/"ม.ม." all start with ม.
        if (t.startsWith("ซ")) {
            return SizeUnit.CM;
        }
        if (t.startsWith("ม")) {
            return SizeUnit.MM;
        }
        return null;
    }

    /**
     * Whether a typed size pair is the SAME face size as the catalogue's own {@code width_mm}/
     * {@code height_mm}, order-insensitive (so "120x60" matches a 600x1200mm catalogue row).
     * Compares with {@link BigDecimal#compareTo}, never {@code equals}, so a trailing ".00" never
     * causes a false mismatch.
     *
     * <p><b>Unit resolution (2026-09-16):</b> {@code typed.unit()} explicit ({@link SizeUnit#CM} or
     * {@link SizeUnit#MM}) checks ONLY that reading — "300x600mm" against a 60x60cm/600x600mm
     * catalogue row is a DIFFERENT tile, full stop, never re-checked against the cm reading just
     * because it would happen to also fail there. {@code null} ("unspecified", no unit typed) keeps
     * today's pre-2026-09-16 ambiguous-case rule unchanged: check BOTH the millimetre reading (the
     * pair as stored) and the centimetre reading (the pair {@link #sizeLine} prints), since a rep
     * who typed no unit at all might have meant either. This is a genuine, documented ambiguity this
     * grammar does not resolve — e.g. a typed "60x120" with no unit reads as matching EITHER a
     * 60x120 MILLIMETRE catalogue row or a 60x120 CENTIMETRE one; only an explicit unit token
     * disambiguates.
     */
    private static boolean matchesCatalogFaceSize(ParsedSize typed, BigDecimal catalogWidthMm,
                                                  BigDecimal catalogHeightMm) {
        BigDecimal catalogWidthCm = catalogWidthMm.movePointLeft(1);
        BigDecimal catalogHeightCm = catalogHeightMm.movePointLeft(1);
        boolean checkCm = typed.unit() != SizeUnit.MM;
        boolean checkMm = typed.unit() != SizeUnit.CM;
        return (checkCm && pairMatches(typed.width(), typed.height(), catalogWidthCm, catalogHeightCm))
            || (checkMm && pairMatches(typed.width(), typed.height(), catalogWidthMm, catalogHeightMm));
    }

    private static boolean pairMatches(BigDecimal a, BigDecimal b, BigDecimal w, BigDecimal h) {
        return (a.compareTo(w) == 0 && b.compareTo(h) == 0) || (a.compareTo(h) == 0 && b.compareTo(w) == 0);
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
