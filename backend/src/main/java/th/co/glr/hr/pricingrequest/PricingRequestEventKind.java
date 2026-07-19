package th.co.glr.hr.pricingrequest;

import java.util.Set;

public final class PricingRequestEventKind {
    public static final String PRICING_REQUEST_CREATED   = "PRICING_REQUEST_CREATED";
    public static final String PRICING_REQUEST_UPDATED    = "PRICING_REQUEST_UPDATED";
    public static final String PRICING_REQUEST_SUBMITTED  = "PRICING_REQUEST_SUBMITTED";
    public static final String PRICING_REQUEST_PICKED_UP  = "PRICING_REQUEST_PICKED_UP";
    public static final String MORE_INFO_REQUESTED        = "MORE_INFO_REQUESTED";
    public static final String MORE_INFO_RESPONDED        = "MORE_INFO_RESPONDED";
    public static final String PRICING_REQUEST_CANCELLED  = "PRICING_REQUEST_CANCELLED";

    public static final Set<String> VALUES = Set.of(
        PRICING_REQUEST_CREATED, PRICING_REQUEST_UPDATED, PRICING_REQUEST_SUBMITTED,
        PRICING_REQUEST_PICKED_UP, MORE_INFO_REQUESTED, MORE_INFO_RESPONDED,
        PRICING_REQUEST_CANCELLED);

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    private PricingRequestEventKind() {}
}
