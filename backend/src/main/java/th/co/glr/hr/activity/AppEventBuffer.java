package th.co.glr.hr.activity;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded hand-off between the logback appender and the database writer.
 *
 * <p><strong>Static, and deliberately so.</strong> A logback appender is constructed by logback,
 * not by Spring, so it cannot be given a {@code DataSource}. Rather than reaching into the
 * application context from inside the logging framework — which inverts the dependency and breaks
 * during startup and shutdown, exactly when logs matter most — the appender only ever pushes onto
 * this queue, and a Spring bean drains it.
 *
 * <p><strong>This class must never log.</strong> Not through slf4j, not through logback, not at
 * all. It sits underneath the logging framework: a log call from here would be appended, which
 * would push onto this queue, which is a feedback loop that a database outage would turn into a
 * spin. Failures are counted and reported to whoever drains the queue instead.
 *
 * <p>Offers are non-blocking and drop when full. Losing an event is strictly better than blocking
 * an application thread inside a log statement.
 */
public final class AppEventBuffer {

    private static final int CAPACITY = 5_000;

    private static final BlockingQueue<AppEvent> QUEUE = new ArrayBlockingQueue<>(CAPACITY);
    private static final AtomicLong DROPPED = new AtomicLong();

    private AppEventBuffer() {
    }

    /** Never blocks, never throws. Returns false when the event was dropped. */
    public static boolean offer(AppEvent event) {
        if (event == null) {
            return false;
        }
        if (!QUEUE.offer(event)) {
            DROPPED.incrementAndGet();
            return false;
        }
        return true;
    }

    /** Moves up to {@code max} events into {@code sink}; returns how many. */
    public static int drainTo(Collection<? super AppEvent> sink, int max) {
        return QUEUE.drainTo(sink, max);
    }

    /** Blocks up to {@code timeoutMs} for one event; null when none arrives. */
    public static AppEvent poll(long timeoutMs) throws InterruptedException {
        return QUEUE.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static long droppedCount() {
        return DROPPED.get();
    }

    /** Test seam only — the queue is static, so a test must be able to start from empty. */
    static void resetForTest() {
        QUEUE.clear();
        DROPPED.set(0);
    }
}
