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
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.commission.SubmitCommissionRequest;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * {@link FileStorageService#store} copies bytes to disk the instant it is called. Postgres knows
 * nothing about that copy, so when the {@code @Transactional} method around it rolls back, the row
 * that referenced the file vanishes and <b>the file stays on disk forever</b> — an orphan nothing
 * references and no sweeper collects. Over time that is unbounded growth on the uploads volume plus
 * a quiet data-leak surface, because the stranded bytes are customer invoices and pricing
 * attachments.
 *
 * <p>Three production call sites stored inside a transaction:
 * {@code CommissionService#submit}, {@code CommissionService#createFromDeal}, and
 * {@code PricingRequestService#uploadAttachment}. This class pins all three, in both directions —
 * a rollback must leave the uploads directory empty, and a commit must leave the file in place and
 * referenced.
 *
 * <p><b>Why these tests are not vacuous.</b> {@link AbstractPostgresIntegrationTest} runs with no
 * Spring context: every integration test in this suite hand-wires its services with {@code new}, so
 * there is no AOP proxy and a bare {@code @Transactional} does nothing at all. A rollback test
 * written the obvious way would therefore never roll anything back and would pass for a reason that
 * has nothing to do with the code under test. Every rollback test below is driven through {@link
 * AbstractPostgresIntegrationTest#transactional} (PR #695), which builds a REAL Spring AOP proxy
 * from {@link org.springframework.transaction.annotation.AnnotationTransactionAttributeSource} — so
 * the transaction genuinely comes from the production {@code @Transactional} annotation, and
 * {@code TransactionSynchronizationManager} is genuinely active inside the call.
 * {@link #commissionSubmit_withoutTheProxy_neverRollsBackSoTheHookNeverFires()} is the control that
 * proves the proxy is what makes the difference.
 *
 * <p>Each test gets its OWN uploads root (a fresh UUID directory), so "how many regular files exist
 * under this root" is an exact, unambiguous count of what the call under test left behind — not a
 * guess filtered out of a shared directory.
 *
 * <p>The Mockito {@link AuditService} and {@link PricingRequestRepository} stubs are <b>fault
 * injectors</b>, not the system under test: they fail the LAST write in each method's sequence, so
 * by the time they throw the file has really been written to a real disk and every row before them
 * has really been inserted into a real Postgres. Everything asserted below is real state.
 */
class FileStorageRollbackOrphanIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final MultipartFile PDF =
        new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf-bytes".getBytes());

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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // CommissionService#submit
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void commissionSubmit_rollingBackAfterTheFileLands_leavesNoOrphanOnDisk() {
        CommissionService service = transactional(commissionService(failingAudit()));
        long salesRepId = createEmployee("พนักงานขาย ออร์แฟน", "rep-orphan-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal manager = actor(createEmployee("ผู้จัดการ ออร์แฟน",
            "sm-orphan-" + UUID.randomUUID() + "@glr.co.th"), "sales_manager");
        String invoiceNumber = "INV-ORPHAN-" + UUID.randomUUID();

        assertThatThrownBy(() -> service.submit(request(salesRepId, invoiceNumber), PDF, manager))
            .isInstanceOf(IllegalStateException.class);

        assertThat(countInvoiceRows(invoiceNumber))
            .as("precondition for the disk assertion below: the transaction really did roll back, "
                + "so there is no sales.invoice_details row referencing the stored file")
            .isZero();
        assertThat(storedFiles())
            .as("the invoice PDF was written to disk BEFORE the audit write failed. Postgres rolled "
                + "the referencing row back; nothing rolls the disk copy back, so without a "
                + "rollback hook this file survives forever as an unreferenced customer invoice")
            .isEmpty();
    }

    @Test
    void commissionSubmit_committing_stillStoresTheFileAndReferencesIt() {
        CommissionService service = transactional(commissionService(mock(AuditService.class)));
        long salesRepId = createEmployee("พนักงานขาย สำเร็จ", "rep-ok-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal manager = actor(createEmployee("ผู้จัดการ สำเร็จ",
            "sm-ok-" + UUID.randomUUID() + "@glr.co.th"), "sales_manager");
        String invoiceNumber = "INV-OK-" + UUID.randomUUID();

        var created = service.submit(request(salesRepId, invoiceNumber), PDF, manager);

        // The success path must be untouched by the rollback hook: same file, same place, same name.
        assertThat(created.invoiceDetails().invoiceAttachmentId()).isNotNull();
        assertThat(countInvoiceRows(invoiceNumber)).isOne();
        assertThat(storedFiles())
            .as("a committed submission keeps exactly the one file store() wrote — proves the "
                + "rollback test above means 'cleaned up', not 'submit never writes a file'")
            .hasSize(1);
        assertThat(committedAttachmentPath(invoiceNumber))
            .as("and the committed row still points at that exact file, which still exists on disk")
            .isEqualTo(storedFiles().get(0).toString());
    }

    /**
     * The vacuity control. Identical injected failure, but the RAW un-proxied service — how ~123
     * other integration tests in this suite are driven. Without a proxy {@code @Transactional} is
     * inert, so nothing rolls back, no synchronization is ever active, and the cleanup hook cannot
     * fire. The orphan therefore survives here BY DESIGN.
     *
     * <p>This asserts today's harness defect, not a desired outcome. Its job is to prove that the
     * two tests above are discriminating: if this one ever starts finding zero files, the proxy is
     * no longer what makes the difference and the rollback evidence above has quietly become
     * vacuous. Delete it the day the harness runs inside a real Spring context with proxied beans.
     */
    @Test
    void commissionSubmit_withoutTheProxy_neverRollsBackSoTheHookNeverFires() {
        CommissionService rawService = commissionService(failingAudit());
        long salesRepId = createEmployee("พนักงานขาย ไม่มีพรอกซี", "rep-np-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal manager = actor(createEmployee("ผู้จัดการ ไม่มีพรอกซี",
            "sm-np-" + UUID.randomUUID() + "@glr.co.th"), "sales_manager");
        String invoiceNumber = "INV-NOPROXY-" + UUID.randomUUID();

        assertThatThrownBy(() -> rawService.submit(request(salesRepId, invoiceNumber), PDF, manager))
            .isInstanceOf(IllegalStateException.class);

        assertThat(countInvoiceRows(invoiceNumber))
            .as("no proxy, no transaction: createInvoice's write auto-commits and survives")
            .isOne();
        assertThat(storedFiles())
            .as("with no transaction there is no rollback to hook, so the file stays — this is the "
                + "control that proves the proxied tests above are not passing trivially")
            .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // CommissionService#createFromDeal — stores the same file_path into sales.attachment too
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createFromDeal_rollingBackAfterTheFileLands_leavesNoOrphanOnDisk() {
        long salesRepId = createEmployee("พนักงานขาย ดีล", "rep-deal-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal account = actor(createEmployee("บัญชี ดีล",
            "acct-deal-" + UUID.randomUUID() + "@glr.co.th"), "account");
        long ticketId = closedPaidTicket(salesRepId);
        CommissionService service = transactional(commissionService(failingAudit()));
        String invoiceNumber = "INV-DEAL-ORPHAN-" + UUID.randomUUID();

        assertThatThrownBy(() -> service.createFromDeal(ticketId, invoiceNumber, LocalDate.of(2026, 7, 1),
                new BigDecimal("50000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PDF, account))
            .isInstanceOf(IllegalStateException.class);

        assertThat(countInvoiceRows(invoiceNumber)).isZero();
        assertThat(countTicketAttachments(ticketId))
            .as("createFromDeal registers the SAME file_path into sales.attachment as well — that "
                + "row rolls back too, so the file it pointed at is doubly unreferenced")
            .isZero();
        assertThat(storedFiles())
            .as("one physical file backed two rolled-back rows; deleting it once must leave the "
                + "uploads root empty")
            .isEmpty();
    }

    @Test
    void createFromDeal_committing_stillStoresTheFileAndReferencesItFromBothRows() {
        long salesRepId = createEmployee("พนักงานขาย ดีลสำเร็จ", "rep-dok-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal account = actor(createEmployee("บัญชี ดีลสำเร็จ",
            "acct-dok-" + UUID.randomUUID() + "@glr.co.th"), "account");
        long ticketId = closedPaidTicket(salesRepId);
        CommissionService service = transactional(commissionService(mock(AuditService.class)));
        String invoiceNumber = "INV-DEAL-OK-" + UUID.randomUUID();

        service.createFromDeal(ticketId, invoiceNumber, LocalDate.of(2026, 7, 1), new BigDecimal("50000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, PDF, account);

        assertThat(countInvoiceRows(invoiceNumber)).isOne();
        assertThat(countTicketAttachments(ticketId))
            .as("the ticket-attachment row that satisfies the deal-close invoiceOnFile gate must "
                + "still be written — the rollback hook must not touch the success path")
            .isOne();
        assertThat(storedFiles()).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // PricingRequestService#uploadAttachment
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void uploadAttachment_rollingBackAfterTheFileLands_leavesNoOrphanOnDisk() {
        long salesRepId = createEmployee("พนักงานขาย คำขอราคา", "rep-pr-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal sales = actor(salesRepId, "sales");
        long pricingRequestId = draftPricingRequest(salesRepId, sales);

        // Fault injector: everything real except addEvent — uploadAttachment's LAST write, so the
        // file and the sales.pricing_request_attachment row both really exist when it throws.
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

    @Test
    void uploadAttachment_committing_stillStoresTheFileAndReferencesIt() {
        long salesRepId = createEmployee("พนักงานขาย คำขอราคาสำเร็จ", "rep-prok-" + UUID.randomUUID() + "@glr.co.th");
        UserPrincipal sales = actor(salesRepId, "sales");
        long pricingRequestId = draftPricingRequest(salesRepId, sales);
        PricingRequestService service = transactional(pricingRequestService(new PricingRequestRepository(jdbc)));

        var attachment = service.uploadAttachment(pricingRequestId, attachmentFile(), sales);

        assertThat(attachment.fileName()).isEqualTo("spec.pdf");
        assertThat(countPricingRequestAttachments(pricingRequestId)).isOne();
        assertThat(storedFiles())
            .as("a committed upload keeps exactly the one file store() wrote, unchanged")
            .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Wiring helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private CommissionService commissionService(AuditService audit) {
        return new CommissionService(
            new CommissionRepository(jdbc),
            new CommissionAttachmentRepository(jdbc),
            new CommissionCalculator(),
            fileStorage,
            audit,
            mock(NotificationService.class),
            tickets,
            new AttachmentRepository(jdbc), new CeoApproverRepository(jdbc));
    }

    private PricingRequestService pricingRequestService(PricingRequestRepository requests) {
        return new PricingRequestService(requests, tickets, new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            new ObjectMapper(), new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
    }

    /** Fails the LAST write of both commission create paths, after the file has really landed. */
    private AuditService failingAudit() {
        AuditService audit = mock(AuditService.class);
        doThrow(new IllegalStateException("injected failure after the file was written to disk"))
            .when(audit).record(any(), any(), any(), any(), any(), any());
        return audit;
    }

    private long closedPaidTicket(long salesRepId) {
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีล Orphan File", "NORMAL", null, null, null, null, null, null, null),
            tickets.nextTicketCode(), salesRepId, "พนักงานขาย ดีล");
        // createFromDeal's resolveDealLinkage requires CLOSED_PAID; a new ticket starts at V50's
        // lead-stage default.
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);
        return ticketId;
    }

    /** A DRAFT pricing request owned by {@code salesRepId} — uploadAttachment's only editable state. */
    private long draftPricingRequest(long salesRepId, UserPrincipal sales) {
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:name, 'factory-orphan@example.com', 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE SET email = EXCLUDED.email
            """, Map.of("name", "Factory Orphan"));
        long productId = insertCatalogProduct("Factory Orphan", "TH", "TEST-ORPHAN-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีล Pricing Orphan", "NORMAL", null, null, null, null, null, null, null),
            tickets.nextTicketCode(), salesRepId, "พนักงานขาย คำขอราคา");
        return pricingRequestService(new PricingRequestRepository(jdbc))
            .createDraft(ticketId, new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                new BigDecimal("1000.00"), "THB", "orphan-file test request", UUID.randomUUID().toString(),
                List.of(new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, "SCG",
                    "Tile Orphan", "SCG Tile Orphan", null, null, "60x60", "Factory Orphan",
                    new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
                    QuantityType.CONFIRMED, null, null, null))),
                sales)
            .summary().id();
    }

    private MultipartFile attachmentFile() {
        return new MockMultipartFile("file", "spec.pdf", "application/pdf", "spec-bytes".getBytes());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Assertion helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    private String committedAttachmentPath(String invoiceNumber) {
        return jdbc.queryForObject("""
            SELECT fa.file_path
              FROM hr.file_attachment fa
              JOIN sales.invoice_details i ON i.invoice_attachment_id = fa.attachment_id
             WHERE i.invoice_number = :invoiceNumber
            """, Map.of("invoiceNumber", invoiceNumber), String.class);
    }

    private int countInvoiceRows(String invoiceNumber) {
        return count("SELECT COUNT(*) FROM sales.invoice_details WHERE invoice_number = :v", invoiceNumber);
    }

    private int countTicketAttachments(long ticketId) {
        return count("SELECT COUNT(*) FROM sales.attachment WHERE ticket_id = :v", ticketId);
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
