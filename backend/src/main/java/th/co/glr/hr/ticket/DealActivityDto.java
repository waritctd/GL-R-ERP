package th.co.glr.hr.ticket;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A single logged follow-up on a deal (V83 {@code sales.deal_activity}) — the record the
 * stage-advance gate in {@code TicketService.updateStage} checks for "was something logged since
 * the last stage change". {@code activityDate} is the business date the rep says the follow-up
 * happened (may be backdated); {@code createdAt} is when the row was actually written and is what
 * both the gate and the staleness computation compare against.
 */
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
