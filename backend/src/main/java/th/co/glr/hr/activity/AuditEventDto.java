package th.co.glr.hr.activity;

import java.time.OffsetDateTime;

/** One semantic action from hr.audit_log — who did what to which record. Distinct from ActivityLogEntryDto,
 * which is one HTTP request. This answers "who submitted the leave and who approved it"; that one answers "who
 * was in the portal at all". */
public record AuditEventDto(
    long id,
    Long actorEmployeeId,
    String actorEmployeeCode,
    String actorName,
    String actorEmail,
    String action,
    String entity,
    Long entityId,
    String subjectName,
    OffsetDateTime at
) {
}
