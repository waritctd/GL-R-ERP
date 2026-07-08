package th.co.glr.hr.commission;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class CommissionAttachmentRepository {
    private static final String DOMAIN = "commission-invoice";

    private final NamedParameterJdbcTemplate jdbc;

    public CommissionAttachmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long save(long invoiceId, String fileName, String filePath, String mimeType, Long fileSize, long uploadedBy) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by)
            VALUES
                (:domain, :ownerId, :fileName, :filePath, :mimeType, :fileSize, :uploadedBy)
            """,
            new MapSqlParameterSource()
                .addValue("domain", DOMAIN)
                .addValue("ownerId", invoiceId)
                .addValue("fileName", fileName)
                .addValue("filePath", filePath)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", fileSize)
                .addValue("uploadedBy", uploadedBy),
            key,
            new String[]{"attachment_id"});
        return key.getKey().longValue();
    }

    public String findFilePathById(long id) {
        return jdbc.queryForObject("""
            SELECT file_path
              FROM hr.file_attachment
             WHERE attachment_id = :id
               AND domain = :domain
            """, Map.of("id", id, "domain", DOMAIN), String.class);
    }
}
