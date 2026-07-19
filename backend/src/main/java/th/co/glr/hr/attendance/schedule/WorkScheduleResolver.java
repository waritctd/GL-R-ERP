package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/**
 * Resolves the {@link WorkSchedule} that applied to an employee on a given date.
 *
 * <p>Today there is exactly one implementation and it ignores every argument — the company runs a
 * single schedule ({@link CompanyWideWorkScheduleResolver}). The arguments exist so that adding
 * per-division or per-employee hours later is a new implementation plus a lookup table, leaving the
 * calculator, repository, and API untouched.
 *
 * <p>{@code workDate} is deliberately part of the contract: a schedule change must not silently
 * rewrite history when past days are recalculated.
 */
public interface WorkScheduleResolver {
    WorkSchedule resolve(long employeeId, Long divisionId, LocalDate workDate);
}
