package th.co.glr.hr.attendance.correction;

import jakarta.validation.constraints.Size;

public record ReviewAttendanceCorrectionRequest(
    @Size(max = 2000) String reviewerNote
) {
}
