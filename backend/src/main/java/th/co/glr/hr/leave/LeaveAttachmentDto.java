package th.co.glr.hr.leave;

import java.time.Instant;

public record LeaveAttachmentDto(
    long id,
    String domain,
    long ownerId,
    String fileName,
    String mimeType,
    Long fileSize,
    long uploadedBy,
    Instant uploadedAt
) {
}
