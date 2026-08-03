package th.co.glr.hr.specialmoney;

import java.time.OffsetDateTime;

/**
 * A piece of evidence attached to a welfare request — the receipt, บัตรเชิญ, สูติบัตร, ใบมรณบัตร or
 * ใบสุทธิ the policy document requires for the type in question.
 *
 * <p>Maps {@code hr.special_money_request_attachment}, created by V66 in 2018 and left unused until
 * 2026-08: the table existed but no Java code read or wrote it, so evidence could never actually be
 * supplied and the CEO approved money with no document trail.
 */
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
