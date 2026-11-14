package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/** Assigns workScheduleId to one scope from effectiveFrom (optionally ending at effectiveTo). */
record WorkScheduleAssignmentCreateRequest(
    ScopeType scopeType,
    Long scopeId,
    Integer workScheduleId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo
) {
}
