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
    LocalDate deliveredOn,
    // The delivery round this record is made through. Optional: null with no note takes the order's oldest INVOICED round
    // with quantity still to deliver; with a note it is a delivery with no round. A sent round that is not yet invoiced is
    // linked too (the note is then required). A round that is not this deal's is a 404, a draft a 409.
    Long deliveryRequestId,
    // Ploy ruling 2026-11-20: with no confirmed tax invoice sales or warehouse staff may still record a delivery, but only
    // with this note/reference (trimmed, non-blank, max 500). Ignored when the delivery is made against an INVOICED round.
    String noInvoiceNote
) {
    /** The shape before slice 6d: no delivery day — the record is stamped with the moment it is saved. */
    public RecordDeliveryRequest(String source, String note, List<Line> lines, String recipientName) {
        this(source, note, lines, recipientName, null, null, null);
    }

    /** The shape before delivery rounds: no round named. */
    public RecordDeliveryRequest(String source, String note, List<Line> lines, String recipientName, LocalDate deliveredOn) {
        this(source, note, lines, recipientName, deliveredOn, null, null);
    }

    /** The shape with a round named and no note. */
    public RecordDeliveryRequest(String source, String note, List<Line> lines, String recipientName, LocalDate deliveredOn,
                                 Long deliveryRequestId) {
        this(source, note, lines, recipientName, deliveredOn, deliveryRequestId, null);
    }

    public record Line(
        @NotNull Long itemId,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal qty
    ) {}
}
