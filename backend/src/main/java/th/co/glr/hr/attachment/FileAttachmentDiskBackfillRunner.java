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

/** V134 storage-durability fix, step 3 of the migration plan: moves every DISK_LEGACY hr.file_attachment row's
 * bytes into hr.file_attachment_blob. It has to run in the app process, where the files (if they still exist
 * at all) actually are. */
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
        // REQUIRES_NEW: each row commits/rolls back entirely on its own, independent of whatever transaction (if any)
        // the ApplicationRunner infrastructure itself is running in.
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
        // The durable, greppable record of how many HR evidence files this app has actually lost -- see this class's
        // javadoc for why that number can be nonzero and unrecoverable (Render has no disk attached and never did).
        tally.forEach((domain, counts) -> log.info(
            "File-attachment disk backfill [domain={}]: migrated={} missing={} skippedOversized={} unresolved={}",
            domain, counts[Outcome.MIGRATED.ordinal()], counts[Outcome.MISSING.ordinal()],
            counts[Outcome.SKIPPED_OVERSIZED.ordinal()], counts[Outcome.UNRESOLVED.ordinal()]));
    }

    /** UNRESOLVED = still DISK_LEGACY in the database -- retried on the next boot. */
    private enum Outcome { MIGRATED, MISSING, SKIPPED_OVERSIZED, UNRESOLVED }

    private Outcome migrateOneRowInItsOwnTransaction(FileAttachmentBlobRepository.LegacyRow row) {
        try {
            Outcome outcome = perRowTransaction.execute(status -> migrateOne(row));
            return outcome == null ? Outcome.UNRESOLVED : outcome;
        } catch (Exception exception) {
            // Deliberately does NOT mark the row MISSING here. The only way execution reaches here is an unexpected
            // failure AFTER that point, most plausibly blobs.saveContent's INSERT itself (a pooler timeout on a large row,
            // a transient connection failure, ...).
            log.warn("File-attachment disk backfill: attachment_id={} domain={} failed unexpectedly "
                + "after the file was confirmed present on disk -- leaving it DISK_LEGACY for the "
                + "next boot rather than risk marking a recoverable file MISSING: {}",
                row.attachmentId(), row.domain(), exception.toString());
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
            // The file genuinely exists -- this is not an orphan. Left DISK_LEGACY (not MISSING) so it is reconsidered
            // (e.g. after a manual review, or if the cap is raised) rather than permanently written off; never read into
            // memory, so it cannot OOM this backfill.
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
