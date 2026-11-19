package th.co.glr.hr.activity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Pins what the log capture does and — more importantly — what it refuses to do. Uses its own AppEventBuffer,
 * never AppEventBuffer#shared(). Two of these are safety properties rather than features. A private instance
 * makes every assertion here independent of what else is running in the JVM. */
class AppEventCaptureTest {

    private AppEventBuffer buffer;
    private DatabaseLogAppender appender;

    @BeforeEach
    void setUp() {
        buffer = new AppEventBuffer();
        appender = new DatabaseLogAppender(buffer);
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
    }

    @Test
    void capturesWarnAndError() {
        appender.doAppend(event(Level.WARN, "something looked wrong", null));
        appender.doAppend(event(Level.ERROR, "something broke", null));

        List<AppEvent> captured = drain();
        assertThat(captured).extracting(AppEvent::level).containsExactly("WARN", "ERROR");
        assertThat(captured).extracting(AppEvent::kind).containsOnly(AppEvent.KIND_LOG);
        assertThat(captured).extracting(AppEvent::message)
            .containsExactly("something looked wrong", "something broke");
    }

    /** Built through a REAL logger from the real context, not new LoggingEvent(). A hand-constructed event has a
     * null logger context, and getMDCPropertyMap() resolves the MDC adapter through it — so the bare constructor
     * throws inside the appender and every assertion here silently sees an empty buffer. */
    private LoggingEvent event(Level level, String message, Throwable thrown) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger("th.co.glr.hr.Example");
        LoggingEvent logged = new LoggingEvent(
            ch.qos.logback.classic.Logger.FQCN, logger, level, message, thrown, null);
        logged.setThreadName("test-thread");
        return logged;
    }

    private List<AppEvent> drain() {
        List<AppEvent> sink = new ArrayList<>();
        buffer.drainTo(sink, 10_000);
        return sink;
    }
}
