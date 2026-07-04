package th.co.glr.hr.ticket;

import java.time.Instant;

public record TicketEventDto(
    long id,
    long ticketId,
    long actorId,
    String actorName,
    String kind,
    String fromStatus,
    String toStatus,
    String message,
    Instant createdAt,
    String itemSnapshot
) {}
