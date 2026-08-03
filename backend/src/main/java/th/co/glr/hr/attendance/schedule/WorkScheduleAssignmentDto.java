package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/** One {@code hr.work_schedule_assignment} row, for the admin list/create/end surface. */
record WorkScheduleAssignmentDto(
    int assignmentId,
    ScopeType scopeType,
    long scopeId,
    int workScheduleId,
    String scheduleCode,
    LocalDate effectiveFrom,
    LocalDate effectiveTo
) {
}
