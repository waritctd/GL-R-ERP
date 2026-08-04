package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * V134 storage-durability fix, step 3: real-Postgres coverage of {@link
 * FileAttachmentDiskBackfillRunner} -- the one-shot Java migrator that moves {@code DISK_LEGACY}
 * rows' bytes into {@code hr.file_attachment_blob} because Flyway/pg_read_binary_file cannot reach
 * the app container's filesystem from the database server.
 */
class FileAttachmentDiskBackfillRunnerIntegrationTest extends AbstractPostgresIntegrationTest {

    @TempDir
    Path uploadsDir;

    private FileAttachmentBlobRepository blobs;
    private FileStorageService fileStorage;
    private PlatformTransactionManager txManager;

    @BeforeEach
    void wireRealCollaborators() {
        blobs = new FileAttachmentBlobRepository(jdbc);
        fileStorage = new FileStorageService(uploadsDir.toString());
        txManager = new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource());
    }

    @Test
    void resolvableFileIsMigratedToDatabaseWithCorrectBytes() throws Exception {
        byte[] content = "หลักฐานประกอบ".getBytes(StandardCharsets.UTF_8);
        Path file = uploadsDir.resolve("leave").resolve("1");
        Files.createDirectories(file);
        Path leafFile = file.resolve("cert.pdf");
        Files.write(leafFile, content);
        long attachmentId = insertLegacyRow("leave", 1L, leafFile.toString());

        runner().run(null);

        assertThat(stateOf(attachmentId)).isEqualTo("DATABASE");
        assertThat(blobs.findContent(attachmentId).orElseThrow()).isEqualTo(content);
    }

    @Test
    void unresolvableFileIsMarkedMissing() {
        long attachmentId = insertLegacyRow("leave", 1L, uploadsDir.resolve("nowhere").resolve("gone.pdf").toString());

        runner().run(null);

        assertThat(stateOf(attachmentId)).isEqualTo("MISSING");
        assertThat(blobs.findContent(attachmentId)).isEmpty();
    }

    @Test
    void reRunningIsIdempotentOnceARowIsMigrated() throws Exception {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        Path file = uploadsDir.resolve("leave").resolve("2");
        Files.createDirectories(file);
        Path leafFile = file.resolve("cert.pdf");
        Files.write(leafFile, content);
        long attachmentId = insertLegacyRow("leave", 2L, leafFile.toString());

        runner().run(null);
        assertThat(stateOf(attachmentId)).isEqualTo("DATABASE");

        // Second run: the row is no longer DISK_LEGACY, so it is not touched again -- re-running
        // changes nothing, even if the underlying file were deleted in between (simulating exactly
        // that by removing it, to prove the second run never even looks).
        Files.delete(leafFile);
        runner().run(null);

        assertThat(stateOf(attachmentId)).isEqualTo("DATABASE");
        assertThat(blobs.findContent(attachmentId).orElseThrow()).isEqualTo(content);
    }

    @Test
    void missingUploadsDirEntirelyMarksEverythingMissingWithoutThrowing() {
        FileStorageService fileStorageWithNoUploadsDir =
            new FileStorageService(uploadsDir.resolve("this-does-not-exist-at-all").toString());
        long attachmentId = insertLegacyRow("factory_quote", 5L, "factory_quote/5/whatever.pdf");

        FileAttachmentDiskBackfillRunner runnerWithMissingDir =
            new FileAttachmentDiskBackfillRunner(blobs, fileStorageWithNoUploadsDir, txManager);

        runnerWithMissingDir.run(null);

        assertThat(stateOf(attachmentId)).isEqualTo("MISSING");
    }

    @Test
    void unexpectedFailureAfterFileIsConfirmedPresentLeavesRowDiskLegacyNotMissing() throws Exception {
        // The bug this guards against: a file that genuinely exists and is readable on disk must
        // never be marked MISSING just because persisting its bytes failed for an unrelated reason
        // (a DB timeout, a transient connection failure, ...). Simulated here by a saveContent that
        // always throws, standing in for that class of failure -- migrateOne has already confirmed
        // the file is present and readable by the time this fires, exactly like the real failure
        // mode (blobs.saveContent's INSERT itself failing after a successful disk read).
        byte[] content = "content that genuinely exists on disk".getBytes(StandardCharsets.UTF_8);
        Path file = uploadsDir.resolve("leave").resolve("4");
        Files.createDirectories(file);
        Path leafFile = file.resolve("cert.pdf");
        Files.write(leafFile, content);
        long attachmentId = insertLegacyRow("leave", 4L, leafFile.toString());

        FileAttachmentBlobRepository blobsThatFailToPersist = new FileAttachmentBlobRepository(jdbc) {
            @Override
            public void saveContent(long id, byte[] bytes) {
                throw new RuntimeException("simulated: DB write failed after the file was already confirmed readable");
            }
        };

        new FileAttachmentDiskBackfillRunner(blobsThatFailToPersist, fileStorage, txManager).run(null);

        // Not MISSING: the file is still sitting right there on disk, unread by anything else.
        assertThat(stateOf(attachmentId)).isEqualTo("DISK_LEGACY");
        assertThat(blobs.findContent(attachmentId)).isEmpty();
        assertThat(Files.exists(leafFile)).isTrue();
    }

    @Test
    void oversizedFileIsSkippedAndLeftDiskLegacyNotReadIntoMemory() throws Exception {
        // Cap tiny on purpose so a small file still counts as "oversized" without allocating a
        // large buffer in this test.
        FileStorageService tinyCapFileStorage = new FileStorageService(uploadsDir.toString(), 4L);
        Path file = uploadsDir.resolve("leave").resolve("3");
        Files.createDirectories(file);
        Path leafFile = file.resolve("cert.pdf");
        Files.write(leafFile, "this is definitely more than four bytes".getBytes(StandardCharsets.UTF_8));
        long attachmentId = insertLegacyRow("leave", 3L, leafFile.toString());

        new FileAttachmentDiskBackfillRunner(blobs, tinyCapFileStorage, txManager).run(null);

        assertThat(stateOf(attachmentId)).isEqualTo("DISK_LEGACY");
        assertThat(blobs.findContent(attachmentId)).isEmpty();
    }

    private FileAttachmentDiskBackfillRunner runner() {
        return new FileAttachmentDiskBackfillRunner(blobs, fileStorage, txManager);
    }

    private String stateOf(long attachmentId) {
        return jdbc.queryForObject(
            "SELECT storage_state FROM hr.file_attachment WHERE attachment_id = :id",
            Map.of("id", attachmentId), String.class);
    }

    private long insertLegacyRow(String domain, long ownerId, String filePath) {
        return jdbc.queryForObject("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, storage_state)
            VALUES (:domain, :ownerId, 'evidence.pdf', :filePath, 'application/pdf', 1024, 'DISK_LEGACY')
            RETURNING attachment_id
            """, new MapSqlParameterSource()
                .addValue("domain", domain)
                .addValue("ownerId", ownerId)
                .addValue("filePath", filePath),
            Long.class);
    }
}
