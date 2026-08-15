package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * {@code PricingRequestService#deleteAttachment} carries the same hazard {@code
 * AttachmentController#delete} carried before PR #719: it is {@code @Transactional} and it
 * <b>removes bytes from the disk inside that transaction</b>. A disk delete is not transactional, so
 * when the transaction rolls back Postgres restores the {@code sales.pricing_request_attachment} row
 * and nothing restores the file. The pricing request then lists a document whose download is
 * permanently broken — the rep's own spec sheet or customer drawing, gone with no sweeper and no way
 * back.
 *
 * <p>This is the mirror of {@code FileStorageRollbackOrphanIntegrationTest}, which pins the OTHER
 * direction on this same service ({@code uploadAttachment} storing a file that a rollback then
 * orphans, PR #708). Both hazards come from the one fact that a disk mutation ignores the
 * transaction; #708 closed the storing direction here and #719 closed the deleting direction in
 * {@code AttachmentController}. This class closes the deleting direction on the pricing-request
 * aggregate, which #719 did not reach.
 *
 * <p><b>How narrow the window is — stated plainly, because it bounds how much this matters.</b> The
 * disk delete is the LAST statement of the method, and {@code PricingRequestController#deleteAttachment}
 * is its only caller (verified across the whole backend), so the method is always the outermost
 * transaction. Nothing inside it can fail after the delete; the only way to reach this state today
 * is a failure at COMMIT — I/O error, connection loss, a deferred constraint. That is rare. It is
 * also undetectable after the fact and unrecoverable when it happens, and the fix is the three-line
 * helper {@code FileStorageService#deleteOnCommit} that already exists — so the asymmetry is worth
 * closing rather than documenting.
 *
 * <p><b>What each test proves.</b> {@link #rollingBackAfterTheDeleteMustLeaveTheFileOnDisk()} is the
 * failing-first case: it drives the real service inside a real transaction that then rolls back, and
 * asserts the file survived. It fails on unmodified code exactly as described above — row restored,
 * file gone. The other two pin the branches a naive "just stop deleting" fix would break: a
 * committing delete must still reclaim the bytes, and a caller with no transaction at all must still
 * delete inline rather than silently leaking every removed attachment forever.
 *
 * <p><b>On the harness.</b> {@link AbstractPostgresIntegrationTest} runs with no Spring context —
 * every integration test here hand-wires its services with {@code new}, so a bare
 * {@code @Transactional} is inert and a rollback assertion written the obvious way would prove
 * nothing. The rollback case below supplies its own outer transaction via {@code transactionTemplate}
 * (the modelled failure happens at commit, after the annotated method has already returned), so it
 * tests the DISK DELETE's binding to the transaction outcome rather than the annotation itself. The
 * commit case runs through {@link AbstractPostgresIntegrationTest#transactional} so the deferred
 * delete is proven to fire from a real annotation-driven commit, and {@link
 * #deletingWithNoTransactionAtAllStillRemovesTheFileImmediately()} is the control: the un-proxied
 * path must behave DIFFERENTLY (inline delete), which is what proves the two proxied tests above are
 * discriminating rather than passing trivially.
 *
 * <p>Each test gets its OWN uploads root (a fresh UUID directory), so "does this file exist" is an
 * exact statement about what the call under test did.
 */
class PricingRequestAttachmentDeleteRollbackIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final MultipartFile PDF =
        new MockMultipartFile("file", "spec.pdf", "application/pdf", "spec-bytes".getBytes());

    private PricingRequestRepository requests;
    private TicketRepository tickets;
    private FileStorageService fileStorage;
    private UserPrincipal salesRep;

    @BeforeEach
    void wireFixture() {
        requests = new PricingRequestRepository(jdbc);
        tickets = new TicketRepository(jdbc);
        fileStorage = new FileStorageService(
            Paths.get("/tmp/glr-pr-delete-rollback-test-" + UUID.randomUUID()).toString());
        // A real hr.employee row: sales.ticket.created_by_id is a real FK, and deleteAttachment
        // admits only the rep who created the ticket the request hangs off.
        long repId = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc))
            .create(new UpsertEmployeeRequest(
                null, null, "พนักงานขาย ลบไฟล์แนบ", null, null, null, null, null, null, null,
                "rep-prdel-" + UUID.randomUUID() + "@glr.co.th", null, "SA", "แผนกขาย", "แผนกขาย",
                null, null, null, "ACT", new BigDecimal("30000"),
                null, null, null, null, null, null, null));
        salesRep = new UserPrincipal(repId, repId + "@glr.co.th", "พนักงานขาย ลบไฟล์แนบ", "sales", repId,
            true, LocalDate.now(), false, null, false);
    }

    @Test
    void rollingBackAfterTheDeleteMustLeaveTheFileOnDisk() {
        Attachment fixture = uploadedAttachment();
        PricingRequestService service = transactional(service());

        // The only window that exists: the method body runs to completion — including the disk
        // delete, its very last statement — and the surrounding transaction then fails to commit.
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            service.deleteAttachment(fixture.id(), salesRep);
            throw new IllegalStateException("commit failed after the method body completed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(attachmentRows(fixture.id()))
            .as("precondition for the disk assertion below: the transaction really did roll back, "
                + "so the sales.pricing_request_attachment row is restored and the pricing request "
                + "still lists this document")
            .isOne();
        assertThat(Files.exists(fixture.path()))
            .as("the row is back, so the bytes it points at must be back too. Deleting the file "
                + "inside the transaction makes that impossible — the request advertises a "
                + "document whose download is permanently broken, and nothing can restore it")
            .isTrue();
    }

    @Test
    void committingTheDeleteStillRemovesTheFile() {
        Attachment fixture = uploadedAttachment();
        PricingRequestService service = transactional(service());

        service.deleteAttachment(fixture.id(), salesRep);

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
        // Raw, un-proxied: no transaction, so there is no commit to defer to. This is the control —
        // the un-proxied path MUST behave differently from the two proxied tests above, or the
        // harness has stopped discriminating and their evidence is vacuous.
        PricingRequestService service = service();

        service.deleteAttachment(fixture.id(), salesRep);

        assertThat(attachmentRows(fixture.id())).isZero();
        assertThat(Files.exists(fixture.path()))
            .as("with no synchronization active the deferred hook would never run, so the delete "
                + "must happen inline — otherwise a non-transactional caller silently leaks every "
                + "file it deletes")
            .isFalse();
    }

    // ── wiring ────────────────────────────────────────────────────────────────────────────

    private PricingRequestService service() {
        return new PricingRequestService(requests, tickets, new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            new ObjectMapper(), new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
    }

    /**
     * A real DRAFT pricing request owned by the fixture rep, with a real file on disk and a real
     * {@code sales.pricing_request_attachment} row pointing at it. Uploaded through the production
     * {@code uploadAttachment} path (un-proxied, so it auto-commits) rather than hand-inserted, so
     * the path under test is the one production actually writes.
     */
    private Attachment uploadedAttachment() {
        long pricingRequestId = draftPricingRequest();
        var dto = service().uploadAttachment(pricingRequestId, PDF, salesRep);
        String filePath = requests.findAttachmentFilePath(dto.id());
        assertThat(filePath).as("fixture sanity: the upload recorded a disk path").isNotNull();
        Path path = Paths.get(filePath);
        assertThat(Files.exists(path)).as("fixture sanity: the file really is on disk to begin with").isTrue();
        return new Attachment(dto.id(), path);
    }

    /** A DRAFT pricing request on a ticket the fixture rep created — deleteAttachment's only editable state. */
    private long draftPricingRequest() {
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:name, 'factory-prdel@example.com', 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE SET email = EXCLUDED.email
            """, Map.of("name", "Factory PrDelete"));
        long productId = insertCatalogProduct("Factory PrDelete", "TH", "TEST-PRDEL-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีล ลบไฟล์แนบคำขอราคา", "NORMAL", null, null, null, null, null, null, null),
            tickets.nextTicketCode(), salesRep.id(), salesRep.name());
        return service()
            .createDraft(ticketId, new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                new BigDecimal("1000.00"), "THB", "delete-attachment test request",
                UUID.randomUUID().toString(),
                List.of(new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, "SCG",
                    "Tile PrDelete", "SCG Tile PrDelete", null, null, "60x60", "Factory PrDelete",
                    new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
                    QuantityType.CONFIRMED, null, null, null))),
                salesRep)
            .summary().id();
    }

    private record Attachment(long id, Path path) {}

    private int attachmentRows(long attachmentId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_request_attachment WHERE pricing_request_attachment_id = :id",
            Map.of("id", attachmentId), Integer.class);
        return count == null ? 0 : count;
    }
}
