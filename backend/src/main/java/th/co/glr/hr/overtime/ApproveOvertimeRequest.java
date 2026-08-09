package th.co.glr.hr.overtime;

import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/overtime/{id}/approve} body -- deliberately its own record rather than a
 * widened {@link ReviewOvertimeRequest}. {@code reject} ({@code OvertimeService#reject}) and the
 * second-stage CEO sign-off endpoint (see {@code OvertimeController.java}) both still take {@code
 * ReviewOvertimeRequest} and would otherwise advertise a {@code dayType} field they silently
 * ignore.
 *
 * <p><b>{@code dayType} is the ONE field in this codebase that may set {@code
 * overtime_request.pay_rate_multiplier}</b> (feat/ot-nonworkday-rate-suggestion, branch B of the OT
 * UAT defect #2 fix). That is a deliberate policy change, not a relaxation of the rule
 * {@link SubmitOvertimeRequest}'s Javadoc documents: a SUBMITTED claim still can never reach pay by
 * itself. What changed is who stands between a non-workday and its 3x -- previously nobody (the
 * server silently derived from {@code hr.holiday} alone); now the APPROVER, explicitly, at the
 * moment they approve. See {@code OvertimeService#suggestDayType} for the system's suggestion
 * (holiday OR the employee's resolved non-workday) that this field may confirm or override, and
 * {@code OvertimeService#managerApprove}/{@code #ceoDirectApprove} for where it is honoured.
 *
 * <p>This value is read ONLY from inside the {@code approve()} call chain, after the caller has
 * already passed {@code requireManager}/{@code requireCeo}/{@code requireCeoForManagerlessRequest}
 * -- i.e. from an actor already authorized to approve this specific request. It is never consulted
 * for a SUBMITTED claim, a reject, or a cancel. The second-stage {@code ceoApprove} (the freeze
 * point does not move -- see the class Javadoc on {@code OvertimeService#calculate}) also never
 * reads it: the manager's (or the manager-less route's CEO's) decision at the FIRST approval stage
 * is frozen and inherited, exactly like {@code payable_minutes}/{@code salary_basis} already are.
 *
 * @param reviewerNote optional note, same shape as {@link ReviewOvertimeRequest#reviewerNote}
 * @param dayType the approver's day-type DECISION -- {@code WORKDAY} or {@code HOLIDAY},
 *     case-insensitive. Absent/{@code null}/blank means "use the system's suggestion" (see
 *     {@code OvertimeService#suggestDayType}). An unrecognised non-blank value is a 400, same as
 *     an unrecognised {@link SubmitOvertimeRequest#dayType} claim.
 */
public record ApproveOvertimeRequest(
    @Size(max = 2000) String reviewerNote,
    String dayType
) {
}
