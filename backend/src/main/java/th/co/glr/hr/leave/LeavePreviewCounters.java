package th.co.glr.hr.leave;

/**
 * POST /api/leave/preview (Phase A0b): the two monthly occasion allowances {@link
 * LeaveService#autoRejectNote} already tracks internally, exposed for the CURRENT calendar month --
 * see {@link LeaveService#previewCounters}'s Javadoc. Both are always present regardless of {@link
 * LeavePreviewDepth} or whether the request has dates yet; {@code 0} means either the type carries
 * no such allowance at all, or this month's allowance is already used up.
 */
public record LeavePreviewCounters(
    int emergencyFilingsRemaining,
    int noCertificateOccasionsRemaining
) {
}
