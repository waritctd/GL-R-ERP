package th.co.glr.hr.dashboard;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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
    long overdueOver3Days,
    long onHold,
    long dormant,
    long paymentOverdue,
    long partiallyDelivered,
    /** Payment receipts dated this Bangkok month (deposit + balance - adjustment); null unless account/ceo/sales_manager. */
    BigDecimal cashReceivedThisMonth,
    /** This month's net cash split per deal creator (sums to cashReceivedThisMonth); null unless account/ceo/sales_manager. */
    List<CashByRepDto> cashReceivedByRep
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
        return of(role, employeeId, divisionId, manager, generatedAt, headcount, pendingApprovals,
            attendance, latestPayrollPeriodId, tickets, notifications, null, null);
    }

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
        NotificationSummaryDto notifications,
        BigDecimal cashReceivedThisMonth
    ) {
        return of(role, employeeId, divisionId, manager, generatedAt, headcount, pendingApprovals,
            attendance, latestPayrollPeriodId, tickets, notifications, cashReceivedThisMonth, null);
    }

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
        NotificationSummaryDto notifications,
        BigDecimal cashReceivedThisMonth,
        List<CashByRepDto> cashReceivedByRep
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
            tickets.overdueOver3Days(),
            tickets.onHold(),
            tickets.dormant(),
            tickets.paymentOverdue(),
            tickets.partiallyDelivered(),
            cashReceivedThisMonth,
            cashReceivedByRep
        );
    }
}
