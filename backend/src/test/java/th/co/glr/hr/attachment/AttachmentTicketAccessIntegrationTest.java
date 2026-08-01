package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Issue #389, step 2 of CLAUDE.md's "Permission changes must ship evidence": the ENFORCEMENT of
 * {@code TicketAccessPolicy}'s decision, through the real {@link AttachmentController} and the
 * real {@link AttachmentRepository}/{@link TicketRepository} against real Postgres. Mockito
 * cannot reach this — a mocked ticket repository happily returns whatever summary the test hands
 * it, while the real {@code sales.ticket} row (and its nullable {@code assigned_to}) is what the
 * gate actually reads in production.
 *
 * <p><b>The defect.</b> {@code AttachmentController} decided ticket-document access with one
 * blanket constant, {@code MANAGER_ROLES = {hr, sales_manager, ceo}}, used for reads and writes
 * alike, and it was wrong in both directions:
 * <ul>
 *   <li>{@code hr} — which is refused the deal itself, its quotations, its payment ledger and its
 *       activity feed everywhere else in the system ({@code TicketService.VIEWER_ROLES} excludes
 *       it, as does the frontend's {@code canViewTickets}) — could list and download every
 *       customer contract, PO and tax invoice on every deal, with no participant relationship at
 *       all. That single constant was the only place hr held any sales access.</li>
 *   <li>{@code account} — the role that confirms deposit and final-payment receipts
 *       ({@code TicketService.ACCOUNT_ROLES}) and the ONLY role permitted to file invoice
 *       evidence ({@code CommissionService#createFromDeal}) — was refused outright, so it could
 *       be asked to confirm money against a document it could not open.</li>
 * </ul>
 *
 * <p><b>The fix.</b> Reading a deal's documents is aligned with reading the deal
 * ({@code TicketAccessPolicy#canViewDocuments}, sharing {@code VIEWER_ROLES} with
 * {@code TicketService} as one constant rather than two drifting copies), with one deliberate
 * narrowing: {@code import} is refused unless it is a participant, because the documents carry the
 * approved customer price that four other gates already withhold from it. Writing is a separate,
 * narrower question again ({@code TicketAccessPolicy#canManageDocuments}): participants plus
 * sales_manager/ceo, and pointedly NOT account — the tax invoice keeps exactly one entry point.
 *
 * <p>Every case below is written wrong-way-round: "hr asks for a deal it does not participate in
 * → 403" and "account uploads → 403" are the assertions that matter; the positive controls exist
 * only to prove the gate is not simply denying everything.
 *
 * <p><b>MUTATION-CHECK RECORD.</b> Reverted {@code AttachmentController#requireTicketReadAccess}
 * and {@code #requireTicketWriteAccess} to the pre-fix vulnerability — a single
 * {@code Set.of("hr", "sales_manager", "ceo")} participant-or-manager check shared by reads and
 * writes — then rebuilt from clean ({@code rm -rf target/classes}, so no stale {@code .class}
 * could mask it). Exactly the expected tests went red and nothing else; see the report in the
 * commit body for the full list. Reverted afterwards; {@code git diff} of production code against
 * the pre-mutation tree was empty.
 */
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
        attachmentPath = stored.filePath();
        // Uploaded by the CEO, not by any actor under test, so no assertion below can accidentally
        // pass through the "uploader may always reach their own file" shortcut.
        attachmentId = attachments.save(ticketId, null, stored.fileName(), stored.filePath(),
            stored.mimeType(), stored.fileSize(), "OTHER", ceo.id()).id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Problem 1: hr held sales-document access it holds nowhere else in the system.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void hrCannotListTheDocumentsOfADealItDoesNotParticipateIn() {
        assertForbidden(() -> controller.list(ticketId, sessionFor(hr)));
    }

    @Test
    void hrCannotDownloadADealDocument() {
        assertForbidden(() -> controller.download(attachmentId, sessionFor(hr)));
    }

    @Test
    void hrCannotUploadADocumentOntoADeal() {
        assertForbidden(() -> controller.upload(
            ticketId, pdfFile(), "OTHER", null, sessionFor(hr)));
        assertThat(attachments.findByTicketId(ticketId))
            .as("a refused upload must leave no row behind")
            .hasSize(1);
    }

    @Test
    void hrCannotDeleteADealDocument() {
        assertForbidden(() -> controller.delete(attachmentId, sessionFor(hr)));
        assertThat(attachments.findById(attachmentId))
            .as("a refused delete must leave the row intact")
            .isPresent();
        assertThat(Files.exists(Path.of(attachmentPath)))
            .as("a refused delete must leave the file intact")
            .isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Everyone else who must stay out. These already held before #389 and must keep holding —
    // narrowing hr must not have loosened anything on the way past.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSalesRepWhoIsNeitherCreatorNorAssigneeIsRefusedEverything() {
        assertForbidden(() -> controller.list(ticketId, sessionFor(salesOther)));
        assertForbidden(() -> controller.download(attachmentId, sessionFor(salesOther)));
        assertForbidden(() -> controller.upload(ticketId, pdfFile(), "OTHER", null, sessionFor(salesOther)));
        assertForbidden(() -> controller.delete(attachmentId, sessionFor(salesOther)));
    }

    @Test
    void aPlainEmployeeIsRefusedEverything() {
        assertForbidden(() -> controller.list(ticketId, sessionFor(employee)));
        assertForbidden(() -> controller.download(attachmentId, sessionFor(employee)));
        assertForbidden(() -> controller.upload(ticketId, pdfFile(), "OTHER", null, sessionFor(employee)));
        assertForbidden(() -> controller.delete(attachmentId, sessionFor(employee)));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Problem 2: account must be able to OPEN the receipt it confirms money against — but the
    // tax invoice keeps exactly one entry point (CommissionService#createFromDeal), so account
    // gaining read must NOT hand it a second write path.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void accountCannotUploadADocument_theTaxInvoiceKeepsExactlyOneEntryPoint() {
        assertForbidden(() -> controller.upload(
            ticketId, pdfFile(), "INVOICE", null, sessionFor(account)));
        assertThat(attachments.findByTicketId(ticketId))
            .as("account must not be able to satisfy the close gate's invoiceOnFile check here")
            .hasSize(1);
    }

    @Test
    void accountCannotDeleteADocumentItDidNotUpload() {
        assertForbidden(() -> controller.delete(attachmentId, sessionFor(account)));
        assertThat(attachments.findById(attachmentId)).isPresent();
    }

    @Test
    void accountCanNowOpenTheDocumentItMustConfirmMoneyAgainst() {
        assertThat(controller.list(ticketId, sessionFor(account)).get("attachments"))
            .extracting(AttachmentDto::id)
            .containsExactly(attachmentId);
        assertThatCode(() -> controller.download(attachmentId, sessionFor(account)))
            .doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Positive controls: the roles whose job this is keep the access they had.
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    /**
     * THE PIN for import's refusal (issue #389 review), enforced end-to-end. Import reads the deal
     * shell but is refused its documents: {@code AttachType} is
     * {@code {PO, SIGNED_QUOTATION, INVOICE, OTHER}} and {@code findByTicketId} applies no type
     * filter, so a document read would hand import the countersigned quotation and the ใบกำกับภาษี
     * — the approved customer price — on a deal whose quotation file, payment ledger and deposit
     * notices it is refused by {@code TicketService}/{@code DepositNoticeService}.
     *
     * <p>An earlier revision of this branch granted the read. If this test goes red, import has
     * been re-added to {@code TicketAccessPolicy#canViewDocuments} and customer pricing is exposed.
     * {@code importUser} here is NOT this deal's assignee; the separate {@code assignee} actor
     * (also role {@code import}) proves the participant grant still works.
     */
    @Test
    void importIsRefusedDocumentsOnADealItHasNotPickedUp() {
        assertForbidden(() -> controller.list(ticketId, sessionFor(importUser)));
        assertForbidden(() -> controller.download(attachmentId, sessionFor(importUser)));
        assertForbidden(() -> controller.upload(ticketId, pdfFile(), "OTHER", null, sessionFor(importUser)));
        assertForbidden(() -> controller.delete(attachmentId, sessionFor(importUser)));
    }

    @Test
    void theUploaderKeepsAccessToTheirOwnFileRegardlessOfTheDealGate() {
        FileStorageService.StoredFile own = storeFile("own-" + UUID.randomUUID() + ".pdf");
        long ownId = attachments.save(ticketId, null, own.fileName(), own.filePath(),
            own.mimeType(), own.fileSize(), "OTHER", account.id()).id();

        assertThatCode(() -> controller.download(ownId, sessionFor(account))).doesNotThrowAnyException();
        assertThat(controller.delete(ownId, sessionFor(account)).get("ok")).isTrue();
    }

    @Test
    void anUnknownDealIs404NotAnAuthzLeak() {
        assertThatThrownBy(() -> controller.list(ticketId + 999_999L, sessionFor(ceo)))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
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
