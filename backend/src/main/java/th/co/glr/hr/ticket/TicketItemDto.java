package th.co.glr.hr.ticket;

import java.math.BigDecimal;

public record TicketItemDto(
    long id,
    long ticketId,
    String brand,
    String model,
    String color,
    String texture,
    String size,
    String factory,
    BigDecimal qty,
    BigDecimal qtySqm,
    BigDecimal rawPrice,
    String rawCurrency,
    String rawUnit,
    BigDecimal proposedPrice,
    BigDecimal approvedPrice,
    String currency,
    int sortOrder,
    BigDecimal calcedCost,
    BigDecimal calcedPrice,
    Integer calcConfigVersion,
    String unitBasis,
    BigDecimal manualPrice,
    String manualOverrideReason,
    BigDecimal qtyDelivered,
    BigDecimal qtyFromStock,
    String stockNote,
    // Fix for "สร้างคำขอราคาไม่ควรต้องกรอกหาจาก catalog ซ้ำ" (V110) -- see
    // TicketItemRequest's matching fields for the full rationale. Read back from
    // sales.ticket_item.catalog_price_id/catalog_product_code.
    Long catalogPriceId,
    String catalogProductCode,
    // Slice F (ticket-workspace IA programme): ราคาตั้ง on the deal's item rows. These 5 fields
    // are resolved ONLY by TicketRepository.findItemsByTicketId's LEFT JOIN to
    // price_catalog.product_prices on catalog_price_id -- every other call site (quotation-item
    // snapshot reads, the write-path merge in TicketService.mergeEditedItemsPreservingPricing,
    // test fixtures) has no reason to populate them and leaves them null via the compat
    // constructors below.
    //
    // Field names are deliberately IDENTICAL to the shape
    // frontend/src/features/tickets/dealEstimatePricing.js's computeItemEstimateThb already reads
    // off a TicketCreateModal item row (item.source / item.catalogPrice / item.catalogCurrency /
    // item.catalogPriceUnit / item.sqmPerPiece) -- combined with the qty/qtySqm/unitBasis fields
    // this record already carries, a TicketItemDto read back from the DB has the exact same shape
    // the create-modal's in-memory item has, so the SAME helper computes the SAME ราคาตั้ง number
    // for both without a second implementation or a field-renaming adapter layer.
    //
    // `source` is NOT a stored column -- it is derived on the read path from catalog_price_id
    // (non-null => "catalog", null => "custom"), the same test the FK's own nullability already
    // encodes, so there is exactly one source of truth for "is this line catalog-linked".
    String source,
    BigDecimal catalogPrice,
    String catalogCurrency,
    String catalogPriceUnit,
    BigDecimal sqmPerPiece,
    // V148 (per-item stock-commission weighting): the manager-set 1/2/3 multiplier for THIS
    // item, mirroring sales.commission_record.weight_multiplier's own shape (V82) but scoped to
    // one line instead of a whole invoice. Placed last, after every Slice F ราคาตั้ง field, so
    // every pre-existing positional constructor call in this codebase (tests, the write-path
    // merge, quotation-item snapshot reconstruction) keeps compiling unchanged and keeps
    // defaulting to 1 -- the same "no weighting" behaviour a brand-new ticket_item row gets from
    // the column's own DEFAULT 1. See TicketRepository#findItemsByTicketId (the sole full-shape
    // reader) and TicketService#mergeEditedItemsPreservingPricing (which must carry a prior
    // item's weight forward across an edit, never silently reset it) for the two places that
    // populate this with a real value.
    int weightMultiplier,
    // V183 (stock-sourced deal-line pricing): sales may flag this line "from warehouse" and type
    // in its own selling price. This is CAPTURE-ONLY today -- nothing downstream (PricingRequest,
    // quotation, commission) reads this pair yet, so a flagged line can still be routed through a
    // PricingRequest exactly as before; wiring it into that chain is a later follow-up -- see the
    // migration's header for the full owner ruling. Unlike qtyFromStock (a commission
    // input) this pair is a pricing-path signal, so it is LEGITIMATELY sales-writable and, unlike
    // proposedPrice/approvedPrice/calcedCost/manualPrice above, is sourced from the incoming
    // request (not guarded to `prior`) in TicketService#mergeEditedItemsPreservingPricing -- with
    // a null-safe fallback to the prior row so a partial edit that omits these fields does not
    // silently reset the flag. sourcedFromStock is primitive (always resolved to a real boolean
    // by the time a TicketItemDto exists); stockSalePrice is non-null only when true, mirroring
    // chk_ticket_item_stock_sale_price. Placed last so every pre-existing positional constructor
    // call in this codebase keeps compiling via the compat constructor below, defaulting to
    // false/null -- the same "not from stock" meaning the column's own DEFAULT false gives a
    // brand-new row.
    boolean sourcedFromStock,
    BigDecimal stockSalePrice
) {
    // V183 compat shape: reproduces the full pre-V183 canonical parameter list (through
    // weightMultiplier) for every call site written before stock-sourced pricing existed (tests,
    // the write-path merge's other callers, quotation-item snapshot reconstruction). Defaults
    // sourcedFromStock/stockSalePrice to false/null, same "not from stock" meaning as an
    // explicit false.
    public TicketItemDto(
        long id,
        long ticketId,
        String brand,
        String model,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal qty,
        BigDecimal qtySqm,
        BigDecimal rawPrice,
        String rawCurrency,
        String rawUnit,
        BigDecimal proposedPrice,
        BigDecimal approvedPrice,
        String currency,
        int sortOrder,
        BigDecimal calcedCost,
        BigDecimal calcedPrice,
        Integer calcConfigVersion,
        String unitBasis,
        BigDecimal manualPrice,
        String manualOverrideReason,
        BigDecimal qtyDelivered,
        BigDecimal qtyFromStock,
        String stockNote,
        Long catalogPriceId,
        String catalogProductCode,
        String source,
        BigDecimal catalogPrice,
        String catalogCurrency,
        String catalogPriceUnit,
        BigDecimal sqmPerPiece,
        int weightMultiplier
    ) {
        this(id, ticketId, brand, model, color, texture, size, factory, qty, qtySqm,
            rawPrice, rawCurrency, rawUnit, proposedPrice, approvedPrice, currency,
            sortOrder, calcedCost, calcedPrice, calcConfigVersion, unitBasis,
            manualPrice, manualOverrideReason, qtyDelivered, qtyFromStock, stockNote,
            catalogPriceId, catalogProductCode, source, catalogPrice, catalogCurrency,
            catalogPriceUnit, sqmPerPiece, weightMultiplier, false, null);
    }

    // Compat shape for every call site written before Slice F: a plain TicketItemDto with no
    // ราคาตั้ง inputs (the write-path merge, quotation-item snapshot reconstruction, and every
    // existing test fixture). source/catalogPrice/catalogCurrency/catalogPriceUnit/sqmPerPiece
    // default to null/"custom" -- these are read-path-only fields nothing here writes back to
    // sales.ticket_item, so a null default cannot lose data. weightMultiplier defaults to 1 (the
    // column default) for the same reason.
    public TicketItemDto(
        long id,
        long ticketId,
        String brand,
        String model,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal qty,
        BigDecimal qtySqm,
        BigDecimal rawPrice,
        String rawCurrency,
        String rawUnit,
        BigDecimal proposedPrice,
        BigDecimal approvedPrice,
        String currency,
        int sortOrder,
        BigDecimal calcedCost,
        BigDecimal calcedPrice,
        Integer calcConfigVersion,
        String unitBasis,
        BigDecimal manualPrice,
        String manualOverrideReason,
        BigDecimal qtyDelivered,
        BigDecimal qtyFromStock,
        String stockNote,
        Long catalogPriceId,
        String catalogProductCode
    ) {
        this(id, ticketId, brand, model, color, texture, size, factory, qty, qtySqm,
            rawPrice, rawCurrency, rawUnit, proposedPrice, approvedPrice, currency,
            sortOrder, calcedCost, calcedPrice, calcConfigVersion, unitBasis,
            manualPrice, manualOverrideReason, qtyDelivered, qtyFromStock, stockNote,
            catalogPriceId, catalogProductCode,
            catalogPriceId != null ? "catalog" : "custom", null, null, null, null, 1);
    }

    // V148 compat shape: the pre-Slice-F fields (through catalogProductCode) PLUS an explicit
    // weightMultiplier, for the one call site that must preserve a prior item's weight across an
    // edit (TicketService#mergeEditedItemsPreservingPricing) without also needing to populate the
    // Slice F ราคาตั้ง read-path fields it never carries either way. source/catalogPrice/
    // catalogCurrency/catalogPriceUnit/sqmPerPiece default exactly as the ctor above derives them.
    public TicketItemDto(
        long id,
        long ticketId,
        String brand,
        String model,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal qty,
        BigDecimal qtySqm,
        BigDecimal rawPrice,
        String rawCurrency,
        String rawUnit,
        BigDecimal proposedPrice,
        BigDecimal approvedPrice,
        String currency,
        int sortOrder,
        BigDecimal calcedCost,
        BigDecimal calcedPrice,
        Integer calcConfigVersion,
        String unitBasis,
        BigDecimal manualPrice,
        String manualOverrideReason,
        BigDecimal qtyDelivered,
        BigDecimal qtyFromStock,
        String stockNote,
        Long catalogPriceId,
        String catalogProductCode,
        int weightMultiplier
    ) {
        this(id, ticketId, brand, model, color, texture, size, factory, qty, qtySqm,
            rawPrice, rawCurrency, rawUnit, proposedPrice, approvedPrice, currency,
            sortOrder, calcedCost, calcedPrice, calcConfigVersion, unitBasis,
            manualPrice, manualOverrideReason, qtyDelivered, qtyFromStock, stockNote,
            catalogPriceId, catalogProductCode,
            catalogPriceId != null ? "catalog" : "custom", null, null, null, null, weightMultiplier);
    }

    public TicketItemDto(
        long id,
        long ticketId,
        String brand,
        String model,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal qty,
        BigDecimal qtySqm,
        BigDecimal rawPrice,
        String rawCurrency,
        String rawUnit,
        BigDecimal proposedPrice,
        BigDecimal approvedPrice,
        String currency,
        int sortOrder,
        BigDecimal calcedCost,
        BigDecimal calcedPrice,
        Integer calcConfigVersion,
        String unitBasis,
        BigDecimal manualPrice,
        String manualOverrideReason
    ) {
        this(id, ticketId, brand, model, color, texture, size, factory, qty, qtySqm,
            rawPrice, rawCurrency, rawUnit, proposedPrice, approvedPrice, currency,
            sortOrder, calcedCost, calcedPrice, calcConfigVersion, unitBasis,
            manualPrice, manualOverrideReason, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null);
    }

    // Compat shape for call sites written before V110 that already specify
    // qtyDelivered/qtyFromStock/stockNote (the pre-V110 full canonical shape) but not the two new
    // trailing catalog fields -- defaults both to null, same "no catalog link" meaning as an
    // explicitly-cleared one.
    public TicketItemDto(
        long id,
        long ticketId,
        String brand,
        String model,
        String color,
        String texture,
        String size,
        String factory,
        BigDecimal qty,
        BigDecimal qtySqm,
        BigDecimal rawPrice,
        String rawCurrency,
        String rawUnit,
        BigDecimal proposedPrice,
        BigDecimal approvedPrice,
        String currency,
        int sortOrder,
        BigDecimal calcedCost,
        BigDecimal calcedPrice,
        Integer calcConfigVersion,
        String unitBasis,
        BigDecimal manualPrice,
        String manualOverrideReason,
        BigDecimal qtyDelivered,
        BigDecimal qtyFromStock,
        String stockNote
    ) {
        this(id, ticketId, brand, model, color, texture, size, factory, qty, qtySqm,
            rawPrice, rawCurrency, rawUnit, proposedPrice, approvedPrice, currency,
            sortOrder, calcedCost, calcedPrice, calcConfigVersion, unitBasis,
            manualPrice, manualOverrideReason, qtyDelivered, qtyFromStock, stockNote, null, null);
    }
}
