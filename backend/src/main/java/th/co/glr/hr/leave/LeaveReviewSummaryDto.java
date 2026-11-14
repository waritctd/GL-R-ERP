package th.co.glr.hr.leave;

/** GET /api/leave/review-summary (Phase A0b). TWO independent signals, deliberately not collapsed into one
 * count -- see LeaveService#reviewSummary's Javadoc for the full rationale: isReviewer: whether this actor may
 * review ANYONE at all, independent of whether anything is pending right now. */
public record LeaveReviewSummaryDto(boolean isReviewer, int pendingCount) {
}
