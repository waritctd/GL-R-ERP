package th.co.glr.hr.leave;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code hr.leave_policy_document} (+ {@code hr.leave_policy_document_blob}) — the §5 announcement
 * PDF a rules-tab reader may download, and HR/CEO may upload a new version of. See V133's migration
 * comment for the schema rationale: bytes split from metadata, effective-dated and never-overwritten
 * versions instead of a single mutable row.
 */
@Repository
public class LeavePolicyDocumentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public LeavePolicyDocumentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The document currently in force: the row with the latest {@code effective_from} that is not
     * in the future. Never touches {@code leave_policy_document_blob} — a caller that only needs to
     * know WHETHER a document exists (the frontend's HEAD availability probe) or its metadata pays
     * no cost for the payload.
     */
    public Optional<LeavePolicyDocumentDto> findCurrent() {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                SELECT document_id, file_name, mime_type, file_size, effective_from, uploaded_at
                  FROM hr.leave_policy_document
                 WHERE effective_from <= CURRENT_DATE
                 ORDER BY effective_from DESC, document_id DESC
                 LIMIT 1
                """,
                Map.of(),
                (rs, i) -> new LeavePolicyDocumentDto(
                    rs.getLong("document_id"),
                    rs.getString("file_name"),
                    rs.getString("mime_type"),
                    rs.getLong("file_size"),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getTimestamp("uploaded_at").toInstant())));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Bytes only, by id — read on an actual download, never as a side effect of {@link #findCurrent()}. */
    public Optional<byte[]> findContent(long documentId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT content FROM hr.leave_policy_document_blob WHERE document_id = :id",
                Map.of("id", documentId), byte[].class));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Inserts a brand-new version — never updates or replaces an existing row. A superseded version
     * stays byte-for-byte retrievable (nothing here ever calls {@code findContent} on a stale id and
     * gets nothing back); only {@link #findCurrent()}'s answer changes as of a new row's
     * {@code effective_from}. Metadata and bytes are written in one transaction so a crash between
     * the two INSERTs can never leave a metadata row with no blob (or vice versa).
     */
    @Transactional
    public LeavePolicyDocumentDto insert(
            String fileName, String mimeType, byte[] content, LocalDate effectiveFrom, Long uploadedBy) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO hr.leave_policy_document (file_name, mime_type, file_size, effective_from, uploaded_by)
            VALUES (:fileName, :mimeType, :fileSize, :effectiveFrom, :uploadedBy)
            """,
            new MapSqlParameterSource()
                .addValue("fileName", fileName)
                .addValue("mimeType", mimeType)
                .addValue("fileSize", (long) content.length)
                .addValue("effectiveFrom", effectiveFrom)
                .addValue("uploadedBy", uploadedBy),
            key, new String[] {"document_id"});
        long documentId = key.getKey().longValue();
        jdbc.update(
            "INSERT INTO hr.leave_policy_document_blob (document_id, content) VALUES (:id, :content)",
            new MapSqlParameterSource().addValue("id", documentId).addValue("content", content));
        return new LeavePolicyDocumentDto(documentId, fileName, mimeType, content.length, effectiveFrom, Instant.now());
    }
}
