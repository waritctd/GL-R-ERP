package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** POST /api/delivery-requests/{rid}/deliveries: record goods against one invoiced round. Either {@code lines} or
 * {@code complete = true} (everything the round still covers). No prices. {@code source} (WAREHOUSE or STOCK) is optional:
 * left out, it is STOCK for a deal fulfilled from stock and WAREHOUSE otherwise; sales picks it on a mixed deal. */
public record RoundDeliveryRequest(
    @Valid List<RecordDeliveryRequest.Line> lines,
    Boolean complete,
    LocalDate deliveredOn,
    @Size(max = 255) String recipientName,
    @Size(max = 2000) String note,
    String noInvoiceNote,
    String source
) {
    public RoundDeliveryRequest(List<RecordDeliveryRequest.Line> lines, Boolean complete, LocalDate deliveredOn,
                                String recipientName, String note, String noInvoiceNote) {
        this(lines, complete, deliveredOn, recipientName, note, noInvoiceNote, null);
    }
}
