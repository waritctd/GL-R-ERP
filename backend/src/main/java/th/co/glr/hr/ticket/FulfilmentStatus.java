package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Set;

public final class FulfilmentStatus {
    public static final String IR_ISSUED = "IR_ISSUED";
    public static final String IR_SENT = "IR_SENT";
    public static final String SHIPPING = "SHIPPING";
    public static final String GOODS_RECEIVED = "GOODS_RECEIVED";
    public static final String FROM_STOCK = "FROM_STOCK";
    public static final String PARTIALLY_DELIVERED = "PARTIALLY_DELIVERED";
    public static final String FULLY_DELIVERED = "FULLY_DELIVERED";

    public static final Set<String> DELIVERY_COMPLETE = Set.of(GOODS_RECEIVED, FULLY_DELIVERED);

    /**
     * The import axis, in progression order — exactly these four values, no more. Used by the
     * factory-PO rollup ({@code TicketRepository#deriveImportStatus}, {@code
     * TicketService#applyPurchaseOrderRollup}) to tell "further along" from "further behind" and
     * to tell the import axis apart from the delivery axis below. Do not add a value here unless
     * it is a real, live step of that sequence: any entry in this list is assigned a position by
     * {@link #importRank}, and a value that is not a genuine sequence step would be given a
     * misleading rank.
     */
    public static final List<String> IMPORT_SEQUENCE = List.of(IR_ISSUED, IR_SENT, SHIPPING, GOODS_RECEIVED);

    private FulfilmentStatus() {}

    public static boolean isDeliveryComplete(String status) {
        return DELIVERY_COMPLETE.contains(status);
    }

    /** True when {@code status} is one of {@link #IMPORT_SEQUENCE}. Null-safe: false for null. */
    public static boolean isImportAxis(String status) {
        return status != null && IMPORT_SEQUENCE.contains(status);
    }

    /**
     * Index of {@code status} within {@link #IMPORT_SEQUENCE}, or {@code -1} when it is not on the
     * import axis at all — this covers every delivery-axis value ({@link #FROM_STOCK}, {@link
     * #PARTIALLY_DELIVERED}, {@link #FULLY_DELIVERED}) and {@code null}. Callers MUST treat -1 as
     * "do not touch", never as "earliest" — a naive {@code importRank(target) > importRank(current)}
     * comparison would otherwise let a PO rollup treat a delivery-axis status as behind IR_ISSUED
     * and overwrite it, which is exactly what the write-through firewall in {@code
     * TicketService#applyPurchaseOrderRollup} exists to prevent.
     */
    public static int importRank(String status) {
        return status == null ? -1 : IMPORT_SEQUENCE.indexOf(status);
    }
}
