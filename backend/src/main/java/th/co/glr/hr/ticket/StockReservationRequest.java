package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * A sales <strong>declaration</strong> of how much of each line can be supplied from stock —
 * <strong>not</strong> a reservation, despite the name.
 *
 * <p>Nothing is reserved and nothing is checked. There is no inventory system in this codebase to
 * reserve against: no stock ledger, no on-hand quantity, and no validation of {@code qtyFromStock}
 * against real availability. The only constraint applied is "no more than was ordered"
 * ({@code qty_from_stock >= 0 AND qty_from_stock <= qty}, a V-migration CHECK plus the same test in
 * {@code TicketService.reserveStock}), which is arithmetic on this deal and says nothing about a
 * warehouse.
 *
 * <p>The figure exists because it is a commission input — the {@code stockShare} half of the owning
 * rep's STOCK_BONUS ({@code CommissionRepository#sumActiveStockActualReceived}).
 *
 * <p>Inventory tracking is deliberately out of scope (owner ruling); do not "fix" this by building
 * a stock ledger, and do not assume anything has corroborated the number. The name is kept only
 * because it is on the API contract ({@code TicketController}) and mirrored in
 * {@code frontend/src/api/mockApi.js} under a contract test. See {@code TicketService#reserveStock}
 * for the full note, including the stage floor a declaration must clear.
 */
public record StockReservationRequest(
    @NotEmpty @Valid List<Line> lines
) {
    public record Line(
        @NotNull Long itemId,
        @NotNull @DecimalMin(value = "0.00") BigDecimal qtyFromStock,
        @Size(max = 2000) String note
    ) {}
}
