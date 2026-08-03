package th.co.glr.hr.commission;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;

@Repository
public class CommissionAttachmentRepository {
    private static final String DOMAIN = "commission-invoice";

    private final NamedParameterJdbcTemplate jdbc;
    private final FileAttachmentBlobRepository blobs;

    @Autowired
    public CommissionAttachmentRepository(NamedParameterJdbcTemplate jdbc, FileAttachmentBlobRepository blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /** Legacy/test convenience overload -- see LeaveAttachmentRepository's own dual-constructor javadoc for why. */
    public CommissionAttachmentRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new FileAttachmentBlobRepository(jdbc));
    }

    /**
     * V132 storage-durability fix -- TRANSITIONAL, commission-invoice ONLY: {@code
     * CommissionService} dual-writes the SAME uploaded file both here (bytes, via this method) and
     * to disk via {@code FileStorageService#store}, because that same disk file is ALSO registered
     * into {@code sales.attachment} under the identical {@code filePath} string -- {@code
     * sales.attachment} is out of this branch's scope and still disk-only. {@code filePath} here is
     * therefore still the disk-path-shaped key {@code store} returns, NOT the bare correlation key
     * the other three {@code hr.file_attachment} domains now use -- it doubles as the legacy
     * locator {@link AttachmentRepository#backsLiveCommission} joins on. Remove the disk limb (and
     * make this domain match the other three) once {@code sales.attachment} migrates to
     * database-backed storage in a follow-up branch.
     */
    public long saveWithContent(long invoiceId, String fileName, String filePath, String mimeType, Long fileSize,
                                long uploadedBy, byte[] content) {
        long attachmentId = save(invoiceId, fileName, filePath, mimeType, fileSize, uploadedBy);
        blobs.saveContent(attachmentId, content);
        return attachmentId;
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
