package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record TicketItemRequest(
    @NotBlank String brand,
    @NotBlank String model,
    @NotBlank String color,
    @NotBlank String texture,
    @NotBlank String size,
    String factory,
    BigDecimal qty,
    BigDecimal qtySqm,
    String unitBasis,
    BigDecimal rawPrice,
    String rawCurrency,
    String rawUnit,
    BigDecimal proposedPrice,
    String currency
) {}
