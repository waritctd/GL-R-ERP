package th.co.glr.hr.leave;

/**
 * GET /api/leave/review-summary (Phase A0b). TWO independent signals, deliberately not collapsed
 * into one count -- see {@code LeaveService#reviewSummary}'s Javadoc for the full rationale:
 *
 * <ul>
 *   <li>{@code isReviewer}: whether this actor may review ANYONE at all, independent of whether
 *       anything is pending right now. {@code true} for HR/CEO ({@link LeaveService#canReviewAll})
 *       and for anyone who is the {@code reports_to_employee_id} of at least one ACTIVE employee --
 *       the SAME {@link LeaveService#canReviewEmployee} shape {@link LeaveService#approve}/{@link
 *       LeaveService#reject} already gate on, NOT a role check ({@code REVIEW_ALL_ROLES} is {@code
 *       {hr}} only, but every direct manager reviews their own reports too). This is the stable,
 *       pending-count-independent signal a frontend should key tab VISIBILITY off -- a manager with
 *       zero currently-pending requests is still a reviewer (they can still open the queue and see
 *       an empty state, or review something they already decided), so this field is {@code true} for
 *       them even when {@code pendingCount} is {@code 0}.
 *   <li>{@code pendingCount}: the count of SUBMITTED requests this actor may act on RIGHT NOW. Drives
 *       a badge/number, not visibility -- a {@code 0} here does not mean "not a reviewer".
 * </ul>
 *
 * <p>Conflating these into a single count was the bug this two-field shape exists to avoid: an empty
 * queue ({@code isReviewer=true, pendingCount=0}) would have been indistinguishable from no
 * permission at all ({@code isReviewer=false, pendingCount=0}), which would have hidden the review
 * tab entirely for a manager who is caught up, and made the tab flicker in and out as requests come
 * and go.
 */
public record LeaveReviewSummaryDto(boolean isReviewer, int pendingCount) {
}
