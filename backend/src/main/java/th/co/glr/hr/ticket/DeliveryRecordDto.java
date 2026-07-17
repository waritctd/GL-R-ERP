package th.co.glr.hr.ticket;

import java.time.Instant;
import java.util.List;

public record DeliveryRecordDto(
    long deliveryId,
    long ticketId,
    String source,
    Instant deliveredAt,
    long deliveredById,
    String deliveredByName,
    String note,
    Instant createdAt,
    List<DeliveryRecordItemDto> items
) {}
