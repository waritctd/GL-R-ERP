package th.co.glr.hr.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Records that the customer signed the ISSUED billing note on paper — ผู้รับวางบิล's own name
 * plus the วันนัดชำระเงิน (payment appointment) date they wrote in. This is this system's OWN
 * record of that having happened; it is never re-printed onto the rendered file, which always
 * ships blank signature lines for physical signing (see V189's own note on {@code
 * sales.billing_note.received_by_name}). */
public record BillingNoteMarkReceivedRequest(
    @NotBlank @Size(max = 200) String receivedByName,
    LocalDate paymentAppointmentDate
) {}
