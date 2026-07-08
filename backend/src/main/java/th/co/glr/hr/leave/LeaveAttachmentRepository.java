package th.co.glr.hr.leave;

import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class LeaveAttachmentRepository {
    private static final String DOMAIN = "leave";

    private final NamedParameterJdbcTemplate jdbc;

    public LeaveAttachmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LeaveAttachmentDto save(long leaveRequestId, String fileName, String filePath,
                                   String mimeType, Long fileSize, long uploadedBy) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by)
            VALUES
                (:domain, :ownerId, :fileName, :filePath, :mimeType, :fileSize, :uploadedBy)
            """,
            new MapSqlParameterSource()
                .addValue("domain", DOMAIN)
                .addValue("ownerId", leaveRequestId)
                .addValue("fileName", fileName)
                .addValue("filePath", filePath)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", fileSize)
                .addValue("uploadedBy", uploadedBy),
            key, new String[]{"attachment_id"});
        return findById(key.getKey().longValue()).orElseThrow();
    }

    public Optional<LeaveAttachmentDto> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT attachment_id, domain, owner_id, file_name, mime_type,
                       file_size, uploaded_by, uploaded_at
                  FROM hr.file_attachment
                 WHERE attachment_id = :id
                   AND domain = :domain
                """,
                Map.of("id", id, "domain", DOMAIN),
                (rs, rowNum) -> new LeaveAttachmentDto(
                    rs.getLong("attachment_id"),
                    rs.getString("domain"),
                    rs.getLong("owner_id"),
                    rs.getString("file_name"),
                    rs.getString("mime_type"),
                    nullableLong(rs, "file_size"),
                    rs.getLong("uploaded_by"),
                    rs.getTimestamp("uploaded_at").toInstant()
                )));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public String findFilePathById(long id) {
        try {
            return jdbc.queryForObject("""
                SELECT file_path
                  FROM hr.file_attachment
                 WHERE attachment_id = :id
                   AND domain = :domain
                """, Map.of("id", id, "domain", DOMAIN), String.class);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
