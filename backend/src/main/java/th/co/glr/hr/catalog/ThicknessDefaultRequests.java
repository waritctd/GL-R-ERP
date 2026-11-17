package th.co.glr.hr.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public final class ThicknessDefaultRequests {

    private ThicknessDefaultRequests() {}

    public record SaveThicknessDefaultsRequest(
        @NotEmpty @Valid List<ThicknessDefaultEntry> entries
    ) {}

    /** Selects affected frontend tests, including readers outside the import graph. */
    public record ThicknessDefaultEntry(
        @NotNull Long factoryId,
        String collection,
        @DecimalMin(value = "0", inclusive = false, message = "ความหนาต้องมากกว่า 0")
        @DecimalMax(value = "100", message = "ความหนาต้องไม่เกิน 100 มม.")
        BigDecimal thicknessMm
    ) {}
}
