package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/** Resolves employee, department, then division schedules for a date, falling back to the company schedule. */
@Component
@Primary
public class TieredWorkScheduleResolver implements WorkScheduleResolver {

    private final WorkScheduleAssignmentRepository repository;
    private final CompanyWideWorkScheduleResolver fallback;
    private final long ttlNanos;

    private volatile CacheEntry cache;

    public TieredWorkScheduleResolver(
            WorkScheduleAssignmentRepository repository,
            CompanyWideWorkScheduleResolver fallback,
            AppProperties properties) {
        this.repository = repository;
        this.fallback = fallback;
        long ttlMs = properties.getAttendance().getSchedule().getAssignmentCacheTtlMs();
        this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, ttlMs));
    }

    @Override
    public WorkSchedule resolve(long employeeId, Long divisionId, Long departmentId, LocalDate workDate) {
        for (ScopeType scope : ScopeType.values()) {
            Long scopeId = switch (scope) {
                case EMPLOYEE -> employeeId;
                case DEPARTMENT -> departmentId;
                case DIVISION -> divisionId;
            };
            if (scopeId == null) {
                continue;
            }
            for (ScheduleAssignment assignment : assignments()) {
                if (assignment.matches(scope, scopeId) && assignment.covers(workDate)) {
                    return assignment.schedule();
                }
            }
        }
        return fallback.resolve(employeeId, divisionId, departmentId, workDate);
    }

    /** Forces the next #resolve call to reload hr.work_schedule_assignment rather than waiting out the TTL. */
    public void invalidate() {
        cache = null;
    }

    /** Double-checked locking, same shape as the original load-once version, extended with a TTL check: a cache
     * entry is used only while both non-null AND not expired. */
    private List<ScheduleAssignment> assignments() {
        CacheEntry local = cache;
        if (local == null || isExpired(local)) {
            synchronized (this) {
                local = cache;
                if (local == null || isExpired(local)) {
                    local = new CacheEntry(repository.findAllAssignments(), System.nanoTime());
                    cache = local;
                }
            }
        }
        return local.assignments();
    }

    private boolean isExpired(CacheEntry entry) {
        return System.nanoTime() - entry.loadedAtNanos() >= ttlNanos;
    }

    /** Immutable so publishing a new one via the {@code volatile cache} field is a single, safe step. */
    private record CacheEntry(List<ScheduleAssignment> assignments, long loadedAtNanos) {
    }
}
