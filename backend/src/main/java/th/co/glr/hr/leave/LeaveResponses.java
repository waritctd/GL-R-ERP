package th.co.glr.hr.leave;

import java.util.List;

public final class LeaveResponses {
    private LeaveResponses() {
    }

    public record LeaveListResponse(List<LeaveRequestDto> requests) {
    }

    public record LeaveDetailResponse(LeaveRequestDto request) {
    }

    public record LeaveEmployeeOptionsResponse(List<LeaveEmployeeOption> employees) {
    }

    public record LeaveBalancesResponse(List<LeaveBalanceDto> balances) {
    }

    public record LeaveTypesResponse(List<LeaveTypeDto> leaveTypes) {
    }
}
