package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record TicketItemRequest(
    String productCode,
    @NotBlank String productName,
    String size,
    String color,
    @NotNull @Positive BigDecimal qty,
    String unit,
    BigDecimal proposedPrice,
    String currency
) {}
