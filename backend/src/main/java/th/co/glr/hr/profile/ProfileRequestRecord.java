package th.co.glr.hr.profile;

import java.time.LocalDate;

public record ProfileRequestRecord(
    long id,
    long employeeId,
    String fieldKey,
    String fieldLabel,
    String oldValue,
    String newValue,
    String requestedBy,
    LocalDate requestedAt,
    String status,
    LocalDate reviewedAt
) {
}
