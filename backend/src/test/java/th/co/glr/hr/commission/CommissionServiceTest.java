package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;

class CommissionServiceTest {
    private final CommissionRepository commissions = mock(CommissionRepository.class);
    private final CommissionCalculator calculator = mock(CommissionCalculator.class);
    private final AuditService auditService = mock(AuditService.class);
    private final CommissionService service = new CommissionService(commissions, calculator, auditService);

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
        when(commissions.createCommissionRecord(eq(500L), eq((Long) null), eq(30L), eq(30L), eq(LocalDate.of(2026, 6, 1)), eq(calculation)))
            .thenReturn(900L);
        CommissionRecord created = record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED);
        when(commissions.findById(900L)).thenReturn(Optional.of(created));
        UserPrincipal sales = salesUser();

        CommissionRecord result = service.submit(request, sales);

        assertThat(result.id()).isEqualTo(900L);
        verify(auditService).record(sales, "SUBMIT_COMMISSION", "commission_record", 900L, null, created);
    }

    @Test
    void approveRecordsAuditTrailWithBeforeAndAfterState() {
        CommissionRecord existing = record(900L, 30L, CommissionKind.SALE, CommissionStatus.SUBMITTED);
        CommissionRecord approved = record(900L, 30L, CommissionKind.SALE, CommissionStatus.APPROVED);
        when(commissions.findById(900L)).thenReturn(Optional.of(existing), Optional.of(approved));
        UserPrincipal approver = approverUser();

        CommissionRecord result = service.approve(900L, approver);

        assertThat(result.status()).isEqualTo(CommissionStatus.APPROVED);
        verify(commissions).approve(900L, approver.id());
        verify(auditService).record(approver, "APPROVE_COMMISSION", "commission_record", 900L, existing, approved);
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
            null,
            null,
            Instant.parse("2026-06-15T00:00:00Z"),
            Instant.parse("2026-06-15T00:00:00Z")
        );
    }

    private UserPrincipal salesUser() {
        return new UserPrincipal(30L, "sales@glr.co.th", "Sales", "sales", 30L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal approverUser() {
        return new UserPrincipal(88L, "manager@glr.co.th", "Sales Manager", "sales_manager", 88L, true, LocalDate.now(), false, null, false);
    }
}
