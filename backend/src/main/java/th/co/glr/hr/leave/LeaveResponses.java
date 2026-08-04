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

    public record LeaveContactDefaultsResponse(LeaveContactDefaultsDto contactDefaults) {
    }

    /** POST /api/leave/preview (Phase A0b dry-run) -- see {@link LeaveService#preview}. */
    public record LeavePreviewResponse(LeavePreviewDto preview) {
    }

    /** GET /api/leave/review-summary (Phase A0b) -- see {@link LeaveService#reviewSummary}. */
    public record LeaveReviewSummaryResponse(LeaveReviewSummaryDto reviewSummary) {
    }

    /**
     * GET /api/leave/calendar-context -- see {@link LeaveCalendarContextService}. A new record
     * (not reusing {@code HolidaysResponse}/{@code WorkSchedulesResponse} from
     * attendance/schedule/) because this wraps one combined DTO, not a bare list.
     */
    public record LeaveCalendarContextResponse(LeaveCalendarContextDto calendarContext) {
    }
}
