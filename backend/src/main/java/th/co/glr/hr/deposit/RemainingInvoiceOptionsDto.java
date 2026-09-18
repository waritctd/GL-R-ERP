package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Prefill + preview payload for the ใบแจ้งหนี้ส่วนที่เหลือ download dialog
 * ({@code GET /api/tickets/{ticketId}/remaining-invoice/options}). Every field here is a
 * DEFAULT/SUGGESTION, never a stored choice — there is no table backing this document (owner
 * decision: stateless, one-click-capable). The dialog lets the caller override
 * {@code defaultReference}/{@code defaultDepositReference}/{@code defaultIssueDate} and the
 * {@code noteTemplates} selection before calling the {@code /file} endpoint with the same values
 * as query params; a bare {@code /file} call with no params reproduces exactly this preview.
 *
 * <p>{@code itemCount}/{@code maxItems} let the dialog show a capacity refusal and disable the
 * download button BEFORE the caller wastes a round trip on the {@code /file} endpoint's 409 —
 * this DTO itself never throws for an over-capacity deal (only the {@code /file} render does),
 * so the dialog can always render a preview and a clear "over capacity" state instead of an
 * opaque failure.
 *
 * <p>{@code quotationOptions}/{@code defaultQuotationId} (owner ruling, 2026-09-17 — see
 * DepositNoticeService's own "Remaining Invoice" section header comment): a deal can have several
 * ACCEPTED quotations (designer asks first, owner/buyer makes the final one) that each qualify as
 * a remaining-invoice source. {@code quotationOptions} is non-empty ONLY when two or more qualify
 * — the dialog is meant to show the ใบเสนอราคา picker only in that case, never for the common
 * one-quotation deal. {@code blockingReason} reports (without throwing — see
 * DepositNoticeService#getRemainingInvoiceOptions's own Javadoc) the two states that make this
 * document currently un-downloadable: an accepted quotation with no matching issued deposit
 * notice yet, or a deposit that would make the net amount negative. (A third state — the matched
 * notice's own subtotal disagreeing with its quotation — used to block here too; owner ruling D11,
 * 2026-09-17, dropped it: items and the deduction now come from the matched notice's own item
 * snapshot, so an edited/re-issued notice's different amount IS the agreed amount, not a
 * mismatch.) The dialog disables the download button whenever this is non-null.
 */
public record RemainingInvoiceOptionsDto(
    String     docNumber,
    LocalDate  defaultIssueDate,
    String     defaultReference,
    List<ReferenceOption> referenceOptions,
    String     defaultDepositReference,
    List<ReferenceOption> depositReferenceOptions,
    List<DocumentNoteTemplateDto> noteTemplates,
    int        itemCount,
    int        maxItems,
    BigDecimal itemsTotal,
    BigDecimal depositAmount,
    BigDecimal netAmount,
    BigDecimal vatAmount,
    BigDecimal totalPayable,
    List<QuotationOption> quotationOptions,
    Long       defaultQuotationId,
    String     blockingReason
) {
    /** One selectable reference/deposit-reference suggestion: {@code value} is what the caller
     * would send back as the query param, {@code label} is what the dialog displays (identical
     * today, kept separate so a future richer label — e.g. amount alongside a receipt ref — does
     * not require a shape change). */
    public record ReferenceOption(String value, String label) {}

    /** One selectable "final quotation" source: {@code value} is the quotation id the caller
     * would send back as {@code quotationId}, {@code label} is the quotation's own number plus
     * (when available) its recipient type/label — e.g. "QT-2026-0042 (ผู้ซื้อ)" — so the dialog
     * can tell a designer's early quotation apart from the buyer's final one. */
    public record QuotationOption(long value, String label) {}
}
