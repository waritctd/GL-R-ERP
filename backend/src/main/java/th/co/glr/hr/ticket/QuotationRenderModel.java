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
    boolean signatureLabelsV2
) {
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
        BigDecimal amount
    ) {}

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
