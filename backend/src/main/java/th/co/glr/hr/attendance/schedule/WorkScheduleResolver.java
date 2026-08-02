package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/**
 * Resolves the {@link WorkSchedule} that applied to an employee on a given date.
 *
 * <p>{@link CompanyWideWorkScheduleResolver} ignores every argument — the whole company runs one
 * configured schedule. {@link TieredWorkScheduleResolver} is {@code @Primary} and actually uses
 * them: an employee/department/division work-schedule assignment (V115) beats that company-wide
 * default when one exists for the date.
 *
 * <p>{@code workDate} is deliberately part of the contract: a schedule change must not silently
 * rewrite history when past days are recalculated.
 *
 * <p>{@code departmentId} was added alongside {@code divisionId} rather than left for each
 * implementation to look up per call: {@link TieredWorkScheduleResolver} must stay a pure,
 * DB-free function like {@link WorkSchedule} itself, and every current caller
 * ({@code AttendanceDailyService}) already batch-loads the employee-&gt;division map for exactly
 * this kind of per-row lookup (see {@code recalculateRange}) — extending that same batch to include
 * department is a smaller, more consistent change than giving the resolver its own database
 * dependency and reintroducing a query per employee-day.
 */
public interface WorkScheduleResolver {
    WorkSchedule resolve(long employeeId, Long divisionId, Long departmentId, LocalDate workDate);
}
