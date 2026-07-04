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
