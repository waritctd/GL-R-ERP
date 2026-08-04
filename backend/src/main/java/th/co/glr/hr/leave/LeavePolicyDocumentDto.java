package th.co.glr.hr.leave;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Metadata for one uploaded version of the §5 announcement PDF — see V133's migration comment for
 * why bytes are never carried alongside this (a separate table, read only on actual download).
 */
public record LeavePolicyDocumentDto(
    long documentId,
    String fileName,
    String mimeType,
    long fileSize,
    LocalDate effectiveFrom,
    Instant uploadedAt
) {
}
