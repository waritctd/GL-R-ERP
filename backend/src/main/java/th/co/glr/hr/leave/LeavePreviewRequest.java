package th.co.glr.hr.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * POST /api/leave/preview (Phase A0b dry-run): the request shape for {@link LeaveService#preview}.
 * Deliberately a SEPARATE record from {@link SubmitLeaveRequest}, not a nullable-everything reuse of
 * it -- a preview is not a real submission (no {@code reason}, no sub-day {@code startTime}/{@code
 * endTime}, no contact-block overrides) and giving it its own shape keeps that difference explicit
 * at the type level rather than leaving a caller to guess which {@code SubmitLeaveRequest} fields a
 * preview call actually reads.
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
 */
public record LeavePreviewRequest(
    @NotBlank @Size(max = 30) String leaveTypeCode,
    LocalDate startDate,
    LocalDate endDate,
    Long employeeId,
    @Size(max = 30) String purposeCode,
    Boolean requestedAsEmergency,
    Boolean hasAttachment,
    LeavePreviewDepth depth
) {
}
