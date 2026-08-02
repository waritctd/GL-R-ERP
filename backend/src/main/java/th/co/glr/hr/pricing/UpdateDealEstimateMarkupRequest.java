package th.co.glr.hr.pricing;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdateDealEstimateMarkupRequest(
    // Bounds mirror V112's `NUMERIC(6,3) CHECK (multiplier > 0)` column exactly: 6 total digits,
    // 3 after the decimal point, so 999.999 is the largest value the column can hold and 0.001 is
    // the smallest value that rounds to something greater than zero (a value that rounds to
    // 0.000 would still trip the CHECK). Without these, e.g. 1000 or 0.0001 reach Postgres and
    // come back as a raw DataAccessException surfaced to the CEO as a 500.
    @NotNull
    @DecimalMin(value = "0.001", inclusive = true)
    @DecimalMax(value = "999.999", inclusive = true)
    BigDecimal multiplier
) {}
