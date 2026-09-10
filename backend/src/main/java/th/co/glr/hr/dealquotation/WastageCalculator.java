package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quotation v2 (direct deal quotation, V165) — the pure arithmetic core. See
 * docs/sales/quotation-v2-plan.md's "Arithmetic" section for the spec this implements verbatim, including
 * its own worked reference figures (QN6900595-3 items 1/3/5 — pinned by
 * {@code WastageCalculatorTest}).
 *
 * <p>Every rounding here follows the meeting rule: pieces round UP (ceiling), money rounds to 2
 * decimal places HALF_UP. This deliberately reproduces a small divergence from a human-made
 * reference document that rounded DOWN twice on one line (869 -&gt; 870 rather than 871 -&gt;
 * 872) — the meeting rule is the one implemented; see the class-level test for item1.
 *
 * <p>No Spring wiring, no DB access, no I/O — a static-methods-only pure class, deliberately, so
 * every branch is exercised by a plain unit test rather than an integration test.
 */
public final class WastageCalculator {
    private WastageCalculator() {}

    public static final String QUANTITY_MODE_AREA = "AREA";
    public static final String QUANTITY_MODE_PIECES = "PIECES";

    public static final String WASTAGE_MODE_PERCENT = "PERCENT";
    public static final String WASTAGE_MODE_PIECES = "PIECES";
    public static final String WASTAGE_MODE_NONE = "NONE";

    private static final BigDecimal VAT_RATE = new BigDecimal("0.07");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Pattern SIZE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[xX×]\\s*(\\d+(?:\\.\\d+)?)");
    // H4 fix: explicit unit tokens always win over the magnitude heuristic below.
    // Anchored so an incidental substring ("60x60 Summer", "Command") can never flip the unit: the
    // latin token must not be preceded or followed by another letter (review finding MED-2 --
    // an unanchored match sent a 60x60 cm tile to 0.0036 m2/piece, a 100x over-count that passed
    // the [0.001, 10] sanity bound).
    private static final Pattern MM_TOKEN = Pattern.compile("(?i)(?<![a-z])mm(?![a-z])|มม");
    private static final Pattern CM_TOKEN = Pattern.compile("(?i)(?<![a-z])cm(?![a-z])|ซม");
    // Above this, either dimension reads as millimetres rather than centimetres — see
    // #detectUnit's Javadoc for why this is 300, not the smaller threshold a first pass at this
    // heuristic might reach for.
    private static final BigDecimal MM_MAGNITUDE_THRESHOLD = BigDecimal.valueOf(300);
    // H4 sanity bound: a resolved sqmPerPiece outside this range is almost certainly a unit
    // mistake (a genuine tile is never smaller than 1000 mm² or larger than 10 sqm per piece).
    private static final BigDecimal MIN_SQM_PER_PIECE = new BigDecimal("0.001");
    private static final BigDecimal MAX_SQM_PER_PIECE = BigDecimal.TEN;
    private static final String UNREASONABLE_SIZE_MESSAGE =
        "ขนาดสินค้าไม่สมเหตุสมผล — กรุณาตรวจสอบหน่วยของขนาด";

    /** One quotation line's inputs, exactly as Sales typed/autofilled them. */
    public record Input(
        // Physical fact (from catalog, parsed from the size text, or typed by Sales) — nullable
        // in PIECES quantity mode, where it is display-only (piecesPerSqm still computed for the
        // printed line when present, but never required to derive piecesBeforeWastage).
        BigDecimal sqmPerPiece,
        String quantityMode,
        BigDecimal areaSqm,
        Integer piecesInput,
        String wastageMode,
        BigDecimal wastageValue,
        // null or <= 0 means "no box rounding" — piecesFinal = piecesAfterWastage, boxes = null.
        Integer piecesPerBox,
        BigDecimal unitPrice,
        BigDecimal discountPct
    ) {}

    /** Everything the server computed for one line — the client never supplies any of this. */
    public record Result(
        BigDecimal piecesPerSqm,
        int piecesBeforeWastage,
        int piecesAfterWastage,
        int piecesFinal,
        Integer boxes,
        BigDecimal netUnitPrice,
        BigDecimal lineAmount
    ) {}

