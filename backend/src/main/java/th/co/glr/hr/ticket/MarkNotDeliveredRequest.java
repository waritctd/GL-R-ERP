package th.co.glr.hr.ticket;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** A deal line the factory never sent: how much, and why. */
public record MarkNotDeliveredRequest(
    @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal qty,
    @NotBlank @Size(max = 255) String reason
) {}
