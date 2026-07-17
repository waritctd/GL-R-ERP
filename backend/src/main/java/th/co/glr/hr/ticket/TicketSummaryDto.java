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
    Long customerId,
    Long projectId,
    String projectName,
    Long contactId,
    String contactName,
    String note,
    Instant createdAt,
    Instant updatedAt,
    Instant closedAt,
    int itemCount,
    boolean hasEdits,
    String paymentStatus,
    String fulfillmentStatus,
    // Deal pipeline (V50): one ticket = one deal running the 14-stage journey.
    String salesStage,
    String lostReason,
    Instant lostAt,
    Instant stageUpdatedAt
) {}
