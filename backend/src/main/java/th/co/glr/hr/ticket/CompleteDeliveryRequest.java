package th.co.glr.hr.ticket;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CompleteDeliveryRequest(
    @Size(max = 2000) String note,
    // Step 8 (V78): threaded through to the resulting delivery_record row — see RecordDeliveryRequest's own field
    // of the same name.
    @Size(max = 255) String recipientName,
    // Slice 6d fix round 1 (M2): the one-click ส่งมอบครบ takes the same วันที่ส่ง as the dialog — RecordDeliveryRequest
    // .deliveredOn's meaning and window, enforced by TicketService. Optional; null (or today) records the moment.
    LocalDate deliveredOn
) {
    /** The shape before slice 6d's fix round 1: no delivery day. */
    public CompleteDeliveryRequest(String note, String recipientName) {
        this(note, recipientName, null);
    }
}
