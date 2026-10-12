package th.co.glr.hr.dealquotation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;
import th.co.glr.hr.attachment.FileStorageService.StoredContent;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationAttachmentDto;

/**
 * The {@code hr.file_attachment} rows that hold a quotation's เอกสารแนบ ({@code domain =
 * 'deal_quotation'}, {@code owner_id} = the quotation). Bytes go through {@link
 * FileAttachmentBlobRepository}, as they do for leave, tax-allowance and factory-quote files.
 *
 * <p>No authorization here. {@link DealQuotationAttachmentService} decides before it calls.
 */
@Repository
public class DealQuotationAttachmentRepository {
    /** {@code hr.file_attachment.domain} for these rows. Every statement on that table states it,
     * so an id that belongs to another domain's file is never found there. {@link #findContent}
     * reads the bytes by id alone and must only follow {@link #findById}. {@code owner_id} has no
     * foreign key, and a leave request or a factory quote can share a quotation's number. */
    private static final String DOMAIN = "deal_quotation";

    /** The longest revision chain {@link #findForQuotation} walks. Chains are a few documents
     * long, so this only stops the walk if a row were ever made its own ancestor. */
    private static final int MAX_CHAIN_DEPTH = 50;

    private static final String NAME_OF_UPLOADER =
        "NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '')";

    private final NamedParameterJdbcTemplate jdbc;
    private final FileAttachmentBlobRepository blobs;

    public DealQuotationAttachmentRepository(NamedParameterJdbcTemplate jdbc, FileAttachmentBlobRepository blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /** The file with this id, if it is a quotation's. {@code inherited} is false, because the row
     * is described as the quotation it is stored on sees it. */
    public Optional<DealQuotationAttachmentDto> findById(long attachmentId) {
        return jdbc.query("""
            SELECT fa.attachment_id, q.quotation_id, q.number, fa.file_name, fa.mime_type, fa.file_size,
                   fa.uploaded_by, %s AS uploaded_by_name, fa.uploaded_at, 0 AS depth
              FROM hr.file_attachment fa
              JOIN sales.quotation q ON q.quotation_id = fa.owner_id
              LEFT JOIN hr.employee e ON e.employee_id = fa.uploaded_by
             WHERE fa.attachment_id = :attachmentId
               AND fa.domain = :domain
            """.formatted(NAME_OF_UPLOADER),
            Map.of("attachmentId", attachmentId, "domain", DOMAIN),
            (rs, rowNum) -> map(rs)).stream().findFirst();
    }

    /**
     * The quotation's own files, newest first, then the files of each document it revises,
     * nearest document first. {@code parent_quotation_id} is what makes a document a revision. A
     * reorder carries none, so it lists nothing of its source.
     */
    public List<DealQuotationAttachmentDto> findForQuotation(long quotationId) {
        return jdbc.query("""
            WITH RECURSIVE chain AS (
                SELECT q.quotation_id, q.number, q.parent_quotation_id, 0 AS depth
                  FROM sales.quotation q
                 WHERE q.quotation_id = :quotationId
                UNION ALL
                SELECT earlier.quotation_id, earlier.number, earlier.parent_quotation_id, chain.depth + 1
                  FROM sales.quotation earlier
                  JOIN chain ON earlier.quotation_id = chain.parent_quotation_id
                 WHERE chain.depth < :maxDepth
            )
            SELECT fa.attachment_id, chain.quotation_id, chain.number, fa.file_name, fa.mime_type, fa.file_size,
                   fa.uploaded_by, %s AS uploaded_by_name, fa.uploaded_at, chain.depth
              FROM chain
              JOIN hr.file_attachment fa ON fa.domain = :domain AND fa.owner_id = chain.quotation_id
              LEFT JOIN hr.employee e ON e.employee_id = fa.uploaded_by
             ORDER BY chain.depth, fa.uploaded_at DESC, fa.attachment_id DESC
            """.formatted(NAME_OF_UPLOADER),
            Map.of("quotationId", quotationId, "domain", DOMAIN, "maxDepth", MAX_CHAIN_DEPTH),
            (rs, rowNum) -> map(rs));
    }

    /** How many files are stored on this quotation itself. Inherited files are not its own. */
    public int countOwn(long quotationId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM hr.file_attachment WHERE domain = :domain AND owner_id = :quotationId",
            Map.of("domain", DOMAIN, "quotationId", quotationId), Integer.class);
    }

    /** Stores the row and its bytes, and answers the new file's id. The caller's transaction makes
     * the two writes one. */
    public long save(long quotationId, StoredContent stored, long uploadedBy) {
        Long attachmentId = jdbc.queryForObject("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by)
            VALUES
                (:domain, :quotationId, :fileName, :storageKey, :mimeType, :fileSize, :uploadedBy)
            RETURNING attachment_id
            """,
            new MapSqlParameterSource()
                .addValue("domain", DOMAIN)
                .addValue("quotationId", quotationId)
                .addValue("fileName", stored.fileName())
                .addValue("storageKey", stored.storageKey())
                .addValue("mimeType", stored.mimeType())
                .addValue("fileSize", stored.fileSize())
                .addValue("uploadedBy", uploadedBy),
            Long.class);
        blobs.saveContent(attachmentId, stored.content());
        return attachmentId;
    }

    /** The bytes of a file {@link #findById} has already found. */
    public Optional<byte[]> findContent(long attachmentId) {
        return blobs.findContent(attachmentId);
    }

    /**
     * Removes the file, row and bytes, if the quotation it is stored on is a DRAFT at the moment
     * of the delete, and answers how many rows went. Zero means the quotation is no longer a
     * draft. The status is part of the statement, as it is in every DRAFT-only write of {@link
     * DealQuotationRepository}, so a first submit that lands between the service's checks and
     * this delete cannot take a file away from the approver. A resubmission leaves the row a
     * DRAFT, so this statement cannot see it. The service closes that one with the deal's lock.
     */
    public int deleteFromDraft(long attachmentId) {
        return jdbc.update("""
            DELETE FROM hr.file_attachment fa
             USING sales.quotation q
             WHERE fa.attachment_id = :attachmentId
               AND fa.domain = :domain
               AND q.quotation_id = fa.owner_id
               AND q.doc_status = 'DRAFT'
            """, Map.of("attachmentId", attachmentId, "domain", DOMAIN));
    }

    private static DealQuotationAttachmentDto map(ResultSet rs) throws SQLException {
        return new DealQuotationAttachmentDto(
            rs.getLong("attachment_id"),
            rs.getLong("quotation_id"),
            rs.getString("number"),
            rs.getString("file_name"),
            rs.getString("mime_type"),
            rs.getObject("file_size", Long.class),
            rs.getObject("uploaded_by", Long.class),
            rs.getString("uploaded_by_name"),
            rs.getTimestamp("uploaded_at").toInstant(),
            rs.getInt("depth") > 0);
    }
}
