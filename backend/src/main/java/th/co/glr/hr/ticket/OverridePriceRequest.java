package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record OverridePriceRequest(
    @NotNull @Positive BigDecimal manualPrice,
    String reason
) {}
