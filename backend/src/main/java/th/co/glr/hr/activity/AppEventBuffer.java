package th.co.glr.hr.activity;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** A bounded hand-off between the logback appender and the database writer. A logback appender is constructed
 * by logback, not by Spring, so it cannot be given a DataSource. This class must never log. Instantiable, but
 * production code shares one instance. */
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
