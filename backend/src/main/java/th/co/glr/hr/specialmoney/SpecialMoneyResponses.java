package th.co.glr.hr.specialmoney;

import java.util.List;

public final class SpecialMoneyResponses {
    private SpecialMoneyResponses() {
    }

    public record SpecialMoneyListResponse(List<SpecialMoneyRequestDto> requests) {
    }

    public record SpecialMoneyDetailResponse(SpecialMoneyRequestDto request) {
    }

    public record SpecialMoneyUsageResponse(SpecialMoneyUsageDto usage) {
    }

    public record SpecialMoneyTypesResponse(List<SpecialMoneyTypeOption> types) {
    }

    public record SpecialMoneyEmployeeOptionsResponse(List<SpecialMoneyEmployeeOption> employees) {
    }
}
