package th.co.glr.hr.pricingrequest;

import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;

public final class PricingRequestResponses {
    private PricingRequestResponses() {}

    public record PricingRequestDetailResponse(PricingRequestDetailDto pricingRequest) {}
}
