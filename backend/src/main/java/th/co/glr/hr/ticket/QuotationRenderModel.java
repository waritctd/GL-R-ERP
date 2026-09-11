package th.co.glr.hr.ticket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The one rich model {@link QuotationRenderer} renders from — see docs/sales/quotation-v2-plan.md's
 * "Printed lines" section. Both callers build this model and hand it to
 * {@code QuotationRenderer#toXls(QuotationRenderModel)} / {@code #toPdf(QuotationRenderModel)}:
 *
 * <ul>
 *   <li>the legacy/PCR path, via {@code QuotationRenderer#toXls/toXlsx/toPdf(TicketDto,
 *       QuotationDto, CustomerDto)} — thin wrappers that build this model EXACTLY as the renderer
 *       used to compute it inline, so their output stays content-equivalent (one description line
 *       per item via {@code buildDesc}, the same three dynamic remark lines, blank signatory
 *       names, {@code signatureLabelsV2 = false});</li>
 *   <li>the direct-deal path, via {@code th.co.glr.hr.dealquotation.DealQuotationRenderAdapter}
 *       (multi-line item descriptions, location headings, all 8 remark lines, real signatory
 *       names + the approver's signature image, {@code signatureLabelsV2 = true}).</li>
 * </ul>
 *
 * <p>{@code signatureLabelsV2} does double duty as "is this the v2/direct-deal render": besides
 * swapping the signature-block labels (see {@link Signatories}), it is also what makes the
 * renderer print a sequence number for a document with a single item — the legacy convention
 * (kept for the legacy wrappers) is that a lone item shows no "1.".
 */
public record QuotationRenderModel(
    LocalDate issueDate,
    String number,
    String deptCode,
    String unitCode,
    String salesLine,
    /** The full B5 cell value ("เรียน" itself is a separate template label cell, A5, never
     * touched) — "คุณ{contact}   /   {customerName}   เลขที่ผู้เสียภาษี : {taxId}". */
    String attnLine,
    /** The full B6 cell value — "โทร. {phone}". */
    String phoneLine,
    String projectName,
    List<RenderItem> items,
    /** Already-composed remark lines, written in order into the template's numbered remark rows
     * (up to 8 — see {@code QuotationRenderer#REMARK_HEAD_ROWS}). The legacy wrappers supply
     * exactly 3 (offer date / deposit / delivery), matching what the renderer always wrote before
     * this model existed; only when all 8 are supplied does the renderer also blank the
     * template's continuation rows for lines 1/2/4/5/6 (their text is now folded into the single
     * composed line instead of spilling onto a second row). */
    List<String> remarkLines,
    Signatories signatories,
    boolean signatureLabelsV2,
    /**
     * Quotation v3b (2026-09-11): {@code "TH"} (default) prints the Thai <b>F-SM-002 (03)</b> —
     * everything this renderer has ever produced; {@code "EN"} prints the English
     * <b>F-SM-008 (01)</b> off the SAME workbook, with English labels, an English footer, a CE
     * date, USD amounts and NO VAT row.
     *
     * <p>⚠️ There is no F-SM-008 template FILE in this repo — {@code templates/} holds only the
     * Thai {@code quotation_template.xls}, and the renderer works by FILLING it. The English
     * document is therefore <b>modelled on the owner's PDF samples (QN6900902-6, QN6900933), not
     * rendered from the real form</b>. Swapping in a genuine F-SM-008.xls later is confined to
     * {@code QuotationRenderer}'s TEMPLATE constant and its row/column map.
     *
     * <p>Nullable for the pre-v3b twelve-argument constructor below, and read through
     * {@link #isEnglish()} so a null can only ever mean TH in one place.
     */
    String documentLanguage,
    /** {@code "THB"} | {@code "USD"} — printed in the เป็นเงิน/Amount column heading and in the
     * grand-total label. Null falls back to THB via {@link #currencyCode()}. */
    String currency
) {
    /** The pre-v3b shape: a Thai/THB document, which is what every caller predating the English
     * form means. Keeps the legacy wrappers and the existing renderer fixtures unchanged. */
    public QuotationRenderModel(
        LocalDate issueDate, String number, String deptCode, String unitCode, String salesLine,
        String attnLine, String phoneLine, String projectName, List<RenderItem> items,
        List<String> remarkLines, Signatories signatories, boolean signatureLabelsV2
    ) {
        this(issueDate, number, deptCode, unitCode, salesLine, attnLine, phoneLine, projectName,
            items, remarkLines, signatories, signatureLabelsV2, null, null);
    }

    /** The ONE place a null {@code documentLanguage} is interpreted. */
    public boolean isEnglish() {
        return "EN".equalsIgnoreCase(documentLanguage);
    }

    /** The currency code the document prints, defaulted from the language when absent. */
    public String currencyCode() {
        if (currency != null && !currency.isBlank()) {
            return currency.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return isEnglish() ? "USD" : "THB";
    }

    /**
     * One printed item. Rows emitted: an optional heading row (only when {@code headingLabel} is
     * non-null AND differs from the PREVIOUS item's raw {@code headingLabel} — consecutive items
     * sharing a label are grouped under one heading), then one row per
     * {@code descriptionLines} entry (1..3). Qty/unit/price/discount/net/amount are printed on the
     * FIRST description row only; continuation rows carry only column B text.
     */
    public record RenderItem(
        String headingLabel,
        List<String> descriptionLines,
        BigDecimal qty,
        String unit,
        BigDecimal unitPrice,
        /** "Net" or "{pct}%" — printed verbatim into the ส่วนลด column. */
        String discountLabel,
        BigDecimal netUnitPrice,
        BigDecimal amount,
        /** GLA-75: the item's picture, or null (every legacy/PCR item, and every direct-deal item
         * without one — which renders exactly as it did before pictures existed). */
        ItemPicture picture
    ) {
        /** The pre-GLA-75 shape: no picture. Every legacy wrapper and existing fixture uses it. */
        public RenderItem(String headingLabel, List<String> descriptionLines, BigDecimal qty, String unit,
                          BigDecimal unitPrice, String discountLabel, BigDecimal netUnitPrice, BigDecimal amount) {
            this(headingLabel, descriptionLines, qty, unit, unitPrice, discountLabel, netUnitPrice, amount, null);
        }
    }

    /**
     * GLA-75 — one item's picture and where it goes (owner, 2026-09-10: "attach image like the
     * reference photo make sure the sizing appropriate like the reference picture"). Her three
     * reference documents need two placements, and the renderer sizes each differently:
     *
     * <ul>
     *   <li>{@link #BELOW} — a LARGE picture on its own rows under the item's description lines,
     *       scaled to the description column's width (aspect kept, height capped) — QN6900902-6's
     *       mosaic pattern, QN6900782-2's cut drawings;</li>
     *   <li>{@link #BESIDE} — a SMALL thumbnail at the right of the description cell on the item's
     *       first rows, about two text rows tall — QN6900971-4's sanitary ware.</li>
     * </ul>
     *
     * {@code mimeType} is {@code image/png} or {@code image/jpeg}, sniffed from the bytes at upload.
     */
    public record ItemPicture(byte[] data, String mimeType, String placement) {
        public static final String BELOW = "BELOW";
        public static final String BESIDE = "BESIDE";

        /** Anything that is not BESIDE is BELOW — the owner's default placement. */
        public boolean beside() {
            return BESIDE.equalsIgnoreCase(placement);
        }
    }

    /**
     * The signature block. Names are nullable (blank on a DRAFT/PENDING_APPROVAL document — a
     * legacy render always passes all three null, which combined with
     * {@code signatureLabelsV2 = false} keeps the template's own labels and prints no names at
     * all, matching the pre-existing behaviour byte-for-byte).
     *
     * <p>Owner feedback pass 1 (2026-09-10): slot 4 (ผู้สั่งซื้อ) prints the deal's contact as
     * {@code orderedBy} — F2, "use that name to auto fill in the name for signature" — and the
     * "วันที่…" row is filled per slot from {@code printedOn} (created), {@code checkedOn}
     * (submitted) and {@code approvedOn} (approved) — F4, "also autofill in the dates". Each date
     * is nullable and prints the dotted placeholder when absent (a DRAFT carries only ผู้พิมพ์'s);
     * ผู้สั่งซื้อ's date is always the placeholder (the customer signs on paper). The five-argument
     * constructor is the pre-feedback shape — no ordered-by name, no dates — kept for the legacy
     * wrappers and the many existing callers.
     */
    public record Signatories(
        String printedBy,
        String checkedBy,
        String approvedBy,
        String orderedBy,
        byte[] approverSignaturePng,
        String approverSignatureMime,
        LocalDate printedOn,
        LocalDate checkedOn,
        LocalDate approvedOn
    ) {
        public Signatories(String printedBy, String checkedBy, String approvedBy,
                           byte[] approverSignaturePng, String approverSignatureMime) {
            this(printedBy, checkedBy, approvedBy, null, approverSignaturePng, approverSignatureMime,
                null, null, null);
        }
    }
}
