package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRecord;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.commission.CommissionStatus;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.ticket.DealStage;

/** The finance page records the commission invoice through the EXISTING CommissionService#createFromDeal (no
 * new write endpoint) and reads it back on the finance deal DTO. */
class FinanceDealCommissionInvoiceIntegrationTest extends FinanceDealTestBase {
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15); // -> payroll month 2026-07-01 (open)

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private NotificationEmailService emailService;
    private long managerEmployeeId;
    private UserPrincipal managerUser;

    @BeforeEach
    void wireCommission() {
        commissions = new CommissionRepository(jdbc);
        emailService = mock(NotificationEmailService.class);
        NotificationService notificationService = new NotificationService(
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), emailService);
        commissionService = new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            new CommissionCalculator(),
            new FileStorageService("/tmp/glr-finance-commission-invoice-test-uploads"),
            mock(AuditService.class),
            notificationService,
            tickets,
            attachments,
            new CeoApproverRepository(jdbc));

        // A real SA-division manager, so findSalesManagerApproverEmployeeIds() has someone to notify.
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        managerEmployeeId = employees.create(new UpsertEmployeeRequest(
            null, null, "ผู้จัดการฝ่ายขาย ใบกำกับ", null, null, null, null, null, null, null,
            "fin-inv-mgr@glr.co.th", null, "SA", "SA", "SA",
            "ผู้จัดการฝ่ายขาย", null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        managerUser = principal(managerEmployeeId, "sales_manager");
        assertThat(commissions.findSalesManagerApproverEmployeeIds()).contains(managerEmployeeId);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void accountRecordsInvoice_viaFromDeal_createsSubmittedCommission_andQueuesManagerEmail() {
        long ticketId = closedPaidDeal();

        CommissionRecord created = recordInvoice(ticketId, "INV-FIN-0001");

        assertThat(created.status()).isEqualTo(CommissionStatus.SUBMITTED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.commission_record WHERE commission_id = :id",
            Map.of("id", created.id()), String.class)).isEqualTo("SUBMITTED");

        List<String> links = jdbc.queryForList("""
            SELECT link FROM hr.notification
             WHERE employee_id = :e AND type = 'COMMISSION_PENDING_MANAGER'
            """, Map.of("e", managerEmployeeId), String.class);
        assertThat(links).containsExactly("/commissions?view=pending");
        // The rep's own "submitted" ping keeps pointing at the list.
        assertThat(jdbc.queryForList("""
            SELECT link FROM hr.notification WHERE employee_id = :e AND type = 'COMMISSION_SUBMITTED'
            """, Map.of("e", salesRepId), String.class)).containsExactly("/commissions");

        // Email queued for that manager (delivery itself is not covered here).
        verify(emailService).send(eq(managerEmployeeId), any(), any(), any(), any(), eq("/commissions?view=pending"));
    }

    /** CLOSED_PAID (S20, inside account's scope) with an accepted 100,000.00 pre-VAT quotation. */
    private long closedPaidDeal() {
        long ticketId = createTicket(DealStage.CLOSED_PAID);
        insertAcceptedQuotation(ticketId, "QT-FCI-" + ticketId, new BigDecimal("100000.00"));
        return ticketId;
    }

    private CommissionRecord recordInvoice(long ticketId, String invoiceNumber) {
        return commissionService.createFromDeal(
            ticketId, invoiceNumber, INVOICE_DATE, new BigDecimal("100000.00"),
            null, null, null, null, null, null, null, invoiceFile(), accountUser);
    }

    private static MockMultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());
    }
}
