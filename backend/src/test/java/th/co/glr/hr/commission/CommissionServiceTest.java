package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketRepository;

class CommissionServiceTest {
    private final CommissionRepository commissions = mock(CommissionRepository.class);
    private final CommissionAttachmentRepository commissionAttachments = mock(CommissionAttachmentRepository.class);
    private final CommissionCalculator calculator = mock(CommissionCalculator.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final CommissionService service = new CommissionService(
        commissions,
        commissionAttachments,
        calculator,
        fileStorage,
        auditService,
        notificationService,
        tickets
    );

    @Test
    void submitRecordsAuditTrailForNewCommission() {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null,
            30L,
            "INV-001",
            LocalDate.of(2026, 6, 15),
            new BigDecimal("1000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        InvoiceCalculation calculation = new InvoiceCalculation(new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        when(calculator.calculateInvoice(any(), any(), any(), any(), any(), any())).thenReturn(calculation);
        when(commissions.createInvoice(any(SubmitCommissionRequest.class))).thenReturn(500L);
        when(fileStorage.store(eq("commission-invoice"), eq(500L), any(), any()))
            .thenReturn(new FileStorageService.StoredFile("invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L));
        when(commissionAttachments.save(500L, "invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L, 30L))
            .thenReturn(700L);
        when(commissions.createCommissionRecord(eq(500L), eq((Long) null), eq(30L), eq(30L), eq(LocalDate.of(2026, 6, 1)),
                eq(calculation), eq((BigDecimal) null), eq(false)))
            .thenReturn(900L);
        CommissionRecord created = record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED);
        when(commissions.findById(900L)).thenReturn(Optional.of(created));
        when(commissions.findSalesManagerApproverEmployeeIds()).thenReturn(List.of(88L));
        UserPrincipal sales = salesUser();

        CommissionRecord result = service.submit(request, invoiceFile(), sales);

        assertThat(result.id()).isEqualTo(900L);
        verify(commissions).attachInvoiceFile(500L, 700L);
        verify(auditService).record(sales, "SUBMIT_COMMISSION", "commission_record", 900L, null, created);
        verify(notificationService).notify(eq(30L), eq("COMMISSION_SUBMITTED"), anyString(), anyString(), eq("/commissions"), eq(true));
        verify(notificationService).notify(eq(88L), eq("COMMISSION_PENDING_MANAGER"), anyString(), anyString(), eq("/commissions"), eq(true));
    }

    @Test
    void submitRequiresInvoiceFile() {
        SubmitCommissionRequest request = submitRequest();

        assertThatThrownBy(() -> service.submit(request, salesUser()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Step 9 gate + cross-check decision (unit level — the SQL enforcement itself is proven by
    // CommissionDealLinkageIntegrationTest against real Postgres, per CLAUDE.md's "unit-test the
    // decision, integration-test the enforcement"). ──────────────────────────────────────────

    @Test
    void submitWithLinkedTicketNotFound_rejectsBeforeTouchingTheInvoice() {
        SubmitCommissionRequest request = submitRequestLinkedTo(999L, new BigDecimal("1000.00"));
        when(tickets.findSalesStage(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(request, invoiceFile(), salesUser()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.NOT_FOUND);
        verify(commissions, never()).createInvoice(any());
    }

    @Test
    void submitWithLinkedTicketNotClosedPaid_rejectsWithUnprocessableEntity() {
        SubmitCommissionRequest request = submitRequestLinkedTo(42L, new BigDecimal("1000.00"));
        when(tickets.findSalesStage(42L)).thenReturn(Optional.of(DealStage.DELIVERED));

        assertThatThrownBy(() -> service.submit(request, invoiceFile(), salesUser()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(commissions, never()).createInvoice(any());
    }

    @Test
    void submitWithLinkedClosedPaidTicket_withinThreshold_succeedsAndRecordsNoMismatch() {
        SubmitCommissionRequest request = submitRequestLinkedTo(42L, new BigDecimal("1000.00"));
        when(tickets.findSalesStage(42L)).thenReturn(Optional.of(DealStage.CLOSED_PAID));
        when(tickets.payableAmount(42L)).thenReturn(new BigDecimal("1030.00")); // 2.9% off — within 5%
        InvoiceCalculation calculation = new InvoiceCalculation(new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        when(calculator.calculateInvoice(any(), any(), any(), any(), any(), any())).thenReturn(calculation);
        when(commissions.createInvoice(any(SubmitCommissionRequest.class))).thenReturn(500L);
        when(fileStorage.store(eq("commission-invoice"), eq(500L), any(), any()))
            .thenReturn(new FileStorageService.StoredFile("invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L));
        when(commissionAttachments.save(500L, "invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L, 30L))
            .thenReturn(700L);
        when(commissions.createCommissionRecord(eq(500L), eq(42L), eq(30L), eq(30L), any(),
                eq(calculation), eq(new BigDecimal("1030.00")), eq(false)))
            .thenReturn(900L);
        CommissionRecord created = record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED);
        when(commissions.findById(900L)).thenReturn(Optional.of(created));
        when(commissions.findSalesManagerApproverEmployeeIds()).thenReturn(List.of(88L));

        CommissionRecord result = service.submit(request, invoiceFile(), salesUser());

        assertThat(result.id()).isEqualTo(900L);
        verify(commissions).createCommissionRecord(eq(500L), eq(42L), eq(30L), eq(30L), any(),
            eq(calculation), eq(new BigDecimal("1030.00")), eq(false));
    }

    @Test
    void submitWithLinkedClosedPaidTicket_beyondThreshold_stillSucceedsButFlagsMismatch() {
        SubmitCommissionRequest request = submitRequestLinkedTo(42L, new BigDecimal("1000.00"));
        when(tickets.findSalesStage(42L)).thenReturn(Optional.of(DealStage.CLOSED_PAID));
        when(tickets.payableAmount(42L)).thenReturn(new BigDecimal("1200.00")); // 16.7% off — beyond 5%
        InvoiceCalculation calculation = new InvoiceCalculation(new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        when(calculator.calculateInvoice(any(), any(), any(), any(), any(), any())).thenReturn(calculation);
        when(commissions.createInvoice(any(SubmitCommissionRequest.class))).thenReturn(500L);
        when(fileStorage.store(eq("commission-invoice"), eq(500L), any(), any()))
            .thenReturn(new FileStorageService.StoredFile("invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L));
        when(commissionAttachments.save(500L, "invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L, 30L))
            .thenReturn(700L);
        when(commissions.createCommissionRecord(eq(500L), eq(42L), eq(30L), eq(30L), any(),
                eq(calculation), eq(new BigDecimal("1200.00")), eq(true)))
            .thenReturn(900L);
        CommissionRecord created = record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED);
        when(commissions.findById(900L)).thenReturn(Optional.of(created));
        when(commissions.findSalesManagerApproverEmployeeIds()).thenReturn(List.of(88L));

        CommissionRecord result = service.submit(request, invoiceFile(), salesUser());

        assertThat(result.id()).isEqualTo(900L);
        verify(commissions).createCommissionRecord(eq(500L), eq(42L), eq(30L), eq(30L), any(),
            eq(calculation), eq(new BigDecimal("1200.00")), eq(true));
    }

    @Test
    void submitWithNoLinkedTicket_neverConsultsTicketRepository_regressionGuard() {
        SubmitCommissionRequest request = submitRequest();
        InvoiceCalculation calculation = new InvoiceCalculation(new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        when(calculator.calculateInvoice(any(), any(), any(), any(), any(), any())).thenReturn(calculation);
        when(commissions.createInvoice(any(SubmitCommissionRequest.class))).thenReturn(500L);
        when(fileStorage.store(eq("commission-invoice"), eq(500L), any(), any()))
            .thenReturn(new FileStorageService.StoredFile("invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L));
        when(commissionAttachments.save(500L, "invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L, 30L))
            .thenReturn(700L);
        when(commissions.createCommissionRecord(eq(500L), eq((Long) null), eq(30L), eq(30L), any(),
                eq(calculation), eq((BigDecimal) null), eq(false)))
            .thenReturn(900L);
        CommissionRecord created = record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED);
        when(commissions.findById(900L)).thenReturn(Optional.of(created));
        when(commissions.findSalesManagerApproverEmployeeIds()).thenReturn(List.of(88L));

        CommissionRecord result = service.submit(request, invoiceFile(), salesUser());

        assertThat(result.id()).isEqualTo(900L);
        assertThat(result.dealPayableAmountSnapshot()).isNull();
        assertThat(result.dealAmountMismatch()).isFalse();
        verify(tickets, never()).findSalesStage(anyLong());
        verify(tickets, never()).payableAmount(anyLong());
    }

    private SubmitCommissionRequest submitRequestLinkedTo(Long sourceTicketId, BigDecimal grossAmount) {
        return new SubmitCommissionRequest(
            sourceTicketId,
            30L,
            "INV-001",
            LocalDate.of(2026, 6, 15),
            grossAmount,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    @Test
    void managerApprovalTransitionsSubmittedToManagerApproved() {
        CommissionRecord existing = record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED);
        CommissionRecord managerApproved = record(900L, 30L, CommissionKind.SALE, CommissionStatus.MANAGER_APPROVED);
        when(commissions.findById(900L)).thenReturn(Optional.of(existing), Optional.of(managerApproved));
        when(commissions.findCeoApproverEmployeeIds()).thenReturn(List.of(500L));
        UserPrincipal approver = approverUser();

        CommissionRecord result = service.approve(900L, approver);

        assertThat(result.status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);
        verify(commissions).managerApprove(900L, approver.id());
        verify(auditService).record(approver, "MANAGER_APPROVE_COMMISSION", "commission_record", 900L, existing, managerApproved);
        verify(notificationService).notify(eq(30L), eq("COMMISSION_MANAGER_APPROVED"), anyString(), anyString(), eq("/commissions"), eq(true));
        verify(notificationService).notify(eq(500L), eq("COMMISSION_PENDING_CEO"), anyString(), anyString(), eq("/commissions"), eq(true));
    }

    @Test
    void ceoApprovalTransitionsManagerApprovedToApproved() {
        CommissionRecord managerApproved = record(900L, 30L, CommissionKind.SALE, CommissionStatus.MANAGER_APPROVED);
        CommissionRecord approved = record(900L, 30L, CommissionKind.SALE, CommissionStatus.APPROVED);
        when(commissions.findById(900L)).thenReturn(Optional.of(managerApproved), Optional.of(approved));
        UserPrincipal ceo = ceoUser();

        CommissionRecord result = service.approve(900L, ceo);

        assertThat(result.status()).isEqualTo(CommissionStatus.APPROVED);
        verify(commissions).ceoApprove(900L, ceo.id());
        verify(auditService).record(ceo, "CEO_APPROVE_COMMISSION", "commission_record", 900L, managerApproved, approved);
        verify(notificationService).notify(eq(30L), eq("COMMISSION_APPROVED"), anyString(), anyString(), eq("/commissions"), eq(true));
        verify(notificationService).notify(eq(88L), eq("COMMISSION_APPROVED"), anyString(), anyString(), eq("/commissions"), eq(true));
    }

    @Test
    void managerRejectTransitionsSubmittedToRejected() {
        CommissionRecord submitted = record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED);
        CommissionRecord rejected = record(900L, 30L, CommissionKind.SALE, CommissionStatus.REJECTED);
        when(commissions.findById(900L)).thenReturn(Optional.of(submitted), Optional.of(rejected));
        UserPrincipal manager = approverUser();

        CommissionRecord result = service.reject(900L, new ReviewCommissionRequest("missing invoice detail"), manager);

        assertThat(result.status()).isEqualTo(CommissionStatus.REJECTED);
        verify(commissions).managerReject(900L, manager.id(), "missing invoice detail");
        verify(auditService).record(manager, "REJECT_COMMISSION", "commission_record", 900L, submitted, rejected);
        verify(notificationService).notify(eq(30L), eq("COMMISSION_REJECTED"), anyString(), anyString(), eq("/commissions"), eq(true));
    }

    @Test
    void ceoRejectTransitionsManagerApprovedToRejected() {
        CommissionRecord managerApproved = record(900L, 30L, CommissionKind.SALE, CommissionStatus.MANAGER_APPROVED);
        CommissionRecord rejected = record(900L, 30L, CommissionKind.SALE, CommissionStatus.REJECTED);
        when(commissions.findById(900L)).thenReturn(Optional.of(managerApproved), Optional.of(rejected));
        UserPrincipal ceo = ceoUser();

        CommissionRecord result = service.reject(900L, new ReviewCommissionRequest("needs review"), ceo);

        assertThat(result.status()).isEqualTo(CommissionStatus.REJECTED);
        verify(commissions).ceoReject(900L, ceo.id(), "needs review");
        verify(auditService).record(ceo, "CEO_REJECT_COMMISSION", "commission_record", 900L, managerApproved, rejected);
        verify(notificationService).notify(eq(30L), eq("COMMISSION_REJECTED"), anyString(), anyString(), eq("/commissions"), eq(true));
    }

    @Test
    void salesManagerCannotCeoApproveManagerApprovedCommission() {
        when(commissions.findById(900L))
            .thenReturn(Optional.of(record(900L, 30L, CommissionKind.SALE, CommissionStatus.MANAGER_APPROVED)));

        assertThatThrownBy(() -> service.approve(900L, approverUser()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private SubmitCommissionRequest submitRequest() {
        return new SubmitCommissionRequest(
            null,
            30L,
            "INV-001",
            LocalDate.of(2026, 6, 15),
            new BigDecimal("1000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    private MockMultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());
    }

    private CommissionRecord record(long id, long salesRepId, String kind, String status) {
        InvoiceDetails invoice = new InvoiceDetails(
            50L,
            "INV-001",
            LocalDate.of(2026, 6, 15),
            new BigDecimal("1000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            700L,
            "invoice.pdf",
            Instant.parse("2026-06-15T00:00:00Z"),
            Instant.parse("2026-06-15T00:00:00Z")
        );
        return new CommissionRecord(
            id,
            invoice,
            null,
            salesRepId,
            "Test Sales Rep",
            salesRepId,
            kind,
            status,
            LocalDate.of(2026, 6, 1),
            new BigDecimal("1000.00"),
            new BigDecimal("1000.00"),
            null,
            null,
            isManagerApproved(status) ? 88L : null,
            isManagerApproved(status) ? "Sales Manager" : null,
            isManagerApproved(status) ? Instant.parse("2026-06-15T01:00:00Z") : null,
            CommissionStatus.APPROVED.equals(status) ? 500L : null,
            CommissionStatus.APPROVED.equals(status) ? "CEO" : null,
            CommissionStatus.APPROVED.equals(status) ? Instant.parse("2026-06-15T02:00:00Z") : null,
            CommissionStatus.REJECTED.equals(status) ? 88L : null,
            CommissionStatus.REJECTED.equals(status) ? "Sales Manager" : null,
            CommissionStatus.REJECTED.equals(status) ? Instant.parse("2026-06-15T03:00:00Z") : null,
            CommissionStatus.REJECTED.equals(status) ? "missing invoice detail" : null,
            null,
            null,
            Instant.parse("2026-06-15T00:00:00Z"),
            Instant.parse("2026-06-15T00:00:00Z"),
            null,
            false
        );
    }

    private boolean isManagerApproved(String status) {
        return CommissionStatus.MANAGER_APPROVED.equals(status) || CommissionStatus.APPROVED.equals(status);
    }

    private UserPrincipal salesUser() {
        return new UserPrincipal(30L, "sales@glr.co.th", "Sales", "sales", 30L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal approverUser() {
        return new UserPrincipal(88L, "manager@glr.co.th", "Sales Manager", "sales_manager", 88L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceoUser() {
        return new UserPrincipal(500L, "ceo@glr.co.th", "CEO", "ceo", 500L, true, LocalDate.now(), false, null, false);
    }
}
