package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Quotation v2 (direct deal quotation, V165) — the pure arithmetic core. See
 * docs/sales/quotation-v2-plan.md's "Arithmetic" section for the spec this implements verbatim, including
 * its own worked reference figures (QN6900595-3 items 1/3/5 — pinned by
 * {@code WastageCalculatorTest}).
 *
 * <p><b>Corrected 2026-09-15 (quotation arithmetic reconciliation):</b> pieces round HALF_UP, not
 * CEILING, both for the AREA-mode piece count (R-B) and for PERCENT wastage (R-C) — and a line
 * amount is a single rounding of the unrounded product, not a rounding of an already-rounded net
 * price (R-D). The three rules rest on DIFFERENT evidence, stated precisely rather than as one
 * blanket "nine documents settle it" claim — see {@code QuotationGoldenDocumentsTest} for all nine
 * documents reproduced to the satang, but only some of the nine actually DISCRIMINATE a rule:
 * <ul>
 *   <li><b>R-B (AREA pieces HALF_UP)</b> is proven by two rows in two attached documents:
 *       QN6900981-1 row 1 (2,793 × 1.39 = 3,882.27 → printed 3,882; CEILING would give an odd
 *       3,883 that box-rounds up again to 3,884) and QN6900648 row 4 (511 × 1.39 = 710.29 →
 *       printed 710; CEILING would give an odd 711 that box-rounds up to 712). FLOOR is refuted by
 *       QN6900648 row 1 (1,313.55 → printed 1,314) and QN6900981-1 row 2 (380.86 → printed 381),
 *       both of which would floor DOWN.</li>
 *   <li><b>R-C (PERCENT wastage HALF_UP)</b> rests on ONE line only: the spec's own recorded
 *       reference, QN6900595-3 item1 (791 × 1.10 = 870.1 → printed 870; the old CEILING reading
 *       gave 871 → box-rounded to 872). None of the eight ATTACHED documents (QN6900971-4,
 *       QN6900971 ใบสรุป, QN6900981-1, QN6900902-6, QN6900704-2, QN6900933, QN6900648,
 *       QN6900782-2) has any wastage at all, so none of them can corroborate or refute this rule —
 *       QN6900595-3 is the sole evidence. This REVERSES the earlier judgement (recorded in this
 *       class's git history and the old plan doc) that 870 was a human rounding error the meeting
 *       rule deliberately diverged from; it is now read as the correct figure instead.</li>
 *   <li><b>R-D (single-rounding line amount)</b> is proven by QN6900971-4 (D1) rows 5.3-5.7: list
 *       × qty × (1−pct/100) rounded once gives 35,799.63/16,502.43/11,445.23, matching the
 *       printed amounts, where rounding the already-2dp net price first and then multiplying gives
 *       35,799.64/16,502.44/11,445.24 instead.</li>
 * </ul>
 * <p>The remaining four attached documents — QN6900971 ใบสรุป, QN6900902-6, QN6900933,
 * QN6900782-2 — discriminate NONE of R-B/R-C/R-D: they are PLAIN-row or no-wastage/no-box-rounding
 * fixtures that reproduce identically under the old and new rules alike. They still appear in
 * {@code QuotationGoldenDocumentsTest} as regression coverage, just not as evidence for any of the
 * three corrections above. Money still rounds to 2 decimal places HALF_UP throughout.
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

    // ── Quotation v3 (owner feedback pass 3, 2026-09-11) ──────────────────────────────────────
    /** A real tile: catalog link, wastage, ตร.ม./แผ่น, แผ่น/กล่อง, auto-composed description. */
    public static final String LINE_TYPE_TILE = "TILE";
    /** Description + quantity + unit + unit price, and none of the tile machinery — freight,
     * Mapei consumables, the cut service, sanitary-ware ชุด rows. */
    public static final String LINE_TYPE_PLAIN = "PLAIN";
    /** The ส่วนลดพิเศษ line: จำนวน −1, no unit, positive ราคา/คงเหลือ, negative เป็นเงิน. */
    public static final String LINE_TYPE_ADJUSTMENT = "ADJUSTMENT";

    /** Today's behaviour and the default: the rep types a list price and a discount percent. */
    public static final String PRICE_MODE_NET = "NET";
    /** The rep types ราคาพิเศษ in บาท per ตร.ม. INCLUDING VAT — see
     * {@link #netPerPieceFromSpecialSqm}. */
    public static final String PRICE_MODE_SPECIAL_SQM = "SPECIAL_SQM";
    /** The rep types the per-piece net price directly; the ราคา column still shows the list price. */
    public static final String PRICE_MODE_DIRECT_NET = "DIRECT_NET";

    // ── Quotation v3b (owner, 2026-09-11 overnight) — the ENGLISH document ────────────────────
    /** The Thai document, form F-SM-002 (03). The default, and the ONLY behaviour before V169. */
    public static final String DOCUMENT_LANGUAGE_TH = "TH";
    /** The English document, form F-SM-008 (01): USD, no VAT row, English labels and remarks. */
    public static final String DOCUMENT_LANGUAGE_EN = "EN";

    public static final String CURRENCY_THB = "THB";
    public static final String CURRENCY_USD = "USD";

    // ── Owner feedback, กำหนดยืนยันราคา (2026-09-14) — remark 7's second variant ───────────────
    /** The rep types a whole number of days, counted from the document's own date. Today's
     * behaviour and the default (every pre-V178 row stores {@code validity_mode = 'DAYS'}). */
    public static final String VALIDITY_MODE_DAYS = "DAYS";
    /** The rep types a specific calendar date ("ราคาพิเศษสำหรับการสั่งซื้อและชำระมัดจำภายในวันที่
     * .../.../....") instead of a day count — for when a promotion or a factory allocation needs
     * an exact deadline rather than "N days from now". */
    public static final String VALIDITY_MODE_DATE = "DATE";

    // ── Item 4 (V181, "ไม่รับมัดจำ", 2026-09-16) — the three 100%-payment-term codes ─────────────
    // Only ever apply on a document whose deposit_percent = 0 ("ไม่รับมัดจำ" ticked); see
    // DealQuotationService#resolveFullPaymentTerm. Deposit 0% stopped being expressible any other
    // way (the editor's percent chips/custom input now reject 0), so a zero-deposit document names
    // ONE of these instead of leaving the reader to infer why no deposit is asked. No credit-days
    // option exists in no-deposit mode (owner ruling, 2026-09-16) — only these three fixed terms.
    /** "บริษัทขอรับเงินค่าสินค้า 100% ก่อนส่งมอบสินค้า" */
    public static final String FULL_PAYMENT_TERM_BEFORE_DELIVERY = "BEFORE_DELIVERY";
    /** "บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้า" */
    public static final String FULL_PAYMENT_TERM_ON_DELIVERY = "ON_DELIVERY";
    /** "บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้าหรือก่อนส่งมอบสินค้า" — owner correction
     * 2026-09-16: "เมื่อ..." first, THEN "หรือก่อน...", the reverse order of the other two terms'
     * own "ก่อน...หรือเมื่อ..." phrasing; code renamed BEFORE_OR_ON_DELIVERY -> ON_OR_BEFORE_DELIVERY
     * to match. */
    public static final String FULL_PAYMENT_TERM_ON_OR_BEFORE_DELIVERY = "ON_OR_BEFORE_DELIVERY";

    /**
     * The currency a document defaults to from its language — TH→THB, EN→USD, so a rep picks ONE
     * thing (ภาษาเอกสาร) and the rest follows. {@code DealQuotationService#resolveCurrency} lets an
     * explicit request override it; this is only the default.
     */
    public static String defaultCurrencyFor(String documentLanguage) {
        return DOCUMENT_LANGUAGE_EN.equals(documentLanguage) ? CURRENCY_USD : CURRENCY_THB;
    }

    /**
     * The VAT rate a document of this language carries: 7% for TH, ZERO for EN.
     *
     * <p>⚠️ <b>This is the ASSUMPTION, not a discovered rule</b>, and it is flagged as such in the
     * PR body and in V169's own header: VAT follows the LANGUAGE, with no separate switch, because
     * none of the owner's samples shows an English document WITH VAT or a Thai one WITHOUT. If the
     * fourth combination is ever needed it is a new column, not a reinterpretation of this one.
     *
     * <p>Every VAT decision on the deal-quotation path routes through here rather than reading
     * {@link #VAT_RATE} directly, so "an EN document has no VAT" cannot be true in one place and
     * false in another — the document total, the per-item stored vat column and the printed
     * footer all ask the same question.
     */
    public static BigDecimal vatRateFor(String documentLanguage) {
        return DOCUMENT_LANGUAGE_EN.equals(documentLanguage) ? BigDecimal.ZERO : VAT_RATE;
    }

    private static final BigDecimal VAT_RATE = new BigDecimal("0.07");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
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
        BigDecimal discountPct,
        // Owner-approved (2026-09-16, "sell loose pieces"): true (the default) is today's ONLY
        // behaviour, byte-for-byte — piecesFinal always rounds UP to the next piecesPerBox
        // multiple. false lets a TILE row sell pieces that do not make a full box: piecesFinal
        // stays piecesAfterWastage, unrounded, and Result#boxes/#loosePieces split it into full
        // boxes plus a remainder instead of forcing it to the next box. Meaningless (never read)
        // when piecesPerBox is null/<=0 — there is no box multiple to round to or split by either
        // way.
        boolean roundToFullBox
    ) {
        /** The pre-loose-pieces shape — every call site before this feature. Defaults
         * {@code roundToFullBox} true, which is byte-for-byte the only behaviour that existed
         * before it, so every existing caller (production and test) compiles and behaves
         * unchanged. */
        public Input(BigDecimal sqmPerPiece, String quantityMode, BigDecimal areaSqm, Integer piecesInput,
                     String wastageMode, BigDecimal wastageValue, Integer piecesPerBox, BigDecimal unitPrice,
                     BigDecimal discountPct) {
            this(sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue, piecesPerBox,
                unitPrice, discountPct, true);
        }
    }

    /** Everything the server computed for one line — the client never supplies any of this.
     *
     * @param boxes the FULL box count when {@code piecesPerBox > 0} (exactly {@code piecesFinal /
     *     piecesPerBox} either way — an EXACT quotient when {@code roundToFullBox} is true, since
     *     {@code piecesFinal} is then itself a multiple of {@code piecesPerBox}); {@code null}
     *     when there is no box multiple at all.
     * @param loosePieces the remainder pieces that do not make a full box: always {@code 0} when
     *     {@code roundToFullBox} is true (the whole point of rounding up), {@code piecesFinal %
     *     piecesPerBox} when it is false, and {@code null} exactly when {@link #boxes} is null.
     */
    public record Result(
        BigDecimal piecesPerSqm,
        int piecesBeforeWastage,
        int piecesAfterWastage,
        int piecesFinal,
        Integer boxes,
        BigDecimal netUnitPrice,
        BigDecimal lineAmount,
        Integer loosePieces
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
            piecesBefore = roundHalfUpToInt(in.areaSqm().multiply(piecesPerSqm));
        } else {
            throw new IllegalArgumentException("quantityMode must be AREA or PIECES, got: " + in.quantityMode());
        }

        int piecesAfter = applyWastage(piecesBefore, in.wastageMode(), in.wastageValue());

        int piecesFinal = piecesAfter;
        Integer boxes = null;
        Integer loosePieces = null;
        if (in.piecesPerBox() != null && in.piecesPerBox() > 0) {
            int ppb = in.piecesPerBox();
            if (in.roundToFullBox()) {
                piecesFinal = ceilToMultiple(piecesAfter, ppb);
                boxes = piecesFinal / ppb;
                loosePieces = 0;
            } else {
                // Owner-approved "sell loose pieces": piecesFinal is NOT rounded up — the rep is
                // selling exactly piecesAfter, split into full boxes plus whatever does not make
                // one more. lineAmount below is unaffected either way: it always derives from
                // THIS piecesFinal, whichever branch set it.
                piecesFinal = piecesAfter;
                boxes = piecesFinal / ppb;
                loosePieces = piecesFinal % ppb;
            }
        }

        BigDecimal discountPct = in.discountPct() == null ? BigDecimal.ZERO : in.discountPct();
        BigDecimal discountFactor = BigDecimal.ONE.subtract(discountPct.divide(HUNDRED, 10, RoundingMode.HALF_UP));
        // R-D input scale (review fix, 2026-09-15): pre-round the LIST price to 2dp before any
        // multiplication. sales.quotation_item.unit_price is NUMERIC(14,2) (V49) and
        // DealQuotationService persists input.unitPrice() through money2() — a 3dp-or-finer typed
        // list price would otherwise be stored at 2dp while this method kept computing from the
        // unrounded value, so the stored row could never reproduce the amount printed for it (a
        // GET → recompute round-trip would disagree with what was saved). None of the nine owner
        // documents discriminates this — every list price in them is already 2dp — so pinning it
        // here is a forward-looking correctness fix, not a change any printed figure depends on.
        BigDecimal listPrice = round2(in.unitPrice());
        BigDecimal netUnitPrice = round2(listPrice.multiply(discountFactor));
        // R-D (quotation arithmetic reconciliation, 2026-09-15): the line amount is
        // round2(list × piecesFinal × (1 − pct/100)) — a SINGLE rounding at the end — not
        // round2(netUnitPrice × piecesFinal), which double-rounds through the already-rounded
        // 2dp net unit price and drifts by a satang on rows where piecesFinal is even (D1's
        // QN6900971-4 rows 5.3-5.7: round2(netUnitPrice × qty) gives 35,799.64/16,502.44/
        // 11,445.24 against the printed 35,799.63/16,502.43/11,445.23). netUnitPrice above stays
        // the ROUNDED display figure printed in the ราคา/คงเหลือ column; only the line amount
        // is computed from the unrounded product — of the already-2dp LIST price, per the note above.
        BigDecimal lineAmount = round2(listPrice.multiply(BigDecimal.valueOf(piecesFinal)).multiply(discountFactor));

        return new Result(piecesPerSqm, piecesBefore, piecesAfter, piecesFinal, boxes, netUnitPrice, lineAmount,
            loosePieces);
    }

    /**
     * {@code round(1 / sqmPerPiece, 2, HALF_UP)} — the plan's own formula, verbatim.
     *
     * <p>PUBLIC since quotation v3: this 2dp reciprocal is no longer only a printed figure, it is
     * a DIVISOR in {@link #netPerPieceFromSpecialSqm}'s money math. Every caller that needs
     * ตร.ม./แผ่น must come through here rather than re-deriving it, or the two roundings drift
     * apart and only some of the owner's documents reproduce — the exact failure the v3 spec warns
     * about. {@code DealQuotationService#toItemDto} and {@code DealQuotationRepository#mapItem}
     * both used to compute their own {@code ONE.divide(sqmPerPiece, 2, HALF_UP)}, which is
     * single-rounding where this is double (round to 10dp, then to 2dp); the two disagree only on
     * a knife-edge value, but "only on a knife edge" is precisely the kind of divergence that
     * makes a printed sub-line contradict the price beside it. Both now call this.
     */
    public static BigDecimal piecesPerSqm(BigDecimal sqmPerPiece) {
        return round2(BigDecimal.ONE.divide(sqmPerPiece, 10, RoundingMode.HALF_UP));
    }

    /**
     * {@code SPECIAL_SQM} — the per-piece NET price from a ราคาพิเศษ quoted in บาท per ตร.ม.
     * INCLUDING VAT. Owner feedback pass 3 (2026-09-11).
     *
     * <p><b>The rounding ORDER is the whole point.</b> The naive reading — strip VAT, then multiply
     * by ตร.ม./แผ่น — is WRONG. Her documents round the pieces-per-ตร.ม. reciprocal to 2dp FIRST
     * (it is the very figure the printed sub-line carries: "ตร.ม.ๆละ 1.39 แผ่น", "2.78 แผ่น") and
     * DIVIDE by that:
     *
     * <pre>
     *   piecesPerSqm = round2(1 / sqmPerPiece)
     *   netPerPiece  = round2( (special / (1 + VAT)) / piecesPerSqm )
     * </pre>
     *
     * <p>Verified to the satang against all four samples — pinned by {@code WastageCalculatorTest}:
     * <table>
     *   <caption>owner documents, 2026-09-11</caption>
     *   <tr><th>document</th><th>ราคาพิเศษ</th><th>ตร.ม./แผ่น</th><th>pcs/ตร.ม.</th><th>คงเหลือ</th></tr>
     *   <tr><td>QN6900704-2 #1</td><td>1,350</td><td>0.36</td><td>2.78</td><td>453.84</td></tr>
     *   <tr><td>QN6900704-2 #2</td><td>1,400</td><td>0.36</td><td>2.78</td><td>470.65</td></tr>
     *   <tr><td>QN6900648</td><td>1,800</td><td>0.72</td><td>1.39</td><td>1,210.25</td></tr>
     *   <tr><td>QN6900981-1</td><td>790</td><td>0.72</td><td>1.39</td><td>531.16</td></tr>
     * </table>
     *
     * <p>The intermediate VAT-stripped figure is carried at 10dp, NOT rounded to 2dp first: a
     * third rounding there breaks QN6900648 (1800/1.07 = 1682.2429907; rounding that to 1682.24
     * and dividing by 1.39 gives 1210.2446 → 1210.24, one satang under the printed 1210.25).
     * Two roundings, in this order, is the rule.
     *
     * <p>Reuses this class's own {@link #VAT_RATE} rather than declaring a fourth copy — the rate
     * is already stated three times across the codebase ({@code DealQuotationService},
     * {@code QuotationRenderer}, here) and here is where the money math lives.
     *
     * <p><b>Correction to the v3 spec, recorded rather than silently reinterpreted:</b> the spec
     * states that the naive reading "reproduces only two of the four samples". It reproduces
     * NONE of them — 454.21/471.03/1211.21/531.59 against her printed
     * 453.84/470.65/1210.25/531.16. The spec's CONCLUSION (naive is wrong, this order is right)
     * is unaffected and is what is implemented; only its supporting count was off. Pinned by
     * {@code WastageCalculatorTest#netPerPieceFromSpecialSqm_dividesByThe2dpReciprocal_notTheExactOne}.
     */
    public static BigDecimal netPerPieceFromSpecialSqm(BigDecimal specialPerSqmIncVat, BigDecimal sqmPerPiece) {
        if (specialPerSqmIncVat == null || specialPerSqmIncVat.signum() <= 0) {
            throw new IllegalArgumentException("ราคาพิเศษ must be positive, got: " + specialPerSqmIncVat);
        }
        if (sqmPerPiece == null || sqmPerPiece.signum() <= 0) {
            throw new IllegalArgumentException("sqmPerPiece is required for a SPECIAL_SQM price");
        }
        requireReasonableSqmPerPiece(sqmPerPiece);
        BigDecimal exVat = specialPerSqmIncVat.divide(BigDecimal.ONE.add(VAT_RATE), 10, RoundingMode.HALF_UP);
        return round2(exVat.divide(piecesPerSqm(sqmPerPiece), 10, RoundingMode.HALF_UP));
    }

    /**
     * The ส่วนลดพิเศษ figure: {@code round2(base × pct / 100)}. Owner feedback pass 3 — her
     * QN6900704-2 proves the printed 38,198.21 is 3% of the four preceding line amounts
     * (149,767.20 + 96,012.60 + 528,269.76 + 499,224.00 = 1,273,273.56).
     *
     * <p>Returns the POSITIVE magnitude. The sign lives in the row's quantity (−1), which is what
     * makes {@code amount = quantity × netPrice} come out negative while ราคา and คงเหลือ print
     * positive — exactly as the owner's document does it.
     */
    public static BigDecimal adjustmentAmount(BigDecimal base, BigDecimal pct) {
        if (pct == null) {
            throw new IllegalArgumentException("adjustmentPct is required");
        }
        BigDecimal b = base == null ? BigDecimal.ZERO : base;
        return round2(b.multiply(pct).divide(HUNDRED, 10, RoundingMode.HALF_UP));
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
            return roundHalfUpToInt(BigDecimal.valueOf(piecesBefore).multiply(factor));
        }
        throw new IllegalArgumentException("wastageMode must be PERCENT, PIECES or NONE, got: " + wastageMode);
    }

    /** H4 sanity bound: catches the class of unit mistake H4 itself was ({@code sqmPerPiece}
     * resolved 100x off) whatever produced the value — a bad catalog row, a typed override, or a
     * future bug in whatever resolves it (see {@code DealQuotationService#resolveSqmPerPiece} —
     * the catalog-geometry fallback that replaced this class's own deleted free-text-size guess,
     * owner ruling 2026-09-12 "2) ไม่มีค่อยคำนวนเอง") — rather than only fixing today's known
     * cause. */
    private static void requireReasonableSqmPerPiece(BigDecimal sqmPerPiece) {
        if (sqmPerPiece.compareTo(MIN_SQM_PER_PIECE) < 0 || sqmPerPiece.compareTo(MAX_SQM_PER_PIECE) > 0) {
            throw new IllegalArgumentException(UNREASONABLE_SIZE_MESSAGE);
        }
    }

    /**
     * Owner decision 2026-09-13 ("Option A") — an ENGLISH quotation priced per square metre. True
     * exactly for {@code SPECIAL_SQM} on an {@code EN} document; the Thai {@code SPECIAL_SQM}
     * (a VAT-inclusive baht/ตร.ม. price turned into a per-piece net by
     * {@link #netPerPieceFromSpecialSqm}) is untouched by everything this flag switches on.
     *
     * <p>The same mode code is reused on purpose: the rep states ONE price per square metre in
     * both, the price lives in the same {@code special_price_sqm} column, and — as with the VAT —
     * what that price MEANS follows the document's language ({@link #vatRateFor}). A distinct
     * code would have had to widen {@code chk_quotation_price_mode} (V168) for no gain.
     */
    public static boolean isEnglishPerSqm(String documentLanguage, String priceMode) {
        return DOCUMENT_LANGUAGE_EN.equals(documentLanguage) && PRICE_MODE_SPECIAL_SQM.equals(priceMode);
    }

    /**
     * The English per-sqm printed quantity: {@code round2(boxes × sqmPerBox)} — the supplier's
     * stated box area times the box count {@link #calculate} already rounded up to. Her QN6900933:
     * 120 × 0.6 = 72.00, 30 × 0.6 = 18.00, 114 × 0.495 = 56.43. There is deliberately no fallback
     * to pieces × sqm/piece: that reproduces neither 72.00 (71.64) nor the owner's rule.
     *
     * @throws IllegalArgumentException when {@code boxes} is null (no pieces-per-box, so no box
     *     rounding happened) or {@code sqmPerBox} is missing or not positive.
     */
    public static BigDecimal sqmQuantityFromBoxes(Integer boxes, BigDecimal sqmPerBox) {
        if (boxes == null || boxes < 0) {
            throw new IllegalArgumentException("boxes is required for a per-sqm quantity, got: " + boxes);
        }
        if (sqmPerBox == null || sqmPerBox.signum() <= 0) {
            throw new IllegalArgumentException("sqmPerBox must be positive, got: " + sqmPerBox);
        }
        return round2(BigDecimal.valueOf(boxes).multiply(sqmPerBox));
    }

    public static BigDecimal subtotal(List<BigDecimal> lineAmounts) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal amount : lineAmounts) {
            sum = sum.add(amount == null ? BigDecimal.ZERO : amount);
        }
        return round2(sum);
    }

    public static BigDecimal vat(BigDecimal subtotal) {
        return vat(subtotal, DOCUMENT_LANGUAGE_TH);
    }

    /** v3b: the document-level VAT for a quotation in {@code documentLanguage} — 7% on a TH
     * document, ZERO on an EN one (see {@link #vatRateFor}). The one-argument overload above is
     * the TH-only shape every pre-v3b caller keeps using unchanged. */
    public static BigDecimal vat(BigDecimal subtotal, String documentLanguage) {
        return round2((subtotal == null ? BigDecimal.ZERO : subtotal)
            .multiply(vatRateFor(documentLanguage)));
    }

    public static BigDecimal grandTotal(BigDecimal subtotal, BigDecimal vat) {
        BigDecimal s = subtotal == null ? BigDecimal.ZERO : subtotal;
        BigDecimal v = vat == null ? BigDecimal.ZERO : vat;
        return round2(s.add(v));
    }

    // ── REMOVED (owner ruling 2026-09-12, "2) ไม่มีค่อยคำนวนเอง") ───────────────────────────────
    // parseSqmPerPieceFromSize/detectUnit/Unit used to live here: a fallback that guessed cm-vs-mm
    // from a free-text size string by an explicit unit token, else magnitude (either dimension
    // over 300 read as millimetres). It got a REAL catalog product wrong — "200x300" is not > 300
    // on either side, so it read as CENTIMETRES (a 2m x 3m tile, 6.0 sqm/piece) when the owner's
    // own document for that product is 0.061 sqm/piece (16.39 pcs/sqm): wrong by ~98x, the same
    // class of "100x off" unit mistake this class's own header already warned about once (H4).
    // The owner's decision was explicit: do not infer a unit from the free-text size AT ALL —
    // read the CATALOGUE instead (its width_mm/height_mm are unambiguous millimetres), and only
    // compute from those when the catalogue has no sqm_per_piece of its own. See
    // {@code DealQuotationService#resolveSqmPerPiece} for the replacement resolution order, and
    // {@code WastageCalculatorTest#sqmPerPiece_regressionCannotBeReadAsCentimetresBy200x300Again}
    // for the regression this guards.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** R-B/R-C: pieces round HALF_UP, not CEILING — see the class Javadoc. Box-multiple rounding
     * ({@link #ceilToMultiple}) is untouched and still rounds up. */
    private static int roundHalfUpToInt(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static int ceilToMultiple(int value, int multiple) {
        int remainder = value % multiple;
        return remainder == 0 ? value : value + (multiple - remainder);
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
