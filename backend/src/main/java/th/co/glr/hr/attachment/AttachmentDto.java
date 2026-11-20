package th.co.glr.hr.attachment;

import java.time.Instant;

public record AttachmentDto(
    long id,
    long ticketId,
    Long quotationId,
    String fileName,
    String attachType,
    String mimeType,
    Long fileSize,
    long uploadedBy,
    Instant uploadedAt,
    /** Set for a file attached to a delivery round (V236), else null: the ไฟล์ tab groups by it. */
    Long deliveryRequestId
) {}
