package th.co.glr.hr.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V132 storage-durability fix, step 3 of the migration plan: moves every {@code DISK_LEGACY} {@code
 * hr.file_attachment} row's bytes into {@code hr.file_attachment_blob}.
 *
 * <p>This CANNOT be a Flyway SQL migration: the bytes live on the APP container's filesystem, and
 * {@code pg_read_binary_file} is superuser-only and reads files on the DATABASE server -- a
 * different machine entirely on Supabase. It has to run in the app process, where the files (if
 * they still exist at all) actually are. Runs once per boot, as an {@link ApplicationRunner}, after
 * Flyway (migrations run before the application context finishes starting, so {@code
 * hr.file_attachment.storage_state} already exists by the time this fires).
 *
 * <p>Safe to re-run on every boot, forever: it only ever selects {@code storage_state =
 * 'DISK_LEGACY'} rows, and a row leaves that state (to {@code DATABASE} or {@code MISSING}) the
 * moment it is processed -- so a second run sees a strict subset (rows this run didn't reach) or
 * nothing at all. One Postgres transaction PER ROW so a single failure (a corrupt file, a
 * permission error, anything) cannot abort the whole batch; nothing here is allowed to prevent the
 * application from finishing startup.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class FileAttachmentDiskBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FileAttachmentDiskBackfillRunner.class);

    private final FileAttachmentBlobRepository blobs;
    private final FileStorageService fileStorage;
    private final TransactionTemplate perRowTransaction;

    public FileAttachmentDiskBackfillRunner(FileAttachmentBlobRepository blobs, FileStorageService fileStorage,
                                             PlatformTransactionManager transactionManager) {
        this.blobs = blobs;
        this.fileStorage = fileStorage;
        this.perRowTransaction = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: each row commits/rolls back entirely on its own, independent of whatever
        // transaction (if any) the ApplicationRunner infrastructure itself is running in.
        this.perRowTransaction.setPropagationBehavior(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void run(ApplicationArguments args) {
        List<FileAttachmentBlobRepository.LegacyRow> rows = blobs.findDiskLegacyRows();
        if (rows.isEmpty()) {
            log.info("File-attachment disk backfill: no DISK_LEGACY rows found, nothing to do.");
            return;
        }
        // domain -> [migrated, missing, skippedOversized, unresolved]
        Map<String, int[]> tally = new TreeMap<>();
        for (FileAttachmentBlobRepository.LegacyRow row : rows) {
            Outcome outcome = migrateOneRowInItsOwnTransaction(row);
            int[] counts = tally.computeIfAbsent(row.domain(), d -> new int[Outcome.values().length]);
            counts[outcome.ordinal()]++;
        }
        // The durable, greppable record of how many HR evidence files this app has actually lost --
        // see this class's javadoc for why that number can be nonzero and unrecoverable (Render has
        // no disk attached and never did).
        tally.forEach((domain, counts) -> log.info(
            "File-attachment disk backfill [domain={}]: migrated={} missing={} skippedOversized={} unresolved={}",
            domain, counts[Outcome.MIGRATED.ordinal()], counts[Outcome.MISSING.ordinal()],
            counts[Outcome.SKIPPED_OVERSIZED.ordinal()], counts[Outcome.UNRESOLVED.ordinal()]));
    }

    /** UNRESOLVED = still DISK_LEGACY in the database (its state update itself failed) -- retried on the next boot. */
    private enum Outcome { MIGRATED, MISSING, SKIPPED_OVERSIZED, UNRESOLVED }

    private Outcome migrateOneRowInItsOwnTransaction(FileAttachmentBlobRepository.LegacyRow row) {
        try {
            Outcome outcome = perRowTransaction.execute(status -> migrateOne(row));
            return outcome == null ? Outcome.UNRESOLVED : outcome;
        } catch (Exception exception) {
            log.warn("File-attachment disk backfill: attachment_id={} domain={} failed unexpectedly, "
                + "attempting to mark MISSING: {}", row.attachmentId(), row.domain(), exception.toString());
            return markMissingBestEffort(row);
        }
    }

    private Outcome markMissingBestEffort(FileAttachmentBlobRepository.LegacyRow row) {
        try {
            perRowTransaction.execute(status -> {
                blobs.markState(row.attachmentId(), FileAttachmentBlobRepository.MISSING);
                return null;
            });
            return Outcome.MISSING;
        } catch (Exception exception) {
            log.error("File-attachment disk backfill: could not even mark attachment_id={} domain={} "
                + "MISSING, leaving it DISK_LEGACY for the next run: {}",
                row.attachmentId(), row.domain(), exception.toString());
            // Row stays DISK_LEGACY in the database -- picked up again on the next boot. Not counted
            // as MISSING since it genuinely is not, yet.
            return Outcome.UNRESOLVED;
        }
    }

    /** Runs inside {@link #perRowTransaction}. Never throws for an ordinary "file's gone" case -- only for genuine infra failure, which the caller catches. */
    private Outcome migrateOne(FileAttachmentBlobRepository.LegacyRow row) {
        Path resolved = fileStorage.resolveDiskPath(row.filePath());
        if (resolved == null || !Files.isReadable(resolved)) {
            blobs.markState(row.attachmentId(), FileAttachmentBlobRepository.MISSING);
            return Outcome.MISSING;
        }
        long size;
        try {
            size = Files.size(resolved);
        } catch (IOException exception) {
            blobs.markState(row.attachmentId(), FileAttachmentBlobRepository.MISSING);
            return Outcome.MISSING;
        }
        if (size > fileStorage.maxUploadBytes()) {
            // The file genuinely exists -- this is not an orphan. Left DISK_LEGACY (not MISSING) so
            // it is reconsidered (e.g. after a manual review, or if the cap is raised) rather than
            // permanently written off; never read into memory, so it cannot OOM this backfill.
            log.warn("File-attachment disk backfill: attachment_id={} domain={} is {} bytes, over the "
                + "{}-byte cap -- skipped, left DISK_LEGACY for manual handling.",
                row.attachmentId(), row.domain(), size, fileStorage.maxUploadBytes());
            return Outcome.SKIPPED_OVERSIZED;
        }
        byte[] content;
        try {
            content = fileStorage.readDiskBytes(row.filePath());
        } catch (IOException exception) {
            blobs.markState(row.attachmentId(), FileAttachmentBlobRepository.MISSING);
            return Outcome.MISSING;
        }
        blobs.saveContent(row.attachmentId(), content);
        return Outcome.MIGRATED;
    }
}
