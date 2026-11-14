package th.co.glr.hr.overtime;

import jakarta.validation.constraints.Size;

/** POST /api/overtime/{id/approve} body -- deliberately its own record rather than a widened
 * ReviewOvertimeRequest. That is a deliberate policy change, not a relaxation of the rule
 * SubmitOvertimeRequest's Javadoc documents: a SUBMITTED claim still can never reach pay by itself. */
public record ApproveOvertimeRequest(
    @Size(max = 2000) String reviewerNote,
    String dayType
) {
}
