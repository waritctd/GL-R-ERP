package th.co.glr.hr.salesboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Visits — planned → done / cancelled, optionally on a deal (spec 2026-11-15 §7.6, V211 {@code sales.visit}). */
public final class VisitDtos {
    private VisitDtos() {}

    public static final String PLANNED = "PLANNED";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    /**
     * {@code ticketTitle} is the linked deal's title, else its customer name; null when the visit has no deal.
     * {@code dealMovedToName} is the deal's current owner when the deal has moved away from this visit's owner
     * (the visit's owner is not its {@code created_by}, even if still its keyer); a tick then stays on the visit
     * only (review NEW-2 / NEW-A). Null otherwise.
     */
    public record VisitDto(long id, long ownerId, String ownerName, Long ticketId, String ticketTitle,
                           String contactName, LocalDate plannedDate, LocalTime plannedTime, String status,
                           Instant doneAt, String note, String dealMovedToName) {}

    /**
     * POST /api/sales/visits. {@code done = true} records a visit that already happened (the phone's บันทึกเข้าพบ):
     * it is created DONE in one step and needs {@code plannedDate} = today (Asia/Bangkok).
     */
    public record CreateVisitRequest(Long ticketId, String contactName, LocalDate plannedDate, LocalTime plannedTime,
                                     String note, Boolean done) {}

    /** POST /api/sales/visits/{id}/done. The note replaces the planned note when given. */
    public record VisitDoneRequest(String note) {}
}
