package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    // ---- A file with no type at all: none declared, and a name that gives none away -------------
    //
    // The allow-lists the callers pass are immutable Set.of(..) sets, and looking a null up in one
    // throws. So this file used to escape as a NullPointerException, which no handler claims: the
    // caller was told 500 "เกิดข้อผิดพลาดภายในระบบ" for a file they could simply have re-saved as a
    // PDF. It is a file of a type that is not allowed, and it is refused as one.

    /** No declared type, and nothing the name can be guessed from. */
    private static List<MockMultipartFile> typelessFiles() {
        byte[] bytes = "scanned receipt".getBytes();
        return List.of(
            new MockMultipartFile("file", "scan.unknownext", null, bytes),
            new MockMultipartFile("file", "scan.unknownext", "", bytes),
            new MockMultipartFile("file", "scan.unknownext", "   ", bytes),
            new MockMultipartFile("file", "certificate", null, bytes),
            new MockMultipartFile("file", null, null, bytes));
    }

    private static String describe(MockMultipartFile file) {
        return "name=" + file.getOriginalFilename() + ", declared type=\"" + file.getContentType() + "\"";
    }

    @Test
    void storeRefusesAFileWithNoTypeAsAnUnsupportedType_andWritesNothing() throws IOException {
        FileStorageService service = new FileStorageService(uploadsDir.toString());

        for (MockMultipartFile file : typelessFiles()) {
            assertThatThrownBy(() -> service.store("special-money", 1L, file, BUSINESS_ATTACHMENT_MIME_TYPES))
                .as(describe(file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PDF")
                .extracting(exception -> ((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        try (var written = Files.list(uploadsDir)) {
            assertThat(written).as("nothing reached the uploads dir").isEmpty();
        }
    }

    @Test
    void storeInDatabaseRefusesAFileWithNoTypeAsAnUnsupportedType() {
        FileStorageService service = new FileStorageService(uploadsDir.toString());

        for (MockMultipartFile file : typelessFiles()) {
            assertThatThrownBy(() -> service.storeInDatabase("leave", 10L, file, BUSINESS_ATTACHMENT_MIME_TYPES))
                .as(describe(file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PDF")
                .extracting(exception -> ((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /** Control: no declared type is not a refusal by itself. The name still decides. */
    @Test
    void aFileWithNoDeclaredTypeIsStillTakenWhenItsNameGivesAnAllowedType() {
        FileStorageService service = new FileStorageService(uploadsDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "certificate.pdf", null, "pdf".getBytes());

        FileStorageService.StoredContent stored =
            service.storeInDatabase("leave", 10L, file, BUSINESS_ATTACHMENT_MIME_TYPES);

        assertThat(stored.mimeType()).isEqualTo("application/pdf");
    }

    /**
     * Control: a caller that accepts anything (deal attachments, คำขอราคา attachments and factory
     * quotes pass an empty allow-list) still takes a file with no type, and stores it with none.
     * Their downloads serve such a file as application/octet-stream.
     */
    @Test
    void aCallerWithNoAllowListStillTakesAFileWithNoType() {
        FileStorageService service = new FileStorageService(uploadsDir.toString());

        for (MockMultipartFile file : typelessFiles()) {
            FileStorageService.StoredFile onDisk = service.store("tickets", 10L, file, Set.of());
            FileStorageService.StoredContent inDatabase = service.storeInDatabase("factory-quotes", 10L, file, Set.of());

            assertThat(onDisk.mimeType()).as(describe(file)).isNull();
            assertThat(Path.of(onDisk.filePath())).as(describe(file)).exists();
            assertThat(inDatabase.mimeType()).as(describe(file)).isNull();
        }
    }

    // ---- 2026-08-31: a storage failure must say it is a storage failure -----------------------

    /**
     * An uploads dir whose PARENT is a regular file: {@code Files.createDirectories} fails on it
     * for every user, root included. Deliberately not a {@code chmod}-based fixture -- that one is
     * satisfiable by privilege and would silently stop testing anything under a root container.
     */
    private Path uncreatableUploadsDir() throws IOException {
        Path blocker = uploadsDir.resolve("not-a-directory");
        Files.writeString(blocker, "x");
        return blocker.resolve("uploads");
    }

    @Test
    void storeReportsAnUnwritableUploadsDirAsSuchInsteadOfAGenericServerError() throws IOException {
        FileStorageService service = new FileStorageService(uncreatableUploadsDir().toString());
        MockMultipartFile file = new MockMultipartFile(
            "file", "receipt.pdf", "application/pdf", "pdf".getBytes());

        // Before this, the IOException escaped as an UncheckedIOException that no handler in
        // ApiExceptionHandler claims, so the caller was told 500 "เกิดข้อผิดพลาดภายในระบบ" -- the
        // same message every unhandled bug in the application produces. On production 2026-08-31
        // that is exactly what every welfare evidence upload returned, and it pointed at nothing.
        assertThatThrownBy(() -> service.store("special-money", 1L, file, BUSINESS_ATTACHMENT_MIME_TYPES))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("APP_UPLOADS_DIR")
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * The mime and size gates must still answer BEFORE the storage gate. A caller who sent a .exe
     * to a broken server has two problems, and the one they can act on is the file they chose --
     * telling them the server's disk is misconfigured would send them to an administrator over
     * something they can fix themselves in a second.
     */
    @Test
    void aRejectedFileTypeStillReportsTheFileTypeEvenWhenStorageIsAlsoBroken() throws IOException {
        FileStorageService service = new FileStorageService(uncreatableUploadsDir().toString());
        MockMultipartFile file = new MockMultipartFile(
            "file", "payload.exe", "application/octet-stream", "exe".getBytes());

        assertThatThrownBy(() -> service.store("special-money", 1L, file, BUSINESS_ATTACHMENT_MIME_TYPES))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("PDF")
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
