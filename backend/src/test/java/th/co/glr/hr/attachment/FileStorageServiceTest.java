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

    @Test
    void storesAllowedBusinessAttachment() {
        FileStorageService service = new FileStorageService(uploadsDir.toString());
        MockMultipartFile file = new MockMultipartFile(
            "file", "quote.pdf", "APPLICATION/PDF", "pdf".getBytes());

        FileStorageService.StoredFile stored = service.store(
            "tickets", 10L, file, BUSINESS_ATTACHMENT_MIME_TYPES);

        assertThat(stored.fileName()).isEqualTo("quote.pdf");
        assertThat(stored.mimeType()).isEqualTo("application/pdf");
        assertThat(Path.of(stored.filePath())).exists();
    }

    @Test
    void rejectsUnsupportedBusinessAttachmentType() {
        FileStorageService service = new FileStorageService(uploadsDir.toString());
        MockMultipartFile file = new MockMultipartFile(
            "file", "payload.html", "text/html", "<script>alert(1)</script>".getBytes());

        assertThatThrownBy(() -> service.store(
                "tickets", 10L, file, BUSINESS_ATTACHMENT_MIME_TYPES))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- V134 storage-durability fix: explicit app.uploads-max-bytes cap, checked before any byte
    // is read into memory (see FileStorageService's constructor javadoc for why this is separate
    // from spring.servlet.multipart.max-file-size). -----------------------------------------------

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

    @Test
    void storeInDatabaseAlsoEnforcesTheCapAndNeverTouchesDisk() {
        FileStorageService service = new FileStorageService(uploadsDir.toString(), 4L);
        MockMultipartFile file = new MockMultipartFile(
            "file", "quote.pdf", "application/pdf", "this is way more than four bytes".getBytes());

        assertThatThrownBy(() -> service.storeInDatabase("leave", 10L, file, BUSINESS_ATTACHMENT_MIME_TYPES))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void storeInDatabaseReturnsContentAndABareRelativeKeyWithNoDiskWrite() {
        FileStorageService service = new FileStorageService(uploadsDir.toString());
        byte[] payload = "pdf-bytes".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "quote.pdf", "application/pdf", payload);

        FileStorageService.StoredContent stored = service.storeInDatabase("leave", 10L, file, BUSINESS_ATTACHMENT_MIME_TYPES);

        assertThat(stored.content()).isEqualTo(payload);
        assertThat(stored.storageKey()).startsWith("leave/10/").endsWith(".pdf");
        assertThat(uploadsDir.resolve(stored.storageKey())).doesNotExist();
    }
}
