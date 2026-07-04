package th.co.glr.hr.dashboard;

public record PendingApprovalsSummaryDto(
    String scope,
    long profileRequests,
    long overtime,
    long leave,
    long commissions,
    long tickets,
    long total
) {
    public static PendingApprovalsSummaryDto of(
        String scope,
        long profileRequests,
        long overtime,
        long leave,
        long commissions,
        long tickets
    ) {
        return new PendingApprovalsSummaryDto(
            scope,
            profileRequests,
            overtime,
            leave,
            commissions,
            tickets,
            profileRequests + overtime + leave + commissions + tickets
        );
    }
}
