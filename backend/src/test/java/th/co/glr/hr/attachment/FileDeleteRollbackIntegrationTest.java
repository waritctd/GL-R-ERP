package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/** The mirror image of FileStorageRollbackOrphanIntegrationTest, and the direction PR #708 did not cover. #708
 * fixed storing a file inside a transaction that then rolls back: the row vanishes, the file survives, and the
 * result is an orphan nobody references. */
class FileDeleteRollbackIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final MultipartFile PDF =
        new MockMultipartFile("file", "contract.pdf", "application/pdf", "contract-bytes".getBytes());

    private AttachmentRepository attachments;
    private TicketRepository tickets;
    private FileStorageService fileStorage;
    private HttpSession session;
    private SessionContext sessions;
    private UserPrincipal uploader;

    @BeforeEach
    void wireFixture() {
        attachments = new AttachmentRepository(jdbc);
        tickets = new TicketRepository(jdbc);
        fileStorage = new FileStorageService(
            Paths.get("/tmp/glr-delete-rollback-test-" + UUID.randomUUID()).toString());
        session = mock(HttpSession.class);
        sessions = mock(SessionContext.class);
        // A real hr.employee row: sales.ticket.created_by_id is a real FK, and the uploader is also what
        // requireAttachmentWriteAccess admits without a role gate.
        long uploaderId = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc))
            .create(new UpsertEmployeeRequest(
                null, null, "ผู้อัปโหลด ไฟล์แนบ", null, null, null, null, null, null, null,
                "uploader-" + UUID.randomUUID() + "@glr.co.th", null, "SA", "แผนกขาย", "แผนกขาย",
                null, null, null, "ACT", new BigDecimal("30000"),
                null, null, null, null, null, null, null));
        uploader = new UserPrincipal(uploaderId, "sales@glr.co.th", "ผู้อัปโหลด", "sales", uploaderId,
            true, LocalDate.now(), false, null, false);
        when(sessions.requireUser(any())).thenReturn(uploader);
    }

    @Test
    void rollingBackAfterTheDeleteMustLeaveTheFileOnDisk() {
        Attachment fixture = uploadedAttachment();
        AttachmentController controller = transactional(controller());

        // The only window that exists: the method body runs to completion — including the disk delete, its very last
        // statement — and the surrounding transaction then fails to commit.
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            controller.delete(fixture.id(), session);
            throw new IllegalStateException("commit failed after the method body completed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(attachmentRows(fixture.id()))
            .as("precondition for the disk assertion below: the transaction really did roll back, "
                + "so the sales.attachment row is restored and the deal still lists this document")
            .isOne();
        assertThat(Files.exists(fixture.path()))
            .as("the row is back, so the bytes it points at must be back too. Deleting the file "
                + "inside the transaction makes that impossible — the deal advertises a contract "
                + "whose download is permanently broken, and nothing can restore it")
            .isTrue();
    }

    @Test
    void committingTheDeleteStillRemovesTheFile() {
        Attachment fixture = uploadedAttachment();
        AttachmentController controller = transactional(controller());

        controller.delete(fixture.id(), session);

        assertThat(attachmentRows(fixture.id())).isZero();
        assertThat(Files.exists(fixture.path()))
            .as("deferring the delete to afterCommit must not turn it into never deleting — a "
                + "committed removal still has to reclaim the bytes, or every deleted attachment "
                + "leaks and the rollback test above would pass by doing nothing at all")
            .isFalse();
    }

    @Test
    void deletingWithNoTransactionAtAllStillRemovesTheFileImmediately() {
        Attachment fixture = uploadedAttachment();
        // Raw, un-proxied: no transaction, so there is no commit to defer to. Mirrors deleteOnRollback's own "nothing
        // can roll back, so this is a no-op" guard — the same branch, resolved the other way, because here the
        // caller's intent is that the file goes.
        AttachmentController controller = controller();

        controller.delete(fixture.id(), session);

        assertThat(attachmentRows(fixture.id())).isZero();
        assertThat(Files.exists(fixture.path()))
            .as("with no synchronization active the deferred hook would never run, so the delete "
                + "must happen inline — otherwise a non-transactional caller silently leaks every "
                + "file it deletes")
            .isFalse();
    }

    // ── wiring ────────────────────────────────────────────────────────────────────────────

    private AttachmentController controller() {
        return new AttachmentController(attachments, sessions, tickets,
            mock(AuditService.class), fileStorage);
    }

    /** A real ticket, a real file on disk, and a real sales.attachment row pointing at it. */
    private Attachment uploadedAttachment() {
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีล ลบไฟล์แนบ", "NORMAL", null, null, null, null, null, null, null),
            tickets.nextTicketCode(), uploader.id(), uploader.name());
        FileStorageService.StoredFile stored = fileStorage.store("tickets", ticketId, PDF, Set.of());
        AttachmentDto dto = attachments.save(ticketId, null, stored.fileName(), stored.filePath(),
            stored.mimeType(), stored.fileSize(), "OTHER", uploader.id());
        Path path = Paths.get(stored.filePath());
        assertThat(Files.exists(path)).as("fixture sanity: the file really is on disk to begin with").isTrue();
        return new Attachment(dto.id(), path);
    }

    private record Attachment(long id, Path path) {}

    private int attachmentRows(long attachmentId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.attachment WHERE attachment_id = :id",
            Map.of("id", attachmentId), Integer.class);
        return count == null ? 0 : count;
    }
}
