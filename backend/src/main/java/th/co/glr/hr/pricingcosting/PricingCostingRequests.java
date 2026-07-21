package th.co.glr.hr.pricingcosting;

public final class PricingCostingRequests {
    private PricingCostingRequests() {}

    public record CreateCostingRequest(String note, String clientRequestId) {}
    public record RecalculateCostingRequest(String note) {}
    public record SubmitCostingRequest(String note) {}
}
