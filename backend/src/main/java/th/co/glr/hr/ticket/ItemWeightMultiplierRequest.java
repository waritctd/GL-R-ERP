package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** V148: a sales_manager/CEO's per-line 1/2/3 multiplier decision for a deal's items. Only STOCK-sourced
 * quantity ever earns credit above 1x -- see th.co.glr.hr.commission.CommissionCalculator#itemDerivedWeight
 * for the blending formula that consumes this value. */
public record ItemWeightMultiplierRequest(
    @NotEmpty @Valid List<Line> lines
) {
    public record Line(
        @NotNull Long itemId,
        @NotNull @Min(1) @Max(3) Integer weightMultiplier
    ) {}
}
