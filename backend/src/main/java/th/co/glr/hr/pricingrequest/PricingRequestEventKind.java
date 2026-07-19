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
    public static final String FACTORY_EMAIL_READY        = "FACTORY_EMAIL_READY";
    public static final String FACTORY_EMAIL_SENT         = "FACTORY_EMAIL_SENT";
    public static final String FACTORY_RESPONSE_RECEIVED  = "FACTORY_RESPONSE_RECEIVED";
    public static final String FACTORY_NEGOTIATION_STARTED = "FACTORY_NEGOTIATION_STARTED";
    public static final String FACTORY_RESPONSE_READY_FOR_COSTING = "FACTORY_RESPONSE_READY_FOR_COSTING";
    public static final String FACTORY_RESPONSE_REVISED   = "FACTORY_RESPONSE_REVISED";
    public static final String FACTORY_NOT_AVAILABLE      = "FACTORY_NOT_AVAILABLE";
    public static final String PRICING_COSTING_STARTED    = "PRICING_COSTING_STARTED";
    public static final String PRICING_COSTING_CALCULATED = "PRICING_COSTING_CALCULATED";
    public static final String PRICING_COSTING_SUBMITTED  = "PRICING_COSTING_SUBMITTED";

    public static final Set<String> VALUES = Set.of(
        PRICING_REQUEST_CREATED, PRICING_REQUEST_UPDATED, PRICING_REQUEST_SUBMITTED,
        PRICING_REQUEST_PICKED_UP, MORE_INFO_REQUESTED, MORE_INFO_RESPONDED,
        PRICING_REQUEST_CANCELLED, FACTORY_EMAIL_READY, FACTORY_EMAIL_SENT,
        FACTORY_RESPONSE_RECEIVED, FACTORY_NEGOTIATION_STARTED,
        FACTORY_RESPONSE_READY_FOR_COSTING, FACTORY_RESPONSE_REVISED,
        FACTORY_NOT_AVAILABLE, PRICING_COSTING_STARTED, PRICING_COSTING_CALCULATED,
        PRICING_COSTING_SUBMITTED);

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    private PricingRequestEventKind() {}
}
