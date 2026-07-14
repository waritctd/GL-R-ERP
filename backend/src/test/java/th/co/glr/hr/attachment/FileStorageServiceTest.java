package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.common.ApiException;

class FileStorageServiceTest {
    @TempDir
    Path uploadsDir;

    @Test
    void storesAllowedBusinessAttachment() {
        FileStorageService service = new FileStorageService(uploadsDir.toString());
        MockMultipartFile file = new MockMultipartFile(
            "file", "quote.pdf", "APPLICATION/PDF", "pdf".getBytes());

        FileStorageService.StoredFile stored = service.store(
            "tickets", 10L, file, FileStorageService.BUSINESS_ATTACHMENT_MIME_TYPES);

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
                "tickets", 10L, file, FileStorageService.BUSINESS_ATTACHMENT_MIME_TYPES))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
