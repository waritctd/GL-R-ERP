package th.co.glr.hr.ticket;

import java.time.Instant;

public record TicketSummaryDto(
    long id,
    String code,
    String type,
    String title,
    String status,
    String priority,
    long createdById,
    String createdByName,
    Long assignedToId,
    String assignedToName,
    String customerName,
    String note,
    Instant createdAt,
    Instant updatedAt,
    Instant closedAt,
    int itemCount
) {}
