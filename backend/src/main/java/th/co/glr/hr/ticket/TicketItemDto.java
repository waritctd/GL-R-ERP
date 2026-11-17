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
    // Fix for "สร้างคำขอราคาไม่ควรต้องกรอกหาจาก catalog ซ้ำ" (V110) -- see TicketItemRequest's matching fields for
    // the full rationale. Read back from sales.ticket_item.catalog_price_id/catalog_product_code.
    Long catalogPriceId,
    String catalogProductCode,
    // Slice F (ticket-workspace IA programme): ราคาตั้ง on the deal's item rows.
    String source,
    BigDecimal catalogPrice,
    String catalogCurrency,
    String catalogPriceUnit,
    BigDecimal sqmPerPiece,
    // V148 (per-item stock-commission weighting): the manager-set 1/2/3 multiplier for THIS item, mirroring
    // sales.commission_record.weight_multiplier's own shape (V82) but scoped to one line instead of a whole
    // invoice.
    int weightMultiplier,
    // V183 (stock-sourced deal-line pricing): sales may flag this line "from warehouse" and type in its own
    // selling price.
    boolean sourcedFromStock,
    BigDecimal stockSalePrice,
    // UAT item 1 follow-up (owner ruling 2026-10-18, "fill from catalogue"): the PCR form seeds
    // ความหนา / แผ่น-กล่อง / ประเทศต้นทาง for a catalogue-linked deal line. Resolved ONLY by
    // TicketRepository.findItemsByTicketId's LEFT JOINs (product_prices + its factory), same as the
    // Slice F fields above; null for a hand-typed line and for a catalogue row that lacks the value
    // (41 % of the prod catalogue has no thickness). Appended last so every pre-existing positional
    // constructor call keeps compiling via the compat constructor below.
    BigDecimal catalogThicknessMm,
    BigDecimal catalogPcsPerBox,
    String catalogOriginCountryCode
) {
    // Pre-2026-10-18 full shape (through stockSalePrice): defaults the three catalogue seed fields
    // to null, the same "nothing resolved" meaning a hand-typed line has.
    public TicketItemDto(
        long id, long ticketId, String brand, String model, String color, String texture, String size,
        String factory, BigDecimal qty, BigDecimal qtySqm, BigDecimal rawPrice, String rawCurrency,
        String rawUnit, BigDecimal proposedPrice, BigDecimal approvedPrice, String currency, int sortOrder,
        BigDecimal calcedCost, BigDecimal calcedPrice, Integer calcConfigVersion, String unitBasis,
        BigDecimal manualPrice, String manualOverrideReason, BigDecimal qtyDelivered, BigDecimal qtyFromStock,
        String stockNote, Long catalogPriceId, String catalogProductCode, String source, BigDecimal catalogPrice,
        String catalogCurrency, String catalogPriceUnit, BigDecimal sqmPerPiece, int weightMultiplier,
        boolean sourcedFromStock, BigDecimal stockSalePrice
    ) {
        this(id, ticketId, brand, model, color, texture, size, factory, qty, qtySqm,
            rawPrice, rawCurrency, rawUnit, proposedPrice, approvedPrice, currency,
            sortOrder, calcedCost, calcedPrice, calcConfigVersion, unitBasis,
            manualPrice, manualOverrideReason, qtyDelivered, qtyFromStock, stockNote,
            catalogPriceId, catalogProductCode, source, catalogPrice, catalogCurrency,
            catalogPriceUnit, sqmPerPiece, weightMultiplier, sourcedFromStock, stockSalePrice,
            null, null, null);
    }

    // V183 compat shape: reproduces the full pre-V183 canonical parameter list (through weightMultiplier) for
    // every call site written before stock-sourced pricing existed (tests, the write-path merge's other callers,
    // quotation-item snapshot reconstruction).
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

    // Compat shape for every call site written before Slice F: a plain TicketItemDto with no ราคาตั้ง inputs (the
    // write-path merge, quotation-item snapshot reconstruction, and every existing test fixture). weightMultiplier
    // defaults to 1 (the column default) for the same reason.
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

    // V148 compat shape: the pre-Slice-F fields PLUS an explicit weightMultiplier, for the one call site that must
    // preserve a prior item's weight across an edit without also needing to populate the Slice F ราคาตั้ง
    // read-path fields it never carries either way.
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

    // Compat shape for call sites written before V110 that already specify qtyDelivered/qtyFromStock/stockNote
    // (the pre-V110 full canonical shape) but not the two new trailing catalog fields -- defaults both to null,
    // same "no catalog link" meaning as an explicitly-cleared one.
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
