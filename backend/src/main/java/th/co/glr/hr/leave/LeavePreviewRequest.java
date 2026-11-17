package th.co.glr.hr.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/** POST /api/leave/preview (Phase A0b dry-run): the request shape for LeaveService#preview. A caller must
 * supply BOTH or NEITHER; one date with the other missing is treated the same as neither being supplied (see
 * LeaveService#preview). */
public record LeavePreviewRequest(
    @NotBlank @Size(max = 30) String leaveTypeCode,
    LocalDate startDate,
    LocalDate endDate,
    Long employeeId,
    @Size(max = 30) String purposeCode,
    Boolean requestedAsEmergency,
    Boolean hasAttachment,
    LeavePreviewDepth depth,
    LeaveQuotaPoolPreference quotaPoolPreference,
    // Partial-day span (V166): see this record's own Javadoc above.
    LocalTime startTime,
    LocalTime endTime
) {
    /** Convenience constructor for every pre-V166 call site (mostly tests) that already constructs the full
     * 9-argument pre-partial-day-span shape -- same convention SubmitLeaveRequest's own trailing-field convenience
     * constructors already use. */
    public LeavePreviewRequest(
            String leaveTypeCode, LocalDate startDate, LocalDate endDate, Long employeeId,
            String purposeCode, Boolean requestedAsEmergency, Boolean hasAttachment,
            LeavePreviewDepth depth, LeaveQuotaPoolPreference quotaPoolPreference) {
        this(leaveTypeCode, startDate, endDate, employeeId, purposeCode, requestedAsEmergency,
            hasAttachment, depth, quotaPoolPreference, null, null);
    }
}
