package th.co.glr.hr.ticket;

import java.time.Instant;
import java.time.LocalDate;

/** A single logged follow-up on a deal (V83 sales.deal_activity) — the record the stage-advance gate in
 * TicketService.updateStage checks for "was something logged since the last stage change". */
public record DealActivityDto(
    long id,
    long ticketId,
    LocalDate activityDate,
    String kind,
    String note,
    long createdById,
    String createdByName,
    Instant createdAt
) {}
