package th.co.glr.hr.dashboard;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;

public record DashboardSummaryDto(
    String role,
    Long employeeId,
    Long divisionId,
    boolean manager,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    OffsetDateTime generatedAt,
    HeadcountSummaryDto headcount,
    PendingApprovalsSummaryDto pendingApprovals,
    AttendanceSummaryDto attendance,
    Long latestPayrollPeriodId,
    TicketSummaryDto tickets,
    NotificationSummaryDto notifications,
    long totalOpen,
    long submitted,
    long inReview,
    long priceProposed,
    long approved,
    long quotationIssued,
    long closedThisMonth,
    long cancelledThisMonth,
    long overdueOver3Days
) {
    public static DashboardSummaryDto of(
        String role,
        Long employeeId,
        Long divisionId,
        boolean manager,
        OffsetDateTime generatedAt,
        HeadcountSummaryDto headcount,
        PendingApprovalsSummaryDto pendingApprovals,
        AttendanceSummaryDto attendance,
        Long latestPayrollPeriodId,
        TicketSummaryDto tickets,
        NotificationSummaryDto notifications
    ) {
        return new DashboardSummaryDto(
            role,
            employeeId,
            divisionId,
            manager,
            generatedAt,
            headcount,
            pendingApprovals,
            attendance,
            latestPayrollPeriodId,
            tickets,
            notifications,
            tickets.totalOpen(),
            tickets.submitted(),
            tickets.inReview(),
            tickets.priceProposed(),
            tickets.approved(),
            tickets.quotationIssued(),
            tickets.closedThisMonth(),
            tickets.cancelledThisMonth(),
            tickets.overdueOver3Days()
        );
    }
}
