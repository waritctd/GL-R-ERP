package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/**
 * One {@code hr.work_schedule_assignment} row (V115), with its {@link WorkSchedule} already
 * resolved — the join {@link WorkScheduleAssignmentRepository#findAllAssignments()} performs once.
 */
public record ScheduleAssignment(
    ScopeType scopeType,
    long scopeId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    WorkSchedule schedule
) {
    public ScheduleAssignment {
        if (scopeType == null || effectiveFrom == null || schedule == null) {
            throw new IllegalArgumentException("scopeType, effectiveFrom and schedule must not be null");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
        }
    }

    /** True when {@code date} falls within this assignment's effective window (inclusive both ends). */
    public boolean covers(LocalDate date) {
        return date != null
            && !date.isBefore(effectiveFrom)
            && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    /** True when this row targets the given scope. */
    public boolean matches(ScopeType type, Long id) {
        return scopeType == type && id != null && scopeId == id;
    }
}
