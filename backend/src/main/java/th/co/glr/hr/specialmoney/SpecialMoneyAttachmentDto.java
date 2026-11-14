package th.co.glr.hr.specialmoney;

import java.time.OffsetDateTime;

/** A piece of evidence attached to a welfare request — the receipt, บัตรเชิญ, สูติบัตร, ใบมรณบัตร or ใบสุทธิ
 * the policy document requires for the type in question. */
public record SpecialMoneyAttachmentDto(
    long id,
    long specialMoneyRequestId,
    String fileName,
    String mimeType,
    Long sizeBytes,
    Long uploadedById,
    String uploadedByName,
    OffsetDateTime uploadedAt) {
}
