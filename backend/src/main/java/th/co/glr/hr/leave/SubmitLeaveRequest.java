package th.co.glr.hr.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record SubmitLeaveRequest(
    Long employeeId,
    @NotBlank @Size(max = 30) String leaveTypeCode,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate,
    @NotBlank @Size(max = 2000) String reason,
    @Size(max = 255) String attachmentName,
    @Size(max = 2000) String attachmentUrl
) {
}
