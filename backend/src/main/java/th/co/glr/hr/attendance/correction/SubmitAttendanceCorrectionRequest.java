package th.co.glr.hr.attendance.correction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record SubmitAttendanceCorrectionRequest(
    @NotNull LocalDate workDate,
    @NotNull AttendanceCorrectionType correctionType,
    OffsetDateTime requestedCheckIn,
    OffsetDateTime requestedCheckOut,
    @NotBlank @Size(max = 2000) String reason
) {
}
