package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * V148 (per-item stock-commission weighting): a sales_manager/CEO's per-line 1/2/3 multiplier
 * decision for a deal's items -- the manager-approved counterpart to {@link
 * StockReservationRequest}'s rep-declared {@code qtyFromStock}, deliberately shaped the same way
 * (a flat list of {@code {itemId, value}} lines) so the two features read as siblings rather than
 * two different conventions for "per-item input on this ticket".
 *
 * <p>Only STOCK-sourced quantity ever earns credit above 1x -- see {@link
 * th.co.glr.hr.commission.CommissionCalculator#itemDerivedWeight} for the blending formula that
 * consumes this value. Setting this multiplier does not, by itself, change any money: it only
 * takes effect the next time a commission record is CREATED against this ticket (frozen once,
 * never recomputed -- see {@code sales.commission_record.effective_weight_multiplier}'s migration
 * comment, V148).
 *
 * <p>Authorization: {@code sales_manager}/{@code ceo} only -- see {@link
 * TicketService#setItemWeightMultipliers}'s own Javadoc for why this is a NEW role set rather
 * than a reuse of {@code STOCK_DECLARATION_ROLES} (the rep-facing {@code reserveStock} gate).
 */
public record ItemWeightMultiplierRequest(
    @NotEmpty @Valid List<Line> lines
) {
    public record Line(
        @NotNull Long itemId,
        @NotNull @Min(1) @Max(3) Integer weightMultiplier
    ) {}
}
