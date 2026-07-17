package th.co.glr.hr.ticket;

import java.util.Set;

/**
 * Deal lifecycle is separate from sales_stage. ON_HOLD and DORMANT are reopenable
 * pauses, CLOSED_LOST carries lost_reason/lost_at, and COMPLETED is reached only
 * through the close() ticket flow.
 */
public final class DealLifecycle {
    public static final String ACTIVE      = "ACTIVE";
    public static final String ON_HOLD     = "ON_HOLD";
    public static final String DORMANT     = "DORMANT";
    public static final String CLOSED_LOST = "CLOSED_LOST";
    public static final String CANCELLED   = "CANCELLED";
    public static final String COMPLETED   = "COMPLETED";

    public static final Set<String> VALID = Set.of(
        ACTIVE, ON_HOLD, DORMANT, CLOSED_LOST, CANCELLED, COMPLETED);

    public static boolean isValid(String value) {
        return value != null && VALID.contains(value);
    }

    private DealLifecycle() {}
}
