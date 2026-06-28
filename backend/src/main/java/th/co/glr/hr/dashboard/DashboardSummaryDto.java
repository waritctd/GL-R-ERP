package th.co.glr.hr.dashboard;

public record DashboardSummaryDto(
    long totalOpen,
    long submitted,
    long inReview,
    long priceProposed,
    long approved,
    long quotationIssued,
    long closedThisMonth,
    long cancelledThisMonth,
    long overdueOver3Days
) {}
