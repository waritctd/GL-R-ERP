package th.co.glr.hr.leave;

/** POST /api/leave/preview (Phase A0b): the two monthly occasion allowances LeaveService#autoRejectNote already
 * tracks internally, exposed for the CURRENT calendar month -- see LeaveService#previewCounters's Javadoc. */
public record LeavePreviewCounters(
    int emergencyFilingsRemaining,
    int noCertificateOccasionsRemaining
) {
}
