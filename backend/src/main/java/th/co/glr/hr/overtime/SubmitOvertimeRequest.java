package th.co.glr.hr.overtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record SubmitOvertimeRequest(
    Long employeeId,
    @NotNull LocalDate workDate,
    @NotNull OffsetDateTime plannedStartAt,
    @NotNull OffsetDateTime plannedEndAt,
    String dayType,
    @NotBlank @Size(max = 2000) String reason
) {
}
