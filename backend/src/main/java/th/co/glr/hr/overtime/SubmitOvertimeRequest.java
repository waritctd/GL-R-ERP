package th.co.glr.hr.overtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** POST /api/overtime body. dayType is a REQUEST, never a pay input. That P0 is closed for good: this field can
 * still never reach pay_rate_multiplier by any path, at ANY stage of the request's life. This field still
 * plays no part. */
public record SubmitOvertimeRequest(
    Long employeeId,
    @NotNull LocalDate workDate,
    @NotNull OffsetDateTime plannedStartAt,
    @NotNull OffsetDateTime plannedEndAt,
    String dayType,
    @NotBlank @Size(max = 2000) String reason
) {
}
