package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RecordDeliveryRequest(
    @NotBlank String source,
    @Size(max = 2000) String note,
    @NotEmpty @Valid List<Line> lines,
    // Step 8 (V78): who on the customer's side received/confirmed this delivery. Optional — every existing caller
    // that omits it keeps working unchanged.
    @Size(max = 255) String recipientName,
    // Slice 6d (วันที่ส่ง): the Asia/Bangkok day the goods went. Optional — null (or today) records the moment it was
    // saved, exactly as before. A past day may be at most 7 days back and never in the future; TicketService
    // #recordPartialDelivery enforces it, not the client. Stored in delivery_record.delivered_at.
    LocalDate deliveredOn
) {
    /** The shape before slice 6d: no delivery day — the record is stamped with the moment it is saved. */
    public RecordDeliveryRequest(String source, String note, List<Line> lines, String recipientName) {
        this(source, note, lines, recipientName, null);
    }

    public record Line(
        @NotNull Long itemId,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal qty
    ) {}
}
