package th.co.glr.hr.ticket;

import java.math.BigDecimal;

public record DeliveryRecordItemDto(
    long deliveryItemId,
    long itemId,
    BigDecimal qty
) {}
