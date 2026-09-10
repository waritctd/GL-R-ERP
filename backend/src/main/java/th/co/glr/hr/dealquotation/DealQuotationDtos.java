package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Quotation v2 (direct deal quotation, V165) — see docs/sales/quotation-v2-plan.md's "API" section for the
 * exact shape. Sales creates a deal, adds items straight onto a quotation with typed unit price +
 * discount (the pricing-request/factory-quote/CEO-costing chain is bypassed for this release —
 * owner ruling 2026-09-09), the server computes every number, and sales_manager/ceo approves.
 *
 * <p>Extends the SAME {@code sales.quotation}/{@code sales.quotation_item} aggregate the pricing
 * chain (Step 4, {@code customerquotation/*}) already uses — tagged {@code origin =
 * 'DEAL_DIRECT'} so the two flows' rows never see each other (see V165's migration comment).
 */
public final class DealQuotationDtos {
    private DealQuotationDtos() {}

    public record DealQuotationDto(
        long id,
        String number,
        long ticketId,
        String docStatus,
        int revisionNo,
        Long parentQuotationId,
        long createdById,
        String createdByName,
        long salesRepId,
        String salesRepName,
        String salesRepPhone,
        Instant submittedAt,
        Long approvedById,
        String approvedByName,
        Instant approvedAt,
        String approvalNote,
        // Approved date, else today — see docs/sales/quotation-v2-plan.md's Data section ("quotationDate in
        // the DTO = approved date, else today").
        LocalDate quotationDate,
        String customerName,
        String customerAddress,
        String customerTaxId,
        String customerPhone,
        String contactName,
        String projectName,
        String deptCode,
        String unitCode,
        LocalDate offerDate,
        Integer depositPercent,
        String remainderMode,
        Integer creditDays,
        Integer validityDays,
        LocalDate validityDate,
        String customerNotes,
        BigDecimal subtotalAmount,
        BigDecimal vatAmount,
        BigDecimal grandTotal,
        String currency,
        boolean approverHasSignature,
        List<DealQuotationItemDto> items,
        Instant createdAt,
        Instant updatedAt
    ) {}

    /** {@link DealQuotationRequests.ItemInput}'s fields, plus what the server computed for it. */
    public record DealQuotationItemDto(
        long id,
        int seq,
        String locationLabel,
        Long catalogPriceId,
        String productCode,
        String brand,
        String model,
        String color,
        String texture,
        String sizeText,
        BigDecimal thicknessMm,
        BigDecimal sqmPerPiece,
        String quantityMode,
        BigDecimal areaSqm,
        Integer piecesInput,
        String wastageMode,
        BigDecimal wastageValue,
        Integer piecesPerBox,
        BigDecimal unitPrice,
        BigDecimal discountPct,
        String originCountry,
        Integer leadTimeMinDays,
        Integer leadTimeMaxDays,
        String itemNotes,
        // Server-computed (WastageCalculator) — never trusted from the client.
        BigDecimal piecesPerSqm,
        int piecesBeforeWastage,
        int piecesAfterWastage,
        int piecesFinal,
        Integer boxes,
        BigDecimal netUnitPrice,
        BigDecimal lineAmount,
        // Printed-line text (DealQuotationLines) — served here so the frontend never has to
        // reimplement the wastage/description phrasing.
        String descriptionLine,
        String sizeLine,
        String calculationLine
    ) {}
}
