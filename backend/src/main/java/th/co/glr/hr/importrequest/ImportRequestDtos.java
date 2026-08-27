package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read shapes for the STORED ใบขอซื้อ. Import/CEO only — the same pair
 * {@link ImportRequestService#IR_ROLES} enforces for the stateless read.
 *
 * <p><strong>WIP — nothing serves these yet.</strong> No controller exposes the stored aggregate;
 * see {@code V154__import_request_document.sql}'s closing list for what is still missing.
 *
 * <p>Carries no price of any kind, matching {@code sales.import_request_item}'s own decision: the
 * printed form has no price column, and a supplier cost here would leak what
 * {@code FactoryQuoteService}/{@code PricingDecisionService} keep to import and the CEO.
 */
public final class ImportRequestDtos {
    private ImportRequestDtos() {}

    public record ImportRequestDto(
        long id,
        long ticketId,
        String ticketCode,
        String brand,
        int version,
        String status,
        /** {@code IR<yy><nnn>}, or null while still a draft. */
        String docNumber,
        LocalDate issueDate,
        // Snapshots frozen at issue.
        String customerName,
        String projectName,
        String requestedByName,
        String requiredByNote,
        LocalDate depositReceivedDate,
        // Import-owned, all optional, editable before and after issue.
        String vesselEtaNote,
        String checkedByName,
        LocalDate checkedDate,
        String approvedByName,
        LocalDate approvedDate,
        // Audit.
        Long createdById,
        String createdByName,
        Long issuedById,
        String issuedByName,
        Long supersededById,
        Instant createdAt,
        Instant updatedAt,
        Instant issuedAt,
        /**
         * Sheets this form prints on, from {@code ImportRequestRenderer.pagesRequired}. Served so a
         * client can warn before issuing that a form will run to a second sheet, rather than
         * discovering it at download.
         */
        int pageCount,
        List<ImportRequestItemDto> items
    ) {}

    public record ImportRequestItemDto(
        long id,
        long importRequestId,
        /** The deal line this was snapshotted from; null once that line has been deleted. */
        Long ticketItemId,
        int seq,
        String code,
        String size,
        BigDecimal qty,
        String unit,
        String note
    ) {}
}
