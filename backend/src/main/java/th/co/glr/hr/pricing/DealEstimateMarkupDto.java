package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.time.Instant;

public record DealEstimateMarkupDto(
    BigDecimal multiplier,
    Instant updatedAt,
    Long updatedBy
) {}
