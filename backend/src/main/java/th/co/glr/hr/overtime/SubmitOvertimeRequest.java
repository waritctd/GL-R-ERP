package th.co.glr.hr.overtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code POST /api/overtime} body.
 *
 * <p>Deliberately has no {@code dayType} field. This request used to carry one, trusted straight
 * into {@code overtime_request.pay_rate_multiplier} with no validation and no calendar check --
 * a caller could self-declare {@code "HOLIDAY"} (3.00x) on an ordinary workday and be overpaid.
 * WORKDAY vs HOLIDAY is now derived server-side, exclusively, from {@code hr.holiday} (see {@code
 * OvertimeService#deriveDayType}) -- there is no channel by which a caller can supply it, so there
 * is nothing here to accidentally start trusting again. Do not re-add it, even as an
 * accepted-and-ignored hint: an unauthenticated field a client can still send, however clearly
 * documented as discarded, is the thing this shape exists to rule out.
 */
public record SubmitOvertimeRequest(
    Long employeeId,
    @NotNull LocalDate workDate,
    @NotNull OffsetDateTime plannedStartAt,
    @NotNull OffsetDateTime plannedEndAt,
    @NotBlank @Size(max = 2000) String reason
) {
}
