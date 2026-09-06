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
        // V163 (ฝ่ายนำเข้า must supply ความหนา when the catalog has none). The next four fields are
        // read-side only — none is written directly by create/update; PricingRequestRepository#
        // findItems computes the first three fresh, per row, from a LIVE join against
        // price_catalog.v_priceable_product (never from the catalog snapshot columns above, which
        // can go stale the moment the CEO adds a collection default AFTER this line was created).
        //
        // resolvedThicknessMm is the SAME value LandedCostCalculator#resolveThicknessMm would see
        // right now: thicknessMmOverride first, then the catalog's own COALESCEd thickness_mm
        // (which already folds in collection_thickness_default). Null means the line is still
        // unresolvable and blocks the CEO hop (see LandedCostCalculator#isFullyResolvable).
        BigDecimal resolvedThicknessMm,
        // Mirrors price_catalog.v_priceable_product.thickness_is_default exactly: true only when
        // the CATALOG side of the resolution above came from a collection_thickness_default row
        // rather than the product's own thickness_mm. Deliberately NOT folded together with
        // thicknessMmOverride below — a caller wanting "was this hand-supplied in any form" checks
        // BOTH fields, since they answer different questions (which catalog fallback fired, vs.
        // whether THIS line has its own override) and conflating them would hide which one applies
        // to a line that could in principle carry both.
        boolean thicknessIsDefault,
        // The line's own override (sales.pricing_request_item.thickness_mm_override, V163) — the
        // ONLY thickness source for a line with no catalog link, and the source that outranks the
        // catalog even for a linked line (see resolveThicknessMm's javadoc for why "most specific,
        // hand-entered for this deal" wins). Null when never set.
        BigDecimal thicknessMmOverride,
        // price_catalog.v_priceable_product.sqm_per_piece for this line's resolved catalog link
        // (null when unlinked, or linked to a row with none) — Change 2's prefill source for the
        // "พื้นที่ต่อ 1 <หน่วย> (ตร.ม.)" input on the factory-quote response screen, read BEFORE the
        // requestedQtySqm/requestedQty fallback.
        BigDecimal catalogSqmPerPiece,
        // SPEC-PREFILL.md ladder B/C (2026-09 "prefill everything" pass) — five more LIVE catalog
        // reads, same v_priceable_product join as the four fields above and the same reason: these
        // must reflect the catalog's CURRENT state, never a submit-time snapshot. All five are
        // KNOWN (a fact read off the row), never estimated, so none needs a confidence label the
        // way thicknessSuggestion below does.
        //
        // catalogProductName / catalogSizeRaw feed the frontend's own prefill (supplier product
        // description, and deriveSqmPerPiece's geometric rung respectively) — see
        // PricingRequestDetailPage.jsx's defaultResponseItems/prefillSqmPerUnit.
        String catalogProductName,
        String catalogSizeRaw,
        // Ladder B rung 2 ("box ratio"): sqmPerBox is v_priceable_product.true_sqm_per_box, NOT the
        // raw sqm_per_box column — already corrected for the per-linear-metre mislabelling (V153),
        // the same column PricingRequestThicknessSuggestionService's box-weight rung reads via
        // CatalogRepository#findThicknessEstimationInputs. pcsPerBox has no such correction to make
        // (a piece count is a piece count regardless of price basis).
        BigDecimal catalogSqmPerBox,
        BigDecimal catalogPcsPerBox,
        // Ladder B rung 4: v_priceable_product.sqm_per_linear_m (V153), populated ONLY for
        // price_unit = 'per_linear_m' rows — its mere presence IS the "is this a per-linear-metre
        // row" signal the frontend rung gates on, so no separate price_unit field is exposed.
        BigDecimal catalogSqmPerLinearM,
        // Ladder A: an ESTIMATED thickness (never a catalog fact) computed by
        // PricingRequestThicknessSuggestionService ONLY when resolvedThicknessMm above is null —
        // non-null here must never be treated as equivalent to a resolved thickness. Rendering this
        // writes nothing; only PricingRequestItemThicknessService#setItemThickness, called once a
        // human has seen and accepted it, does (SPEC-PREFILL.md's own governing distinction).
        ThicknessSuggestionDto thicknessSuggestion
    ) {
        /**
         * Copies every field unchanged except {@code thicknessSuggestion} — the one field this DTO
         * gains AFTER construction, once {@code PricingRequestThicknessSuggestionService} has
         * queried the catalog for siblings/box-weight ingredients {@link
         * PricingRequestRepository#findItems}'s single-row mapper has no way to fetch (they read
         * OTHER rows, or raw base-table columns the read-time join does not carry). Records have no
         * built-in "with" support; this is the one place that needs it, so it lives here rather
         * than as full positional re-construction at every call site that needs it.
         */
        public PricingRequestItemDto withThicknessSuggestion(ThicknessSuggestionDto suggestion) {
            return new PricingRequestItemDto(id, pricingRequestId, sourceTicketItemId, productId, variantId,
                brand, model, productDescription, color, texture, size, factory, requestedQty, requestedQtySqm,
                requestedUnit, requestedUnitBasis, quantityType, targetDeliveryDate, deliveryLocation,
                specialRequirement, sortOrder, priceListVersionId, catalogPriceId, catalogBasePrice,
                catalogCurrency, catalogEffectiveDate, resolvedFactoryId, resolvedFactoryName,
                catalogProductCode, catalogBrand, catalogCollection, catalogModel, productTypeOverride,
                resolvedThicknessMm, thicknessIsDefault, thicknessMmOverride, catalogSqmPerPiece,
                catalogProductName, catalogSizeRaw, catalogSqmPerBox, catalogPcsPerBox, catalogSqmPerLinearM,
                suggestion);
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

    /**
     * One ladder-A suggestion (SPEC-PREFILL.md) — {@code ThicknessEstimator#fromSiblings}/{@code
     * #fromBoxWeight}'s output, carried onto {@code PricingRequestItemDto#thicknessSuggestion}
     * unchanged. {@code thicknessMm} is the value a "บันทึก" click would submit if accepted as-is;
     * {@code basis} is the Thai derivation text shown alongside it; {@code confidence} is one of
     * {@code HIGH}/{@code MEDIUM}/{@code LOW}/{@code UNVALIDATED} (see {@code ThicknessEstimator}'s
     * own Javadoc for the measured meaning of each). Never null when returned as a whole — a rung
     * that yields nothing returns {@code Optional.empty()} to its caller, not a
     * {@code ThicknessSuggestionDto} with null fields.
     */
    public record ThicknessSuggestionDto(BigDecimal thicknessMm, String basis, String confidence) {}

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
