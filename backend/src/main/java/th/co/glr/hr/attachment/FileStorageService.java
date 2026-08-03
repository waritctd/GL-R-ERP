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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.common.ApiException;

@Service
public class FileStorageService {
    private final Path uploadsDir;
    private final long maxUploadBytes;

    /**
     * V132 storage-durability fix: {@code app.uploads-max-bytes} is an explicit server-side cap,
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
     * V132 storage-durability fix: computes the same {@code domain/ownerId/uuid.ext} storage KEY
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

    /** V132: the DB-backed counterpart of {@link StoredFile} -- carries bytes instead of a disk path. */
    public record StoredContent(
        String fileName,
        String storageKey,
        String mimeType,
        long fileSize,
        byte[] content
    ) {
    }
}
