package th.co.glr.hr.procurement;

import java.util.Set;

/** Deliberately a short, linear status (OPEN -> SHIPPING -> RECEIVED, or CANCELLED from either of the first
 * two) — NOT a duplicate of th.co.glr.hr.ticket.FulfilmentStatus's 5-value
 * IR_ISSUED/IR_SENT/SHIPPING/CUSTOMS_CLEARANCE/GOODS_RECEIVED sequence. */
public final class FactoryPurchaseOrderStatus {
    public static final String OPEN = "OPEN";
    public static final String SHIPPING = "SHIPPING";
    public static final String RECEIVED = "RECEIVED";
    public static final String CANCELLED = "CANCELLED";

    public static final Set<String> VALUES = Set.of(OPEN, SHIPPING, RECEIVED, CANCELLED);
    /** Terminal — neither shipping detail, receipt, nor cancellation may act on these again. */
    public static final Set<String> CLOSED = Set.of(RECEIVED, CANCELLED);

    private FactoryPurchaseOrderStatus() {}
}
