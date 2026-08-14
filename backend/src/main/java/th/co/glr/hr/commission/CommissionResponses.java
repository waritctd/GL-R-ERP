package th.co.glr.hr.commission;

import java.util.List;

public final class CommissionResponses {
    private CommissionResponses() {
    }

    public record CommissionListResponse(List<CommissionRecord> commissions) {}
    public record CommissionDetailResponse(CommissionRecord commission) {}
    public record CommissionSimulationResponse(CommissionSimulationDto simulation) {}
    public record PayrollSummaryResponse(PayrollCommissionSummaryDto summary) {}
    public record CommissionMonthlySummaryResponse(CommissionMonthlySummaryDto summary) {}
    // Issue #737 — the manual-commission rep picker. See CommissionController#reps.
    public record CommissionRepOptionsResponse(List<CommissionRepOptionDto> reps) {}
}
