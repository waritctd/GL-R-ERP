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
    /**
     * Accepted for backward wire-compatibility with existing callers, but {@code
     * OvertimeService} deliberately never reads it: WORKDAY vs HOLIDAY is derived server-side from
     * {@code hr.holiday} (see {@code OvertimeService#deriveDayType}), never taken from client
     * input. This field is unauthenticated user input and was previously trusted straight into
     * {@code pay_rate_multiplier} -- a caller could self-declare HOLIDAY (3.00x) on an ordinary
     * workday. Do not wire this back into the persisted day type without re-reading that history.
     */
    String dayType,
    @NotBlank @Size(max = 2000) String reason
) {
}
