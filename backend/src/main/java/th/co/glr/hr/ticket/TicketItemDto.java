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
    BigDecimal sqmPerPiece
) {
    // Compat shape for every call site written before Slice F: a plain TicketItemDto with no
    // ราคาตั้ง inputs (the write-path merge, quotation-item snapshot reconstruction, and every
    // existing test fixture). source/catalogPrice/catalogCurrency/catalogPriceUnit/sqmPerPiece
    // default to null/"custom" -- these are read-path-only fields nothing here writes back to
    // sales.ticket_item, so a null default cannot lose data.
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
            catalogPriceId != null ? "catalog" : "custom", null, null, null, null);
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
