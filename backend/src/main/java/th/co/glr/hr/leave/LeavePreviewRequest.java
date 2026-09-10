package th.co.glr.hr.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * POST /api/leave/preview (Phase A0b dry-run): the request shape for {@link LeaveService#preview}.
 * Deliberately a SEPARATE record from {@link SubmitLeaveRequest}, not a nullable-everything reuse of
 * it -- a preview is not a real submission (no {@code reason}, no contact-block overrides) and
 * giving it its own shape keeps that difference explicit at the type level rather than leaving a
 * caller to guess which {@code SubmitLeaveRequest} fields a preview call actually reads.
 *
 * <p><b>{@code startTime}/{@code endTime} (V166, 2026-09-10):</b> widened from the original "no
 * sub-day times in this shape at all" design -- a partial-day-span composer needs an accurate live
 * {@code totalDays} (including a multi-day timed span's fractional boundary days) BEFORE the
 * employee submits, the same "dry-run mirrors the real thing exactly" guarantee this endpoint
 * already gives every other field. Both null (the default) takes the unchanged whole-day path; both
 * non-null resolves and ledgers the request identically to {@link LeaveService#submit} -- see that
 * method's own Javadoc and {@code LeaveDayMath#spanDayFractions} for the shared arithmetic. Unlike
 * {@link SubmitLeaveRequest}, this endpoint does NOT run {@code LeaveService#validateSubDayTimes}'s
 * structural checks (malformed times just compute a defensive {@code ZERO} fraction rather than
 * throwing -- see {@code LeaveDayMath#boundaryFraction}), consistent with this being a lenient dry
 * run rather than the authoritative submission path.
 *
 * <p>{@code startDate}/{@code endDate} are BOTH nullable (unlike {@link SubmitLeaveRequest}, where
 * they are {@code @NotNull}) -- see {@link LeaveService#preview}'s Javadoc for what happens when
 * either (or both) is absent. A caller must supply BOTH or NEITHER; one date with the other missing
 * is treated the same as neither being supplied (see {@link LeaveService#preview}).
 *
 * <p>{@code hasAttachment} is the caller's OWN declaration -- there is no multipart upload on this
 * endpoint, so this cannot be verified server-side the way {@link LeaveService#submit}'s actual
 * {@code MultipartFile} presence is. A caller previewing a SICK request should pass this truthfully
 * to get an accurate certificate-gate verdict.
 *
 * <p>{@code quotaPoolPreference} (V161): same meaning and same {@code null} default as {@link
 * SubmitLeaveRequest#quotaPoolPreference()} -- see {@link LeaveQuotaPoolPreference}'s Javadoc. Lets a
 * preview show the split ({@link LeavePreviewDto#quotaYearSplits()}) the requester would actually get
 * for either pool order, before they submit.
 */
public record LeavePreviewRequest(
    @NotBlank @Size(max = 30) String leaveTypeCode,
    LocalDate startDate,
    LocalDate endDate,
    Long employeeId,
    @Size(max = 30) String purposeCode,
    Boolean requestedAsEmergency,
    Boolean hasAttachment,
    LeavePreviewDepth depth,
    LeaveQuotaPoolPreference quotaPoolPreference,
    // Partial-day span (V166): see this record's own Javadoc above.
    LocalTime startTime,
    LocalTime endTime
) {
    /**
     * Convenience constructor for every pre-V166 call site (mostly tests) that already constructs
     * the full 9-argument pre-partial-day-span shape -- same convention {@link SubmitLeaveRequest}'s
     * own trailing-field convenience constructors already use. {@code startTime}/{@code endTime}
     * default to {@code null} (the whole-day path, unchanged).
     */
    public LeavePreviewRequest(
            String leaveTypeCode, LocalDate startDate, LocalDate endDate, Long employeeId,
            String purposeCode, Boolean requestedAsEmergency, Boolean hasAttachment,
            LeavePreviewDepth depth, LeaveQuotaPoolPreference quotaPoolPreference) {
        this(leaveTypeCode, startDate, endDate, employeeId, purposeCode, requestedAsEmergency,
            hasAttachment, depth, quotaPoolPreference, null, null);
    }
}
