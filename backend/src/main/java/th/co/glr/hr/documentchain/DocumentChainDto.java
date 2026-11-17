package th.co.glr.hr.documentchain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code GET /api/tickets/{ticketId}/document-chain}: where one deal stands in its document chain — ใบเสนอราคา →
 * ยืนยันคำสั่งซื้อ → ใบแจ้งยอดเงินรับมัดจำ → ใบแจ้งหนี้ส่วนที่เหลือ. The ONE source both the quotation page's chain card
 * and the deal page's เงิน tab render from, so the two pages cannot tell different stories. States and reasons are
 * {@link DocumentChainRules}' codes; the frontend words them (documentChainText.js).
 *
 * <p>Read scope: exactly the deal's deposit-notice / remaining-invoice read gate
 * ({@code DepositNoticeService#requireTicketViewer}). Every document field below is read through
 * {@code DepositNoticeService#listByTicket} and {@code RemainingInvoiceService#list} AS THIS VIEWER, so the summary can
 * never carry a document the viewer could not already list.
 */
public record DocumentChainDto(
    long ticketId,
    String dealCode,
    /** {@code sales.ticket.status} (draft, quotation_issued, …). */
    String dealStatus,
    /** {@code sales.ticket.lifecycle} (ACTIVE, ON_HOLD, DORMANT, CLOSED_LOST, CANCELLED, COMPLETED). */
    String lifecycle,
    Order order,
    Deposit deposit,
    Remaining remaining,
    Viewer viewer
) {

    /**
     * @param confirmed the payment track has started (either confirm route)
     * @param confirmable the deal can still take a confirm-order from an APPROVED direct quotation: status draft,
     *                    ACTIVE, no confirm-order event (DealQuotationService#confirmOrderFromDirectQuotation's own
     *                    deal-level preconditions)
     * @param confirmedFromQuotationId the direct quotation the order was confirmed from, only when this viewer can see
     *                                 that quotation (DealQuotationService#orderConfirmedQuotationId)
     * @param confirmedAt when; null for {@code account}, which reads no ticket events
     * @param undoable the state half of "ยกเลิกการยืนยันคำสั่งซื้อ": the order was confirmed from a direct quotation and
     *                 nothing downstream stops it (DealQuotationService#isOrderConfirmationUndoable, the very rule the
     *                 undo endpoint's 409s come from); who may press it is {@code viewer.mayConfirmOrder}
     */
    public record Order(boolean confirmed, boolean confirmable, Long confirmedFromQuotationId,
                        String confirmedFromQuotationNumber, Instant confirmedAt, boolean undoable) {}

    /**
     * @param policy {@code sales.ticket.deposit_policy}
     * @param required false on a no-deposit policy (DepositPolicy#bypassesDepositNotice)
     * @param percent whole percent: the notice's stored percent, else the confirmed quotation's; null when neither
     * @param state {@link DocumentChainRules#depositState}
     * @param blockedReason {@link DocumentChainRules#depositBlockedReason}
     * @param noticeId the DRAFT notice, else the latest ISSUED one (the deal page's pick), else null
     * @param docNumber / issueDate of the latest ISSUED notice
     * @param received the deposit is in hand (state PAID)
     */
    public record Deposit(String policy, boolean required, Integer percent, String state, String blockedReason,
                          Long noticeId, Long issuedNoticeId, String docNumber, LocalDate issueDate,
                          boolean draftExists, boolean received) {}

    /**
     * @param state {@link DocumentChainRules#remainingState}
     * @param blockedReason {@link DocumentChainRules#remainingBlockedReason}
     * @param canCreate state TO_CREATE and nothing blocks it
     * @param invoiceId the ISSUED invoice, else the DRAFT (the deal page's pick)
     */
    public record Remaining(String state, String blockedReason, boolean canCreate, Long invoiceId, String docNumber,
                            LocalDate docDate, boolean draftExists) {}

    /**
     * Which of the chain's actions THIS viewer is authorised to take — the identity half only, decided by the same
     * methods the action endpoints enforce with; whether the deal's state allows the action now is the states above.
     *
     * @param mayConfirmOrder DealQuotationService#hasEditAccess (the confirm-order authz)
     * @param mayWriteDepositNotice DepositNoticeService#mayWriteDealDocuments (create / save / issue)
     * @param mayWriteRemainingInvoice the same gate: RemainingInvoiceService writes go through requireDepositNoticeIssueGate
     */
    public record Viewer(boolean mayConfirmOrder, boolean mayWriteDepositNotice, boolean mayWriteRemainingInvoice) {}
}
