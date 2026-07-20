package th.co.glr.hr.pricingrequest;

import java.util.Map;
import java.util.Set;

public final class PricingRequestStatus {
    public static final String DRAFT               = "DRAFT";
    public static final String SUBMITTED            = "SUBMITTED";
    public static final String IMPORT_REVIEWING      = "IMPORT_REVIEWING";
    public static final String AWAITING_FACTORY_RESPONSE = "AWAITING_FACTORY_RESPONSE";
    public static final String COSTING_IN_PROGRESS  = "COSTING_IN_PROGRESS";
    public static final String READY_FOR_CEO_REVIEW = "READY_FOR_CEO_REVIEW";
    public static final String MORE_INFO_REQUIRED    = "MORE_INFO_REQUIRED";
    public static final String CANCELLED             = "CANCELLED";
    public static final String SUPERSEDED            = "SUPERSEDED";

    /** Exactly the set the DB's chk_pricing_request_status constraint (V59+V61) accepts. */
    public static final Set<String> VALUES = Set.of(
        DRAFT, SUBMITTED, IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE, COSTING_IN_PROGRESS,
        READY_FOR_CEO_REVIEW, MORE_INFO_REQUIRED, CANCELLED, SUPERSEDED);

    /**
     * Allowed forward/lateral transitions. DRAFT -> DRAFT is deliberately absent:
     * editing a draft's fields is a mutation guarded by WHERE status = 'DRAFT',
     * not a state transition, so it is never checked against this table.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
        DRAFT,               Set.of(SUBMITTED, CANCELLED),
        SUBMITTED,           Set.of(IMPORT_REVIEWING, CANCELLED),
        IMPORT_REVIEWING,    Set.of(AWAITING_FACTORY_RESPONSE, COSTING_IN_PROGRESS, MORE_INFO_REQUIRED, CANCELLED, SUPERSEDED),
        AWAITING_FACTORY_RESPONSE, Set.of(COSTING_IN_PROGRESS, MORE_INFO_REQUIRED, CANCELLED, SUPERSEDED),
        COSTING_IN_PROGRESS, Set.of(AWAITING_FACTORY_RESPONSE, READY_FOR_CEO_REVIEW, MORE_INFO_REQUIRED, CANCELLED, SUPERSEDED),
        MORE_INFO_REQUIRED,  Set.of(IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE, COSTING_IN_PROGRESS, CANCELLED),
        // Costing v2 path (review remediation COMMIT 5): a factory revising its quote after
        // CEO review has already started (FactoryQuoteService.receive's revision branch), or
        // Import opening a brand-new costing draft against an already-submitted request
        // (PricingCostingService.createDraft, gated on COSTING_CREATE_STATUS including
        // READY_FOR_CEO_REVIEW), both reopen costing rather than only allowing SUPERSEDED.
        READY_FOR_CEO_REVIEW, Set.of(COSTING_IN_PROGRESS, SUPERSEDED),
        SUPERSEDED,          Set.of(),
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
