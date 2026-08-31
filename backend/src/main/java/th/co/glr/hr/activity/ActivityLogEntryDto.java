package th.co.glr.hr.activity;

import java.time.OffsetDateTime;

/** One activity row as the admin page renders it. */
public record ActivityLogEntryDto(
    long id,
    Long employeeId,
    String employeeCode,
    String name,
    String email,
    String method,
    String path,
    int status,
    Integer durationMs,
    OffsetDateTime at
) {
}
