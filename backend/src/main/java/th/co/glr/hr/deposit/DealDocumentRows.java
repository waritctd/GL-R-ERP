package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Row shapes of the cross-deal lists {@code GET /api/deposit-notices} and {@code GET /api/remaining-invoices}, and of
 * the {@code documents} summary on each {@code GET /api/deal-quotations} row. */
public final class DealDocumentRows {
    private DealDocumentRows() {}

    /** Where a notice or invoice belongs: the deal, the customer as printed on the document, the owner rep and the
     * quotation it resolves to (null when none does). */
    public record Origin(long ticketId, String ticketCode, String projectName, String customerName,
                         Long ownerId, String ownerName, Long quotationId, String quotationNumber) {}

    public record NoticeRow(long id, String status, String docNumber, int version, LocalDate issueDate,
                            OffsetDateTime updatedAt, BigDecimal depositPercent, BigDecimal depositAmount,
                            BigDecimal vatAmount, BigDecimal totalPayable, boolean moneyReceived,
                            Origin origin, boolean viewerMayWrite) {}

    public record OlderVersion(long id, String docNumber, int version, LocalDate docDate, BigDecimal grandTotal) {}

    public record InvoiceRow(long id, String status, String docNumber, int version, LocalDate docDate,
                             OffsetDateTime updatedAt, BigDecimal itemsTotal, BigDecimal depositDeduction,
                             BigDecimal netAmount, BigDecimal vatAmount, BigDecimal grandTotal,
                             Origin origin, boolean viewerMayWrite, PendingRevision pendingRevision,
                             List<OlderVersion> olderVersions) {}

    /** A DRAFT revision waiting beside the ISSUED invoice it will replace. */
    public record PendingRevision(long id, int nextVersion) {}

    public record Counts(int all, int draft, int issued) {}

    /** The deal's deposit notice as one quotation row sees it; {@code forThisQuotation} = it resolves to this quotation. */
    public record NoticeRef(long id, String status, String docNumber, boolean forThisQuotation) {}

    /** {@code version} of a DRAFT is null for a first draft, and the issued version + 1 for a revision (its stored version
     * is 1 until it is issued). */
    public record InvoiceRef(long id, String status, String docNumber, Integer version) {}

    /** {@code viewerMayWrite}: the same rule as the list rows' flag (owner or keyer in a sales role, or the all-sales-documents grant). */
    public record QuotationDocuments(NoticeRef depositNotice, List<InvoiceRef> remainingInvoices, boolean viewerMayWrite) {}
}
