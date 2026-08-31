package th.co.glr.hr.activity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Captures WARN and ERROR log events for {@code hr.app_event}.
 *
 * <p>Attached to the root logger programmatically by {@link AppEventWriter} rather than through a
 * {@code logback-spring.xml}. That is a safety choice: this project has no logback config file, so
 * introducing one to add an appender would put every existing console log line at the mercy of
 * getting that file right. Attaching in code adds an appender and changes nothing else.
 *
 * <p><strong>Never logs.</strong> Failures go to logback's own status manager via
 * {@link #addError}, which does not re-enter the logging pipeline. A {@code log.warn} here would be
 * appended by this appender, and a database outage would turn that into a spin.
 */
public class DatabaseLogAppender extends AppenderBase<ILoggingEvent> {

    static final String CORRELATION_ID_KEY = "correlationId";

    /** Message and exception text are truncated: this is an audit trail, not a log store. */
    private static final int MAX_MESSAGE = 4_000;
    private static final int MAX_EXCEPTION_MESSAGE = 1_000;

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (event == null || !isCapturedLevel(event.getLevel())) {
                return;
            }
            AppEventBuffer.offer(toAppEvent(event));
        } catch (RuntimeException e) {
            // addError, never a log call — see the class javadoc.
            addError("Could not capture a log event for hr.app_event", e);
        }
    }

    private static boolean isCapturedLevel(Level level) {
        return level != null && level.isGreaterOrEqual(Level.WARN);
    }

    private static AppEvent toAppEvent(ILoggingEvent event) {
        IThrowableProxy thrown = event.getThrowableProxy();
        return new AppEvent(
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), ZoneOffset.UTC),
            AppEvent.KIND_LOG,
            event.getLevel().toString(),
            event.getLoggerName(),
            truncate(event.getFormattedMessage(), MAX_MESSAGE),
            thrown == null ? null : thrown.getClassName(),
            thrown == null ? null : truncate(thrown.getMessage(), MAX_EXCEPTION_MESSAGE),
            firstFrameOf(thrown),
            correlationIdOf(event),
            event.getThreadName(),
            null);
    }

    /**
     * The correlation id, or null if it cannot be read.
     *
     * <p>Guarded separately rather than folded into the caller's catch, because the id is the
     * least valuable field on the row and losing the whole event to get it would be a bad trade.
     * {@code getMDCPropertyMap()} resolves the MDC adapter lazily through the event's logger
     * context, so it throws on any event that does not have one.
     */
    private static String correlationIdOf(ILoggingEvent event) {
        try {
            var mdc = event.getMDCPropertyMap();
            return mdc == null ? null : mdc.get(CORRELATION_ID_KEY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The first stack frame, and only the first.
     *
     * <p>Enough to find the line that failed; not enough to reconstruct the request. Storing whole
     * traces here would put connection strings, tokens and employee data on a web page — see V159.
     */
    private static String firstFrameOf(IThrowableProxy thrown) {
        if (thrown == null) {
            return null;
        }
        StackTraceElementProxy[] frames = thrown.getStackTraceElementProxyArray();
        if (frames == null || frames.length == 0) {
            return null;
        }
        return truncate(frames[0].getStackTraceElement().toString(), 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
