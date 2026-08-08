package th.co.glr.hr.overtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code POST /api/overtime} body.
 *
 * <p><b>{@code dayType} is a CLAIM, never a pay input.</b> It used to be trusted straight into
 * {@code overtime_request.pay_rate_multiplier} with no validation and no calendar check -- a caller
 * could self-declare {@code "HOLIDAY"} (3.00x) on an ordinary workday and be overpaid. Money is now
 * derived exclusively server-side from {@code hr.holiday} (see {@code
 * OvertimeService#deriveDayType}) -- {@code dayType} on this record can no longer reach {@code
 * pay_rate_multiplier} by any path.
 *
 * <p>The field is kept, not deleted, because the claim itself is meaningful: {@code
 * OvertimeService#validateDayTypeClaim} checks it against {@link
 * th.co.glr.hr.attendance.schedule.HolidayCalendar} and refuses outright a {@code HOLIDAY} claim the
 * calendar can actively disprove (loaded for the year, date not in it), while a claim the calendar
 * cannot yet corroborate (nothing loaded for the year at all) is accepted but flagged in {@code
 * calculation_note} for HR to review. A blank/null value is simply "no claim made" -- validated the
 * same as always (derived from the calendar, no flag).
 *
 * <p>Do not wire this field back into anything that sets {@code pay_rate_multiplier} or {@code
 * day_type} directly, even conditionally -- {@code deriveDayType} is the only permitted source for
 * both, by design (see this fix's PR body for the defect this restriction closes).
 */
public record SubmitOvertimeRequest(
    Long employeeId,
    @NotNull LocalDate workDate,
    @NotNull OffsetDateTime plannedStartAt,
    @NotNull OffsetDateTime plannedEndAt,
    String dayType,
    @NotBlank @Size(max = 2000) String reason
) {
}