    public static Result calculate(Input in) {
        if (in.unitPrice() == null) {
            throw new IllegalArgumentException("unitPrice is required");
        }
        if (in.sqmPerPiece() != null && in.sqmPerPiece().signum() > 0) {
            requireReasonableSqmPerPiece(in.sqmPerPiece());
        }
        BigDecimal piecesPerSqm = null;
        int piecesBefore;
        if (QUANTITY_MODE_PIECES.equals(in.quantityMode())) {
            if (in.piecesInput() == null) {
                throw new IllegalArgumentException("piecesInput is required in PIECES quantity mode");
            }
            piecesBefore = in.piecesInput();
            if (in.sqmPerPiece() != null && in.sqmPerPiece().signum() > 0) {
                piecesPerSqm = piecesPerSqm(in.sqmPerPiece());
            }
        } else if (QUANTITY_MODE_AREA.equals(in.quantityMode())) {
            if (in.sqmPerPiece() == null || in.sqmPerPiece().signum() <= 0) {
                throw new IllegalArgumentException("sqmPerPiece is required in AREA quantity mode");
            }
            if (in.areaSqm() == null) {
                throw new IllegalArgumentException("areaSqm is required in AREA quantity mode");
            }
            piecesPerSqm = piecesPerSqm(in.sqmPerPiece());
            piecesBefore = ceilToInt(in.areaSqm().multiply(piecesPerSqm));
        } else {
            throw new IllegalArgumentException("quantityMode must be AREA or PIECES, got: " + in.quantityMode());
        }

        int piecesAfter = applyWastage(piecesBefore, in.wastageMode(), in.wastageValue());

        int piecesFinal = piecesAfter;
        Integer boxes = null;
        if (in.piecesPerBox() != null && in.piecesPerBox() > 0) {
            int ppb = in.piecesPerBox();
            piecesFinal = ceilToMultiple(piecesAfter, ppb);
            boxes = piecesFinal / ppb;
        }

        BigDecimal discountPct = in.discountPct() == null ? BigDecimal.ZERO : in.discountPct();
        BigDecimal netUnitPrice = round2(in.unitPrice().multiply(
            BigDecimal.ONE.subtract(discountPct.divide(HUNDRED, 10, RoundingMode.HALF_UP))));
        BigDecimal lineAmount = round2(netUnitPrice.multiply(BigDecimal.valueOf(piecesFinal)));

        return new Result(piecesPerSqm, piecesBefore, piecesAfter, piecesFinal, boxes, netUnitPrice, lineAmount);
    }

    /** {@code round(1 / sqmPerPiece, 2, HALF_UP)} — the plan's own formula, verbatim. */
    private static BigDecimal piecesPerSqm(BigDecimal sqmPerPiece) {
        return round2(BigDecimal.ONE.divide(sqmPerPiece, 10, RoundingMode.HALF_UP));
    }

    // M4: wastageValue bounds are MODE-dependent (a flat piece count can legitimately be in the
    // thousands; a percentage over 100 is almost certainly a typo), so this can only be enforced
    // here, not as a single static @DecimalMax on the request DTO.
    private static final BigDecimal MAX_WASTAGE_PERCENT = HUNDRED;
    private static final BigDecimal MAX_WASTAGE_PIECES = BigDecimal.valueOf(1_000_000);

    private static int applyWastage(int piecesBefore, String wastageMode, BigDecimal wastageValue) {
        if (wastageMode == null || WASTAGE_MODE_NONE.equals(wastageMode)) {
            return piecesBefore;
        }
        BigDecimal value = wastageValue == null ? BigDecimal.ZERO : wastageValue;
        if (value.signum() < 0) {
            throw new IllegalArgumentException("wastageValue must not be negative, got: " + value);
        }
        if (WASTAGE_MODE_PIECES.equals(wastageMode)) {
            if (value.compareTo(MAX_WASTAGE_PIECES) > 0) {
                throw new IllegalArgumentException("wastageValue must be <= " + MAX_WASTAGE_PIECES + " pieces, got: " + value);
            }
            return piecesBefore + value.setScale(0, RoundingMode.HALF_UP).intValueExact();
        }
        if (WASTAGE_MODE_PERCENT.equals(wastageMode)) {
            if (value.compareTo(MAX_WASTAGE_PERCENT) > 0) {
                throw new IllegalArgumentException("wastageValue must be <= 100 percent, got: " + value);
            }
            BigDecimal factor = BigDecimal.ONE.add(value.divide(HUNDRED, 10, RoundingMode.HALF_UP));
            return ceilToInt(BigDecimal.valueOf(piecesBefore).multiply(factor));
        }
        throw new IllegalArgumentException("wastageMode must be PERCENT, PIECES or NONE, got: " + wastageMode);
    }

    /** H4 sanity bound: catches the class of unit mistake H4 itself was ({@code sqmPerPiece}
     * resolved 100x off) whatever produced the value — a bad catalog row, a typed override, or a
     * future bug in {@link #parseSqmPerPieceFromSize} — rather than only fixing today's known
     * cause. */
    private static void requireReasonableSqmPerPiece(BigDecimal sqmPerPiece) {
        if (sqmPerPiece.compareTo(MIN_SQM_PER_PIECE) < 0 || sqmPerPiece.compareTo(MAX_SQM_PER_PIECE) > 0) {
            throw new IllegalArgumentException(UNREASONABLE_SIZE_MESSAGE);
        }
    }

