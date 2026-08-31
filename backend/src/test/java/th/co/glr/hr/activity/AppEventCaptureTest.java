package th.co.glr.hr.activity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins what the log capture does and — more importantly — what it refuses to do.
 *
 * <p>Two of these are safety properties rather than features. The level filter and the single-frame
 * rule are what keep {@code hr.app_event} from becoming a full log mirror on a web page; V159 says
 * so in the schema and this says so in a test that fails if someone widens either.
 */
class AppEventCaptureTest {

    private DatabaseLogAppender appender;

    @BeforeEach
    void setUp() {
        AppEventBuffer.resetForTest();
        appender = new DatabaseLogAppender();
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

    @Test
    void ignoresInfoAndBelow() {
        // The volume decision, pinned. INFO is 36 of this codebase's 76 log statements and is
        // routine chatter; capturing it would multiply the table for no diagnostic gain.
        appender.doAppend(event(Level.INFO, "routine", null));
        appender.doAppend(event(Level.DEBUG, "noisy", null));
        appender.doAppend(event(Level.TRACE, "very noisy", null));

        assertThat(drain()).isEmpty();
    }

    @Test
    void storesOneStackFrameAndNeverTheWholeTrace() {
        // The data-exposure guard. A full trace is where a connection string, a token or an
        // employee's data reaches a page that a human reads in a browser.
        RuntimeException failure = new IllegalStateException("connection to db.internal failed");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("th.co.glr.hr.Deep", "inner", "Deep.java", 10),
            new StackTraceElement("th.co.glr.hr.Outer", "outer", "Outer.java", 20),
            new StackTraceElement("th.co.glr.hr.Top", "top", "Top.java", 30),
        });

        appender.doAppend(event(Level.ERROR, "boom", failure));

        AppEvent captured = drain().get(0);
        assertThat(captured.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(captured.exceptionMessage()).isEqualTo("connection to db.internal failed");
        assertThat(captured.firstFrame()).contains("Deep.java:10");
        // The frames below the first must not be anywhere in the row.
        assertThat(captured.firstFrame()).doesNotContain("Outer.java", "Top.java");
    }

    @Test
    void carriesTheCorrelationIdSoAnEventCanBeTiedToItsRequest() {
        LoggingEvent withMdc = event(Level.ERROR, "failed mid-request", null);
        withMdc.setMDCPropertyMap(Map.of(DatabaseLogAppender.CORRELATION_ID_KEY, "abc-123"));

        appender.doAppend(withMdc);

        assertThat(drain().get(0).correlationId()).isEqualTo("abc-123");
    }

    @Test
    void leavesTheCorrelationIdNullWhenThereIsNoRequest() {
        // Boot-time and scheduler-thread events have no request, and must not borrow one.
        appender.doAppend(event(Level.WARN, "started up oddly", null));

        assertThat(drain().get(0).correlationId()).isNull();
    }

    @Test
    void dropsRatherThanGrowsWithoutBound() {
        // A database outage must not turn the log pipeline into a memory leak.
        for (int i = 0; i < 6_000; i++) {
            appender.doAppend(event(Level.WARN, "flood " + i, null));
        }

        List<AppEvent> captured = drain();
        assertThat(captured).hasSizeLessThanOrEqualTo(5_000);
        assertThat(AppEventBuffer.droppedCount()).isPositive();
    }

    @Test
    void namesAScheduledJobByItsMethodNotItsLambdaIdentity() {
        // A lambda's toString() is a synthetic identity that changes between builds, which would
        // make job history impossible to group by worker.
        Runnable plain = () -> { };
        assertThat(ScheduledJobEventConfig.describe(plain)).doesNotContain("$$Lambda");
        assertThat(ScheduledJobEventConfig.describe(null)).isEqualTo("unknown");
    }

    /**
     * Built through a REAL logger from the real context, not {@code new LoggingEvent()}.
     *
     * <p>A hand-constructed event has a null logger context, and {@code getMDCPropertyMap()}
     * resolves the MDC adapter through it — so the bare constructor throws inside the appender and
     * every assertion here silently sees an empty buffer. The first draft of this test did exactly
     * that: `ignoresInfoAndBelow` passed against an empty queue and proved nothing at all.
     */
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
        AppEventBuffer.drainTo(sink, 10_000);
        return sink;
    }
}
