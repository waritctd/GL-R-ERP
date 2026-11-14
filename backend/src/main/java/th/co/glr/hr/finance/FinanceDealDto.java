package th.co.glr.hr.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Finance-only read model of one deal (GET /api/finance/deals/{id}) for the account and ceo roles.
 * Deliberately NOT built from TicketDto: that carries pricing/cost internals, deal tracking and the activity
 * feed, none of which finance may see. */
public record FinanceDealDto(
    long id,
    String code,
    String title,
    String salesStage,
    String lifecycle,
    String status,
    Milestone moneyMilestone,
    List<Milestone> milestoneTrack,
    String customerName,
    Long customerId,
    String projectName,
    String contactName,
    List<Item> items,
    Money money,
    Documents documents,
    /** The deal's COMMENT events only, every author, oldest first. No other event kind, no snapshot. */
    List<Comment> comments,
    /** The money actions THIS caller can take on this deal right now (same gates as the ticket actions). */
    List<Action> availableActions,
    /** The deal's non-VOID SALE commission INVOICE record (no commission amounts); null when none. */
    CommissionInvoice commissionInvoice
) {
    /** VAT basis of every amount, stated once so a UI never has to guess: INCLUDING_VAT:
     * money.amountPayable/amountOutstanding, deposit-notice totalPayable, remaining-invoice grandTotal, billing-note
     * amountForThisDeal; EXCLUDING_VAT: the quotation's totalAmount and every items[].unitPrice/lineTotal. */
    public static final String EXCLUDING_VAT = "EXCLUDING_VAT";
    public static final String INCLUDING_VAT = "INCLUDING_VAT";

    public record Comment(long id, String authorName, java.time.Instant createdAt, String message) {}

    public record Action(String action, String label, String targetStage, List<String> requiredFields) {}

    /** One of the five money milestones; {@code current} marks where this deal stands. */
    public record Milestone(String key, int index, String label, boolean skipped, boolean current) {}

    /** Customer-facing selling line only -- never a cost, FX or margin figure. */
    public record Item(String description, BigDecimal qty, String unit, BigDecimal unitPrice, BigDecimal lineTotal,
                       String vatBasis) {}

    public record Payment(long id, String kind, BigDecimal amount, String currency, Instant receivedAt,
                          String receiptRef, String note, Long depositNoticeId, String recordedByName) {}

    public record Money(
        BigDecimal amountPayable, BigDecimal amountPayableExVat, BigDecimal amountPaid, BigDecimal amountOutstanding,
        String depositPolicy, String paymentStatus, String paymentStage, String fulfillmentStatus,
        // When the balance is due, from the payable quotation's terms + the delivery date (NOT the stored billing
        // column): null until the deal is delivered, or when the quotation has no structured term. paymentDueBasis is
        // CREDIT_FROM_DELIVERY (with paymentDueCreditDays) or ON_DELIVERY.
        LocalDate paymentDueDate, String paymentDueBasis, Integer paymentDueCreditDays,
        boolean overdue, Instant closeConfirmedAt,
        boolean invoiceOnFile, boolean commissionRecorded, String amountVatBasis, List<Payment> payments) {}

    /** The invoice the account recorded for the deal's commission, as finance may see it: the invoice fields and
     * the approval status only -- never a commission amount, weight, tier or payroll month. downloadPath is
     * /api/attachments/{ticketAttachmentId/file}. */
    public record CommissionInvoice(
        String invoiceNumber, LocalDate invoiceDate,
        BigDecimal grossAmount, BigDecimal bankFees, BigDecimal suspenseVat, BigDecimal transportFee,
        BigDecimal cutFee, BigDecimal shortfall, BigDecimal withholdingTax, BigDecimal overpayment,
        String fileName, String downloadPath,
        String approvalStatus, String rejectionReason, Instant recordedAt) {}

    /** {@code downloadPath} points at the EXISTING download endpoint, which enforces its own authz. */
    public record QuotationDoc(long id, String number, String status, BigDecimal totalAmount, String vatBasis,
                               String currency, Instant issuedAt, Instant acceptedAt, String downloadPath) {}

    public record DepositNoticeDoc(long id, String docNumber, int version, String status, LocalDate issueDate,
                                   BigDecimal depositAmount, BigDecimal totalPayable, String totalPayableVatBasis,
                                   String downloadPath) {}

    public record RemainingInvoiceDoc(long id, String docNumber, int version, String status, LocalDate docDate,
                                      BigDecimal grandTotal, String grandTotalVatBasis, String downloadPath) {}

    public record BillingNoteDoc(long id, String docNumber, String status, LocalDate billDate,
                                 BigDecimal amountForThisDeal, String amountVatBasis, String downloadPath) {}

    public record FileDoc(long id, String fileName, String attachType, Instant uploadedAt, Long fileSize,
                          String downloadPath) {}

    public record Documents(
        QuotationDoc acceptedQuotation,
        List<DepositNoticeDoc> depositNotices,
        List<RemainingInvoiceDoc> remainingInvoices,
        List<FileDoc> taxInvoices,
        List<BillingNoteDoc> billingNotes,
        List<FileDoc> purchaseOrders,
        List<FileDoc> contracts) {}
}
