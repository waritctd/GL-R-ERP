package th.co.glr.hr.activity;

import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Attaches DatabaseLogAppender to the root logger and drains AppEventBuffer into hr.app_event on a single
 * background thread. This class must not report its own failures through slf4j. That is the feedback loop this
 * design exists to avoid, so its own errors go to System.err, which no appender reads. */
@Component
public class AppEventWriter {

    private static final int BATCH_SIZE = 200;
    private static final long POLL_TIMEOUT_MS = 500;

    private final ActivityLogRepository repository;
    private final boolean enabled;
    private final AppEventBuffer buffer = AppEventBuffer.shared();
    private Thread worker;
    private volatile boolean running = true;
    private DatabaseLogAppender appender;

    public AppEventWriter(ActivityLogRepository repository,
                          @Value("${app.app-event-log.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        attachAppender();
        worker = new Thread(this::drainForever, "app-event-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /** Adds the appender to the ROOT logger in code. If the logging backend is not logback (a test running
     * slf4j-simple, say), this quietly does nothing rather than failing startup — losing this capture is never
     * worth refusing to boot. */
    private void attachAppender() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            System.err.println("[app-event] logging backend is not logback; app events not captured");
            return;
        }
        appender = new DatabaseLogAppender();
        appender.setName("hr-app-event");
        appender.setContext(context);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    private void drainForever() {
        while (running) {
            try {
                AppEvent first = buffer.poll(POLL_TIMEOUT_MS);
                if (first == null) {
                    continue;
                }
                List<AppEvent> batch = new ArrayList<>(BATCH_SIZE);
                batch.add(first);
                buffer.drainTo(batch, BATCH_SIZE - 1);
                repository.insertAppEvents(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // System.err, NOT a logger — see the class javadoc. A failed batch is dropped rather than retried; retrying a
                // structurally bad write would spin forever, and this thread must survive to give the next batch a chance.
                System.err.println("[app-event] dropped a batch: " + e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (appender != null) {
            // Detach first, so shutdown logging from other beans cannot enqueue into a buffer that no longer has a drain.
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (factory instanceof LoggerContext context) {
                context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            }
            appender.stop();
        }
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            List<AppEvent> remaining = new ArrayList<>();
            buffer.drainTo(remaining, BATCH_SIZE);
            repository.insertAppEvents(remaining);
        } catch (RuntimeException e) {
            System.err.println("[app-event] could not flush on shutdown: " + e);
        }
    }
}
