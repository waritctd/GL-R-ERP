package th.co.glr.hr.ticket;

import java.util.Set;

public final class FulfilmentStatus {
    public static final String IR_ISSUED = "IR_ISSUED";
    public static final String IR_SENT = "IR_SENT";
    public static final String PICKED_UP = "PICKED_UP";
    public static final String SHIPPING = "SHIPPING";
    public static final String CUSTOMS_CLEARANCE = "CUSTOMS_CLEARANCE";
    public static final String GOODS_RECEIVED = "GOODS_RECEIVED";
    public static final String FROM_STOCK = "FROM_STOCK";
    public static final String PARTIALLY_DELIVERED = "PARTIALLY_DELIVERED";
    public static final String FULLY_DELIVERED = "FULLY_DELIVERED";

    public static final Set<String> DELIVERY_COMPLETE = Set.of(GOODS_RECEIVED, FULLY_DELIVERED);

    private FulfilmentStatus() {}

    public static boolean isDeliveryComplete(String status) {
        return DELIVERY_COMPLETE.contains(status);
    }
}
