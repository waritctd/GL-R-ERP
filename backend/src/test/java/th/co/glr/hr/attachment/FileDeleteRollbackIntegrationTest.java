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

/**
 * The mirror image of {@link FileStorageRollbackOrphanIntegrationTest}, and the direction PR #708
 * did not cover.
 *
 * <p>#708 fixed <b>storing</b> a file inside a transaction that then rolls back: the row vanishes,
 * the file survives, and the result is an orphan nobody references. {@code
 * AttachmentController#delete} has the same hazard pointing the other way. It is
 * {@code @Transactional}, and it <b>deletes bytes off the disk inside that transaction</b>. If the
 * transaction rolls back, Postgres restores the {@code sales.attachment} row — and nothing restores
 * the file. The deal's document list then advertises an attachment whose download returns
 * "ไฟล์ถูกลบออกจากเซิร์ฟเวอร์แล้ว" forever.
 *
 * <p>Of the two, this is the worse failure. An orphan costs disk and is invisible; a dangling row
 * is user-visible, and the bytes it lost are customer contracts, POs and tax invoices that no
 * sweeper can bring back.
 *
 * <p><b>How narrow the window actually is — stated plainly, because it bounds how much this
 * matters.</b> The disk delete is the LAST statement of the method, and the method is the outermost
 * transaction (one controller entry point, no other caller — verified across the whole backend). So
 * nothing inside the method can fail after the delete; the only way to reach this state today is a
 * failure at COMMIT (I/O error, connection loss, a deferred constraint). That is rare. It is not
 * impossible, it is not detectable after the fact, and it is unrecoverable when it happens — and
 * the fix is the same three lines {@code deleteOnRollback} already established, so the asymmetry is
 * worth closing rather than documenting.
 *
 * <p><b>What each test proves.</b> {@link #rollingBackAfterTheDeleteMustLeaveTheFileOnDisk()} is
 * the failing-first case: it drives the real controller inside a real transaction that then rolls
 * back, and asserts the file is still there. Before {@code FileStorageService#deleteOnCommit}
 * existed it failed exactly as described above (row restored, file gone). The other two pin the
 * branches that a naive "just don't delete" fix would break: a committing delete must still remove
 * the file, and a caller with no transaction at all must still remove it immediately rather than
 * silently leaking every deleted attachment forever.
 *
 * <p><b>On the harness.</b> Unlike the tests in {@code FileStorageRollbackOrphanIntegrationTest},
 * the rollback case here supplies its own outer transaction via {@code transactionTemplate} rather
 * than relying solely on the proxy, because the failure being modelled happens at commit — after
 * the annotated method has already returned. That makes this a test of the DISK DELETE's binding to
 * the transaction outcome, not a test of the annotation itself; it would not go red if
 * {@code @Transactional} were removed from the controller. {@link
 * #deletingWithNoTransactionAtAllStillRemovesTheFileImmediately()} covers the un-synchronized
 * branch, and the commit case below is driven through {@link
 * AbstractPostgresIntegrationTest#transactional} so the deferred delete is proven to fire from a
 * real annotation-driven commit.
 *
 * <p>Each test gets its OWN uploads root (a fresh UUID directory), so "does this file exist" is an
 * exact statement about what the call under test did.
 */
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
        // A real hr.employee row: sales.ticket.created_by_id is a real FK, and the uploader is
        // also what requireAttachmentWriteAccess admits without a role gate.
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

        // The only window that exists: the method body runs to completion — including the disk
        // delete, its very last statement — and the surrounding transaction then fails to commit.
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
        // Raw, un-proxied: no transaction, so there is no commit to defer to. Mirrors
        // deleteOnRollback's own "nothing can roll back, so this is a no-op" guard — the same
        // branch, resolved the other way, because here the caller's intent is that the file goes.
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
