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
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

class CommissionServiceTest {
    private final CommissionRepository commissions = mock(CommissionRepository.class);
    private final CommissionAttachmentRepository commissionAttachments = mock(CommissionAttachmentRepository.class);
    private final CommissionCalculator calculator = mock(CommissionCalculator.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final CeoApproverRepository ceoApprovers = mock(CeoApproverRepository.class);
    private final CommissionService service = new CommissionService(
        commissions,
        commissionAttachments,
        calculator,
        fileStorage,
        auditService,
        notificationService,
        tickets,
        attachments,
        ceoApprovers
    );

    // Slice A2 (AUTHZ CHANGE): sales can no longer submit/create commissions at all. Written wrong-way-round per
    // CLAUDE.md — the "sales is rejected, nothing is created" case is the one that matters.

    @Test
    void submitAsSales_isForbidden_andCreatesNoRecord() {
        SubmitCommissionRequest request = submitRequest();

        assertThatThrownBy(() -> service.submit(request, invoiceFile(), salesUser()))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(commissions, never()).createInvoice(any());
    }

    // Step 9 gate + cross-check decision (unit level — the SQL enforcement itself is proven by
    // CommissionDealLinkageIntegrationTest against real Postgres, per CLAUDE.md's "unit-test the decision,
    // integration-test the enforcement").

    @Test
    void submitWithLinkedTicketNotClosedPaid_rejectsWithUnprocessableEntity() {
        SubmitCommissionRequest request = submitRequestLinkedTo(42L, new BigDecimal("1000.00"));
        when(tickets.findSalesStage(42L)).thenReturn(Optional.of(DealStage.DELIVERED));

        assertThatThrownBy(() -> service.submit(request, invoiceFile(), accountUser()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verify(commissions, never()).createInvoice(any());
    }

    // Slice A2 createFromDeal decision-level tests (unit) — role gate, salesRepId resolution, grossAmount
    // defaulting, and the duplicate/CLOSED_PAID guards.

    @Test
    void createFromDeal_rejectsDuplicateCommissionForSameDeal_beforeCheckingDealStage() {
        long ticketId = 42L;
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticketWithOwner(ticketId, 30L)));
        when(commissions.hasActiveCommissionForTicket(ticketId)).thenReturn(true);

        assertThatThrownBy(() -> service.createFromDeal(
                ticketId, "INV-X", LocalDate.of(2026, 6, 15), null, null, null, null, null, null, null, null,
                invoiceFile(), accountUser()))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(commissions, never()).createInvoice(any());
        verify(tickets, never()).findSalesStage(anyLong());
    }

    /** FLAG-10 (2026-07-23, owner-confirmed against reconciled real May 2026 accountant data): commission on money
     * received in month M is paid in payroll month M+1 -- invoiceDate May 20 must land payroll_month June 1, not
     * May 1. */
    @Test
    void submit_shiftsPayrollMonthToTheMonthAfterInvoiceDate_flag10() {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, 30L, "INV-FLAG10", LocalDate.of(2026, 5, 20), new BigDecimal("1000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = new InvoiceCalculation(new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        when(calculator.calculateInvoice(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(calculation);
        when(commissions.createInvoice(any(SubmitCommissionRequest.class))).thenReturn(500L);
        when(fileStorage.store(eq("commission-invoice"), eq(500L), any(), any()))
            .thenReturn(new FileStorageService.StoredFile("invoice.pdf", "/tmp/invoice.pdf", "application/pdf", 100L));
        when(commissionAttachments.saveWithContent(eq(500L), any(), any(), any(), any(), anyLong(), any())).thenReturn(700L);
        when(commissions.createCommissionRecord(eq(500L), eq((Long) null), eq(30L), eq(30L), eq(LocalDate.of(2026, 6, 1)),
                eq(calculation), eq((BigDecimal) null), eq(false), eq((BigDecimal) null)))
            .thenReturn(900L);
        when(commissions.findById(900L)).thenReturn(
            Optional.of(record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED)));
        when(commissions.findSalesManagerApproverEmployeeIds()).thenReturn(List.of(88L));

        service.submit(request, invoiceFile(), accountUser());

        verify(commissions).createCommissionRecord(eq(500L), eq((Long) null), eq(30L), eq(30L), eq(LocalDate.of(2026, 6, 1)),
            eq(calculation), eq((BigDecimal) null), eq(false), eq((BigDecimal) null));
    }

    private TicketDto ticketWithOwner(long ticketId, long ownerId) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "TCK-" + ticketId, "SALES", "Deal " + ticketId, "QUOTATION_ISSUED", "NORMAL",
            ownerId, "Owner", null, null,
            "Customer", null, null, null,
            null, null, null,
            Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"), null, 1, false,
            "FULLY_PAID", "FULLY_DELIVERED",
            DealStage.CLOSED_PAID, null, null, null,
            null, null, null, null,
            null);
        return new TicketDto(summary, List.of(), List.of(), null, List.of());
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
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
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
            1,
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
            false,
            null,
            null,
            // V148 (per-item stock-commission weighting): NULL here -- this fixture predates the feature and
            // effectiveWeight() falls back to the plain weightMultiplier (1) above, matching this record's behavior before
            // V148 existed.
            null
        );
    }

    private boolean isManagerApproved(String status) {
        return CommissionStatus.MANAGER_APPROVED.equals(status) || CommissionStatus.APPROVED.equals(status);
    }

    private UserPrincipal salesUser() {
        return new UserPrincipal(30L, "sales@glr.co.th", "Sales", "sales", 30L, true, LocalDate.now(), false, null, false);
    }

    // Slice A2: account replaces sales as the day-to-day commission-creation actor. Same id (30L) as salesUser()
    // so the pre-existing mock stubs below (keyed on submittedById/uploadedBy=30L) keep working unchanged — only
    // the role differs.
    private UserPrincipal accountUser() {
        return new UserPrincipal(30L, "account@glr.co.th", "Account", "account", 30L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal approverUser() {
        return new UserPrincipal(88L, "manager@glr.co.th", "Sales Manager", "sales_manager", 88L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceoUser() {
        return new UserPrincipal(500L, "ceo@glr.co.th", "CEO", "ceo", 500L, true, LocalDate.now(), false, null, false);
    }
}
