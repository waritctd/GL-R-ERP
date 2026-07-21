package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record RecordDeliveryRequest(
    @NotBlank String source,
    @Size(max = 2000) String note,
    @NotEmpty @Valid List<Line> lines,
    // Step 8 (V78): who on the customer's side received/confirmed this delivery. Optional —
    // every existing caller that omits it keeps working unchanged.
    @Size(max = 255) String recipientName
) {
    public record Line(
        @NotNull Long itemId,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal qty
    ) {}
}
