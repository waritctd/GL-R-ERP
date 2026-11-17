package th.co.glr.hr.attachment;

import java.io.IOException;
import java.io.InputStream;
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

    /** V134 storage-durability fix: app.uploads-max-bytes is an explicit server-side cap, separate from
     * spring.servlet.multipart.max-file-size (${APP_MAX_FILE_SIZE:10MB} in application.yml). */
    @Autowired
    public FileStorageService(@Value("${app.uploads-dir:./uploads}") String uploadsDir,
                               @Value("${app.uploads-max-bytes:10485760}") long maxUploadBytes) {
        this.uploadsDir = Paths.get(uploadsDir);
        this.maxUploadBytes = maxUploadBytes;
    }

    /** Legacy/test convenience overload -- keeps every existing new FileStorageService(dir) call site (this class
     * is constructed directly, bypassing Spring DI, by dozens of integration tests across this codebase) compiling
     * unchanged, using the same default app.uploads-max-bytes would inject in production. */
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
            throw storageUnavailable(dir, exception);
        }
        return new StoredFile(validated.originalName(), dest.toString(), validated.mime(), file.getSize());
    }

    /** Turns a failed disk write into a 503 that SAYS SO, and logs the path that could not be written. The resolved
     * PATH stays in the log, not in the response. */
    private ApiException storageUnavailable(Path target, IOException cause) {
        log.error("Attachment storage is not writable: app.uploads-dir={} target={} — every upload "
            + "will fail until this path exists and is writable by the runtime user", uploadsDir, target, cause);
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "ไม่สามารถบันทึกไฟล์แนบได้ เนื่องจากที่จัดเก็บไฟล์ของระบบใช้งานไม่ได้ (APP_UPLOADS_DIR) "
                + "กรุณาติดต่อผู้ดูแลระบบ");
    }

    /** Ties a file #store has just written to the OUTCOME of the transaction the caller is running in: if that
     * transaction rolls back, the file is deleted; if it commits, nothing happens. Deletes on STATUS_ROLLED_BACK
     * only — deliberately not on STATUS_UNKNOWN. */
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

    /** The mirror of #deleteOnRollback, for the other direction: a caller that wants a file GONE, running inside a
     * @Transactional method. Both hazards come from the same fact — a disk mutation is not transactional — but
     * they fail in opposite directions, and #708 only closed one of them. */
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

    /** Best-effort delete that can never throw. Tolerant of an already-absent file by design: this runs from
     * afterCompletion, where the transaction has already failed, and an exception escaping here would replace the
     * real rollback cause in the logs with a cleanup error. */
    private void deleteQuietly(String storedPath) {
        try {
            Files.deleteIfExists(resolveDiskPath(storedPath));
        } catch (Exception exception) {
            log.warn("Could not delete orphaned upload after rollback: {}", storedPath, exception);
        }
    }

    /** V134 storage-durability fix: computes the same domain/ownerId/uuid.ext storage KEY #store would, WITHOUT
     * writing anything to disk, and returns the file's bytes so the caller can insert them into
     * hr.file_attachment_blob. */
    public StoredContent storeInDatabase(String domain, long ownerId, MultipartFile file, Set<String> allowedMimeTypes) {
        Validated validated = validate(file, allowedMimeTypes);
        String extension = extensionOf(validated.originalName());
        String storageKey = domain + "/" + ownerId + "/" + UUID.randomUUID() + extension;
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            // Same reasoning as storageUnavailable(..) above, different cause and therefore a different message: nothing
            // here touches app.uploads-dir.
            log.error("Could not read the uploaded file for domain={} ownerId={}", domain, ownerId, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "อ่านไฟล์ที่อัปโหลดไม่สำเร็จ กรุณาลองใหม่อีกครั้ง");
        }
        return new StoredContent(validated.originalName(), storageKey, validated.mime(), file.getSize(), content);
    }

    /** True if a DISK_LEGACY row's stored path still resolves to a readable file. */
    public boolean existsOnDisk(String storedPath) {
        return storedPath != null && !storedPath.isBlank() && Files.exists(resolveDiskPath(storedPath));
    }

    /** Reads a {@code DISK_LEGACY} row's bytes straight off disk -- see {@link #existsOnDisk}'s caution. */
    public byte[] readDiskBytes(String storedPath) throws IOException {
        return Files.readAllBytes(resolveDiskPath(storedPath));
    }

    /** A DISK_LEGACY row's file_path holds exactly what #store returned at write time: dest.toString, where dest =
     * uploadsDir.resolve .resolve.resolve. */
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
        // Checked against the already-known spooled size, before any byte of the file is read into memory -- see this
        // class's constructor javadoc for why this cap exists separately from spring.servlet.multipart.max-file-size.
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