    public static BigDecimal subtotal(List<BigDecimal> lineAmounts) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal amount : lineAmounts) {
            sum = sum.add(amount == null ? BigDecimal.ZERO : amount);
        }
        return round2(sum);
    }

    public static BigDecimal vat(BigDecimal subtotal) {
        return round2((subtotal == null ? BigDecimal.ZERO : subtotal).multiply(VAT_RATE));
    }

    public static BigDecimal grandTotal(BigDecimal subtotal, BigDecimal vat) {
        BigDecimal s = subtotal == null ? BigDecimal.ZERO : subtotal;
        BigDecimal v = vat == null ? BigDecimal.ZERO : vat;
        return round2(s.add(v));
    }

    /**
     * Fallback for {@code sqmPerPiece} when neither the catalog nor Sales supplied one: parses a
     * plain "WxH" size string and resolves it to sqm per piece, choosing centimetres or
     * millimetres per {@link #detectUnit}. {@code "60x120"} (cm) and {@code "600x1200"} (mm)
     * describe the SAME physical tile and both must resolve to 0.72 sqm — the worked example in
     * docs/sales/quotation-v2-plan.md's arithmetic section.
     *
     * <p><b>H4 (unconditional ÷100, fixed here):</b> this method used to divide every dimension by
     * 100 regardless of scale, so {@code "600x1200"} resolved as (6 m × 12 m) = 72 sqm — a 100×
     * over-count that under-quoted the customer by the same factor. {@link #detectUnit} now picks
     * the unit per call: an explicit token in the text (cm/ซม, mm/มม) always wins; failing that,
     * magnitude decides.
     *
     * <p><b>Deviation from the plan's own prose, recorded rather than silently reinterpreted (as
     * the original version of this Javadoc already did for the plan's literal "W×H(mm)/1e6"):</b>
     * the same discipline applies to the magnitude threshold. A literal "either dimension &gt; 30"
     * cannot be the rule — {@code "60x120"} and {@code "60x60"} (both explicitly required to
     * resolve as CENTIMETRES, by the pinned pre-existing test and by H4's own worked example) both
     * have a dimension of 60, comfortably over 30. 300 is used instead: it is the smallest
     * round threshold that classifies every one of H4's own specified cases correctly (cm:
     * {@code 60x120}, {@code 60x60x0.9} → max dimension 120/60; mm, no explicit token:
     * {@code 598x598x18} → max dimension 598), and matches how ceramic tile sizing is written in
     * practice — catalog cm sizes rarely exceed ~120, mm sizes for large-format tiles start around
     * 300–600.
     *
     * @return {@code null} when the text does not contain a recognisable "WxH" pair.
     */
    public static BigDecimal parseSqmPerPieceFromSize(String sizeText) {
        if (sizeText == null) {
            return null;
        }
        Matcher m = SIZE_PATTERN.matcher(sizeText);
        if (!m.find()) {
            return null;
        }
        BigDecimal width = new BigDecimal(m.group(1));
        BigDecimal height = new BigDecimal(m.group(2));
        BigDecimal divisor = detectUnit(sizeText, width, height) == Unit.MM
            ? new BigDecimal("1000000") : new BigDecimal("10000");
        return width.multiply(height).divide(divisor, 6, RoundingMode.HALF_UP);
    }

    private enum Unit { CM, MM }

    /** An explicit unit token in the text always wins; otherwise, either dimension over
     * {@link #MM_MAGNITUDE_THRESHOLD} means the pair is in millimetres — see this method's
     * caller's Javadoc for why 300, not the plan's literal "30". */
    private static Unit detectUnit(String sizeText, BigDecimal width, BigDecimal height) {
        if (MM_TOKEN.matcher(sizeText).find()) {
            return Unit.MM;
        }
        if (CM_TOKEN.matcher(sizeText).find()) {
            return Unit.CM;
        }
        boolean millimetreScale = width.compareTo(MM_MAGNITUDE_THRESHOLD) > 0
            || height.compareTo(MM_MAGNITUDE_THRESHOLD) > 0;
        return millimetreScale ? Unit.MM : Unit.CM;
    }

    private static int ceilToInt(BigDecimal value) {
        return value.setScale(0, RoundingMode.CEILING).intValueExact();
    }

    private static int ceilToMultiple(int value, int multiple) {
        int remainder = value % multiple;
        return remainder == 0 ? value : value + (multiple - remainder);
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
