package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/** Resolves the WorkSchedule that applied to an employee on a given date. workDate is deliberately part of the
 * contract: a schedule change must not silently rewrite history when past days are recalculated. */
public interface WorkScheduleResolver {
    WorkSchedule resolve(long employeeId, Long divisionId, Long departmentId, LocalDate workDate);
}
