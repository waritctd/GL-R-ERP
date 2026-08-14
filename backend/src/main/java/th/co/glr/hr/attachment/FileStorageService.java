package th.co.glr.hr.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.common.ApiException;

@Service
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path uploadsDir;
    private final long maxUploadBytes;

    /**
     * V134 storage-durability fix: {@code app.uploads-max-bytes} is an explicit server-side cap,
     * separate from {@code spring.servlet.multipart.max-file-size} ({@code
     * ${APP_MAX_FILE_SIZE:10MB}} in application.yml). The multipart property is env-overridable and
     * therefore not a reliable cap on its own -- anyone who changes {@code APP_MAX_FILE_SIZE} for
     * an unrelated reason (e.g. a large ticket attachment) would silently also raise the size of
     * every file this service is willing to pull into memory for database storage. Checked against
     * {@code MultipartFile#getSize()} (already known from the spooled upload, no read required)
     * BEFORE any byte is read into a Java array -- see {@link #requireWithinSizeCap}.
     */
    @Autowired
    public FileStorageService(@Value("${app.uploads-dir:./uploads}") String uploadsDir,
                               @Value("${app.uploads-max-bytes:10485760}") long maxUploadBytes) {
        this.uploadsDir = Paths.get(uploadsDir);
        this.maxUploadBytes = maxUploadBytes;
    }

    /**
     * Legacy/test convenience overload -- keeps every existing {@code new FileStorageService(dir)}
     * call site (this class is constructed directly, bypassing Spring DI, by dozens of integration
     * tests across this codebase) compiling unchanged, using the same default {@code
     * app.uploads-max-bytes} would inject in production.
     */
    public FileStorageService(String uploadsDir) {
        this(uploadsDir, 10_485_760L);
    }

    public long maxUploadBytes() {
        return maxUploadBytes;
    }

    public StoredFile store(String domain, long ownerId, MultipartFile file, Set<String> allowedMimeTypes) {
        Validated validated = validate(file, allowedMimeTypes);
        String extension = extensionOf(validated.originalName());
        Path dir = uploadsDir.resolve(domain).resolve(String.valueOf(ownerId));
        Path dest = dir.resolve(UUID.randomUUID() + extension);

        try {
            Files.createDirectories(dir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new StoredFile(validated.originalName(), dest.toString(), validated.mime(), file.getSize());
    }

    /**
     * Ties a file {@link #store} has just written to the OUTCOME of the transaction the caller is
     * running in: if that transaction rolls back, the file is deleted; if it commits, nothing
     * happens. Call it immediately after {@code store(...)} at any site that stores inside a
     * {@code @Transactional} method.
     *
     * <p><b>The problem this exists to solve.</b> A disk write is not transactional. {@link #store}
     * copies bytes to disk the moment it is called, and Postgres knows nothing about it — so when
     * the surrounding transaction rolls back, the database row that referenced the file disappears
     * while the file itself stays on disk forever. Nothing references it and no sweeper exists to
     * collect it: unbounded growth on the uploads volume, plus a quiet data-leak surface, because
     * the orphan can be a customer invoice or a pricing attachment.
     *
     * <p><b>Why a synchronization rather than moving the write out of the transaction.</b> Both
     * commission call sites derive the storage path from an {@code invoiceId} that
     * {@code CommissionRepository#createInvoice} generates INSIDE the transaction, so there is no
     * "outside" to hoist the write to without changing the stored path. {@code
     * PricingRequestService#uploadAttachment} knows its id up front but runs every authorization
     * and status gate before storing, and hoisting the write above those gates would store first
     * and authorize second — the exact hole {@code SpecialMoneyController#addAttachment} already
     * carries a comment warning about (any session could fill the uploads volume by POSTing to
     * someone else's record). Keeping the write where it is and cleaning up on rollback leaves the
     * success path byte-for-byte identical and preserves authorize-before-store everywhere.
     *
     * <p>Follows {@code NotificationService#sendEmailAfterCommit}, the existing solution to the
     * same shape in this codebase: guard on {@link
     * TransactionSynchronizationManager#isSynchronizationActive()}, then register a {@link
     * TransactionSynchronization}. With no transaction active there is nothing that can roll back,
     * so the file is the caller's to keep and this is a no-op — that is what makes it safe to call
     * unconditionally, and why the two non-transactional controller upload paths ({@code
     * AttachmentController#upload}, {@code SpecialMoneyController#addAttachment}) would be
     * unaffected if they ever adopted it.
     *
     * <p><b>Deletes on {@code STATUS_ROLLED_BACK} only — deliberately not on {@code
     * STATUS_UNKNOWN}.</b> The two failure directions are not symmetric. Leaving a file behind
     * after a commit-outcome we could not determine costs disk space and is recoverable by hand;
     * deleting a file whose row DID commit produces a permanently broken reference and a
     * user-visible "file missing" download. When the outcome is genuinely unknown, the safe answer
     * is to keep the bytes.
     */
    public void deleteOnRollback(StoredFile stored) {
        if (stored == null || stored.filePath() == null || stored.filePath().isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        String path = stored.filePath();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteQuietly(path);
                }
            }
        });
    }

    /**
     * The mirror of {@link #deleteOnRollback}, for the other direction: a caller that wants a file
     * GONE, running inside a {@code @Transactional} method. Defers the disk delete until the
     * transaction actually commits, so a rollback leaves the bytes exactly where the restored row
     * still expects them.
     *
     * <p><b>Why this is needed even though {@link #deleteOnRollback} already exists.</b> Both
     * hazards come from the same fact — a disk mutation is not transactional — but they fail in
     * opposite directions, and #708 only closed one of them. Deleting inside a transaction that
     * then rolls back is the worse of the two: Postgres restores the row that referenced the file
     * and nothing restores the file, so the deal keeps advertising a document whose download is
     * permanently broken. An orphaned file costs disk and is invisible; a dangling row is
     * user-visible and the lost bytes are customer contracts, POs and tax invoices.
     *
     * <p><b>Deletes on commit, and deletes INLINE when there is no transaction.</b> That asymmetry
     * with {@link #deleteOnRollback}'s no-op is deliberate and is the whole point of the guard:
     * there, no transaction means nothing can roll back, so the file is the caller's to keep;
     * here, no transaction means there is no commit to wait for, and deferring would silently leak
     * every file a non-transactional caller ever deleted. Both branches resolve to "do what the
     * caller asked, unless a rollback is still possible".
     *
     * <p>Uses {@code afterCommit} rather than {@code afterCompletion}, following {@code
     * NotificationService#sendEmailAfterCommit}: on an outcome we could not determine the safe
     * answer is to keep the bytes, exactly as {@link #deleteOnRollback} keeps them on {@code
     * STATUS_UNKNOWN}. A file left behind is recoverable by hand; bytes deleted under a row that
     * committed are not.
     */
    public void deleteOnCommit(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(storedPath);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(storedPath);
            }
        });
    }

    /**
     * Best-effort delete that can never throw. Tolerant of an already-absent file by design: this
     * runs from {@code afterCompletion}, where the transaction has already failed, and an
     * exception escaping here would replace the real rollback cause in the logs with a cleanup
     * error. A file we could not remove is a bounded, visible problem (one WARN, one orphan); a
     * masked root cause is not.
     */
    private void deleteQuietly(String storedPath) {
        try {
            Files.deleteIfExists(resolveDiskPath(storedPath));
        } catch (Exception exception) {
            log.warn("Could not delete orphaned upload after rollback: {}", storedPath, exception);
        }
    }

    /**
     * V134 storage-durability fix: computes the same {@code domain/ownerId/uuid.ext} storage KEY
     * {@link #store} would, WITHOUT writing anything to disk, and returns the file's bytes so the
     * caller can insert them into {@code hr.file_attachment_blob}. Used by the four {@code
     * hr.file_attachment} domains this branch converts (leave, commission-invoice evidence,
     * tax_allowance_declaration, factory_quote); {@code sales.attachment}, {@code
     * sales.pricing_request_attachment}, and {@code hr.special_money_request_attachment} are out of
     * this branch's scope and keep calling {@link #store} unchanged.
     *
     * <p>The returned key is a BARE relative string (no uploads-dir prefix) -- for the three
     * domains that go pure-database it is now only a correlation key, never a resolvable disk
     * path, so it must never be handed to {@link #resolveDiskPath}/{@link #existsOnDisk}. (The
     * commission-invoice domain is the one exception: {@code CommissionService} dual-writes and
     * keeps using {@link #store}'s disk-path-shaped key there -- see its own javadoc for why.)
     */
    public StoredContent storeInDatabase(String domain, long ownerId, MultipartFile file, Set<String> allowedMimeTypes) {
        Validated validated = validate(file, allowedMimeTypes);
        String extension = extensionOf(validated.originalName());
        String storageKey = domain + "/" + ownerId + "/" + UUID.randomUUID() + extension;
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new StoredContent(validated.originalName(), storageKey, validated.mime(), file.getSize(), content);
    }

    /**
     * True if a {@code DISK_LEGACY} row's stored path still resolves to a readable file. Never call
     * this for a {@code DATABASE}-state row -- its {@code file_path} is a bare correlation key, not
     * a disk path (see {@link #storeInDatabase}), so it would almost certainly resolve to nothing
     * even while the row's bytes are perfectly readable from {@code hr.file_attachment_blob}.
     */
    public boolean existsOnDisk(String storedPath) {
        return storedPath != null && !storedPath.isBlank() && Files.exists(resolveDiskPath(storedPath));
    }

    /** Reads a {@code DISK_LEGACY} row's bytes straight off disk -- see {@link #existsOnDisk}'s caution. */
    public byte[] readDiskBytes(String storedPath) throws IOException {
        return Files.readAllBytes(resolveDiskPath(storedPath));
    }

    /**
     * A {@code DISK_LEGACY} row's {@code file_path} holds exactly what {@link #store} returned at
     * write time: {@code dest.toString()}, where {@code dest = uploadsDir.resolve(domain)
     * .resolve(ownerId).resolve(uuid + extension)} -- so the stored value already carries whatever
     * uploads-dir prefix (relative or absolute) was configured at upload time, the SAME way {@link
     * org.springframework.core.io.FileSystemResource} always resolved it before this migration:
     * absolute paths as-is, relative ones against the process's current working directory. No
     * re-prefixing is applied here -- doing so would double up the uploads-dir segment for the
     * common case where it is itself a relative path (the {@code ./uploads} default).
     */
    public Path resolveDiskPath(String storedPath) {
        return Paths.get(storedPath);
    }

    private void requireWithinSizeCap(long size) {
        if (size > maxUploadBytes) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไฟล์มีขนาดใหญ่เกินไป (ขนาดสูงสุดที่รองรับคือ " + (maxUploadBytes / (1024 * 1024)) + " MB)");
        }
    }

    private Validated validate(MultipartFile file, Set<String> allowedMimeTypes) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");
        }
        // Checked against the already-known spooled size, before any byte of the file is read into
        // memory -- see this class's constructor javadoc for why this cap exists separately from
        // spring.servlet.multipart.max-file-size.
        requireWithinSizeCap(file.getSize());
        String originalName = file.getOriginalFilename() != null
            ? Paths.get(file.getOriginalFilename()).getFileName().toString()
            : "file";
        String mime = file.getContentType();
        if (mime == null || mime.isBlank()) {
            mime = URLConnection.guessContentTypeFromName(originalName);
        }
        if (mime != null) {
            mime = mime.toLowerCase(Locale.ROOT);
        }
        if (allowedMimeTypes != null && !allowedMimeTypes.isEmpty() && !allowedMimeTypes.contains(mime)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG");
        }
        return new Validated(originalName, mime);
    }

    private String extensionOf(String originalName) {
        return originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : "";
    }

    private record Validated(String originalName, String mime) {
    }

    public record StoredFile(
        String fileName,
        String filePath,
        String mimeType,
        long fileSize
    ) {
    }

    /** V134: the DB-backed counterpart of {@link StoredFile} -- carries bytes instead of a disk path. */
    public record StoredContent(
        String fileName,
        String storageKey,
        String mimeType,
        long fileSize,
        byte[] content
    ) {
    }
}
