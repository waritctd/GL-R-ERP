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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.common.ApiException;

@Service
public class FileStorageService {
    private final Path uploadsDir;

    public FileStorageService(@Value("${app.uploads-dir:./uploads}") String uploadsDir) {
        this.uploadsDir = Paths.get(uploadsDir);
    }

    public StoredFile store(String domain, long ownerId, MultipartFile file, Set<String> allowedMimeTypes) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");
        }
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

        String extension = originalName.contains(".")
            ? originalName.substring(originalName.lastIndexOf('.'))
            : "";
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
        return new StoredFile(originalName, dest.toString(), mime, file.getSize());
    }

    public record StoredFile(
        String fileName,
        String filePath,
        String mimeType,
        long fileSize
    ) {
    }
}
