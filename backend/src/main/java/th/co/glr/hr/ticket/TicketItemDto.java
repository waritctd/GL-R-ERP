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
    String stockNote
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
            manualPrice, manualOverrideReason, BigDecimal.ZERO, BigDecimal.ZERO, null);
    }
}
