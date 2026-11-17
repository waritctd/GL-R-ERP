package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * POST /api/tickets/{id}/activities. {@code nextFollowUpAt} (slice 6.4, optional) is the call dialog's
 * ตามครั้งถัดไป: when present the server writes ONLY {@code sales.ticket.next_follow_up_at}, in the same transaction
 * as the activity, under the activity's own gate plus the tracking path's ACTIVE requirement (see
 * {@link TicketService#addActivity}). Absent (null) leaves the deal's date unchanged.
 */
public record DealActivityRequest(
    @NotNull LocalDate activityDate,
    @NotBlank String kind,
    @Size(max = 2000) String note,
    LocalDate nextFollowUpAt
) {
    /** The shape before slice 6.4: no follow-up date. */
    public DealActivityRequest(LocalDate activityDate, String kind, String note) {
        this(activityDate, kind, note, null);
    }
}
