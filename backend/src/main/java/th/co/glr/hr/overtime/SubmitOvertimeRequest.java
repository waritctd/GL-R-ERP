package th.co.glr.hr.overtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code POST /api/overtime} body.
 *
 * <p><b>{@code dayType} is a REQUEST, never a pay input.</b> It used to be trusted straight into
 * {@code overtime_request.pay_rate_multiplier} with no validation and no calendar check -- a caller
 * could self-declare {@code "HOLIDAY"} (3.00x) on an ordinary workday and be overpaid. That P0 is
 * closed for good: this field can still never reach {@code pay_rate_multiplier} by any path, at
 * ANY stage of the request's life. What money actually derives from at each stage
 * (feat/ot-nonworkday-rate-suggestion):
 *
 * <ul>
 *   <li>At submit, {@code day_type}/{@code pay_rate_multiplier} are set from {@code
 *       OvertimeService#suggestDayType} -- the system's SUGGESTION (holiday OR the employee's
 *       resolved {@code WorkSchedule} non-workday), never this field;
 *   <li>At approval, the value that actually freezes into pay is {@code
 *       ApproveOvertimeRequest#dayType} -- the APPROVER's decision, defaulted to the suggestion,
 *       from an actor already authorized to approve this specific request. This field still plays
 *       no part.
 * </ul>
 *
 * <p>The field is kept, not deleted, because the claim itself is meaningful to an approver: {@code
 * OvertimeService#resolveDayTypeSubmitNote} compares it against the suggestion and flags a
 * disagreement in {@code calculation_note} for the approver to see -- in EITHER direction, and the
 * request is accepted either way. This is a deliberate owner-ruled change from the previous
 * behaviour of refusing an over-claim outright (400) when the calendar could actively disprove it:
 * the employee may now submit a value that disagrees with the suggestion and still have the request
 * go through. Separately -- and REGARDLESS of what this field holds, including blank/{@code null}
 * -- a WORKDAY suggestion for a work date whose year the calendar has no {@code hr.holiday} data for
 * at all is flagged too: the suggestion itself is unverified in that case (an unrecorded public
 * holiday could still be hiding on that date), a fact that has nothing to do with what (if anything)
 * was requested.
 *
 * <p>Do not wire this field back into anything that sets {@code pay_rate_multiplier} or {@code
 * day_type} directly, even conditionally -- {@code suggestDayType} and {@code
 * ApproveOvertimeRequest#dayType} are the only permitted sources for either, by design (see this
 * fix's PR body, and the branch that introduced this Javadoc's predecessor, for the defect this
 * restriction originally closed).
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
