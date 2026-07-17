package th.co.glr.hr.ticket;

import java.util.Set;

public final class EntryChannel {
    public static final String DESIGNER_LED = "DESIGNER_LED";
    public static final String OWNER_DIRECT = "OWNER_DIRECT";
    public static final String BUYER_DIRECT = "BUYER_DIRECT";

    public static final Set<String> VALID = Set.of(DESIGNER_LED, OWNER_DIRECT, BUYER_DIRECT);

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }

    private EntryChannel() {}
}
