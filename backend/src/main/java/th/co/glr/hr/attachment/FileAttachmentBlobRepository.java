package th.co.glr.hr.attachment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * V134 storage-durability fix: CRUD against {@code hr.file_attachment_blob} (the byte payload,
 * kept in a table sibling to {@code hr.file_attachment} so that listing/resolving an attachment
 * never loads its content) and the {@code storage_state} column on {@code hr.file_attachment}
 * itself.
 *
 * <p>Shared across all four {@code hr.file_attachment} domains -- leave, commission-invoice
 * evidence, {@code tax_allowance_declaration}, {@code factory_quote} -- because the blob table and
 * the state column are properties of the shared row, not of any one domain's own repository.
 *
 * <p>{@link #findContent} must only ever be called AFTER the caller's domain-specific service has
 * already authorized the request (e.g. {@code LeaveService#resolveAttachmentForDownload}) -- this
 * repository has no authorization logic of its own, by design, so that the authorization decision
 * always lives in exactly one place per domain.
 */
@Repository
public class FileAttachmentBlobRepository {
    public static final String DATABASE = "DATABASE";
    public static final String DISK_LEGACY = "DISK_LEGACY";
    public static final String MISSING = "MISSING";

    private final NamedParameterJdbcTemplate jdbc;

    public FileAttachmentBlobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the blob (idempotent -- {@code ON CONFLICT DO NOTHING}, so a re-run of the backfill
     * over a row it already migrated changes nothing) and flips {@code storage_state} to {@code
     * DATABASE}. Callers run this inside their own transaction (upload path: the same transaction
     * as the {@code hr.file_attachment} insert; backfill: one transaction per row).
     */
    public void saveContent(long attachmentId, byte[] content) {
        jdbc.update("""
            INSERT INTO hr.file_attachment_blob (attachment_id, content)
            VALUES (:id, :content)
            ON CONFLICT (attachment_id) DO NOTHING
            """,
            new MapSqlParameterSource().addValue("id", attachmentId).addValue("content", content));
        markState(attachmentId, DATABASE);
    }

    /** Bytes for a {@code DATABASE}-state attachment. Read ONLY on download, after authorization. */
    public Optional<byte[]> findContent(long attachmentId) {
        try {
            byte[] content = jdbc.queryForObject(
                "SELECT content FROM hr.file_attachment_blob WHERE attachment_id = :id",
                Map.of("id", attachmentId), byte[].class);
            return Optional.ofNullable(content);
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public void markState(long attachmentId, String state) {
        jdbc.update("UPDATE hr.file_attachment SET storage_state = :state WHERE attachment_id = :id",
            Map.of("state", state, "id", attachmentId));
    }

    /**
     * The backfill's work queue: every row still on the pre-migration disk path, across every
     * domain. {@code file_path} is included so the backfill can resolve it against disk without a
     * second query per row.
     */
    public List<LegacyRow> findDiskLegacyRows() {
        return jdbc.query(
            "SELECT attachment_id, domain, file_path FROM hr.file_attachment WHERE storage_state = :state",
            Map.of("state", DISK_LEGACY),
            (rs, rowNum) -> new LegacyRow(rs.getLong("attachment_id"), rs.getString("domain"), rs.getString("file_path")));
    }

    public record LegacyRow(long attachmentId, String domain, String filePath) {
    }
}
