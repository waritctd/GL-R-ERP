package th.co.glr.hr.activity;

import java.time.OffsetDateTime;

/** Per-employee roll-up for a window: who was in the portal, how much, and when. */
public record ActivityLogSummaryDto(
    long employeeId,
    String employeeCode,
    String name,
    String email,
    long requestCount,
    OffsetDateTime firstSeen,
    OffsetDateTime lastSeen
) {
}
