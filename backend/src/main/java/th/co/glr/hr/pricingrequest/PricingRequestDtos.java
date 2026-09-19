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
        Instant orderConfirmedAt,

        // ── GLA-125 header terms (owner ruling 2026-09-18) — mirror sales.quotation's own
        // DEAL_DIRECT header columns (V165/V179/V180). NOT yet carried onto the quotation itself
        // (Phase 3) — see this migration's own header. Nullable throughout: every pre-GLA-125 row
        // reads every one of these as null/false, exactly what "Sales has not filled this in yet"
        // already means everywhere else on this DTO. ─────────────────────────────────────────────
        /** CREDIT | ON_DELIVERY. */
        String paymentTermMode,
        /** เครดิต N วัน — meaningful only when paymentTermMode = CREDIT. */
        Integer creditDays,
        /** ยืนราคา N วัน. */
        Integer validityDays,
        Long printedByDisplayId,
        Long salesRepDisplayId,
        /** ฝ่าย. */
        String deptCode,
        /** หน่วยงาน — also the ผู้ออกแบบ picker's target, mirrors sales.quotation.unit_code. */
        String unitCode,
        /** ไม่เติม "คุณ" หน้าชื่อผู้สั่งซื้อ — mirrors sales.quotation.omit_contact_honorific (V180). */
        boolean omitContactHonorific,
        // GLA-125 follow-up (owner directive, 2026-09-18): the ticket's own customer_id (sales.
        // ticket.customer_id, V23) — needed so the PCR create/edit/revision form can scope
        // `QuotationContactPicker` (the SAME ผู้สั่งซื้อ picker the direct-deal quotation editor
        // uses) to this deal's customer in EVERY mode, not just create (where `deal.customerId`
        // was already available from the ticket-list query the panel already runs). Appended at
        // the very end, after omitContactHonorific, so every existing positional constructor call
        // keeps compiling unchanged.
        Long customerId
    ) {
        /** The pre-GLA-125 shape — kept so every existing construction site (tests, mostly)
         * compiles unchanged. Defaults every new field to null/false, exactly what a pre-GLA-125
         * row reads as. */
        public PricingRequestSummaryDto(
            long id, String requestCode, long ticketId, String ticketCode, String projectName,
            String customerName, long ticketCreatedById, String recipientType, Long recipientContactId,
            String recipientLabel, String status, long requestedById, String requestedByName,
            Long assignedImportId, String assignedImportName, LocalDate requiredDate,
            BigDecimal customerTargetPrice, String targetCurrency, String note, int itemCount,
            int revisionNo, Long parentPricingRequestId, Instant submittedAt, Instant pickedUpAt,
            Instant cancelledAt, Instant createdAt, Instant updatedAt, Instant orderConfirmedAt
        ) {
            this(id, requestCode, ticketId, ticketCode, projectName, customerName, ticketCreatedById,
                recipientType, recipientContactId, recipientLabel, status, requestedById,
                requestedByName, assignedImportId, assignedImportName, requiredDate,
                customerTargetPrice, targetCurrency, note, itemCount, revisionNo,
                parentPricingRequestId, submittedAt, pickedUpAt, cancelledAt, createdAt, updatedAt,
                orderConfirmedAt, null, null, null, null, null, null, null, false, null);
        }

        /** The pre-customerId (but post-GLA-125-header-terms) shape — the 36-field constructor
         * that existed before customerId was appended. Defaults it to null. */
        public PricingRequestSummaryDto(
            long id, String requestCode, long ticketId, String ticketCode, String projectName,
            String customerName, long ticketCreatedById, String recipientType, Long recipientContactId,
            String recipientLabel, String status, long requestedById, String requestedByName,
            Long assignedImportId, String assignedImportName, LocalDate requiredDate,
            BigDecimal customerTargetPrice, String targetCurrency, String note, int itemCount,
            int revisionNo, Long parentPricingRequestId, Instant submittedAt, Instant pickedUpAt,
            Instant cancelledAt, Instant createdAt, Instant updatedAt, Instant orderConfirmedAt,
            String paymentTermMode, Integer creditDays, Integer validityDays, Long printedByDisplayId,
            Long salesRepDisplayId, String deptCode, String unitCode, boolean omitContactHonorific
        ) {
            this(id, requestCode, ticketId, ticketCode, projectName, customerName, ticketCreatedById,
                recipientType, recipientContactId, recipientLabel, status, requestedById,
                requestedByName, assignedImportId, assignedImportName, requiredDate,
                customerTargetPrice, targetCurrency, note, itemCount, revisionNo,
                parentPricingRequestId, submittedAt, pickedUpAt, cancelledAt, createdAt, updatedAt,
                orderConfirmedAt, paymentTermMode, creditDays, validityDays, printedByDisplayId,
                salesRepDisplayId, deptCode, unitCode, omitContactHonorific, null);
        }
    }

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

        // ── Direct-deal-form parity (V185) — see PricingRequestRequests.PricingRequestItemRequest's
        // own class-level Javadoc and the migration header for the full rationale. Nullable: a
        // legacy (pre-V185) item reads every one of these as null and the frontend renders "—".
        String productCode,
        BigDecimal thicknessMm,
        BigDecimal sqmPerPiece,
        String quantityMode,
        BigDecimal areaSqm,
        Integer piecesInput,
        String wastageMode,
        BigDecimal wastageValue,
        Integer piecesPerBox,
        BigDecimal sqmPerBox,
        // Server-derived (WastageCalculator#calculate) — audit/display only, never client-supplied.
        Integer piecesBeforeWastage,
        Integer piecesAfterWastage,
        Integer boxes,
        // Mirrors sales.quotation_item.round_to_full_box's own NOT NULL DEFAULT TRUE semantics —
        // always non-null once read back (see PricingRequestRepository#mapItem).
        boolean roundToFullBox,
        String originCountry,
        Integer leadTimeMinDays,
        Integer leadTimeMaxDays,
        // GLA-125 (owner ruling 2026-09-18): typed name when originCountry = "อื่นๆ" — appended at
        // the END, not next to originCountry above, purely so every existing positional
        // constructor call (this class's own two compat ctors below, plus every hand-wired test
        // call site) keeps compiling unchanged.
        String originCountryOther
    ) {
        /** The pre-V185 shape — kept so every existing construction site (tests, mostly) compiles
         * unchanged. Defaults every new field to null/false, exactly what a legacy row reads as. */
        public PricingRequestItemDto(
            long id, long pricingRequestId, Long sourceTicketItemId, Long productId, Long variantId,
            String brand, String model, String productDescription, String color, String texture,
            String size, String factory, BigDecimal requestedQty, BigDecimal requestedQtySqm,
            String requestedUnit, String requestedUnitBasis, String quantityType,
            LocalDate targetDeliveryDate, String deliveryLocation, String specialRequirement,
            int sortOrder, Long priceListVersionId, Long catalogPriceId, BigDecimal catalogBasePrice,
            String catalogCurrency, LocalDate catalogEffectiveDate, Long resolvedFactoryId,
            String resolvedFactoryName, String catalogProductCode, String catalogBrand,
            String catalogCollection, String catalogModel, String productTypeOverride
        ) {
            this(id, pricingRequestId, sourceTicketItemId, productId, variantId, brand, model,
                productDescription, color, texture, size, factory, requestedQty, requestedQtySqm,
                requestedUnit, requestedUnitBasis, quantityType, targetDeliveryDate,
                deliveryLocation, specialRequirement, sortOrder, priceListVersionId, catalogPriceId,
                catalogBasePrice, catalogCurrency, catalogEffectiveDate, resolvedFactoryId,
                resolvedFactoryName, catalogProductCode, catalogBrand, catalogCollection,
                catalogModel, productTypeOverride, null, null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null);
        }

        /** The pre-GLA-125 (but post-V185) shape — the CANONICAL 35-field constructor that existed
         * before originCountryOther was appended above. Defaults it to null. */
        public PricingRequestItemDto(
            long id, long pricingRequestId, Long sourceTicketItemId, Long productId, Long variantId,
            String brand, String model, String productDescription, String color, String texture,
            String size, String factory, BigDecimal requestedQty, BigDecimal requestedQtySqm,
            String requestedUnit, String requestedUnitBasis, String quantityType,
            LocalDate targetDeliveryDate, String deliveryLocation, String specialRequirement,
            int sortOrder, Long priceListVersionId, Long catalogPriceId, BigDecimal catalogBasePrice,
            String catalogCurrency, LocalDate catalogEffectiveDate, Long resolvedFactoryId,
            String resolvedFactoryName, String catalogProductCode, String catalogBrand,
            String catalogCollection, String catalogModel, String productTypeOverride,
            String productCode, BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode,
            BigDecimal areaSqm, Integer piecesInput, String wastageMode, BigDecimal wastageValue,
            Integer piecesPerBox, BigDecimal sqmPerBox, Integer piecesBeforeWastage,
            Integer piecesAfterWastage, Integer boxes, boolean roundToFullBox, String originCountry,
            Integer leadTimeMinDays, Integer leadTimeMaxDays
        ) {
            this(id, pricingRequestId, sourceTicketItemId, productId, variantId, brand, model,
                productDescription, color, texture, size, factory, requestedQty, requestedQtySqm,
                requestedUnit, requestedUnitBasis, quantityType, targetDeliveryDate,
                deliveryLocation, specialRequirement, sortOrder, priceListVersionId, catalogPriceId,
                catalogBasePrice, catalogCurrency, catalogEffectiveDate, resolvedFactoryId,
                resolvedFactoryName, catalogProductCode, catalogBrand, catalogCollection,
                catalogModel, productTypeOverride, productCode, thicknessMm, sqmPerPiece,
                quantityMode, areaSqm, piecesInput, wastageMode, wastageValue, piecesPerBox,
                sqmPerBox, piecesBeforeWastage, piecesAfterWastage, boxes, roundToFullBox,
                originCountry, leadTimeMinDays, leadTimeMaxDays, null);
        }
        /**
         * The factory this line is routed to, or {@code null} when it has none — the price-catalog
         * snapshot first, then Sales's own free text. This precedence was already written out by
         * hand at three call sites (grouping the factory-email drafts, validating a factory
         * response, and now filling a blank); it lives here so all three cannot drift apart.
         */
        public String resolvedFactory() {
            return firstText(resolvedFactoryName, factory);
        }

        /**
         * The line's human-readable identity for an error or event message — the SAME fields, in
         * the same precedence, that the pricing-request detail page prints on each row, so a
         * message naming a line points at text the reader can actually see on screen. Lives on the
         * DTO rather than in either service because both {@code PricingRequestService} and
         * {@code FactoryQuoteService} need it and neither should depend on the other for it.
         */
        public String displayName() {
            String brand = firstText(catalogBrand, this.brand);
            String model = firstText(catalogModel, this.model);
            String name = ((brand == null ? "" : brand) + " " + (model == null ? "" : model)).trim();
            if (!name.isEmpty()) {
                return name;
            }
            String description = firstText(productDescription, null);
            return description == null ? "(ไม่มีชื่อสินค้า)" : description;
        }

        private static String firstText(String first, String fallback) {
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
            return fallback != null && !fallback.isBlank() ? fallback.trim() : null;
        }
    }

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
     * server-internal (see {@code PricingRequestRepository.PricingRequestEmailAttachmentFile}; its
     * sole reader today is {@code FactoryQuoteService.emailBody}, at DRAFT-GENERATION time — the
     * old {@code FactoryQuoteService.attemptSend} dispatch worker that used to read it at
     * actual-send time is deleted, factory RFQ email being manual-only now).
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
