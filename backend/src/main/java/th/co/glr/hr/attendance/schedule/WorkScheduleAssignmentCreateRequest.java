package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/**
 * Assigns {@code workScheduleId} to one scope from {@code effectiveFrom} (optionally ending at
 * {@code effectiveTo}). All four required fields are validated in
 * {@link WorkScheduleAssignmentAdminService#create} with Thai {@code ApiException} messages, not
 * Jakarta bean validation — see that method for the exact rules (back-dating is rejected,
 * overlaps with an existing assignment for the same scope are normalised or rejected).
 */
record WorkScheduleAssignmentCreateRequest(
    ScopeType scopeType,
    Long scopeId,
    Integer workScheduleId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo
) {
}
