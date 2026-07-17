package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record StockReservationRequest(
    @NotEmpty @Valid List<Line> lines
) {
    public record Line(
        @NotNull Long itemId,
        @NotNull @DecimalMin(value = "0.00") BigDecimal qtyFromStock,
        @Size(max = 2000) String note
    ) {}
}
