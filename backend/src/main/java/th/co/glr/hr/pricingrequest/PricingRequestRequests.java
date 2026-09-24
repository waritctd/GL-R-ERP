package th.co.glr.hr.pricingrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PricingRequestRequests {
    private PricingRequestRequests() {}

    /**
     * GLA-125 header-term fields (owner ruling 2026-09-18): mirror sales.quotation's own
     * DEAL_DIRECT header columns so Sales can fill the same terms on the PCR that they will
     * eventually fill on the quotation. NOT yet carried onto the quotation itself (Phase 3) — see
     * V185's migration header. All nullable/optional, exactly as most of the direct-deal
     * equivalents already are.
     */
    public record CreatePricingRequestRequest(
        @NotBlank String recipientType,
        Long recipientContactId,
        String recipientLabel,
        LocalDate requiredDate,
        @DecimalMin("0.00") BigDecimal customerTargetPrice,
        String targetCurrency,
        String note,
        String clientRequestId,
        /** CREDIT | ON_DELIVERY — validated in Java (PricingRequestService), not a bean
         * annotation, matching quantityMode/wastageMode's own posture on the item record. */
        String paymentTermMode,
        @Min(0) @Max(32767) Integer creditDays,
        @Min(0) @Max(32767) Integer validityDays,
        Long printedByDisplayId,
        Long salesRepDisplayId,
        @Size(max = 20) String deptCode,
        @Size(max = 20) String unitCode,
        Boolean omitContactHonorific,
        @NotEmpty List<@Valid PricingRequestItemRequest> items
    ) {
        /** The pre-GLA-125 shape — kept so every existing construction site (tests, mostly)
         * compiles unchanged. Defaults every new field to null. */
        public CreatePricingRequestRequest(String recipientType, Long recipientContactId,
                                           String recipientLabel, LocalDate requiredDate,
                                           BigDecimal customerTargetPrice, String targetCurrency,
                                           String note, String clientRequestId,
                                           List<PricingRequestItemRequest> items) {
            this(recipientType, recipientContactId, recipientLabel, requiredDate,
                customerTargetPrice, targetCurrency, note, clientRequestId,
                null, null, null, null, null, null, null, null, items);
        }
    }

    /** Same fields as {@link CreatePricingRequestRequest}, but all optional. */
    public record UpdatePricingRequestRequest(
        String recipientType,
        Long recipientContactId,
        String recipientLabel,
        LocalDate requiredDate,
        @DecimalMin("0.00") BigDecimal customerTargetPrice,
        String targetCurrency,
        String note,
        String paymentTermMode,
        @Min(0) @Max(32767) Integer creditDays,
        @Min(0) @Max(32767) Integer validityDays,
        Long printedByDisplayId,
        Long salesRepDisplayId,
        @Size(max = 20) String deptCode,
        @Size(max = 20) String unitCode,
        Boolean omitContactHonorific,
        List<@Valid PricingRequestItemRequest> items
    ) {
        /** The pre-GLA-125 shape — kept so every existing construction site (tests, mostly)
         * compiles unchanged. Defaults every new field to null. */
        public UpdatePricingRequestRequest(String recipientType, Long recipientContactId,
                                           String recipientLabel, LocalDate requiredDate,
                                           BigDecimal customerTargetPrice, String targetCurrency,
                                           String note, List<PricingRequestItemRequest> items) {
            this(recipientType, recipientContactId, recipientLabel, requiredDate,
                customerTargetPrice, targetCurrency, note,
                null, null, null, null, null, null, null, null, items);
        }
    }

    /**
     * Phase 1 of the sales-flow redesign (owner ruling, 2026-09-18): the item form Sales fills is
     * now the SAME as the direct-deal quotation's TILE row —
     * {@code th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput} — minus price/discount,
     * which stay a later phase. {@code requestedQty}/{@code requestedQtySqm}/{@code requestedUnit}/
     * {@code requestedUnitBasis} lost their {@code @NotNull}/{@code @NotBlank}: Sales no longer
     * types a quantity or unit directly, so a new-form payload sends none of the four and
     * {@code PricingRequestService#resolveItems} DERIVES all four from the fields below via
     * {@code WastageCalculator} (mirroring {@code DealQuotationService#buildTileItem} exactly,
     * with a zero price) before persisting — see that method's Javadoc. A legacy caller that still
     * sends the four directly (an old client, a test fixture) is unaffected by the relaxation
     * itself; whether it is ACCEPTED depends on {@code resolveItems}, which as of this phase always
     * re-derives them regardless of what was sent, so a legacy caller must now also supply the new
     * tile fields (see that method's own required-field list) — required-field validation applies
     * to every item created/updated from this migration onward, never to an already-persisted
     * legacy row (ruling 4).
     */
    public record PricingRequestItemRequest(
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
        @DecimalMin("0.0001") BigDecimal requestedQty,
        @DecimalMin("0.0000") BigDecimal requestedQtySqm,
        String requestedUnit,
        // See PricingRequestItemDto.requestedUnitBasis's javadoc — required so submit()/
        // PricingCostingService can normalize this line's requested quantity onto the same
        // basis as the factory-quoted price (financial-integrity review Finding B). No longer
        // @NotBlank -- see this record's own class-level Javadoc: resolveItems() always derives
        // this to PER_PIECE for a new-form item now, so a client no longer sends it.
        String requestedUnitBasis,
        @NotBlank String quantityType,
        LocalDate targetDeliveryDate,
        String deliveryLocation,
        String specialRequirement,

        // ── Direct-deal-form parity (V185) — mirrors DealQuotationRequests.ItemInput's TILE
        // fields, minus price/discount/lineType/description/adjustment/id/locationLabel, none of
        // which this phase brings over (see V185's own migration header for the full list of what
        // is deliberately excluded). ─────────────────────────────────────────────────────────────
        /** Sales-typed รหัสสินค้า — distinct from {@code catalogProductCode}, the catalog
         * SNAPSHOT taken at submit() time. See V185's migration header. */
        @Size(max = 255) String productCode,
        /** ความหนา (มม.) — required (owner ruling). {@code @Digits}/{@code @DecimalMax} mirror
         * {@code sales.pricing_request_item.thickness_mm} (NUMERIC(6,2)) so an out-of-range value
         * 400s here instead of overflowing the column as a raw 500 (Opus review finding #7,
         * 2026-09-18). */
        @DecimalMin("0") @DecimalMax("9999") @Digits(integer = 4, fraction = 2) BigDecimal thicknessMm,
        /** ตร.ม./แผ่น — required. {@code @Digits} matches {@code sales.quotation_item.sqm_per_piece}
         * (NUMERIC(10,6)), the same column this mirrors. */
        @DecimalMin("0") @DecimalMax("9999") @Digits(integer = 4, fraction = 6) BigDecimal sqmPerPiece,
        /** "AREA" | "PIECES" — which of areaSqm/piecesInput is the entered quantity. */
        String quantityMode,
        @DecimalMax("999999") BigDecimal areaSqm,
        @Max(1_000_000) Integer piecesInput,
        /** "PERCENT" | "PIECES" | "NONE" — เผื่อ (wastage). */
        String wastageMode,
        BigDecimal wastageValue,
        /** แผ่น/กล่อง — required (owner ruling). */
        @Min(1) @Max(1000) Integer piecesPerBox,
        /** ตร.ม./กล่อง — optional, matching the direct-deal form. */
        @DecimalMin("0") @DecimalMax("9999") @Digits(integer = 4, fraction = 6) BigDecimal sqmPerBox,
        /** ขายแผ่นไม่เต็มกล่อง (inverted) — null reads as {@code true} (round up to a full box),
         * exactly {@code DealQuotationRequests.ItemInput#roundToFullBox}'s own convention. */
        Boolean roundToFullBox,
        @Size(max = 40) String originCountry,
        // SMALLINT columns (V185) -- @Max(32767) stops an overflow value reaching the column as a
        // raw 500 (Opus review finding #7, 2026-09-18); @Min(0) matches "a lead time cannot be
        // negative days", the same business floor every other quantity field in this record uses.
        @Min(0) @Max(32767) Integer leadTimeMinDays,
        @Min(0) @Max(32767) Integer leadTimeMaxDays,

        // Server-computed (WastageCalculator#calculate, via PricingRequestService#resolveItem) —
        // NEVER client-supplied; any value a caller sends here is overwritten on every
        // create/update. Carried on this record (rather than a separate insert-only type) purely
        // so PricingRequestRepository.replaceItems can read them off the same object it already
        // reads every other column from — see resolveItem's own Javadoc.
        Integer piecesBeforeWastage,
        Integer piecesAfterWastage,
        Integer boxes,

        // GLA-125 (owner ruling 2026-09-18) — typed name when originCountry = "อื่นๆ". Required by
        // PricingRequestService#requireItemFieldsComplete exactly then. Appended at the END (not
        // beside originCountry above) so every existing positional constructor call — the compat
        // ctor below, and every hand-wired test call site — keeps compiling unchanged.
        @Size(max = 500) String originCountryOther
    ) {
        /** The pre-V185 shape — kept so every existing construction site (tests, mostly) compiles
         * unchanged. Defaults every new field to null; {@code resolveItems} then requires the
         * caller to have supplied the tile fields directly, same as any other item. */
        public PricingRequestItemRequest(Long sourceTicketItemId, Long productId, Long variantId,
                                         String brand, String model, String productDescription,
                                         String color, String texture, String size, String factory,
                                         BigDecimal requestedQty, BigDecimal requestedQtySqm,
                                         String requestedUnit, String requestedUnitBasis,
                                         String quantityType, LocalDate targetDeliveryDate,
                                         String deliveryLocation, String specialRequirement) {
            this(sourceTicketItemId, productId, variantId, brand, model, productDescription, color,
                texture, size, factory, requestedQty, requestedQtySqm, requestedUnit,
                requestedUnitBasis, quantityType, targetDeliveryDate, deliveryLocation,
                specialRequirement,
                // productCode, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                // wastageMode, wastageValue, piecesPerBox, sqmPerBox, roundToFullBox,
                // originCountry, leadTimeMinDays, leadTimeMaxDays (14):
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                // piecesBeforeWastage, piecesAfterWastage, boxes (3):
                null, null, null,
                // originCountryOther (1):
                null);
        }

        /** The pre-GLA-125 (post-V185) shape — the canonical 35-field constructor that existed
         * before originCountryOther was appended. Defaults it to null. */
        public PricingRequestItemRequest(Long sourceTicketItemId, Long productId, Long variantId,
                                         String brand, String model, String productDescription,
                                         String color, String texture, String size, String factory,
                                         BigDecimal requestedQty, BigDecimal requestedQtySqm,
                                         String requestedUnit, String requestedUnitBasis,
                                         String quantityType, LocalDate targetDeliveryDate,
                                         String deliveryLocation, String specialRequirement,
                                         String productCode, BigDecimal thicknessMm,
                                         BigDecimal sqmPerPiece, String quantityMode,
                                         BigDecimal areaSqm, Integer piecesInput,
                                         String wastageMode, BigDecimal wastageValue,
                                         Integer piecesPerBox, BigDecimal sqmPerBox,
                                         Boolean roundToFullBox, String originCountry,
                                         Integer leadTimeMinDays, Integer leadTimeMaxDays,
                                         Integer piecesBeforeWastage, Integer piecesAfterWastage,
                                         Integer boxes) {
            this(sourceTicketItemId, productId, variantId, brand, model, productDescription, color,
                texture, size, factory, requestedQty, requestedQtySqm, requestedUnit,
                requestedUnitBasis, quantityType, targetDeliveryDate, deliveryLocation,
                specialRequirement, productCode, thicknessMm, sqmPerPiece, quantityMode, areaSqm,
                piecesInput, wastageMode, wastageValue, piecesPerBox, sqmPerBox, roundToFullBox,
                originCountry, leadTimeMinDays, leadTimeMaxDays, piecesBeforeWastage,
                piecesAfterWastage, boxes, null);
        }
    }

    public record CancelPricingRequestRequest(
        @NotBlank String reason
    ) {}

    /**
     * Import fills in the factory on ONE line Sales left blank, so the factory-email step can
     * route it. Import-only, and only while the request sits in Import's hands — see
     * {@code PricingRequestService#setItemFactory} for the full set of guards and why this is a
     * gap-FILL rather than a re-route.
     *
     * <p>The 255 cap matches {@code sales.pricing_request_item.factory VARCHAR(255)} (V59): the
     * column would otherwise reject the write as a raw constraint violation (500) instead of the
     * 400 a too-long name deserves.
     */
    public record SetItemFactoryRequest(
        @NotBlank @Size(max = 255) String factory
    ) {}

    /** Import-only toggle on a Pricing Request attachment (V69, review remediation COMMIT 4). */
    public record UpdatePricingRequestAttachmentRequest(
        @NotNull Boolean includeInFactoryEmail
    ) {}

    public record CustomerChangeRevisionRequest(
        @NotBlank String revisionReason,
        @NotBlank String clientRequestId,
        @NotBlank String recipientType,
        Long recipientContactId,
        String recipientLabel,
        LocalDate requiredDate,
        @DecimalMin("0.00") BigDecimal customerTargetPrice,
        String targetCurrency,
        String note,
        // GLA-125 header terms — see CreatePricingRequestRequest's own Javadoc.
        String paymentTermMode,
        @Min(0) @Max(32767) Integer creditDays,
        @Min(0) @Max(32767) Integer validityDays,
        Long printedByDisplayId,
        Long salesRepDisplayId,
        @Size(max = 20) String deptCode,
        @Size(max = 20) String unitCode,
        Boolean omitContactHonorific,
        @NotEmpty List<@Valid PricingRequestItemRequest> items
    ) {
        /** The pre-GLA-125 shape — kept so every existing construction site (tests, mostly)
         * compiles unchanged. Defaults every new field to null. */
        public CustomerChangeRevisionRequest(String revisionReason, String clientRequestId,
                                             String recipientType, Long recipientContactId,
                                             String recipientLabel, LocalDate requiredDate,
                                             BigDecimal customerTargetPrice, String targetCurrency,
                                             String note, List<PricingRequestItemRequest> items) {
            this(revisionReason, clientRequestId, recipientType, recipientContactId, recipientLabel,
                requiredDate, customerTargetPrice, targetCurrency, note,
                null, null, null, null, null, null, null, null, items);
        }
    }
}
