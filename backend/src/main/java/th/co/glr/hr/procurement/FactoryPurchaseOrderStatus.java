package th.co.glr.hr.procurement;

import java.util.Set;

/**
 * Deliberately a short, linear status (OPEN -&gt; SHIPPING -&gt; RECEIVED, or CANCELLED from either
 * of the first two) — NOT a duplicate of {@link th.co.glr.hr.ticket.FulfilmentStatus}'s 5-value
 * IR_ISSUED/IR_SENT/SHIPPING/CUSTOMS_CLEARANCE/GOODS_RECEIVED sequence. The PO's own {@code
 * customs_status} is a free-text field, not a status value, on purpose — see {@code
 * ProcurementService}'s class Javadoc ("do not over-engineer") for why this table does not
 * reimplement the ticket-level fulfillment machinery.
 */
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
