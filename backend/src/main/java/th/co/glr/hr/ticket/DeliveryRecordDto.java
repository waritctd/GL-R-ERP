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
    // Step 8 (V78): who on the CUSTOMER's side received/confirmed this delivery — the one field
    // genuinely missing before (deliveredByName above is OUR OWN staff). Nullable/free text, not
    // a signature capture — see the migration's own header comment for why.
    String recipientName,
    Instant createdAt,
    List<DeliveryRecordItemDto> items
) {}
