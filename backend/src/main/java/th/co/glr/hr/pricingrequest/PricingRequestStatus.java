package th.co.glr.hr.pricingrequest;

import java.util.Map;
import java.util.Set;

public final class PricingRequestStatus {
    public static final String DRAFT               = "DRAFT";
    public static final String SUBMITTED            = "SUBMITTED";
    public static final String IMPORT_REVIEWING      = "IMPORT_REVIEWING";
    public static final String MORE_INFO_REQUIRED    = "MORE_INFO_REQUIRED";
    public static final String CANCELLED             = "CANCELLED";

    /** Exactly the set the DB's chk_pricing_request_status constraint (V58) accepts. */
    public static final Set<String> VALUES = Set.of(
        DRAFT, SUBMITTED, IMPORT_REVIEWING, MORE_INFO_REQUIRED, CANCELLED);

    /**
     * Allowed forward/lateral transitions. DRAFT -> DRAFT is deliberately absent:
     * editing a draft's fields is a mutation guarded by WHERE status = 'DRAFT',
     * not a state transition, so it is never checked against this table.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
        DRAFT,               Set.of(SUBMITTED, CANCELLED),
        SUBMITTED,           Set.of(IMPORT_REVIEWING, CANCELLED),
        IMPORT_REVIEWING,    Set.of(MORE_INFO_REQUIRED),
        MORE_INFO_REQUIRED,  Set.of(IMPORT_REVIEWING, CANCELLED),
        CANCELLED,           Set.of());

    public static boolean canTransition(String from, String to) {
        return from != null && to != null
            && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isValid(String status) {
        return status != null && VALUES.contains(status);
    }

    private PricingRequestStatus() {}
}
