package th.co.glr.hr.activity;

import java.time.OffsetDateTime;

/**
 * One application event bound for {@code hr.app_event} — a WARN/ERROR log line, or one execution
 * of a {@code @Scheduled} worker.
 *
 * <p>{@code firstFrame} is deliberately one frame rather than a stack trace. See V158: this ends up
 * on a web page, and a whole trace is where a connection string or an employee's data leaks.
 */
public record AppEvent(
    OffsetDateTime at,
    String kind,              // LOG | JOB
    String level,             // WARN | ERROR | INFO
    String logger,
    String message,
    String exceptionType,
    String exceptionMessage,
    String firstFrame,
    String correlationId,
    String thread,
    Integer durationMs
) {
    public static final String KIND_LOG = "LOG";
    public static final String KIND_JOB = "JOB";
}
