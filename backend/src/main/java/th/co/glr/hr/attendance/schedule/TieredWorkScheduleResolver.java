package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import th.co.glr.hr.config.AppProperties;

/**
 * Resolves the schedule assigned to an employee, their department, or their division (V115),
 * falling back to {@link CompanyWideWorkScheduleResolver}'s single company-wide schedule when
 * nothing is assigned for the date. {@code @Primary} so this is the {@link WorkScheduleResolver}
 * every caller gets by default; {@link CompanyWideWorkScheduleResolver} stays an ordinary
 * {@code @Component} so it is still directly constructible (existing tests do this) and remains
 * the fallback this class is built around rather than something it duplicates.
 *
 * <h2>Precedence</h2>
 * EMPLOYEE beats DEPARTMENT beats DIVISION beats the company-wide default — see {@link ScopeType}'s
 * declared order. Each scope is also effective-dated ({@link ScheduleAssignment#covers}): an
 * assignment that does not cover {@code workDate} is treated as absent for that call, so a schedule
 * change never rewrites the classification of a day before it took effect.
 *
 * <h2>Overlapping assignments within one scope: last-write-wins</h2>
 * The schema does not forbid two rows for the same {@code (scope_type, scope_id)} with overlapping
 * effective ranges (there is no admin UI in this branch to prevent it by construction). The
 * per-tier loop below returns the <em>first</em> match it finds, so which row wins an overlap is
 * entirely decided by {@link WorkScheduleAssignmentRepository#findAllAssignments()}'s SQL
 * {@code ORDER BY} (newest {@code effective_from} first, ties broken by {@code assignment_id}
 * descending) — this class does not, and must not, re-sort the list in Java. Relying on the SQL
 * order rather than sorting here keeps "which row wins" defined in exactly one place.
 *
 * <h2>Caching and invalidation</h2>
 * {@link WorkScheduleAssignmentRepository#findAllAssignments()} is still called at most once per
 * TTL window rather than once per {@link #resolve} call — see that repository class's javadoc for
 * why a full-table, batch read is the right shape here. What changed from the original load-once
 * design: the cached copy now expires after {@code app.attendance.schedule.assignment-cache-ttl-ms}
 * (default 5 minutes) and is transparently reloaded on the next {@link #resolve} call after that,
 * plus {@link #invalidate()} exists for a caller that wants a change visible sooner than the TTL.
 *
 * <p>Two designs were on the table — a TTL, or requiring every writer to call an explicit
 * invalidation hook — and this class uses <strong>both</strong>, with the TTL as the mechanism that
 * must not be allowed to fail: assignments today are edited by hand-written SQL or a migration,
 * never through this application (there is deliberately no admin CRUD UI in this branch — see
 * {@link WorkScheduleAssignmentRepository}'s javadoc), so there is no application code path that
 * could reliably call {@link #invalidate()} in the first place. An explicit-hook-only design would
 * therefore be silently dead today and would depend, forever after an admin UI ships, on every
 * future write path remembering to call it — the exact failure mode this class's own javadoc used
 * to warn about ("a future admin path must add cache invalidation alongside it"). A missed call is
 * invisible: nothing errors, the cache just quietly serves stale schedules until the next restart,
 * which is precisely today's bug. A missed TTL configuration, by contrast, still self-heals — worst
 * case the default applies and staleness is bounded at 5 minutes. That asymmetry (silent-forever vs.
 * bounded-and-self-healing) is why the TTL is the load-bearing mechanism and {@link #invalidate()}
 * is a courtesy for a future write path, not a requirement for correctness.
 */
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

    /**
     * Forces the next {@link #resolve} call to reload {@code hr.work_schedule_assignment} rather
     * than waiting out the TTL. No production caller does this yet (see class javadoc for why), but
     * the seam is here for a future admin write path — call it right after a create/update/delete so
     * the change is visible immediately instead of within the TTL window.
     */
    public void invalidate() {
        cache = null;
    }

    /**
     * Double-checked locking, same shape as the original load-once version, extended with a TTL
     * check: a cache entry is used only while both non-null AND not expired. The read on the
     * {@code volatile cache} field and the write that replaces it are each a single reference
     * assignment, so a concurrent {@link #resolve} call during a reload either sees the old
     * (still-valid-at-the-time-it-was-read) entry or the fully-built new one — never a partially
     * constructed list, since {@link CacheEntry} is immutable and published only after
     * {@link WorkScheduleAssignmentRepository#findAllAssignments()} returns.
     */
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
