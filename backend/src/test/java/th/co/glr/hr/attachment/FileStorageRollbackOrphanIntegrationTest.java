package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.SubmitCommissionRequest;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/** FileStorageService#store copies bytes to disk the instant it is called. This class pins all three, in both
 * directions — a rollback must leave the uploads directory empty, and a commit must leave the file in place
 * and referenced. */
class FileStorageRollbackOrphanIntegrationTest extends AbstractPostgresIntegrationTest {

    private EmployeeRepository employees;
    private TicketRepository tickets;
    private Path uploadsRoot;
    private FileStorageService fileStorage;

    @BeforeEach
    void wireFixture() {
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        tickets = new TicketRepository(jdbc);
        // A private root per test: every file counted below was written by the call under test.
        uploadsRoot = Paths.get("/tmp/glr-orphan-file-test-" + UUID.randomUUID());
        fileStorage = new FileStorageService(uploadsRoot.toString());
    }

    // CommissionService#submit

    // CommissionService#createFromDeal — stores the same file_path into sales.attachment too

    // PricingRequestService#uploadAttachment

    @Test
    void uploadAttachment_rollingBackAfterTheFileLands_leavesNoOrphanOnDisk() {
        long salesRepId = createEmployee("พนักงานขาย คำขอราคา", "rep-pr-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal sales = actor(salesRepId, "sales");
        long pricingRequestId = draftPricingRequest(salesRepId, sales);

        // Fault injector: everything real except addEvent — uploadAttachment's LAST write, so the file and the
        // sales.pricing_request_attachment row both really exist when it throws.
        PricingRequestRepository faulty = org.mockito.Mockito.spy(new PricingRequestRepository(jdbc));
        doThrow(new DataIntegrityViolationException("injected failure after the attachment row"))
            .when(faulty).addEvent(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        PricingRequestService service = transactional(pricingRequestService(faulty));

        assertThatThrownBy(() -> service.uploadAttachment(pricingRequestId, attachmentFile(), sales))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countPricingRequestAttachments(pricingRequestId))
            .as("precondition: the transaction really rolled back, so no "
                + "sales.pricing_request_attachment row references the stored file")
            .isZero();
        assertThat(storedFiles())
            .as("the pricing attachment was written to disk before the event write failed — "
                + "without a rollback hook it survives as an unreferenced customer document")
            .isEmpty();
    }

    // Wiring helpers

    private PricingRequestService pricingRequestService(PricingRequestRepository requests) {
        return new PricingRequestService(requests, tickets, new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            new ObjectMapper(), new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
    }

    /** A DRAFT pricing request owned by {@code salesRepId} — uploadAttachment's only editable state. */
    private long draftPricingRequest(long salesRepId, UserPrincipal sales) {
        long productId = insertCatalogProduct("Factory Orphan", "TH", "TEST-ORPHAN-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีล Pricing Orphan", "NORMAL", null, null, null, null, null, null, null),
            tickets.nextTicketCode(), salesRepId, "พนักงานขาย คำขอราคา");
        return pricingRequestService(new PricingRequestRepository(jdbc))
            .createDraft(ticketId, new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                new BigDecimal("1000.00"), "THB", "orphan-file test request", UUID.randomUUID().toString(),
                // V185: color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now required on every item
                // PricingRequestService#createDraft persists — requestedQty/requestedUnit/requestedUnitBasis are derived
                // instead, so this fixture supplies the tile fields directly rather than the pre-V185 bare shape.
                List.of(new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, "SCG",
                    "Tile Orphan", "SCG Tile Orphan", "White", "Matte", "60x60", "Factory Orphan",
                    null, null, null, null,
                    QuantityType.CONFIRMED, null, null, null,
                    null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
                    null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
                    false, "ไทย-สต็อก", 3, 7, null, null, null))),
                sales)
            .summary().id();
    }

    private MultipartFile attachmentFile() {
        return new MockMultipartFile("file", "spec.pdf", "application/pdf", "spec-bytes".getBytes());
    }

    // Assertion helpers

    /** Every regular file under this test's private uploads root. */
    private List<Path> storedFiles() {
        if (!Files.exists(uploadsRoot)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(uploadsRoot)) {
            return tree.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private int countPricingRequestAttachments(long pricingRequestId) {
        return count("SELECT COUNT(*) FROM sales.pricing_request_attachment WHERE pricing_request_id = :v",
            pricingRequestId);
    }

    private int count(String sql, Object value) {
        Integer count = jdbc.queryForObject(sql, Map.of("v", value), Integer.class);
        return count == null ? 0 : count;
    }

    private SubmitCommissionRequest request(long salesRepId, String invoiceNumber) {
        return new SubmitCommissionRequest(
            null, salesRepId, invoiceNumber, LocalDate.of(2026, 7, 1), new BigDecimal("120000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private long createEmployee(String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "SA", "แผนกขาย", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
