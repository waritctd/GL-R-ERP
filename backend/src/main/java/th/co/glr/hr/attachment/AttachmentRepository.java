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
        return findByTicketId(ticketId, true);
    }

    /** {@code includeRoundFiles} false leaves out every file linked to a delivery round (V236: the stored system
     * documents and the extra files such as slips), which carry prices and so are not for every role. */
    public List<AttachmentDto> findByTicketId(long ticketId, boolean includeRoundFiles) {
        return jdbc.query("""
            SELECT attachment_id, ticket_id, quotation_id, file_name,
                   attach_type, mime_type, file_size, uploaded_by, uploaded_at, delivery_request_id
              FROM sales.attachment
             WHERE ticket_id = :ticketId
               AND (:includeRound OR delivery_request_id IS NULL)
             ORDER BY uploaded_at DESC
            """,
            Map.of("ticketId", ticketId, "includeRound", includeRoundFiles),
            (rs, i) -> map(rs));
    }

    public boolean isRoundFile(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.attachment WHERE attachment_id = :id AND delivery_request_id IS NOT NULL)",
            Map.of("id", id), Boolean.class));
    }

    /** The status of the delivery round this file belongs to, empty for a file that is not a round's. */
    public Optional<String> roundStatus(long id) {
        return jdbc.query("""
            SELECT r.status FROM sales.attachment a
              JOIN sales.delivery_request r ON r.delivery_request_id = a.delivery_request_id
             WHERE a.attachment_id = :id
            """, Map.of("id", id), (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    public Optional<AttachmentDto> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT attachment_id, ticket_id, quotation_id, file_name,
                       attach_type, mime_type, file_size, uploaded_by, uploaded_at, delivery_request_id
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
        return save(ticketId, quotationId, fileName, filePath, mimeType, fileSize, attachType, uploadedBy, null, null);
    }

    /** {@code deliveryRequestId} / {@code uploaderSide}: set for a file attached to a delivery round (V236), else null. */
    public AttachmentDto save(long ticketId, Long quotationId, String fileName,
                              String filePath, String mimeType, Long fileSize,
                              String attachType, long uploadedBy, Long deliveryRequestId, String uploaderSide) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.attachment
                (ticket_id, quotation_id, file_name, file_path, mime_type,
                 file_size, attach_type, uploaded_by, delivery_request_id, uploader_side)
            VALUES (:ticketId, :quotationId, :fileName, :filePath, :mimeType,
                    :fileSize, :attachType, :uploadedBy, :deliveryRequestId, :uploaderSide)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("quotationId", quotationId)
                .addValue("fileName", fileName)
                .addValue("filePath", filePath)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", fileSize)
                .addValue("attachType", attachType)
                .addValue("uploadedBy", uploadedBy)
                .addValue("deliveryRequestId", deliveryRequestId)
                .addValue("uploaderSide", uploaderSide),
            key, new String[]{"attachment_id"});
        return findById(key.getKey().longValue()).orElseThrow();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM sales.attachment WHERE attachment_id = :id", Map.of("id", id));
    }

    /** CommissionService#createFromDeal registers ONE physical file into TWO independent registries that share
     * nothing but the file's path — this table and hr.file_attachment. */
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
        long drRaw = rs.getLong("delivery_request_id");
        Long dr = rs.wasNull() ? null : drRaw;
        return new AttachmentDto(
            rs.getLong("attachment_id"),
            rs.getLong("ticket_id"),
            qid,
            rs.getString("file_name"),
            rs.getString("attach_type"),
            rs.getString("mime_type"),
            fs,
            rs.getLong("uploaded_by"),
            rs.getTimestamp("uploaded_at").toInstant(),
            dr);
    }
}
