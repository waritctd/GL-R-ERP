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
     * only (review NEW-2 / NEW-A). Null otherwise. {@code dealMoved} says why, when the name is set: true when the
     * deal was reassigned at or after this visit was created ("ดีลนี้ย้ายไปให้ …"), false when the visit's owner never
     * held it — a keyer's or a manager's own visit ("… ดีลนี้เป็นของ …"). Always false when the name is null.
     * {@code dealFollowUpOpen} (slice 6b fix round 1): the visit is on a deal, its owner is the deal's current owner
     * ({@code created_by}, so a tick lands on the deal) and the deal is ACTIVE — exactly when ตามต่อ may set the deal's
     * follow-up date (TicketService#requireVisitFollowUpWritable). Unlike dealMovedToName it does not depend on the
     * owner having a Thai name.
     */
    public record VisitDto(long id, long ownerId, String ownerName, Long ticketId, String ticketTitle,
                           String contactName, LocalDate plannedDate, LocalTime plannedTime, String status,
                           Instant doneAt, String note, String dealMovedToName, boolean dealMoved,
                           boolean dealFollowUpOpen) {}

    /**
     * POST /api/sales/visits. {@code done = true} records a visit that already happened (the phone's บันทึกเข้าพบ):
     * it is created DONE in one step and needs {@code plannedDate} = today (Asia/Bangkok).
     * {@code nextFollowUpAt} (slice 6.4, optional) is the log dialog's ตามต่อ: the deal's next follow-up date, accepted
     * only on a {@code done} log on a deal where the visit's activity lands on the deal (see
     * {@link VisitService#create}); absent leaves the deal's date unchanged.
     */
    public record CreateVisitRequest(Long ticketId, String contactName, LocalDate plannedDate, LocalTime plannedTime,
                                     String note, Boolean done, LocalDate nextFollowUpAt) {
        /** The shape before slice 6.4: no follow-up date. */
        public CreateVisitRequest(Long ticketId, String contactName, LocalDate plannedDate, LocalTime plannedTime,
                                  String note, Boolean done) {
            this(ticketId, contactName, plannedDate, plannedTime, note, done, null);
        }
    }

    /**
     * POST /api/sales/visits/{id}/done. The note replaces the planned note when given. {@code nextFollowUpAt}
     * (slice 6.4, optional): the deal's next follow-up date, under the same rule as {@link CreateVisitRequest}'s.
     */
    public record VisitDoneRequest(String note, LocalDate nextFollowUpAt) {
        /** The shape before slice 6.4: no follow-up date. */
        public VisitDoneRequest(String note) {
            this(note, null);
        }
    }
}
