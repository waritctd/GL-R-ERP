package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Quotation v2 (direct deal quotation) — the three printed-line strings per item, exactly
 * per docs/sales/quotation-v2-plan.md's "Printed lines" section. Served on {@code DealQuotationItemDto} so
 * the frontend never has to reimplement the wastage/description phrasing, and available for a
 * renderer to consume directly (see {@code DealQuotationRenderAdapter}).
 *
 * <p>Numbers are formatted with a plain {@code #,##0.##} pattern — grouped thousands, up to two
 * decimal places, trailing zeros dropped — using plain ASCII digits (no Thai numeral glyphs).
 * {@code java.text.DecimalFormat} is not thread-safe, so every call builds its own instance
 * rather than sharing one across this singleton-free, static-methods-only class.
 */
public final class DealQuotationLines {
    /** The unit-token vocabulary the shared grammar recognises — shared by {@link #SIZE_HAS_UNIT}
     * and {@link #TWO_DIMENSIONS} so the two can never drift. (If {@code SIZE_HAS_UNIT} misses the
     * middle-dot Thai forms {@code ซ.ม.}/{@code ม.ม.} that {@code TWO_DIMENSIONS} accepts,
     * {@code descriptionLine} prints a millimetre size labelled "cm." on a customer document.) */
    private static final String UNIT_ALTERNATION = "cm\\.?|mm\\.?|ซ\\.?ม\\.?|ม\\.?ม\\.?";

    private static final java.util.regex.Pattern SIZE_HAS_UNIT =
        java.util.regex.Pattern.compile("(?i)(" + UNIT_ALTERNATION + ")\\s*$");

    /** ReDoS guard — the maximum {@code sizeText} length
     * {@link #parseTwoDimensions} will attempt to match against {@link #TWO_DIMENSIONS} at all. 64
     * comfortably covers every real "WxH[xT] [unit] (note)" free-text cell in this file's own vector
     * table with room to spare, so this never rejects a genuine size — it exists purely so a pasted
     * multi-hundred-character string can never reach the matcher, as a second, independent line of
     * defense alongside the grammar fix below (a length guard costs nothing and catches a regression
     * in the regex fix; the regex fix costs nothing and catches a regression in the guard). Same
     * number, same reasoning, on the frontend side — {@code quotationMeta.js#MAX_SIZE_TEXT_LENGTH}. */
    private static final int MAX_SIZE_TEXT_LENGTH = 64;

    /** Every Unicode space separator (general category {@code Z}:
     * NBSP, thin space, ideographic space, narrow no-break space, …), the BOM/ZWNBSP ({@code U+FEFF},
     * category {@code Cf} so not covered by {@code \p{Z}}) and the line/paragraph
     * separators. Java's {@code \s} (used throughout {@link #TWO_DIMENSIONS}) is ASCII-only and
     * {@link String#trim()} strips only code points {@code <= U+0020}, while JS's {@code \s} is
     * Unicode-aware — so the two engines disagreed on every character in this class (see {@link
     * #normalize}'s own doc for the measured vectors). Folding them all to a plain ASCII space
     * BEFORE the shared grammar ever runs is what keeps the two engines agreeing, rather than
     * trying to reconcile two different {@code \s} definitions inside the pattern itself. */
    private static final java.util.regex.Pattern SIZE_TEXT_WHITESPACE =
        java.util.regex.Pattern.compile("[\\p{Z}\\uFEFF\\u2028\\u2029]");

    /**
     * ⚠️ SHARED GRAMMAR: this pattern and the frontend's {@code quotationMeta.js#SIZE_PATTERN}
     * MUST stay identical. If they drift, a size the rep typed can recompute แผ่น/ตร.ม. on the
     * screen (frontend parsed it) while the printed PDF still shows the linked catalogue's size
     * (this side didn't parse it, so {@link #sizeLine} falls back to "unparseable, keep the
     * catalogue"). The vector table pinning both sides is in {@code DealQuotationLinesTest} and
     * {@code quotationMeta.test.js} — same inputs, same {width, height, unit} result — so a
     * drift turns a test red.
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
     *
     * <p>⚠️ ReDoS trap — with a leading and trailing {@code \s*} around each optional,
     * possibly-empty unit group (four independent {@code \s*(unit)?\s*} positions), a whitespace run
     * of length n between two required tokens can be split n+1 ways between the leading and
     * trailing {@code \s*}, and these splits multiply across the four positions — a ~degree-5
     * polynomial blow-up (seconds to minutes on a few hundred characters of {@code "30" + " "×n +
     * "x60" + " "×n + "x1" + " "×n + "!"}). So each position is a SINGLE leading {@code \s*+} with
     * the unit token consuming its own trailing whitespace inside the optional group — {@code
     * \s*+(?:(unit)\s*+)?} — leaving exactly one way to distribute a whitespace run rather than
     * n+1 (this does not change what the pattern MATCHES, only how many ways it can try to match
     * it). Possessive quantifiers ({@code *+}) are additionally used throughout on this (Java) side,
     * so backtracking into an already-fully-consumed whitespace run is not even attempted. See
     * {@link #MAX_SIZE_TEXT_LENGTH} for the second, independent line of defense, and {@code
     * DealQuotationLinesTest}'s "F1" section for the timing vectors that pin both.
     */
    private static final java.util.regex.Pattern TWO_DIMENSIONS = java.util.regex.Pattern.compile(
        "(?i)^\\s*+(\\d+(?:[.,]\\d+)?)\\s*+(?:(" + UNIT_ALTERNATION + ")\\s*+)?"
        + "[x×*]\\s*+(\\d+(?:[.,]\\d+)?)\\s*+(?:(" + UNIT_ALTERNATION + ")\\s*+)?"
        + "(?:[x×*]\\s*+\\d+(?:[.,]\\d+)?\\s*+(?:(?:" + UNIT_ALTERNATION + ")\\s*+)?)?"
        + "(?:\\([^)]*\\))?\\s*+$");

    /** The unit a typed size pair states — null reads as "unspecified" (today's ambiguous rule: compare the
     * catalogue in BOTH cm and mm readings; see #matchesCatalogFaceSize). */
    enum SizeUnit { CM, MM }

    /** A parsed {width, height} pair plus its resolved unit — null unit means "unspecified" (see SizeUnit). Digits
     * only, never rounded or unit-converted here. */
    record ParsedSize(BigDecimal width, BigDecimal height, SizeUnit unit) {}

    private DealQuotationLines() {}

    /** กระเบื้อง รุ่น {model สี {color} ผิว {texture}}, plus No.{productCode} when present. Blank components are
     * simply omitted (never printed as "null" or an empty "รุ่น "/"สี "/"ผิว " fragment). No.BS66R13GP"). */
    public static String descriptionLine(String model, String color, String texture, String productCode,
                                         String sizeText, BigDecimal thicknessMm) {
        return descriptionLine(WastageCalculator.DOCUMENT_LANGUAGE_TH, model, color, texture, productCode,
            sizeText, thicknessMm);
    }

    /**
     * The ENGLISH document prints the same description, word for
     * word translated, with every component the Thai line carries and in the same order —
     * {@code Tile Model {model} Color {color} Finish {texture}}, the inline {@code Size {size} cm.}
     * when there is no thickness, then {@code No.{productCode}}. Any language other than
     * {@code EN} (including null) is the Thai line, byte-for-byte what the six-argument overload
     * above returns.
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
            // "600x1200 mm"; appending " cm." to that would print "mm cm.").
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
     * {@code ขนาด {width} cm x {height} cm x {thickness} mm (ขนาดโดยประมาณ)} — the size prints in
     * cm and the thickness in mm. STORAGE stays MILLIMETRES (the columns are literally
     * named {@code width_mm}/{@code height_mm}/{@code thickness_mm} — writing centimetres into a
     * {@code _mm} column would manufacture a 10x error); PRESENTATION is normalized here, in ONE
     * place: face size divided by 10 into centimetres, thickness left in millimetres.
     *
     * <p>Do NOT build the face size by splitting the REP'S FREE-TEXT {@code sizeText} on "x" and
     * unit-suffixing both halves "cm": {@code sizeLine("200x300", 9, ...)} would print
     * {@code "ขนาด 200 cm x 300 cm x 9 mm"}, wrong whenever the rep typed millimetres (the
     * owner's price lists mix cm-typed and mm-typed sizes in the same free-text column). The real
     * product behind a typed "200x300" is a 20 cm x 30 cm tile, so that line would be off by 10x on
     * both dimensions — the same defect as reading "200x300" as centimetres in {@code
     * DealQuotationService#resolveSqmPerPiece} (200mm x 300mm is 0.06 sqm/piece, not 6.0).
     *
     * <p>So derive the face size from the CATALOGUE's own {@code width_mm}/{@code height_mm}
     * — {@code catalogWidthMm}/{@code catalogHeightMm} here, unambiguous millimetres, never
     * inferred from text — resolved the SAME way {@code DealQuotationService#resolveSqmPerPiece}
     * already resolves {@code sqmPerPiece}: via {@code CatalogRepository#findSqmBasis}/{@code
     * #findSqmBases}. Divide by 10 for centimetres; {@link #format(BigDecimal)}'s {@code "#,##0.##"}
     * pattern already drops a trailing ".0" (60 cm, never 60.0 cm).
     *
     * <p><b>REFINEMENT.</b> The catalogue-first
     * rule above is for a rep typing the SAME tile's size in an unreliable unit (the
     * "200x300" example is genuinely a 200mm x 300mm tile). It must not override a rep
     * who edited the ขนาด field to a genuinely DIFFERENT size on a row that still happens to carry
     * a catalogue link — printing the linked catalogue's 600x600mm dims on a line the
     * rep retyped to "30x60" is wrong. So: when {@code sizeText} parses into exactly two
     * positive numbers (tolerating spaces; {@code x}/{@code X}/{@code ×}/{@code *} as the
     * separator; a trailing {@code cm}/{@code ซม.}/{@code mm}/{@code มม.} unit; decimals like
     * "6x24.6") AND that pair equals the catalogue's own face size — compared with {@link
     * BigDecimal#compareTo}, order-insensitive, in EITHER centimetres or millimetres (so "120x60"
     * matches a 600x1200mm catalogue row) — the catalogue dims print exactly as before (this is
     * what keeps the 200x300 case above, and every other existing case, correct). Only when
     * the typed pair parses AND matches NEITHER unit does the rep's typed text win, printed
     * verbatim via the FALLBACK below — never unit-guessed. Blank or unparseable {@code sizeText}
     * (spelled-out shapes, three-number typos, anything the regex does not recognise as two plain
     * numbers) is unchanged: catalogue dims when present, the FALLBACK otherwise.
     *
     * <p>This is a pure render-time rule — {@code sizeText}/{@code catalogWidthMm}/{@code
     * catalogHeightMm} are re-read from the row's live catalogue link on every load (see {@code
     * DealQuotationRepository#toItemDto}) — so it also repairs already-saved documents
     * the next time they are rendered, with no data migration.
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

    /** {@code Size {w} cm x {h} cm x {t} mm (approx.)} on an English
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
        // Resolved once, used by BOTH fallback branches below.
        ParsedSize typed = parseTwoDimensions(sizeText);
        if (facePart != null) {
            if (typed != null && !matchesCatalogFaceSize(typed, catalogWidthMm, catalogHeightMm)) {
                // The rep typed a genuinely DIFFERENT size than the row's linked catalogue tile --
                // print exactly what they typed rather than the (wrong-for-this-line) catalogue
                // dims. See this method's Javadoc "REFINEMENT" section.
                // UNLESS they typed no unit at all, in which case print it in the catalogue's
                // own cm format rather than the ambiguous verbatim text -- see the Javadoc below.
                facePart = typed.unit() == null ? formatUnitlessTypedSize(typed) : sizeText.trim();
            }
        } else if (!blank(sizeText)) {
            // FALLBACK: no catalogue geometry -- print exactly what the rep typed. Never split it
            // on "x", never unit-suffix it, never treat SIZE_HAS_UNIT as license to reformat it --
            // see this method's Javadoc for why guessing here is wrong. EXCEPT a typed size
            // that parses with no unit at all, printed in cm instead -- see the Javadoc below.
            facePart = typed != null && typed.unit() == null ? formatUnitlessTypedSize(typed) : sizeText.trim();
        }
        if (blank(facePart)) {
            return label + thicknessPart + approx;
        }
        return label + facePart + " x " + thicknessPart + approx;
    }

    /** The shared size grammar — see #TWO_DIMENSIONS's Javadoc for the full spec (separators, per-number/trailing
     * units, decimal comma, ignored third dimension, ignored trailing parenthetical). */
    static ParsedSize parseTwoDimensions(String sizeText) {
        if (blank(sizeText)) {
            return null;
        }
        String normalized = normalize(sizeText);
        // ReDoS guard: reject BEFORE the regex ever runs, independent of the grammar's own protection.
        if (normalized.isEmpty() || normalized.length() > MAX_SIZE_TEXT_LENGTH) {
            return null;
        }
        java.util.regex.Matcher m = TWO_DIMENSIONS.matcher(normalized);
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
        // Unit resolution: an explicit unit on EITHER number wins outright.
        SizeUnit unit = widthUnit != null ? widthUnit : heightUnit;
        if (widthUnit != null && heightUnit != null && widthUnit != heightUnit) {
            unit = null;
        }
        return new ParsedSize(a, b, unit);
    }

    /**
     * Folds every character {@link #SIZE_TEXT_WHITESPACE} matches to
     * a plain ASCII space, then trims (via {@link String#trim()}, which strips anything
     * {@code <= U+0020} — control bytes included, e.g. a stray {@code U+0001}). Mirrors
     * {@code normalizeSizeText} in {@code quotationMeta.js} exactly.
     *
     * <p>Divergences this closes, all against a plain {@code "30x60"} pair unless noted:
     * NBSP between the numbers or before a trailing unit, U+3000 (ideographic space) before a unit,
     * U+2009 (thin space) and U+202F (narrow NBSP) between the numbers, a leading U+FEFF (BOM), and
     * a trailing U+2028 (line separator) would return {@code null} here (Java's {@code \s} is
     * ASCII-only) while {@code parseSizeText} parses them (JS's {@code \s} is Unicode-aware) — a
     * rep's pasted (often Excel/Word-sourced) size would recompute แผ่น/ตร.ม. on screen while the
     * printed PDF kept the catalogue size. The OTHER direction — a leading/trailing raw control byte
     * like {@code U+0001} — parses here (because {@link String#trim()} strips it) but
     * returns {@code null} in JS (because neither {@code \s} nor {@code String#trim()} in JS treats a
     * bare control byte as whitespace); {@code normalizeSizeText}'s own explicit {@code [\x00-\x20]}
     * trim closes that gap from the other side. See {@code DealQuotationLinesTest}'s "F2" section
     * for the full vector table, pinned identically in {@code quotationMeta.test.js}.
     */
    private static String normalize(String sizeText) {
        return SIZE_TEXT_WHITESPACE.matcher(sizeText).replaceAll(" ").trim();
    }

    /** cm/cm./ซม/ซม./ซ.ม. → SizeUnit#CM; mm/mm./มม/มม./ม.ม. → SizeUnit#MM; null (no unit token captured) → null
     * ("unspecified"). */
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
        // Thai: ซ (cm) and ม (mm) never share a leading character, so a plain startsWith is unambiguous --
        // "ซม."/"ซม"/"ซ.ม." all start with ซ; "มม."/"มม"/"ม.ม." all start with ม.
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
     * <p><b>Unit resolution:</b> {@code typed.unit()} explicit ({@link SizeUnit#CM} or
     * {@link SizeUnit#MM}) checks ONLY that reading — "300x600mm" against a 60x60cm/600x600mm
     * catalogue row is a DIFFERENT tile, full stop, never re-checked against the cm reading just
     * because it would happen to also fail there. {@code null} ("unspecified", no unit typed)
     * is ambiguous: check BOTH the millimetre reading (the
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

    /** "{width cm x {height} cm"} from the catalogue's OWN width_mm/height_mm — always millimetres, never inferred
     * from free text — divided by 10 into centimetres via BigDecimal#movePointLeft, which is an exact decimal
     * shift (no division rounding to worry about). */
    private static String faceSizeFromCatalogMm(BigDecimal widthMm, BigDecimal heightMm) {
        if (widthMm == null || heightMm == null || widthMm.signum() <= 0 || heightMm.signum() <= 0) {
            return null;
        }
        return format(widthMm.movePointLeft(1)) + " cm x " + format(heightMm.movePointLeft(1)) + " cm";
    }

    /**
     * A typed size that parses via {@link #parseTwoDimensions} but
     * carries explicitly NO unit token. A rep typing {@code "14.8x14.8"} into the
     * field labelled {@code "ขนาด (ซม.)"} means centimetres by the field's own label; printing it
     * VERBATIM and appending only the thickness in millimetres would read
     * {@code "ขนาด 14.8x14.8 x 7 mm"} — 14.8 MILLIMETRES, a 10x misread. Format the parsed pair in
     * the catalogue's own {@code "{w} cm x {h} cm"} shape instead — same {@link #format} discipline
     * (trailing zeros dropped), the numbers read AS TYPED (unit-less means cm by the field's own
     * label, never guessed), never converted. An EXPLICIT unit ({@code "14.8x14.8mm"},
     * {@code "300x600mm"}) is untouched by this fix — it says what it means and keeps printing
     * verbatim, same as an unparseable size.
     */
    private static String formatUnitlessTypedSize(ParsedSize typed) {
        return format(typed.width()) + " cm x " + format(typed.height()) + " cm";
    }

    /**
     * The wastage/box-rounding calculation line — see docs/sales/quotation-v2-plan.md's "Printed lines"
     * section for the four variants this reproduces (AREA vs PIECES quantity mode; PERCENT vs
     * PIECES vs NONE wastage; with vs without a pieces-per-box).
     *
     * <p>The English line is clause for clause the Thai one:
     * <pre>
     *   (Area 300 sqm @ 16.39 pcs/sqm = 4,917 pcs + 5% allowance, rounded up to full boxes = 5,180 pcs) (20 pcs/box)
     *   (Quantity 200 pcs + 10 pcs allowance, rounded up to full boxes = 220 pcs) (20 pcs/box)
     *   (Area 10 sqm @ 2 pcs/sqm = 20 pcs + 10% allowance = 22 pcs)          -- no pieces-per-box
     * </pre>
     * The zero-wastage omission and the grouped number format are shared with the Thai line, so
     * the two cannot drift apart on anything but the words.
     *
     * <p>"Sell loose pieces": {@code roundToFullBox}. {@code true} — the
     * default when box data is present — prints the Thai rounding phrase as
     * {@code "และปัดขึ้นเต็มกล่อง"} ("rounded UP to a full box"), never {@code "และปัดลงกล่อง"}
     * ("rounded DOWN"), because the arithmetic ({@link WastageCalculator#calculate}'s
     * {@code ceilToMultiple}) rounds the piece count UP. The English line says
     * "rounded up to full boxes". {@code
     * false} lets a TILE row sell pieces that do not make a full box: neither rounding phrase is
     * printed (nothing was rounded), and the box tail is instead split into full boxes plus a
     * loose-piece remainder, e.g. {@code (จำนวน 32 แผ่น = 3 กล่อง + 2 แผ่น) (บรรจุ 10 แผ่น/กล่อง)}.
     * See {@code WastageCalculatorTest}/{@code DealQuotationLinesTest} for every shape this prints
     * (loose = 0, full boxes = 0, with/without wastage).
     *
     * <p>{@code piecesAfterWastage} is passed in — stored on a read, freshly computed on a write —
     * and the box-rounding clause is decided from that number, never re-derived, because the
     * wastage rounding rule has changed over time (HALF_UP, then ceiling) and a stored 870 must
     * not print as if it were 871.
     */
    public static String calculationLine(String documentLanguage, String quantityMode, BigDecimal areaSqm,
                                         BigDecimal piecesPerSqm, int piecesBeforeWastage, String wastageMode,
                                         BigDecimal wastageValue, int piecesAfterWastage, int piecesFinal,
                                         Integer piecesPerBox, boolean roundToFullBox) {
        if (english(documentLanguage)) {
            return englishCalculationLine(quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage, wastageMode,
                wastageValue, piecesAfterWastage, piecesFinal, piecesPerBox, null, roundToFullBox);
        }
        String quantityPart = WastageCalculator.QUANTITY_MODE_PIECES.equals(quantityMode)
            ? "จำนวน " + format(piecesBeforeWastage) + " แผ่น"
            : "พื้นที่ " + format(areaSqm) + " ตร.ม.ๆละ " + format(piecesPerSqm) + " แผ่น รวม "
                + format(piecesBeforeWastage) + " แผ่น";

        // A ZERO wastage value prints NOTHING for this part, in either mode, rather than
        // "+ เผื่อ 0%" / "+ เผื่อ 0 แผ่น". Printing-only: piecesFinal itself is unaffected, it is
        // computed upstream and simply echoed below.
        //
        // Gated on the MODE too, not merely a non-zero value -- a row
        // switched to wastageMode=NONE keeps whatever wastageValue it last held (WastageCalculator
        // ignores it entirely in NONE mode, see WastageCalculator#calculate), so an ungated
        // `hasWastage` would read true for a row applying no wastage at all. wastagePart below is
        // already immune (its own `WASTAGE_MODE_PERCENT`/`WASTAGE_MODE_PIECES` checks exclude
        // NONE), but thaiLoosePiecesLine's intermediate "= N แผ่น" clause trusts this
        // flag alone, and would print the duplicate `(จำนวน 32 แผ่น = 32 แผ่น = 3 กล่อง + 2 แผ่น)` its
        // own Javadoc says it exists to avoid.
        boolean hasWastage = wastageValue != null && wastageValue.signum() != 0
            && !WastageCalculator.WASTAGE_MODE_NONE.equals(wastageMode);
        String wastagePart = "";
        if (hasWastage && WastageCalculator.WASTAGE_MODE_PERCENT.equals(wastageMode)) {
            wastagePart = " + เผื่อ " + format(wastageValue) + "%";
        } else if (hasWastage && WastageCalculator.WASTAGE_MODE_PIECES.equals(wastageMode)) {
            wastagePart = " + เผื่อ " + format(wastageValue) + " แผ่น";
        }

        boolean hasBox = piecesPerBox != null && piecesPerBox > 0;

        if (hasBox && !roundToFullBox) {
            return thaiLoosePiecesLine(quantityPart, wastagePart, hasWastage, piecesFinal, piecesPerBox);
        }

        // The box-rounding phrase and its "= N แผ่น" echo print only when box rounding actually
        // moved the count. If the count going in (piecesAfterWastage, passed in: stored on a read,
        // freshly computed on a write) is ALREADY a whole number of boxes, "และปัดขึ้นเต็มกล่อง = 20 แผ่น"
        // would be misleading, since nothing was rounded. The box COUNT tail below prints
        // either way (a rounded-up line also states its box count).
        //
        // "Changed" is asked directly of the box arithmetic itself -- piecesAfterWastage % ppb != 0
        // -- rather than by comparing against piecesFinal: ceilToMultiple is a no-op EXACTLY when
        // its input is already a multiple, so this is the more direct question.
        boolean boxRoundingChangedCount = hasBox && piecesAfterWastage % piecesPerBox != 0;

        // "และปัดขึ้นเต็มกล่อง" ("rounded up to a full box"): the arithmetic rounds UP
        // (ceilToMultiple), so never print "ปัดลง" ("rounded DOWN").
        String roundingPart = boxRoundingChangedCount ? " และปัดขึ้นเต็มกล่อง" : "";

        // The trailing "= N แผ่น" clause only states something new
        // when box rounding or wastage may have moved piecesFinal away from the count
        // quantityPart already states. Without a แผ่น/กล่อง and without wastage, piecesFinal
        // always equals piecesBeforeWastage -- printing "= N แผ่น" then is a pure echo, e.g.
        // "(จำนวน 32 แผ่น = 32 แผ่น)". Box rounding only justifies the clause when it
        // actually CHANGED the count.
        StringBuilder sb = new StringBuilder("(").append(quantityPart).append(wastagePart).append(roundingPart);
        if (boxRoundingChangedCount || hasWastage) {
            sb.append(" = ").append(format(piecesFinal)).append(" แผ่น");
        }
        // A box of ONE piece makes "= N กล่อง" the same number as the piece count in another
        // unit -- another echo, not information. Printed whether or not rounding changed the count,
        // but only when piecesFinal is a whole number of boxes -- never print a box count that does
        // not divide evenly.
        if (hasBox && piecesPerBox > 1 && piecesFinal % piecesPerBox == 0) {
            sb.append(" = ").append(format(piecesFinal / piecesPerBox)).append(" กล่อง");
        }
        sb.append(")");
        String line = sb.toString();
        if (hasBox) {
            line += " (บรรจุ " + format(piecesPerBox) + " แผ่น/กล่อง)";
        }
        return line;
    }

    /**
     * The Thai "sell loose pieces" tail — {@code piecesFinal} split into full boxes plus a
     * remainder instead of forced up to the next box. The intermediate {@code "= N แผ่น"} clause
     * (matching the DEFAULT branch's own trailing clause) is printed only when it says something
     * the box/loose split does not already say on its own:
     *
     * <ul>
     *   <li>{@code boxes > 0} — the box/loose breakdown below always adds new grouping
     *       information (a piece count on its own does not say how many full boxes that is), so it
     *       always prints; the wastage-adjusted total additionally restates {@code piecesFinal}
     *       first, but only when wastage actually moved it away from {@code quantityPart}'s own.</li>
     *   <li>{@code boxes == 0} — the box/loose breakdown would just be "= piecesFinal แผ่น", a pure
     *       echo of {@code quantityPart}'s own count UNLESS wastage moved it — printed only then.
     *       This avoids "(จำนวน 15 แผ่น = 15 แผ่น) (บรรจุ 26 แผ่น/กล่อง)":
     *       {@code loose == piecesFinal} here, so an unconditional
     *       {@code else} branch would be a pure duplicate whenever there is no wastage to justify it.</li>
     * </ul>
     */
    private static String thaiLoosePiecesLine(String quantityPart, String wastagePart, boolean hasWastage,
                                              int piecesFinal, int piecesPerBox) {
        int boxes = piecesFinal / piecesPerBox;
        int loose = piecesFinal % piecesPerBox;
        StringBuilder sb = new StringBuilder("(").append(quantityPart).append(wastagePart);
        if (boxes > 0) {
            if (hasWastage) {
                sb.append(" = ").append(format(piecesFinal)).append(" แผ่น");
            }
            if (loose > 0) {
                sb.append(" = ").append(format(boxes)).append(" กล่อง + ").append(format(loose)).append(" แผ่น");
            } else {
                sb.append(" = ").append(format(boxes)).append(" กล่อง");
            }
        } else if (hasWastage) {
            sb.append(" = ").append(format(loose)).append(" แผ่น");
        }
        sb.append(") (บรรจุ ").append(format(piecesPerBox)).append(" แผ่น/กล่อง)");
        return sb.toString();
    }

    /**
     * {@code boxes} non-null = the English PER-SQM variant: the same
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
                                                 BigDecimal wastageValue, int piecesAfterWastage, int piecesFinal,
                                                 Integer piecesPerBox, Integer boxes, boolean roundToFullBox) {
        String quantityPart = WastageCalculator.QUANTITY_MODE_PIECES.equals(quantityMode)
            ? "Quantity " + format(piecesBeforeWastage) + " " + pluralPcs(piecesBeforeWastage)
            : "Area " + format(areaSqm) + " sqm @ " + format(piecesPerSqm) + " " + pluralPcs(piecesPerSqm)
                + "/sqm = " + format(piecesBeforeWastage) + " " + pluralPcs(piecesBeforeWastage);
        // Mode-gated, mirroring the Thai branch above -- see its own
        // comment. Without this, englishLoosePiecesLine's intermediate "= N pcs" clause could print
        // the same duplicate its own Javadoc says it exists to avoid.
        boolean hasWastage = wastageValue != null && wastageValue.signum() != 0
            && !WastageCalculator.WASTAGE_MODE_NONE.equals(wastageMode);
        String wastagePart = "";
        if (hasWastage && WastageCalculator.WASTAGE_MODE_PERCENT.equals(wastageMode)) {
            wastagePart = " + " + format(wastageValue) + "% allowance";
        } else if (hasWastage && WastageCalculator.WASTAGE_MODE_PIECES.equals(wastageMode)) {
            wastagePart = " + " + format(wastageValue) + " " + pluralPcs(wastageValue) + " allowance";
        }
        boolean hasBox = piecesPerBox != null && piecesPerBox > 0;

        // "Sell loose pieces": boxes is non-null ONLY for the English per-sqm
        // variant above, which always rounds (the service refuses roundToFullBox=false there — see
        // DealQuotationService#requireBoxDataForPerSqm) — so reaching here with boxes == null is
        // the only way this branch is ever taken.
        if (hasBox && !roundToFullBox && boxes == null) {
            return englishLoosePiecesLine(quantityPart, wastagePart, hasWastage, piecesFinal, piecesPerBox);
        }

        // The Thai mirror of this method's own comment applies
        // here verbatim -- see #calculationLine's matching block for the full reasoning, including
        // why "changed" is asked of piecesAfterWastage % ppb directly rather than by comparing
        // against piecesFinal. piecesAfterWastage is passed in (stored on a read, freshly computed
        // on a write).
        boolean boxRoundingChangedCount = hasBox && piecesAfterWastage % piecesPerBox != 0;
        String roundingPart = boxRoundingChangedCount ? ", rounded up to full boxes" : "";
        if (boxes != null) {
            // The per-sqm box-AREA variant: the box count is this branch's own defining fact (the
            // printed sqm quantity is derived FROM it), so it is always stated, whether or not box
            // rounding itself changed anything -- only the rounding phrase and the "= N pcs" echo
            // (dropped when it says nothing the quantity part or the wastage did not already say)
            // follow the same rule as below.
            StringBuilder sb = new StringBuilder("(").append(quantityPart).append(wastagePart).append(roundingPart);
            if (boxRoundingChangedCount || hasWastage) {
                sb.append(" = ").append(format(piecesFinal)).append(" ").append(pluralPcs(piecesFinal));
            }
            sb.append(" = ").append(format(boxes)).append(" ").append(pluralBoxes(boxes)).append(")");
            return sb.toString();
        }
        // Mirrors the Thai branch above -- omit a trailing "= N pcs"
        // that only echoes a count quantityPart already states (no pcs/box, no wastage), e.g.
        // "(Quantity 3,360 pcs = 3,360 pcs)" and, in AREA mode,
        // "(Area 1,000 sqm @ 2.78 pcs/sqm = 2,780 pcs = 2,780 pcs)" -- AREA mode's own "= 2,780
        // pcs" clause (baked into quantityPart, converting sqm to pieces) is genuine information
        // and stays untouched; only this SECOND, redundant echo is dropped. It is also dropped
        // when box rounding changed nothing.
        StringBuilder sb = new StringBuilder("(").append(quantityPart).append(wastagePart).append(roundingPart);
        if (boxRoundingChangedCount || hasWastage) {
            sb.append(" = ").append(format(piecesFinal)).append(" ").append(pluralPcs(piecesFinal));
        }
        // Same one-piece-box and whole-box rules as the Thai line above.
        if (hasBox && piecesPerBox > 1 && piecesFinal % piecesPerBox == 0) {
            int boxCount = piecesFinal / piecesPerBox;
            sb.append(" = ").append(format(boxCount)).append(" ").append(pluralBoxes(boxCount));
        }
        sb.append(")");
        String line = sb.toString();
        if (hasBox) {
            line += " (" + format(piecesPerBox) + " " + pluralPcs(piecesPerBox) + "/box)";
        }
        return line;
    }

    /** The English mirror of {@link #thaiLoosePiecesLine} — same split, same "print the
     * intermediate clause only when it says something new" rule (zero full
     * boxes AND no wastage prints no intermediate clause at all, e.g. {@code "(Quantity 7 pcs)
     * (10 pcs/box)"}, not the duplicate {@code "(Quantity 7 pcs = 7 pcs) (10 pcs/box)"}),
     * singular "box"/"pc" at 1 — routed through the shared {@link #pluralPcs}/{@link #pluralBoxes}
     * helpers rather than ad-hoc ternaries, so
     * every English count on the document agrees on the same singular/plural rule. */
    private static String englishLoosePiecesLine(String quantityPart, String wastagePart, boolean hasWastage,
                                                 int piecesFinal, int piecesPerBox) {
        int boxes = piecesFinal / piecesPerBox;
        int loose = piecesFinal % piecesPerBox;
        StringBuilder sb = new StringBuilder("(").append(quantityPart).append(wastagePart);
        if (boxes > 0) {
            if (hasWastage) {
                sb.append(" = ").append(format(piecesFinal)).append(" ").append(pluralPcs(piecesFinal));
            }
            if (loose > 0) {
                sb.append(" = ").append(format(boxes)).append(" ").append(pluralBoxes(boxes)).append(" + ")
                    .append(format(loose)).append(" ").append(pluralPcs(loose));
            } else {
                sb.append(" = ").append(format(boxes)).append(" ").append(pluralBoxes(boxes));
            }
        } else if (hasWastage) {
            sb.append(" = ").append(format(loose)).append(" ").append(pluralPcs(loose));
        }
        // The box tail must not hardcode "pcs" regardless of count -- a piecesPerBox of 1 would
        // print "(1 pcs/box)".
        sb.append(") (").append(format(piecesPerBox)).append(" ").append(pluralPcs(piecesPerBox)).append("/box)");
        return sb.toString();
    }

    /** The English per-sqm box sub-line, exactly her QN6900933's shape: (1 box = 28 pcs = 0.6 sqm), (1 box = 66 pcs
     * = 0.495 sqm) — the box area as the supplier states it, trailing zeros dropped (up to the column's 6
     * decimals). */
    public static String boxLine(Integer piecesPerBox, BigDecimal sqmPerBox) {
        if (piecesPerBox == null || piecesPerBox <= 0 || sqmPerBox == null || sqmPerBox.signum() <= 0) {
            return null;
        }
        DecimalFormat area = new DecimalFormat("#,##0.######", DecimalFormatSymbols.getInstance(Locale.US));
        return "(1 box = " + format(piecesPerBox) + " " + pluralPcs(piecesPerBox) + " = " + area.format(sqmPerBox)
            + " sqm)";
    }

    /**
     * The ONE shared English singular/plural helper for a
     * printed count, used everywhere a count appears on the English document (calculation lines,
     * the box line, the per-sqm line, the wastage allowance, credit days, lead time, validity
     * days) instead of an ad-hoc ternary at each call site. Without it a document prints
     * "1 pcs", "1 days", "(1 pcs/box)", "@ 1 pcs/sqm", "+ 1 pcs allowance", "on 1 days
     * credit" and "approximately 1 days" — one shared rule covers all of them, and
     * whichever is added next.
     *
     * @return {@code singular} when {@code count} is exactly one, {@code pluralWord} otherwise
     *     (zero, negative, or greater than one) — English count agreement, not a sign check.
     */
    static String plural(int count, String singular, String pluralWord) {
        return count == 1 ? singular : pluralWord;
    }

    /** #plural(int, String, String) for a BigDecimal count (the wastage-allowance piece count, which is user-typed
     * and so arrives as a decimal even though fix 5 requires it be a whole number in PIECES mode) — compared with
     * BigDecimal#compareTo so "1.00" reads as singular exactly like 1. */
    static String plural(BigDecimal count, String singular, String pluralWord) {
        return count != null && count.compareTo(BigDecimal.ONE) == 0 ? singular : pluralWord;
    }

    /** "pc"/"pcs" via #plural(int, String, String) — the single most common shape this document prints, given its
     * own tiny helper so call sites read as one word rather than a three-argument call. */
    private static String pluralPcs(int count) {
        return plural(count, "pc", "pcs");
    }

    /** #pluralPcs(int) for a BigDecimal-typed count (the wastage allowance and the per-sqm rate, e.g. "@ 1
     * pc/sqm"). */
    private static String pluralPcs(BigDecimal count) {
        return plural(count, "pc", "pcs");
    }

    /** {@code "box"}/{@code "boxes"} via {@link #plural(int, String, String)}. */
    private static String pluralBoxes(int count) {
        return plural(count, "box", "boxes");
    }

    /** What a TILE row prints in the quantity/unit cells and under its description, decided in ONE place for both
     * the stored-row mapping and the calculate-line preview. */
    public record TilePrint(String calculationLine, BigDecimal quantity, String unit, String subLine) {}

    /**
     * The TILE row's calculation line, printed quantity, unit and sub-line.
     *
     * <ul>
     *   <li><b>English per-sqm</b> ({@link WastageCalculator#isEnglishPerSqm}) with a box AREA
     *       (ตร.ม./กล่อง filled): quantity = {@link WastageCalculator#sqmQuantityFromBoxes}, unit
     *       {@code SQM}, the box-count calculation line and {@link #boxLine}.</li>
     *   <li><b>English per-sqm WITHOUT a box area</b>: quantity
     *       = {@link WastageCalculator#sqmQuantityFromPieces} (pieces × ตร.ม./แผ่น, the same area
     *       basis the Thai SPECIAL_SQM mode uses), unit {@code SQM}, the ORDINARY English piece-based
     *       calculation line (loose-pieces phrasing included when แผ่น/กล่อง is present and
     *       {@code roundToFullBox} is false), and NO box line — there is no supplier box area to
     *       print one from.</li>
     *   <li>Everything else: {@link #calculationLine}, the stored quantity (pieces), the language's
     *       tile unit ({@link #printedTileUnit}) and {@link #specialPriceLine}. English per-sqm reaches this branch only when
     *       {@code sqmPerPiece} itself is missing/non-positive — a hand-edited row, since a normal
     *       save always resolves and stores one; it prints pieces rather than inventing an area.</li>
     * </ul>
     *
     * <p>"Sell loose pieces": {@code roundToFullBox}. English per-sqm WITH a
     * box area can never reach the loose-pieces branch (the service still refuses
     * {@code roundToFullBox = false} whenever ตร.ม./กล่อง is filled — see
     * {@code DealQuotationService#buildTileItem}'s {@code hasBoxArea} branch), so that row's
     * box-count calculation line always rounds. WITHOUT a box area, {@code
     * roundToFullBox} is honoured normally.
     *
     * <p>{@code piecesAfterWastage} is passed in (stored on a read, freshly computed on a write).
     */
    public static TilePrint tilePrint(String documentLanguage, String priceMode, String quantityMode,
                                      BigDecimal areaSqm, BigDecimal sqmPerPiece, BigDecimal piecesPerSqm,
                                      int piecesBeforeWastage, String wastageMode, BigDecimal wastageValue,
                                      int piecesAfterWastage, int piecesFinal, Integer piecesPerBox, Integer boxes,
                                      BigDecimal sqmPerBox, BigDecimal storedQuantity, String storedUnit,
                                      BigDecimal specialPriceSqm, boolean roundToFullBox) {
        boolean perSqm = WastageCalculator.isEnglishPerSqm(documentLanguage, priceMode);
        boolean hasBoxArea = sqmPerBox != null && sqmPerBox.signum() > 0;
        if (perSqm && hasBoxArea && boxes != null && piecesPerBox != null && piecesPerBox > 0) {
            // Unchanged from before Option B — byte-identical for every existing box-area document.
            return new TilePrint(
                englishCalculationLine(quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage, wastageMode,
                    wastageValue, piecesAfterWastage, piecesFinal, piecesPerBox, boxes, true),
                WastageCalculator.sqmQuantityFromBoxes(boxes, sqmPerBox), TILE_UNIT_SQM,
                boxLine(piecesPerBox, sqmPerBox));
        }
        if (perSqm && !hasBoxArea && sqmPerPiece != null && sqmPerPiece.signum() > 0) {
            // No supplier box area — derive the printed sqm quantity from the
            // FINAL piece count instead (which already reflects box rounding when piecesPerBox is
            // present, or the exact wastage-adjusted count when it is not / loose pieces is ticked).
            return new TilePrint(
                calculationLine(documentLanguage, quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage,
                    wastageMode, wastageValue, piecesAfterWastage, piecesFinal, piecesPerBox, roundToFullBox),
                WastageCalculator.sqmQuantityFromPieces(piecesFinal, sqmPerPiece), TILE_UNIT_SQM, null);
        }
        return new TilePrint(
            calculationLine(documentLanguage, quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage, wastageMode,
                wastageValue, piecesAfterWastage, piecesFinal, piecesPerBox, roundToFullBox),
            storedQuantity, printedTileUnit(documentLanguage, storedUnit),
            specialPriceLine(documentLanguage, specialPriceSqm));
    }

    /** The tile row's printed unit: "แผ่น" on the Thai form, "PCS" on the English one. */
    public static String tileUnit(String documentLanguage) {
        return english(documentLanguage) ? TILE_UNIT_EN : TILE_UNIT_TH;
    }

    /**
     * The unit a stored TILE row prints, resolved at READ time so every existing English quotation
     * picks it up. Thai returns the stored {@code raw_unit} untouched, null included.
     * English maps the Thai tile unit
     * (and a missing one) to "PCS" and leaves any other stored unit alone.
     */
    public static String printedTileUnit(String documentLanguage, String storedUnit) {
        if (!english(documentLanguage)) {
            return storedUnit;
        }
        return storedUnit == null || TILE_UNIT_TH.equals(storedUnit.trim()) ? TILE_UNIT_EN : storedUnit;
    }

    // ── quotation v3 ─────────────────────────────────────

    /** The SPECIAL_SQM sub-line the owner's documents carry under a ราคาพิเศษ tile row:. "ตรม" without full stops,
     * and no space before "บาท/ตรม" beyond the single one. */
    public static String specialPriceLine(BigDecimal specialPriceSqm) {
        return specialPriceLine(WastageCalculator.DOCUMENT_LANGUAGE_TH, specialPriceSqm);
    }

    /** ⚠️ English: ALWAYS null. The Thai sub-line states a VAT-inclusive baht price, which is false on a USD/no-VAT
     * document. The English per-sqm row prints #boxLine in this slot instead — see #tilePrint. */
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
     * <p>The date is Buddhist-era and ZERO-PADDED to {@code dd/MM}, matching the owner's sample
     * document ("31/07/2569") and {@code DealQuotationRenderAdapter#shortThaiDate}'s existing
     * discipline. (The v3 spec writes the placeholder as {@code d/M/BBBB}, which would render
     * "31/7/2569"; the sample document it is drawn from prints the padded form, so the sample
     * wins.)
     *
     * @param deadline nullable — with no date the "สำหรับการสั่งซื้อภายใน" clause is dropped
     *     entirely rather than printed with a blank or a placeholder date.
     */
    public static String adjustmentDescription(BigDecimal pct, LocalDate deadline) {
        return adjustmentDescription(WastageCalculator.DOCUMENT_LANGUAGE_TH, pct, deadline);
    }

    /**
     * {@code Special discount 3% for orders placed by July 31, 2026}
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
     * row survives. A flat adjustment has no column of its own — its figure lives
     * in {@code unit_price}/{@code amount} exactly like any other row's price — so it is recovered
     * here from the stored price rather than duplicated into the schema.
     *
     * @return {@code null} on any row that is not a flat ADJUSTMENT, including a PERCENTAGE
     *     adjustment (which round-trips through {@code adjustmentPct}). Exactly one of the two is
     *     ever non-null, which is what keeps the echoed payload legal under
     *     {@code DealQuotationService#requirePriceValidForType}'s "exactly one of percent / flat".
     */
    /**
     * The description an ADJUSTMENT row prints, resolved at READ time (applies to every existing
     * English quotation, no migration). The stored {@code description} is composed at WRITE time,
     * in Thai, whatever the document's language.
     *
     * <ul>
     *   <li>Thai: the stored text, untouched.</li>
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
    /** The English per-sqm tile unit. */
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
     * Thousands-grouped, no
     * decimal places (these are always whole piece/box counts), Locale.US so the separator is
     * "," and the decimal point (never reached here) would be ".". Same discipline as
     * {@link #format(BigDecimal)}, just for the {@code int} piece counts.
     */
    private static String format(int value) {
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }
}
