package th.co.glr.hr.ticket;

import jakarta.validation.constraints.Size;

public record CompleteDeliveryRequest(
    @Size(max = 2000) String note,
    // Step 8 (V78): threaded through to the resulting delivery_record row — see
    // RecordDeliveryRequest's own field of the same name.
    @Size(max = 255) String recipientName
) {}
