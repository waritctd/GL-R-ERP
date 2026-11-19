package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.common.ApiException;

class FileStorageServiceTest {
    private static final Set<String> BUSINESS_ATTACHMENT_MIME_TYPES = Set.of(
        "application/pdf",
        "image/jpeg",
        "image/png"
    );

    @TempDir
    Path uploadsDir;

    // V134 storage-durability fix: explicit app.uploads-max-bytes cap, checked before any byte is read into memory
    // (see FileStorageService's constructor javadoc for why this is separate from
    // spring.servlet.multipart.max-file-size).

    @Test
    void storeRejectsAFileOverTheConfiguredCap() {
        FileStorageService service = new FileStorageService(uploadsDir.toString(), 4L);
        MockMultipartFile file = new MockMultipartFile(
            "file", "quote.pdf", "application/pdf", "this is way more than four bytes".getBytes());

        assertThatThrownBy(() -> service.store("tickets", 10L, file, BUSINESS_ATTACHMENT_MIME_TYPES))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void storeAllowsAFileExactlyAtTheCap() {
        byte[] exactlyFourBytes = "abcd".getBytes();
        FileStorageService service = new FileStorageService(uploadsDir.toString(), exactlyFourBytes.length);
        MockMultipartFile file = new MockMultipartFile(
            "file", "quote.pdf", "application/pdf", exactlyFourBytes);

        FileStorageService.StoredFile stored = service.store("tickets", 10L, file, BUSINESS_ATTACHMENT_MIME_TYPES);

        assertThat(stored.fileSize()).isEqualTo(exactlyFourBytes.length);
    }

}
