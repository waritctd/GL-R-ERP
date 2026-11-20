package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Issue #389, step 2 of CLAUDE.md's "Permission changes must ship evidence": the ENFORCEMENT of
 * TicketAccessPolicy's decision, through the real AttachmentController and the real
 * AttachmentRepository/TicketRepository against real Postgres. */
class AttachmentTicketAccessIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String UPLOAD_DIR = "/tmp/glr-attachment-ticket-access-test-uploads";

    private AttachmentRepository attachments;
    private AttachmentController controller;

    private long ticketId;
    private long attachmentId;
    private String attachmentPath;

    private UserPrincipal salesOwner;
    private UserPrincipal salesOther;
    private UserPrincipal assignee;
    private UserPrincipal salesManager;
    private UserPrincipal importUser;
    private UserPrincipal ceo;
    private UserPrincipal account;
    private UserPrincipal hr;
    private UserPrincipal employee;

    @BeforeEach
    void wireRealCollaboratorsAndSeedADealWithADocument() {
        attachments = new AttachmentRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);
        controller = new AttachmentController(
            attachments, new SessionContext(), tickets, mock(AuditService.class),
            new FileStorageService(UPLOAD_DIR));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));

        salesOwner   = actor(createEmployee(employees, "ตัวแทนขาย เจ้าของ", "sales-owner-389@glr.co.th"), "sales");
        salesOther   = actor(createEmployee(employees, "ตัวแทนขาย อื่น", "sales-other-389@glr.co.th"), "sales");
        assignee     = actor(createEmployee(employees, "ผู้รับมอบหมาย", "assignee-389@glr.co.th"), "import");
        salesManager = actor(createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager-389@glr.co.th"), "sales_manager");
        importUser   = actor(createEmployee(employees, "ฝ่ายนำเข้า", "import-389@glr.co.th"), "import");
        ceo          = actor(createEmployee(employees, "ผู้บริหาร", "ceo-389@glr.co.th"), "ceo");
        account      = actor(createEmployee(employees, "ฝ่ายบัญชี", "account-389@glr.co.th"), "account");
        hr           = actor(createEmployee(employees, "ฝ่ายบุคคล", "hr-389@glr.co.th"), "hr");
        employee     = actor(createEmployee(employees, "พนักงานทั่วไป", "employee-389@glr.co.th"), "employee");

        ticketId = insertTicket(salesOwner.id(), assignee.id());
        FileStorageService.StoredFile stored = storeFile("contract-" + UUID.randomUUID() + ".pdf");
        // Uploaded by the CEO, not by any actor under test, so no assertion below can accidentally pass through the
        // "uploader may always reach their own file" shortcut.
        attachmentId = attachments.save(ticketId, null, stored.fileName(), stored.filePath(),
            stored.mimeType(), stored.fileSize(), "OTHER", ceo.id()).id();
        attachmentPath = stored.filePath();
    }

    // Problem 1: hr held sales-document access it holds nowhere else in the system.

    @Test
    void hrCannotDownloadADealDocument() {
        assertForbidden(() -> controller.download(attachmentId, sessionFor(hr)));
    }

    // Everyone else who must stay out. These already held before #389 and must keep holding — narrowing hr must
    // not have loosened anything on the way past.

    @Test
    void aSalesRepWhoIsNeitherCreatorNorAssigneeIsRefusedEverything() {
        assertForbidden(() -> controller.list(ticketId, sessionFor(salesOther)));
        assertForbidden(() -> controller.download(attachmentId, sessionFor(salesOther)));
        assertForbidden(() -> controller.upload(ticketId, pdfFile(), "OTHER", null, sessionFor(salesOther)));
        assertForbidden(() -> controller.delete(attachmentId, sessionFor(salesOther)));
    }

    // Problem 2: account must be able to OPEN the receipt it confirms money against — but the tax invoice keeps
    // exactly one entry point (CommissionService#createFromDeal), so account gaining read must NOT hand it a
    // second write path.

    // Positive controls: the roles whose job this is keep the access they had.

    @Test
    void theDealsOwnRepAndItsAssigneeAndOversightRolesKeepFullAccess() {
        for (UserPrincipal actor : List.of(salesOwner, assignee, salesManager, ceo)) {
            assertThatCode(() -> controller.list(ticketId, sessionFor(actor)))
                .as("list for role %s", actor.role()).doesNotThrowAnyException();
            assertThatCode(() -> controller.download(attachmentId, sessionFor(actor)))
                .as("download for role %s", actor.role()).doesNotThrowAnyException();
            assertThatCode(() -> controller.upload(ticketId, pdfFile(), "OTHER", null, sessionFor(actor)))
                .as("upload for role %s", actor.role()).doesNotThrowAnyException();
        }
    }

    // ── dead deals (owner ruling 2026-11-04): import / account may READ the documents of a lost or cancelled deal
    // that still has THEIR issued document (IR / deposit notice) -- the link in the lost/cancelled notice -- and
    // nothing else is widened: not the other role's document, not hr, not a non-owner rep, not any write ──────

    private void markDead() {
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED', status = 'cancelled' WHERE ticket_id = :id",
            Map.of("id", ticketId));
    }

    private void seedIssuedDepositNotice() {
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:id, 1, 'ISSUED', 'x', 0.3, 100000.00, 30000.00, 0.07, 2100.00, 32100.00)
            """, Map.of("id", ticketId));
    }

    private void seedIssuedImportRequest() {
        long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES ('Factory Attach Dead', 'IT', 'EUR') RETURNING factory_id
            """, Map.of(), Long.class);
        jdbc.update("""
            INSERT INTO sales.import_request
                (ticket_id, factory_id, factory_name, brand, version, status, doc_number, issue_date,
                 import_step, import_step_at, first_issued_date)
            VALUES (:t, :f, 'Factory Attach Dead', 'Brand', 1, 'ISSUED', 'IR-ATT-1', CURRENT_DATE,
                    'CONTACTED', CURRENT_DATE, CURRENT_DATE)
            """, Map.of("t", ticketId, "f", factoryId));
    }

    private void assertCanRead(UserPrincipal who) {
        assertThatCode(() -> controller.list(ticketId, sessionFor(who))).as("list, %s", who.role()).doesNotThrowAnyException();
        assertThatCode(() -> controller.download(attachmentId, sessionFor(who))).as("download, %s", who.role()).doesNotThrowAnyException();
    }

    @Test
    void accountCanReadTheDocumentsOfADeadDeal_thatHasAnIssuedDepositNotice_butNotWriteThem() {
        markDead();
        seedIssuedDepositNotice();

        assertCanRead(account);
        assertForbidden(() -> controller.upload(ticketId, pdfFile(), "OTHER", null, sessionFor(account)));
        assertForbidden(() -> controller.delete(attachmentId, sessionFor(account)));
    }

    /** A delivery round's files (stored system documents, slips) carry prices: import never sees them, in the list or by
     * id, while an ordinary attachment of the same deal stays visible to it and the money roles keep the round files. */
    @Test
    void importNeverSeesADeliveryRoundsFiles_butStillSeesOrdinaryAttachments() {
        markDead();
        seedIssuedImportRequest();
        long requestId = jdbc.queryForObject("""
            INSERT INTO sales.delivery_request (ticket_id, round_no, status, requested_by)
            VALUES (:t, 1, 'SENT', :u) RETURNING delivery_request_id
            """, Map.of("t", ticketId, "u", salesOwner.id()), Long.class);
        long roundFile = attachments.save(ticketId, null, "DN-1.pdf", attachmentPath, "application/pdf", 3L, "SYSTEM_DOC",
            salesOwner.id(), requestId, "SYSTEM").id();

        assertThat(controller.list(ticketId, sessionFor(importUser)).get("attachments"))
            .extracting(a -> a.id()).containsExactly(attachmentId);
        assertThatThrownBy(() -> controller.download(roundFile, sessionFor(importUser)))
            .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThatCode(() -> controller.download(attachmentId, sessionFor(importUser))).doesNotThrowAnyException();
        assertThat(controller.list(ticketId, sessionFor(salesManager)).get("attachments")).hasSize(2);
        assertThatCode(() -> controller.download(roundFile, sessionFor(ceo))).doesNotThrowAnyException();

        // The warehouse (refused the deal itself) reads a SENT round's file, never a plain attachment, never a draft's.
        UserPrincipal warehouse = actor(salesOther.id(), "warehouse");
        assertThatCode(() -> controller.download(roundFile, sessionFor(warehouse))).doesNotThrowAnyException();
        assertForbidden(() -> controller.download(attachmentId, sessionFor(warehouse)));
        jdbc.update("UPDATE sales.delivery_request SET status = 'DRAFT' WHERE delivery_request_id = :r", Map.of("r", requestId));
        assertForbidden(() -> controller.download(roundFile, sessionFor(warehouse)));
    }

    /** A round's stored copy or extra file can be removed by its uploader only while the round is a draft or returned. */
    @Test
    void aDeliveryRoundsFileCannotBeDeletedOnceItsRoundIsSent() {
        long roundId = jdbc.queryForObject("""
            INSERT INTO sales.delivery_request (ticket_id, round_no, status, requested_by)
            VALUES (:t, 1, 'SENT', :u) RETURNING delivery_request_id
            """, Map.of("t", ticketId, "u", salesOwner.id()), Long.class);
        FileStorageService.StoredFile own = storeFile("round-" + UUID.randomUUID() + ".pdf");
        long fileId = attachments.save(ticketId, null, own.fileName(), own.filePath(), own.mimeType(), own.fileSize(),
            "OTHER", salesOwner.id(), roundId, "SALES").id();

        assertThatThrownBy(() -> controller.delete(fileId, sessionFor(ceo))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT)); // someone else's file stays frozen
        assertThat(attachments.findById(fileId)).isPresent();

        // SENT (not yet forwarded): the uploader may take back their own extra file, never a stored system copy.
        FileStorageService.StoredFile copy = storeFile("copy-" + UUID.randomUUID() + ".pdf");
        long copyId = attachments.save(ticketId, null, copy.fileName(), copy.filePath(), copy.mimeType(), copy.fileSize(),
            "SYSTEM_DOC", salesOwner.id(), roundId, "SYSTEM").id();
        assertThatThrownBy(() -> controller.delete(copyId, sessionFor(salesOwner))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(controller.delete(fileId, sessionFor(salesOwner)).get("ok")).isTrue();
        FileStorageService.StoredFile again = storeFile("again-" + UUID.randomUUID() + ".pdf");
        long againId = attachments.save(ticketId, null, again.fileName(), again.filePath(), again.mimeType(), again.fileSize(),
            "OTHER", salesOwner.id(), roundId, "SALES").id();
        jdbc.update("UPDATE sales.delivery_request SET status = 'FORWARDED' WHERE delivery_request_id = :r", Map.of("r", roundId));
        assertThatThrownBy(() -> controller.delete(againId, sessionFor(salesOwner))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        jdbc.update("UPDATE sales.delivery_request SET status = 'RETURNED' WHERE delivery_request_id = :r", Map.of("r", roundId));
        FileStorageService.StoredFile photo = storeFile("wh-" + UUID.randomUUID() + ".jpg");
        long photoId = attachments.save(ticketId, null, photo.fileName(), photo.filePath(), "image/jpeg", photo.fileSize(),
            "OTHER", salesOther.id(), roundId, "WAREHOUSE").id();
        assertThatThrownBy(() -> controller.delete(photoId, sessionFor(salesOwner))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN)); // the warehouse's evidence is its own
        assertThat(controller.delete(againId, sessionFor(salesOwner)).get("ok")).isTrue();

        // The issued tax invoice: the issuer (a per-person grant, any role) reads it from FORWARDED on and may replace or
        // remove it only until the round is confirmed; import never reads it even when it holds the grant.
        jdbc.update("UPDATE sales.delivery_request SET status = 'FORWARDED' WHERE delivery_request_id = :r", Map.of("r", roundId));
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id IN (:a, :b)",
            Map.of("a", salesOther.id(), "b", importUser.id()));
        UserPrincipal issuer = actor(salesOther.id(), "employee");
        FileStorageService.StoredFile iv = storeFile("iv-" + UUID.randomUUID() + ".pdf");
        long invoiceId = attachments.save(ticketId, null, iv.fileName(), iv.filePath(), iv.mimeType(), iv.fileSize(),
            "INVOICE", salesOther.id(), roundId, "ISSUER").id();
        assertThatCode(() -> controller.download(invoiceId, sessionFor(issuer))).doesNotThrowAnyException();
        assertThatThrownBy(() -> controller.download(invoiceId, sessionFor(importUser))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.delete(invoiceId, sessionFor(ceo))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        jdbc.update("UPDATE sales.delivery_request SET status = 'INVOICED' WHERE delivery_request_id = :r", Map.of("r", roundId));
        assertThatThrownBy(() -> controller.delete(invoiceId, sessionFor(issuer))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        jdbc.update("UPDATE sales.delivery_request SET status = 'FORWARDED' WHERE delivery_request_id = :r", Map.of("r", roundId));
        assertThat(controller.delete(invoiceId, sessionFor(issuer)).get("ok")).isTrue();
    }
    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private MultipartFile pdfFile() {
        return new MockMultipartFile("file", "upload-" + UUID.randomUUID() + ".pdf",
            "application/pdf", "pdf-bytes".getBytes());
    }

    private FileStorageService.StoredFile storeFile(String originalName) {
        MultipartFile file = new MockMultipartFile("file", originalName, "application/pdf", "pdf-bytes".getBytes());
        return new FileStorageService(UPLOAD_DIR).store("tickets", 1L, file, java.util.Set.of());
    }

    private long insertTicket(long createdBy, long assignedTo) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, assigned_to)
            VALUES (:code, :title, :createdBy, :assignedTo)
            RETURNING ticket_id
            """,
            Map.of("code", "A389-" + UUID.randomUUID().toString().substring(0, 8),
                "title", "Deal document access fixture",
                "createdBy", createdBy, "assignedTo", assignedTo),
            Long.class);
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "SA", "แผนกขาย", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId,
            role, employeeId, true, LocalDate.of(2026, 1, 1), false, null, false);
    }

    private HttpSession sessionFor(UserPrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, principal);
        return session;
    }
}
