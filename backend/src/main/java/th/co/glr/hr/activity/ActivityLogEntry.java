package th.co.glr.hr.activity;

import java.time.OffsetDateTime;

/** One captured request, queued by {@link ActivityLogFilter} for the writer thread. */
public record ActivityLogEntry(
    Long employeeId,
    String actorEmail,
    String method,
    String path,
    int status,
    Integer durationMs,
    OffsetDateTime at
) {
}
