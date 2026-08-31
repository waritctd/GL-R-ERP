package th.co.glr.hr.activity;

import java.time.OffsetDateTime;

/** One {@code hr.app_event} row as the admin page renders it. */
public record AppEventDto(
    long id,
    OffsetDateTime at,
    String kind,
    String level,
    String logger,
    String message,
    String exceptionType,
    String exceptionMessage,
    String firstFrame,
    String correlationId,
    Integer durationMs
) {
}
