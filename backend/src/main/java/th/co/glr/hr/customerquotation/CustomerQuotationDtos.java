package th.co.glr.hr.customerquotation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class CustomerQuotationDtos {
    private CustomerQuotationDtos() {}

    /**
     * Step 4 quotation: extends {@code sales.quotation} (V74), sourced from the current APPROVED
     * {@code sales.pricing_decision} — never from legacy {@code sales.ticket_item} price columns.
     * Deliberately carries NO cost, margin, or FX field — the item rows below only ever hold the
     * customer-facing approved/final selling price, mirroring {@code PricingDecisionSalesViewDto}'s
     * own "structurally cannot leak cost" shape.
     */
    public record CustomerQuotationDto(
        long id,
        String number,
        long ticketId,
        long pricingRequestId,
        long pricingDecisionId,
        String recipientType,
        String recipientLabel,
        String docStatus,
        int quotationVersion,
        int quotationRevisionNo,
        Long parentQuotationId,
        long issuedById,
        String issuedByName,
        Instant issuedAt,
        BigDecimal subtotalAmount,
        BigDecimal vatAmount,
        BigDecimal grandTotal,
        String currency,
        String paymentTerms,
        String leadTime,
        String deliveryTerms,
        LocalDate validityDate,
        String customerNotes,
        Instant sentAt,
        Instant acceptedAt,
        Instant rejectedAt,
        Instant createdAt,
        // Step 5 (V75): the customer's own note recorded alongside an ACCEPTED/REJECTED/
        // REVISION_REQUESTED outcome (CustomerQuotationService.recordOutcome) — distinct from
        // customerNotes above (Sales-authored, written into the document pre-issue).
        String outcomeNote,
        Instant outcomeRecordedAt,
        List<CustomerQuotationItemDto> items
    ) {}

    /**
     * One quotation line, immutably snapshotted at creation from the approved pricing_decision_item
     * (design correction pattern from Step 3: frozen at creation, never recomputed from a live
     * decision afterward). {@code description}/{@code itemNotes}/{@code salesDiscount} are the ONLY
     * sales-editable fields pre-issue (rule 4/5) — everything else on this record is read-only.
     */
    public record CustomerQuotationItemDto(
        long id,
        int seq,
        long pricingRequestItemId,
        long pricingDecisionItemId,
        String description,
        String itemNotes,
        String requestedUnitBasis,
        BigDecimal requestedQuantity,
        BigDecimal approvedUnitPrice,
        BigDecimal salesDiscount,
        BigDecimal finalUnitPrice,
        // Joined live from pricing_decision_item for display/validation only — never duplicated
        // into a stored column on this row (single source of truth; frozen post-approval anyway).
        BigDecimal minimumSellingPricePerRequestedUnit,
        BigDecimal lineSubtotal,
        BigDecimal vat,
        BigDecimal lineTotal
    ) {}
}
