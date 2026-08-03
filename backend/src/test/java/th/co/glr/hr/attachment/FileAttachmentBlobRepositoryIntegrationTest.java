package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * V132 storage-durability fix: real-Postgres coverage of {@link FileAttachmentBlobRepository} --
 * the byte round-trip through {@code hr.file_attachment_blob}, and the structural guarantee the
 * migration's whole point depends on: listing/resolving an {@code hr.file_attachment} row never
 * loads its payload, because the payload lives in a SIBLING table, not a column on that row.
 */
class FileAttachmentBlobRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    void savedContentRoundTripsExactly() {
        long attachmentId = insertAttachment("leave", 1L, "DISK_LEGACY");
        FileAttachmentBlobRepository blobs = new FileAttachmentBlobRepository(jdbc);
        byte[] original = "หลักฐานประกอบการยื่นแบบ -- exact bytes must round-trip".getBytes(StandardCharsets.UTF_8);

        blobs.saveContent(attachmentId, original);

        byte[] readBack = blobs.findContent(attachmentId).orElseThrow();
        assertThat(readBack).isEqualTo(original);
    }

    @Test
    void savingContentFlipsStorageStateToDatabase() {
        long attachmentId = insertAttachment("factory_quote", 1L, "DISK_LEGACY");
        FileAttachmentBlobRepository blobs = new FileAttachmentBlobRepository(jdbc);

        blobs.saveContent(attachmentId, "bytes".getBytes(StandardCharsets.UTF_8));

        String state = jdbc.queryForObject(
            "SELECT storage_state FROM hr.file_attachment WHERE attachment_id = :id",
            Map.of("id", attachmentId), String.class);
        assertThat(state).isEqualTo("DATABASE");
    }

    @Test
    void findContentIsEmptyWhenNoBlobRowExists() {
        long attachmentId = insertAttachment("leave", 1L, "MISSING");
        FileAttachmentBlobRepository blobs = new FileAttachmentBlobRepository(jdbc);

        assertThat(blobs.findContent(attachmentId)).isEmpty();
    }

    @Test
    void findDiskLegacyRowsOnlyReturnsRowsStillInThatState() {
        long legacy = insertAttachment("leave", 1L, "DISK_LEGACY");
        insertAttachment("leave", 1L, "DATABASE");
        insertAttachment("leave", 1L, "MISSING");
        FileAttachmentBlobRepository blobs = new FileAttachmentBlobRepository(jdbc);

        assertThat(blobs.findDiskLegacyRows())
            .extracting(FileAttachmentBlobRepository.LegacyRow::attachmentId)
            .containsExactly(legacy);
    }

    /**
     * The structural guarantee this whole migration depends on: {@code hr.file_attachment} itself
     * has NO {@code content}/bytea column -- the payload can only ever live in the sibling blob
     * table, so no existing (or future) plain {@code SELECT ... FROM hr.file_attachment} query can
     * accidentally start loading payloads. A regression here would mean someone added a bytea
     * column directly onto the row table instead of using the sibling.
     */
    @Test
    void fileAttachmentTableItselfHasNoContentColumn() {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = 'hr' AND table_name = 'file_attachment' AND column_name = 'content'
            """, Map.of(), Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void fileAttachmentBlobTableIsTheOnlyPlaceContentLives() {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = 'hr' AND table_name = 'file_attachment_blob' AND column_name = 'content'
            """, Map.of(), Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private long insertAttachment(String domain, long ownerId, String storageState) {
        return jdbc.queryForObject("""
            INSERT INTO hr.file_attachment (domain, owner_id, file_name, file_path, mime_type, file_size, storage_state)
            VALUES (:domain, :ownerId, 'evidence.pdf', :domain || '/' || :ownerId || '/dummy.pdf',
                    'application/pdf', 1024, :storageState)
            RETURNING attachment_id
            """, new MapSqlParameterSource()
                .addValue("domain", domain)
                .addValue("ownerId", ownerId)
                .addValue("storageState", storageState),
            Long.class);
    }
}
