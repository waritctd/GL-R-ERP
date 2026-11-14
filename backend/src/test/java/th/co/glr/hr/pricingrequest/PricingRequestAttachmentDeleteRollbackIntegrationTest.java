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
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/** PricingRequestService#deleteAttachment carries the same hazard AttachmentController#delete carried before PR
 * #719: it is @Transactional and it removes bytes from the disk inside that transaction. This class closes the
 * deleting direction on the pricing-request aggregate, which #719 did not reach. */
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
        // A real hr.employee row: sales.ticket.created_by_id is a real FK, and deleteAttachment admits only the rep
        // who created the ticket the request hangs off.
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

        // The only window that exists: the method body runs to completion — including the disk delete, its very last
        // statement — and the surrounding transaction then fails to commit.
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
        // Raw, un-proxied: no transaction, so there is no commit to defer to. This is the control — the un-proxied
        // path MUST behave differently from the two proxied tests above, or the harness has stopped discriminating and
        // their evidence is vacuous.
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

    /** A real DRAFT pricing request owned by the fixture rep, with a real file on disk and a real
     * sales.pricing_request_attachment row pointing at it. */
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
                // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/ piecesPerBox/a quantity are now
                // required on every item createDraft persists.
                List.of(new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, "SCG",
                    "Tile PrDelete", "SCG Tile PrDelete", "White", "Matte", "60x60", "Factory PrDelete",
                    null, null, null, null, QuantityType.CONFIRMED, null, null, null,
                    null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
                    null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
                    false, "ไทย-สต็อก", 3, 7, null, null, null))),
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
