package th.co.glr.hr.dashboard;

public record TicketSummaryDto(
    String scope,
    long draft,
    long submitted,
    long inReview,
    long priceProposed,
    long approved,
    long quotationIssued,
    long documentIssued,
    long closed,
    long cancelled,
    long total,
    long totalOpen,
    long closedThisMonth,
    long cancelledThisMonth,
    long overdueOver3Days
) {
    public static TicketSummaryDto empty(String scope) {
        return new TicketSummaryDto(scope, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
