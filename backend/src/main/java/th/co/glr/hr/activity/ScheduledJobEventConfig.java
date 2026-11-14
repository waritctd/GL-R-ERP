package th.co.glr.hr.activity;

import java.time.OffsetDateTime;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/** Records one hr.app_event row per @Scheduled execution. Why a task decorator and not an aspect. The seven
 * background workers make no HTTP request, so ActivityLogFilter is structurally blind to them — "did the FX
 * fetch run last night?" is unanswerable from a request log. */
@Configuration
public class ScheduledJobEventConfig {

    @Bean
    ThreadPoolTaskSchedulerCustomizer appEventJobRecorder() {
        return scheduler -> scheduler.setTaskDecorator(task -> () -> {
            long startedNanos = System.nanoTime();
            String name = describe(task);
            try {
                task.run();
                record(name, startedNanos, null);
            } catch (RuntimeException | Error e) {
                record(name, startedNanos, e);
                throw e;
            }
        });
    }

    private static void record(String name, long startedNanos, Throwable failure) {
        try {
            int durationMs = (int) Math.min(Integer.MAX_VALUE,
                (System.nanoTime() - startedNanos) / 1_000_000L);
            AppEventBuffer.shared().offer(new AppEvent(
                OffsetDateTime.now(),
                AppEvent.KIND_JOB,
                failure == null ? "INFO" : "ERROR",
                name,
                failure == null ? "job completed" : "job failed",
                failure == null ? null : failure.getClass().getName(),
                failure == null ? null : failure.getMessage(),
                firstFrameOf(failure),
                null,   // background work has no request, so no correlation id
                Thread.currentThread().getName(),
                durationMs));
        } catch (RuntimeException ignored) {
            // Observability must never break the job it is describing, and this must not log — see AppEventWriter's
            // javadoc on the feedback loop.
        }
    }

    /** First frame only, never the whole trace — the V159 rule. */
    private static String firstFrameOf(Throwable failure) {
        if (failure == null) {
            return null;
        }
        StackTraceElement[] frames = failure.getStackTrace();
        return frames == null || frames.length == 0 ? null : frames[0].toString();
    }

    /** A readable name for the scheduled task. Never the runnable's toString() — a lambda's is an unreadable
     * synthetic identity that changes between builds, which would make job history impossible to group. */
    static String describe(Runnable task) {
        if (task instanceof ScheduledMethodRunnable scheduled) {
            return scheduled.getTarget().getClass().getSimpleName()
                + "." + scheduled.getMethod().getName();
        }
        if (task == null) {
            return "unknown";
        }
        // A lambda's simple name is "Owner$$Lambda/0x00001c00011b97e8" — a synthetic identity that changes between
        // builds and even between runs, so grouping job history by it would produce a new "job" every deploy. Keep the
        // owning class, drop the identity.
        String simpleName = task.getClass().getSimpleName();
        int lambdaMarker = simpleName.indexOf("$$Lambda");
        return lambdaMarker > 0 ? simpleName.substring(0, lambdaMarker) : simpleName;
    }
}
