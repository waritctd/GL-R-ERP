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
    LocalDate deliveredOn,
    // The invoiced delivery round to complete; null takes the order's oldest INVOICED round with quantity left.
    Long deliveryRequestId,
    // See RecordDeliveryRequest.noInvoiceNote.
    String noInvoiceNote
) {
    /** The shape before slice 6d's fix round 1: no delivery day. */
    public CompleteDeliveryRequest(String note, String recipientName) {
        this(note, recipientName, null, null, null);
    }

    /** A round named, no note. */
    public CompleteDeliveryRequest(String note, String recipientName, LocalDate deliveredOn, Long deliveryRequestId) {
        this(note, recipientName, deliveredOn, deliveryRequestId, null);
    }

    /** The shape before delivery rounds: no round named. */
    public CompleteDeliveryRequest(String note, String recipientName, LocalDate deliveredOn) {
        this(note, recipientName, deliveredOn, null, null);
    }
}
