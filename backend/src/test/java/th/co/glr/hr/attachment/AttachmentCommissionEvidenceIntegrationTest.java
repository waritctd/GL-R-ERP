package th.co.glr.hr.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionStatus;
import th.co.glr.hr.commission.InvoiceCalculation;
import th.co.glr.hr.commission.SubmitCommissionRequest;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** real-DB, real-AttachmentController coverage that the sales rep a commission pays cannot destroy the receipt
 * behind it. */
class AttachmentCommissionEvidenceIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 7, 1);

    private AttachmentRepository attachments;
    private CommissionRepository commissions;
    private AttachmentController controller;
    private long repEmployeeId;
    private long accountEmployeeId;

    private void wire() {
        attachments = new AttachmentRepository(jdbc);
        commissions = new CommissionRepository(jdbc);
        th.co.glr.hr.ticket.TicketRepository tickets = new th.co.glr.hr.ticket.TicketRepository(jdbc);
        controller = new AttachmentController(
            attachments, new SessionContext(), tickets, mock(AuditService.class),
            new FileStorageService("/tmp/glr-attachment-commission-evidence-test-uploads"));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        repEmployeeId = createEmployee(employees, "พนักงานขาย หลักฐาน", "rep-evidence@glr.co.th");
        accountEmployeeId = createEmployee(employees, "บัญชี หลักฐาน", "account-evidence@glr.co.th");
    }

    // Wrong-way-round: the rep being paid cannot delete the receipt behind their own commission, at any live
    // status -- not just APPROVED (a MANAGER_APPROVED commission's approver still needs the file to review it).

    @Test
    void repCannotDelete_evenAtSubmittedStatus_evidenceStillNeededForApproval() {
        assertRepCannotDeleteEvidenceAt(CommissionStatus.SUBMITTED);
    }

    @Test
    void repCannotDelete_evenAtManagerApprovedStatus_evidenceStillNeededForCeoApproval() {
        assertRepCannotDeleteEvidenceAt(CommissionStatus.MANAGER_APPROVED);
    }

    @Test
    void repCannotDelete_onceApproved_theReceiptBehindMoneyAlreadyOwed() {
        assertRepCannotDeleteEvidenceAt(CommissionStatus.APPROVED);
    }

    private void assertRepCannotDeleteEvidenceAt(String commissionStatus) {
        wire();
        SharedFileFixture fixture = seedSharedFileFixture(commissionStatus);

        assertThatThrownBy(() -> controller.delete(fixture.ticketAttachmentId(), sessionFor(repEmployeeId, "sales")))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(attachments.findById(fixture.ticketAttachmentId()))
            .as("the ticket-level attachment row must survive a refused delete")
            .isPresent();
        assertThat(Files.exists(Path.of(fixture.filePath())))
            .as("the physical file must survive a refused delete")
            .isTrue();
    }

    // Positive controls: the guard is scoped to files that actually back a live commission, not a blanket "sales
    // reps can never delete their own attachments" rule.

    @Test
    void unrelatedAttachment_notBackingAnyCommission_stillDeletesNormally() throws Exception {
        wire();
        long ticketId = insertTicket(repEmployeeId);
        FileStorageService.StoredFile stored = storeFile("unrelated-" + UUID.randomUUID() + ".pdf");
        AttachmentDto ticketAttachment = attachments.save(ticketId, null, stored.fileName(), stored.filePath(),
            stored.mimeType(), stored.fileSize(), "OTHER", accountEmployeeId);

        Map<String, Boolean> result = controller.delete(ticketAttachment.id(), sessionFor(repEmployeeId, "sales"));

        assertThat(result.get("ok")).isTrue();
        assertThat(attachments.findById(ticketAttachment.id())).isEmpty();
        assertThat(Files.exists(Path.of(stored.filePath()))).isFalse();
    }

    @Test
    void onceCommissionIsVoided_theReceiptCanBeDeletedAgain() {
        wire();
        SharedFileFixture fixture = seedSharedFileFixture(CommissionStatus.APPROVED);
        jdbc.update("UPDATE sales.commission_record SET status = 'VOID' WHERE commission_id = :id",
            Map.of("id", fixture.commissionId()));

        Map<String, Boolean> result = controller.delete(fixture.ticketAttachmentId(), sessionFor(repEmployeeId, "sales"));

        assertThat(result.get("ok")).isTrue();
        assertThat(attachments.findById(fixture.ticketAttachmentId())).isEmpty();
        assertThat(Files.exists(Path.of(fixture.filePath()))).isFalse();
    }

    // Fixture: reproduces exactly what CommissionService#createFromDeal does.

    private record SharedFileFixture(long ticketAttachmentId, String filePath, long commissionId) {}

    private SharedFileFixture seedSharedFileFixture(String commissionStatus) {
        long ticketId = insertTicket(repEmployeeId);
        FileStorageService.StoredFile stored = storeFile("invoice-" + UUID.randomUUID() + ".pdf");

        AttachmentDto ticketAttachment = attachments.save(ticketId, null, stored.fileName(), stored.filePath(),
            stored.mimeType(), stored.fileSize(), "INVOICE", accountEmployeeId);

        CommissionAttachmentRepository commissionAttachments = new CommissionAttachmentRepository(jdbc);
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            ticketId, repEmployeeId, "INV-EVIDENCE-" + UUID.randomUUID(), INVOICE_DATE,
            new BigDecimal("120000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        long invoiceId = commissions.createInvoice(request);
        long commissionAttachmentId = commissionAttachments.save(
            invoiceId, stored.fileName(), stored.filePath(), stored.mimeType(), stored.fileSize(), accountEmployeeId);
        commissions.attachInvoiceFile(invoiceId, commissionAttachmentId);

        InvoiceCalculation calculation = new CommissionCalculator().calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long commissionId = commissions.createCommissionRecord(
            invoiceId, ticketId, repEmployeeId, accountEmployeeId, PAYROLL_MONTH, calculation);
        if (!CommissionStatus.SUBMITTED.equals(commissionStatus)) {
            jdbc.update("UPDATE sales.commission_record SET status = :status WHERE commission_id = :id",
                Map.of("status", commissionStatus, "id", commissionId));
        }

        return new SharedFileFixture(ticketAttachment.id(), stored.filePath(), commissionId);
    }

    private FileStorageService.StoredFile storeFile(String originalName) {
        MultipartFile file = new MockMultipartFile("file", originalName, "application/pdf", "pdf-bytes".getBytes());
        return new FileStorageService("/tmp/glr-attachment-commission-evidence-test-uploads")
            .store("tickets", 1L, file, java.util.Set.of());
    }

    private long insertTicket(long createdBy) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by)
            VALUES (:code, :title, :createdBy)
            RETURNING ticket_id
            """,
            Map.of("code", "EVID-" + UUID.randomUUID().toString().substring(0, 8), "title", "Evidence fixture ticket",
                "createdBy", createdBy),
            Long.class);
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "SA", "แผนกขาย", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private HttpSession sessionFor(long employeeId, String role) {
        MockHttpSession session = new MockHttpSession();
        UserPrincipal principal = new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId,
            role, employeeId, true, LocalDate.now(), false, null, false);
        session.setAttribute(SessionContext.SESSION_USER_KEY, principal);
        return session;
    }
}
