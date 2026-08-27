package th.co.glr.hr.customerquotation;

public final class DiscountApprovalRequests {
    private DiscountApprovalRequests() {}

    /**
     * {@code reason} is mandatory — checked in {@link DiscountApprovalService#reject}, not via a
     * bean-validation annotation, mirroring {@code PricingDecisionRequests.ReturnPricingDecisionRequest}'s
     * own manual check (and its Thai-language 400 message) rather than
     * {@code PricingDecisionRequests.CostOverrideRequest}'s annotated style — this codebase uses
     * both, and the mandatory-reason-on-a-CEO-decision shape is the returnReason one.
     */
    public record RejectDiscountApprovalRequest(String reason) {}
}
