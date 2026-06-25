package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record TicketItemRequest(
    @NotBlank String brand,
    @NotBlank String model,
    @NotBlank String color,
    @NotBlank String texture,
    @NotBlank String size,
    @NotNull @Positive BigDecimal qty,
    BigDecimal proposedPrice,
    String currency
) {}
