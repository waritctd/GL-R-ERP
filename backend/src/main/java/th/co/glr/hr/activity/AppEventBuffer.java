package th.co.glr.hr.activity;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded hand-off between the logback appender and the database writer.
 *
 * <p><strong>Instantiable, but production code shares one instance.</strong> A logback appender is
 * constructed by logback, not by Spring, so it cannot be given a {@code DataSource}. Rather than
 * reaching into the application context from inside the logging framework — which inverts the
 * dependency and breaks during startup and shutdown, exactly when logs matter most — the appender
 * only ever pushes onto a buffer, and a Spring bean drains it. {@link #shared()} is the single
 * instance {@link DatabaseLogAppender} and {@link AppEventWriter} both use by default, so production
 * behaviour is exactly what it was when this class was a bag of static methods over one static
 * queue.
 *
 * <p>The instance form exists for tests: a JVM-wide singleton means any two tests that each boot a
 * {@code DatabaseLogAppender} — one via a full Spring context, one directly — fight over the same
 * queue for the rest of the surefire fork's life, with no way for either to isolate itself. A test
 * that needs its own buffer constructs {@code new AppEventBuffer()} and injects it.
 *
 * <p><strong>This class must never log.</strong> Not through slf4j, not through logback, not at
 * all. It sits underneath the logging framework: a log call from here would be appended, which
 * would push onto a buffer, which is a feedback loop that a database outage would turn into a spin.
 * Failures are counted and reported to whoever drains the queue instead.
 *
 * <p>Offers are non-blocking and drop when full. Losing an event is strictly better than blocking
 * an application thread inside a log statement.
 */
public final class AppEventBuffer {

    private static final int CAPACITY = 5_000;

    private static final AppEventBuffer SHARED = new AppEventBuffer();

    private final BlockingQueue<AppEvent> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    /** The JVM-wide instance production code feeds and drains. */
    public static AppEventBuffer shared() {
        return SHARED;
    }

    /** Never blocks, never throws. Returns false when the event was dropped. */
    public boolean offer(AppEvent event) {
        if (event == null) {
            return false;
        }
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /** Moves up to {@code max} events into {@code sink}; returns how many. */
    public int drainTo(Collection<? super AppEvent> sink, int max) {
        return queue.drainTo(sink, max);
    }

    /** Blocks up to {@code timeoutMs} for one event; null when none arrives. */
    public AppEvent poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public long droppedCount() {
        return dropped.get();
    }
}
