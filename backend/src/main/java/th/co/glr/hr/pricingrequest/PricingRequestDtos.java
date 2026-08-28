package th.co.glr.hr.pricingrequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PricingRequestDtos {
    private PricingRequestDtos() {}

    public record PricingRequestSummaryDto(
        long id,
        String requestCode,
        long ticketId,
        String ticketCode,
        String projectName,
        String customerName,
        // Required for read-scoping: "sales" role may only see requests on
        // tickets they created (commit 3).
        long ticketCreatedById,
        String recipientType,
        Long recipientContactId,
        String recipientLabel,
        String status,
        long requestedById,
        String requestedByName,
        Long assignedImportId,
        String assignedImportName,
        LocalDate requiredDate,
        BigDecimal customerTargetPrice,
        String targetCurrency,
        String note,
        int itemCount,
        int revisionNo,
        Long parentPricingRequestId,
        Instant submittedAt,
        Instant pickedUpAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt,
        // Step 6 (V76): non-null once OrderConfirmationService.confirmOrder has bridged this
        // (terminal, QUOTATION_ACCEPTED) request into the legacy ticket payment/deposit
        // pipeline. QUOTATION_ACCEPTED itself never changes again, so this is the only signal
        // the frontend (or a replay) has that the bridge already ran.
        Instant orderConfirmedAt
    ) {}

    public record PricingRequestItemDto(
        long id,
        long pricingRequestId,
        Long sourceTicketItemId,
        Long productId,
        Long variantId,
        String brand,
        String model,
        String productDescription,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal requestedQty,
        BigDecimal requestedQtySqm,
        String requestedUnit,
        // Machine-readable basis for requestedQty/requestedUnit (V68, financial-integrity
        // review Finding B) — one of UnitBasis's four canonical codes. requestedUnit stays
        // free text for display/the factory email body; this is what PricingCostingService
        // now uses to normalize the requested quantity onto the same basis as the quoted
        // price before multiplying.
        String requestedUnitBasis,
        String quantityType,
        LocalDate targetDeliveryDate,
        String deliveryLocation,
        String specialRequirement,
        int sortOrder,
        Long priceListVersionId,
        Long catalogPriceId,
        BigDecimal catalogBasePrice,
        String catalogCurrency,
        LocalDate catalogEffectiveDate,
        Long resolvedFactoryId,
        String resolvedFactoryName,
        String catalogProductCode,
        String catalogBrand,
        String catalogCollection,
        String catalogModel,
        // V152 (V109 engine wiring): CEO override of the duty product_type used at costing time
        // (sales.pricing_duty_rate.product_type). NULL = no override — LandedCostCalculator
        // defaults to PricingFormulaEngine.DEFAULT_PRODUCT_TYPE ("TILE"). See
        // PricingDecisionService#overrideItemProductType for who may set this and why it lives
        // here rather than on pricing_decision_item.
        String productTypeOverride,
        // Physical conversion factors read LIVE from the catalog row catalog_price_id points at
        // (price_catalog.product_prices), NOT snapshotted onto this table like catalog_base_price
        // and friends above. Live is right here for the same reason LandedCostCalculator reads
        // thickness live via CatalogRepository#findThicknessMm: these are geometry, not commercial
        // terms — a tile's face area does not change when a new price list is published, so there
        // is no version to freeze, and a corrected catalog row should reach the form immediately.
        //
        // They exist to PREFILL the factory-quote response form's conversion-factor inputs
        // (sqmPerUnit / piecesPerBox), which Import otherwise types by hand off the factory's
        // packing list. Null whenever the item has no catalog link or the catalog itself has no
        // value — the form then asks, exactly as it did before.
        //
        // Deliberately no linearMPerUnit counterpart: that factor is "linear metres per physical
        // piece" (V68), while the catalog's sqm_per_linear_m is m² PER linear metre — a different
        // quantity, not a unit conversion away. Prefilling one from the other would be a guess.
        BigDecimal catalogSqmPerPiece,
        BigDecimal catalogPcsPerBox
    ) {}

    public record PricingRequestEventDto(
        long id,
        long pricingRequestId,
        long ticketId,
        Long actorId,
        String actorName,
        String eventKind,
        String fromStatus,
        String toStatus,
        String message,
        // Raw JSON string — the service owns (de)serialisation, not this DTO.
        String metadata,
        Instant createdAt
    ) {}

    public record PricingRequestDetailDto(
        PricingRequestSummaryDto summary,
        List<PricingRequestItemDto> items,
        List<PricingRequestEventDto> events
    ) {}

    /**
     * Sales-level supporting attachment on the Pricing Request itself (V69, review remediation
     * COMMIT 4) — distinct from a factory quote's raw supplier evidence
     * ({@code FactoryQuoteDtos.FactoryQuoteAttachmentDto}). Uploaded by Sales while the request is
     * still {@code DRAFT} (V140 narrowed that from {@code DRAFT}/{@code MORE_INFO_REQUIRED} when
     * the ขอข้อมูลเพิ่มเติม round-trip left the product); Import may mark {@code includeInFactoryEmail}
     * so a later factory email carries it. Deliberately has no local file path field — that stays
     * server-internal (see {@code PricingRequestRepository.PricingRequestEmailAttachmentFile}, used
     * only by {@code FactoryQuoteService.attemptSend}).
     */
    public record PricingRequestAttachmentDto(
        long id,
        long pricingRequestId,
        String fileName,
        String mimeType,
        Long fileSize,
        boolean includeInFactoryEmail,
        long uploadedBy,
        Instant uploadedAt
    ) {}
}
