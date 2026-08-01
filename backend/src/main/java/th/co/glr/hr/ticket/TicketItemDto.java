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
    String catalogProductCode
) {
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
