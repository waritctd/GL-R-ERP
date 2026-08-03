package th.co.glr.hr.leave;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;

@Repository
public class LeaveAttachmentRepository {
    private static final String DOMAIN = "leave";

    private final NamedParameterJdbcTemplate jdbc;
    private final FileAttachmentBlobRepository blobs;

    @Autowired
    public LeaveAttachmentRepository(NamedParameterJdbcTemplate jdbc, FileAttachmentBlobRepository blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /**
     * Legacy/test convenience overload -- keeps every existing direct-construction call site (this
     * class is built by hand, bypassing Spring DI, by a couple of integration tests) compiling
     * unchanged. Builds its own {@link FileAttachmentBlobRepository} off the SAME {@code jdbc}, so
     * in an integration test (a real Postgres connection) blob reads/writes through this instance
     * are fully real, not a stub.
     */
    public LeaveAttachmentRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new FileAttachmentBlobRepository(jdbc));
    }

    /**
     * V132 storage-durability fix: the leave domain writes ONLY to the database now -- {@code
     * filePath} here is the bare correlation key {@link
     * th.co.glr.hr.attachment.FileStorageService#storeInDatabase} computed (never a disk path), and
     * {@code content} is inserted into {@code hr.file_attachment_blob} in the SAME transaction as
     * the {@code hr.file_attachment} row (this method's caller, {@code LeaveService#submit}, is
     * {@code @Transactional}), flipping {@code storage_state} straight to {@code DATABASE} -- a
     * newly-created leave attachment is never briefly {@code DISK_LEGACY}.
     */
    public LeaveAttachmentDto saveWithContent(long leaveRequestId, String fileName, String storageKey,
                                              String mimeType, Long fileSize, long uploadedBy, byte[] content) {
        LeaveAttachmentDto saved = save(leaveRequestId, fileName, storageKey, mimeType, fileSize, uploadedBy);
        blobs.saveContent(saved.id(), content);
        return saved;
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

    /**
     * GET /api/leave/attachments/{attachmentId} (Phase A0b): storage path + owning leave request, so
     * {@code LeaveService#resolveAttachmentForDownload} can authorize BEFORE the controller serves
     * the file -- mirrors {@code SpecialMoneyRepository#findAttachmentLocation}/{@code
     * AttachmentLocation}, the pattern this phase's brief names to copy. One query, scoped to {@code
     * domain = 'leave'} the same way every other method here is.
     */
    public Optional<AttachmentLocation> findAttachmentLocation(long attachmentId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT owner_id, file_name, file_path, mime_type, storage_state
                  FROM hr.file_attachment
                 WHERE attachment_id = :id
                   AND domain = :domain
                """,
                Map.of("id", attachmentId, "domain", DOMAIN),
                (rs, rowNum) -> new AttachmentLocation(
                    rs.getLong("owner_id"),
                    rs.getString("file_name"),
                    rs.getString("file_path"),
                    rs.getString("mime_type"),
                    rs.getString("storage_state"))));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    /**
     * {@code leaveRequestId} is {@code hr.file_attachment.owner_id} for {@code domain = 'leave'}.
     *
     * <p>{@code storageState} is one of {@code DATABASE} (bytes in {@code hr.file_attachment_blob},
     * read via {@link th.co.glr.hr.attachment.FileAttachmentBlobRepository#findContent}), {@code
     * DISK_LEGACY} (bytes may still be at {@code storagePath} on disk -- check with {@code
     * FileStorageService#existsOnDisk} before trusting it), or {@code MISSING} (bytes confirmed
     * gone). See V132's migration comment for the full rationale.
     */
    public record AttachmentLocation(long leaveRequestId, String fileName, String storagePath, String mimeType,
                                      String storageState) {
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
