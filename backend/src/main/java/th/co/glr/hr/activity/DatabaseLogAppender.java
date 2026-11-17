package th.co.glr.hr.activity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Captures WARN and ERROR log events for hr.app_event. Never logs. Attached to the root logger
 * programmatically by AppEventWriter rather than through a logback-spring.xml. Attaching in code adds an
 * appender and changes nothing else. */
public class DatabaseLogAppender extends AppenderBase<ILoggingEvent> {

    static final String CORRELATION_ID_KEY = "correlationId";

    /** Message and exception text are truncated: this is an audit trail, not a log store. */
    private static final int MAX_MESSAGE = 4_000;
    private static final int MAX_EXCEPTION_MESSAGE = 1_000;

    private final AppEventBuffer buffer;

    public DatabaseLogAppender() {
        this(AppEventBuffer.shared());
    }

    /** Test seam: an isolated buffer nothing else in the JVM can touch. */
    DatabaseLogAppender(AppEventBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (event == null || !isCapturedLevel(event.getLevel())) {
                return;
            }
            buffer.offer(toAppEvent(event));
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

    /** The correlation id, or null if it cannot be read. Guarded separately rather than folded into the caller's
     * catch, because the id is the least valuable field on the row and losing the whole event to get it would be a
     * bad trade. */
    private static String correlationIdOf(ILoggingEvent event) {
        try {
            var mdc = event.getMDCPropertyMap();
            return mdc == null ? null : mdc.get(CORRELATION_ID_KEY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The first stack frame, and only the first. Enough to find the line that failed; not enough to reconstruct
     * the request. Storing whole traces here would put connection strings, tokens and employee data on a web page
     * — see V159. */
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
