package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

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
 * <h2>Caching</h2>
 * {@link WorkScheduleAssignmentRepository#findAllAssignments()} is called once, lazily, and the
 * result is kept for the lifetime of this bean — see that repository class's javadoc for why a
 * full-table, load-once read is the right shape here rather than a query per {@link #resolve} call.
 */
@Component
@Primary
public class TieredWorkScheduleResolver implements WorkScheduleResolver {

    private final WorkScheduleAssignmentRepository repository;
    private final CompanyWideWorkScheduleResolver fallback;

    private volatile List<ScheduleAssignment> cachedAssignments;

    public TieredWorkScheduleResolver(
            WorkScheduleAssignmentRepository repository, CompanyWideWorkScheduleResolver fallback) {
        this.repository = repository;
        this.fallback = fallback;
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

    private List<ScheduleAssignment> assignments() {
        List<ScheduleAssignment> local = cachedAssignments;
        if (local == null) {
            synchronized (this) {
                local = cachedAssignments;
                if (local == null) {
                    local = repository.findAllAssignments();
                    cachedAssignments = local;
                }
            }
        }
        return local;
    }
}
