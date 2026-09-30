package th.co.glr.hr.commission;

import java.math.BigDecimal;

/** One deal line on a pending commission card: what the sales manager weighs per item. */
public record PendingCommissionItemDto(
    long itemId,
    String description,
    BigDecimal qty,
    BigDecimal qtyFromStock,
    int weightMultiplier
) {}
