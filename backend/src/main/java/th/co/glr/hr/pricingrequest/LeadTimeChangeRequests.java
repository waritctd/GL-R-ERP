package th.co.glr.hr.pricingrequest;

import java.util.List;

/** CR-1 (GLA-167): request bodies for the lead-time change flow. */
public final class LeadTimeChangeRequests {
    private LeadTimeChangeRequests() {}

    /** One ticked line: the new min/max lead time (days) for one pricing-request item. */
    public record LineInput(Long pricingRequestItemId, Integer newMinDays, Integer newMaxDays) {}

    /** Used for both create and update (import edits while PENDING). */
    public record CreateLeadTimeChangeRequest(String reason, List<LineInput> lines) {}

    public record RejectLeadTimeChangeRequest(String reason) {}
}
