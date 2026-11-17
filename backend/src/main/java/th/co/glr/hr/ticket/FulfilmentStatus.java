package th.co.glr.hr.ticket;

import java.util.List;

public final class FulfilmentStatus {
    public static final String IR_ISSUED = "IR_ISSUED";
    public static final String IR_SENT = "IR_SENT";
    public static final String SHIPPING = "SHIPPING";
    public static final String GOODS_RECEIVED = "GOODS_RECEIVED";
    public static final String FROM_STOCK = "FROM_STOCK";
    public static final String PARTIALLY_DELIVERED = "PARTIALLY_DELIVERED";
    public static final String FULLY_DELIVERED = "FULLY_DELIVERED";

    // There is deliberately no DELIVERY_COMPLETE set and no isDeliveryComplete() here. A public helper still
    // encoding the abandoned rule was a loaded gun for the next caller, not merely litter. "Delivered" means
    // FULLY_DELIVERED.

    /** The import axis, in progression order — exactly these four values, no more. Do not add a value here unless
     * it is a real, live step of that sequence: any entry in this list is assigned a position by #importRank, and
     * a value that is not a genuine sequence step would be given a misleading rank. */
    public static final List<String> IMPORT_SEQUENCE = List.of(IR_ISSUED, IR_SENT, SHIPPING, GOODS_RECEIVED);

    private FulfilmentStatus() {}

    /** True when {@code status} is one of {@link #IMPORT_SEQUENCE}. Null-safe: false for null. */
    public static boolean isImportAxis(String status) {
        return status != null && IMPORT_SEQUENCE.contains(status);
    }

    /** Index of status within #IMPORT_SEQUENCE, or -1 when it is not on the import axis at all — this covers every
     * delivery-axis value (#FROM_STOCK, #PARTIALLY_DELIVERED, #FULLY_DELIVERED) and null. */
    public static int importRank(String status) {
        return status == null ? -1 : IMPORT_SEQUENCE.indexOf(status);
    }
}
