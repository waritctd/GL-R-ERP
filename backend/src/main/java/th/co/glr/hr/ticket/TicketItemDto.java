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
    BigDecimal qty,
    BigDecimal proposedPrice,
    BigDecimal approvedPrice,
    String currency,
    int sortOrder
) {}
