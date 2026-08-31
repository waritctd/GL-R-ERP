package th.co.glr.hr.activity;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Buffers captured requests and writes them on a single background thread.
 *
 * <p><strong>Why not just INSERT in the filter.</strong> This log sits in front of <em>every</em>
 * {@code /api/} request, and the production database is Supabase — a network hop away from Render,
 * not a local socket. A synchronous insert would add that round trip to every call the portal
 * makes, so opening one page would pay it several times over. The write is therefore moved off the
 * request thread entirely: the filter's only cost is an {@code offer} onto a bounded queue.
 *
 * <p><strong>Deliberately lossy under pressure.</strong> The queue is bounded and {@code offer}
 * returns false rather than blocking when it is full, so a slow or unavailable database can never
 * back-pressure into request handling and stall the portal. Drops are counted and logged rather
 * than silently swallowed — an activity log that quietly loses rows while claiming completeness
 * would be worse than none, so the count is the signal that the buffer needs raising. This is the
 * same trade made in {@code AuthService.recordLogin}: observability must never take the product
 * down with it.
 */
@Component
public class ActivityLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogRecorder.class);
    private static final int BATCH_SIZE = 100;
    private static final long POLL_TIMEOUT_MS = 500;

    private final ActivityLogRepository repository;
    private final BlockingQueue<ActivityLogEntry> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean running = true;

    public ActivityLogRecorder(ActivityLogRepository repository,
                               @Value("${app.activity-log.queue-capacity:10000}") int queueCapacity) {
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.writer = new Thread(this::drainForever, "activity-log-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /** Never blocks and never throws; returns false when the entry was dropped. */
    public boolean record(ActivityLogEntry entry) {
        if (!queue.offer(entry)) {
            long total = dropped.incrementAndGet();
            // Every 1000th drop, so a sustained outage does not itself become a log flood.
            if (total % 1000 == 1) {
                log.warn("Activity log buffer full; dropped {} entries so far", total);
            }
            return false;
        }
        return true;
    }

    /** Entries discarded because the buffer was full. Exposed for tests and diagnostics. */
    public long droppedCount() {
        return dropped.get();
    }

    private void drainForever() {
        while (running) {
            try {
                ActivityLogEntry first = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<ActivityLogEntry> batch = new ArrayList<>(BATCH_SIZE);
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                repository.insertAll(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A failed batch is dropped rather than retried: retrying a write that failed for
                // a structural reason would spin forever, and this thread must stay alive so the
                // next batch still has a chance.
                log.warn("Could not write an activity-log batch; the batch is discarded", e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        running = false;
        writer.interrupt();
        try {
            writer.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // One last synchronous drain so a graceful shutdown does not lose the tail. Best-effort:
        // if this fails there is nothing further to try.
        try {
            List<ActivityLogEntry> remaining = new ArrayList<>();
            queue.drainTo(remaining);
            repository.insertAll(remaining);
        } catch (RuntimeException e) {
            log.warn("Could not flush the activity-log buffer on shutdown", e);
        }
    }
}
