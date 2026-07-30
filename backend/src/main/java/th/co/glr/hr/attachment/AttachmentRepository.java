package th.co.glr.hr.attachment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AttachmentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AttachmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AttachmentDto> findByTicketId(long ticketId) {
        return jdbc.query("""
            SELECT attachment_id, ticket_id, quotation_id, file_name,
                   attach_type, mime_type, file_size, uploaded_by, uploaded_at
              FROM sales.attachment
             WHERE ticket_id = :ticketId
             ORDER BY uploaded_at DESC
            """,
            Map.of("ticketId", ticketId),
            (rs, i) -> map(rs));
    }

    public Optional<AttachmentDto> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT attachment_id, ticket_id, quotation_id, file_name,
                       attach_type, mime_type, file_size, uploaded_by, uploaded_at
                  FROM sales.attachment WHERE attachment_id = :id
                """,
                Map.of("id", id),
                (rs, i) -> map(rs)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public String findFilePathById(long id) {
        try {
            return jdbc.queryForObject(
                "SELECT file_path FROM sales.attachment WHERE attachment_id = :id",
                Map.of("id", id), String.class);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public AttachmentDto save(long ticketId, Long quotationId, String fileName,
                              String filePath, String mimeType, Long fileSize,
                              String attachType, long uploadedBy) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.attachment
                (ticket_id, quotation_id, file_name, file_path, mime_type,
                 file_size, attach_type, uploaded_by)
            VALUES (:ticketId, :quotationId, :fileName, :filePath, :mimeType,
                    :fileSize, :attachType, :uploadedBy)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("quotationId", quotationId)
                .addValue("fileName", fileName)
                .addValue("filePath", filePath)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", fileSize)
                .addValue("attachType", attachType)
                .addValue("uploadedBy", uploadedBy),
            key, new String[]{"attachment_id"});
        return findById(key.getKey().longValue()).orElseThrow();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM sales.attachment WHERE attachment_id = :id", Map.of("id", id));
    }

    /**
     * F5 fix (2026-07-30, ข้อ 15 evidence-chain hardening): {@code
     * CommissionService#createFromDeal} registers ONE physical file into TWO independent
     * registries that share nothing but the file's path — this table ({@code sales.attachment},
     * the ticket's {@code INVOICE} attachment, deletable by the ticket's creator/assignee via
     * {@link th.co.glr.hr.attachment.AttachmentController#delete}) and {@code hr.file_attachment}
     * (the commission's ข้อ 15 evidence, referenced by {@code
     * sales.invoice_details.invoice_attachment_id}, with no delete route of its own anywhere in
     * this codebase). Before {@code AttachmentController#delete} unlinks a row AND the physical
     * file, it must know whether some OTHER registry is still relying on that same file — this is
     * that check, by file path (the only field the two registries actually share).
     *
     * <p>Scoped to commissions that are NOT {@code VOID}/{@code REJECTED} — mirrors {@code
     * CommissionService#updateDeductions}'s own "cannot edit a void commission record" gate. A
     * {@code SUBMITTED}/{@code MANAGER_APPROVED} commission needs this file intact for its
     * remaining approver(s) to review, not just an {@code APPROVED} one for the permanent audit
     * trail behind money already paid — gating on {@code APPROVED} alone would let a rep destroy
     * the evidence a CEO is about to review, before it ever reaches that status.
     */
    public boolean backsLiveCommission(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Boolean value = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM hr.file_attachment fa
                  JOIN sales.invoice_details inv ON inv.invoice_attachment_id = fa.attachment_id
                  JOIN sales.commission_record cr ON cr.invoice_id = inv.invoice_id
                 WHERE fa.file_path = :filePath
                   AND cr.status NOT IN ('VOID', 'REJECTED')
            )
            """, Map.of("filePath", filePath), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    private AttachmentDto map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long qidRaw = rs.getLong("quotation_id");
        Long qid = rs.wasNull() ? null : qidRaw;
        long fsRaw = rs.getLong("file_size");
        Long fs = rs.wasNull() ? null : fsRaw;
        return new AttachmentDto(
            rs.getLong("attachment_id"),
            rs.getLong("ticket_id"),
            qid,
            rs.getString("file_name"),
            rs.getString("attach_type"),
            rs.getString("mime_type"),
            fs,
            rs.getLong("uploaded_by"),
            rs.getTimestamp("uploaded_at").toInstant());
    }
}
