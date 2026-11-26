package th.co.glr.hr.deliveryrequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class DeliveryRequestDtos {
    public static final String DRAFT = "DRAFT";
    public static final String SENT = "SENT";
    public static final String RETURNED = "RETURNED";
    public static final String FORWARDED = "FORWARDED";
    public static final String INVOICED = "INVOICED";

    private DeliveryRequestDtos() {}

    /** {@code unconfirmed}: an old or pre-portal deal with no order lines yet; its first send confirms the order from the
     * chosen quotation, so a draft round has no lines to show until then. */
    public record OrderRef(long ticketId, String code, String displayCode, int orderNo, String customerName,
                           String projectName, String parentCode, String ownerName, boolean prePortal,
                           boolean unconfirmed, boolean asksPreDeposit) {}

    /** {@code preDepositMax}: only while the send asks for the pre-portal deposit: the most the rep may enter, the
     * quotation total (VAT included) less the money already on the deal; null otherwise. {@code depositBlockedReason}: why a deposit notice cannot be drafted from this quotation (DepositNoticeRepository's
     * own rule), null when it can. */
    public record ApprovedQuotationDto(long id, String number, BigDecimal total, String docStatus, String depositBlockedReason,
                                       BigDecimal preDepositMax, boolean legacy) {}

    public record LineDto(long itemId, String description, String unit, BigDecimal coveredQty, BigDecimal deliveredQty) {}

    /** {@code uploaderSide}: SALES, WAREHOUSE, ISSUER or SYSTEM, fixed when the file was attached. */
    public record FileDto(long attachmentId, String fileName, String mimeType, Long fileSize, String kind,
                          String uploaderSide, Long uploadedById, String uploadedByName, Instant uploadedAt) {}

    /** {@code canVoid}: the order's latest INVOICED round with no delivery against it, for an issuer-grant holder or an
     * account person in scope; {@code voidBlockedReason} says in Thai why an INVOICED round cannot be voided (null otherwise).
     * {@code canRecordDelivery} also needs the goods to be able to leave (what the record path enforces); when only that is
     * missing, {@code recordBlockedReason} says why in Thai. */
    public record ViewerDto(boolean canEdit, boolean canSend, boolean canReview, boolean canIssue,
                            boolean canRecordDelivery, boolean canVoid, String voidBlockedReason,
                            String recordBlockedReason) {}

    /** The confirmed tax invoice of a round: {@code total} is the invoice, {@code recordedAmount} the part of it counted as
     * received (the rest was already recorded). Both null for the warehouse, which never sees money. */
    public record InvoiceDto(String number, java.time.LocalDate date, BigDecimal total, BigDecimal recordedAmount,
                             Long receiptId, Long attachmentId, String invoicedByName, Instant invoicedAt) {}

    /** What the issuer's upload read from the PDF (any field may be null). */
    public record InvoiceRead(String invoiceNumber, java.time.LocalDate invoiceDate, BigDecimal total) {}

    /** {@code totalWeak}: the total is a guess (no grand-total label), so the screen asks the issuer to check it. */
    public record TaxInvoiceUpload(long attachmentId, InvoiceRead read, boolean readOk, boolean totalWeak) {}

    public record VoidInvoiceRequest(String reason) {}

    public record ConfirmInvoiceRequest(String invoiceNumber, java.time.LocalDate invoiceDate, BigDecimal total) {}

    /** {@code customerName}/{@code taxId}/{@code branch}: the buyer, read from the chosen quotation's party (owner rule #65);
     * {@code customerFromDeal} is true when the quotation has no party and the deal's own customer stands in.
     * {@code invoice} is null until the issuer confirms the tax invoice (the attached file shows in {@code files}). */
    public record RoundDto(long id, int roundNo, String status, Long quotationId, String quotationNumber, String note,
                           List<String> returnReasonCodes, String returnReasonText, String requestedByName,
                           Instant sentAt, String forwardedByName, Instant forwardedAt, InvoiceDto invoice,
                           List<LineDto> lines, List<FileDto> files, List<String> untickedDocs, ViewerDto viewer,
                           String customerName, String taxId, String branch, boolean customerFromDeal, TripPlanDto trip,
                           java.time.LocalDate requestedDate) {}

    /** The warehouse's plan for this round (V248), null when it was never on a trip. {@code state}: PLANNED, DELAYED,
     * REMOVED or DELIVERED. */
    public record TripPlanDto(String state, java.time.LocalDate deliveryDate, String route, String timeSlot,
                              String vehicle, String note) {}

    /** {@code invoiceable}: what the order may still be invoiced for (payable minus confirmed invoices); null for the
     * warehouse and import, which never see money. */
    public record RoundsResponse(OrderRef order, List<RoundDto> rounds, List<ApprovedQuotationDto> approvedQuotations,
                                 boolean canStart, String startBlockedReason, BigDecimal invoiceable) {}

    public record RoundResponse(RoundDto round) {}

    public record CreateRequest(Long quotationId) {}

    /** {@code kind}: QUOTATION, DEPOSIT_NOTICE, REMAINING_INVOICE or IMPORT_REQUEST. */
    public record SystemDocRef(String kind, long id) {}

    /** {@code preDeposit}: the deposit already received outside the portal, required only when the send makes the deal pre-portal. */
    public record SendRequest(List<SystemDocRef> systemDocs, BigDecimal preDeposit, java.time.LocalDate preDepositDate) {}

    /** {@code reasonCodes}: ADDRESS_TAX_ID, PAYMENT_PROOF, DELIVERY_DATE_PLACE; {@code detail}: free text. One of the two is required. */
    public record ReturnRequest(List<String> reasonCodes, String detail) {}

    /** {@code requestedDate}: when sales would like the goods delivered; null keeps what is stored, so a save from an old
     * screen never wipes it. {@code clearRequestedDate} true removes it. */
    public record UpdateRequest(Long quotationId, String note, List<String> untickedDocs, java.time.LocalDate requestedDate,
                                Boolean clearRequestedDate) {
        public UpdateRequest(Long quotationId, String note, List<String> untickedDocs) {
            this(quotationId, note, untickedDocs, null, null);
        }
    }
}
