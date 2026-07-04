package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record FxRateDto(
    long id,
    String currency,
    BigDecimal rateToThb,
    LocalDate effectiveDate,
    Instant updatedAt
) {}
