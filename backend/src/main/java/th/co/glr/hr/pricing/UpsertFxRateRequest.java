package th.co.glr.hr.pricing;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpsertFxRateRequest(
    @NotNull @Positive BigDecimal rateToThb,
    LocalDate effectiveDate
) {}
