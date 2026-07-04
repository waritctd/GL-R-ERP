package th.co.glr.hr.dashboard;

public record NotificationSummaryDto(
    long unread,
    long read,
    long total
) {
    public static NotificationSummaryDto empty() {
        return new NotificationSummaryDto(0, 0, 0);
    }
}
