package th.co.glr.hr.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Records that the customer signed the ISSUED billing note on paper — ผู้รับวางบิล's own name plus the
 * วันนัดชำระเงิน (payment appointment) date they wrote in. */
public record BillingNoteMarkReceivedRequest(
    @NotBlank @Size(max = 200) String receivedByName,
    LocalDate paymentAppointmentDate
) {}
