package th.co.glr.hr.payroll;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.notification.NotificationEmailService;

class PayslipDistributionServiceTest {
    private final PayrollRepository payrollRepository = mock(PayrollRepository.class);
    private final PayslipRenderer payslipRenderer = mock(PayslipRenderer.class);
    private final NotificationEmailService emailService = mock(NotificationEmailService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PayslipDistributionService service = new PayslipDistributionService(
        payrollRepository,
        payslipRenderer,
        emailService,
        auditService
    );

    @Test
    void sendPayslipsSkipsSentLinesAndMarksSuccessfulDelivery() {
        PayrollLineDto sentLine = line(123L, 42L);
        PayrollLineDto pendingLine = line(124L, 77L);
        PayrollPeriodDto period = period(List.of(sentLine, pendingLine));
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period));
        when(payrollRepository.findSentPayslipLineIds(99L)).thenReturn(Set.of(123L));
        when(payrollRepository.findEmployeeEmailsByIds(Set.of(42L, 77L)))
            .thenReturn(Map.of(77L, "employee@glr.co.th"));
        when(payrollRepository.markPayslipEmailPending(99L, pendingLine, "employee@glr.co.th")).thenReturn(true);
        when(payslipRenderer.toPdf(pendingLine, period)).thenReturn("%PDF".getBytes());

        service.sendPayslips(99L, hrUser());

        verify(emailService).sendWithAttachment(
            eq("employee@glr.co.th"),
            eq("[GL&R Portal] Payslip 2026-06-01"),
            any(String.class),
            eq("glr-payslip-2026-06-01-GLR-77.pdf"),
            eq("%PDF".getBytes()));
        verify(payrollRepository).markPayslipEmailSent(99L, pendingLine, "employee@glr.co.th");
        verify(payslipRenderer, never()).toPdf(sentLine, period);
    }

    private UserPrincipal hrUser() {
        return new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), false, null, false);
    }

    private PayrollPeriodDto period(List<PayrollLineDto> lines) {
        return new PayrollPeriodDto(
            99L,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 6, 30),
            "PROCESSED",
            OffsetDateTime.now(),
            7L,
            lines.size(),
            money("40000.00"),
            money("10000.00"),
            money("30000.00"),
            money("750.00"),
            money("500.00"),
            lines
        );
    }

    private PayrollLineDto line(Long id, long employeeId) {
        return new PayrollLineDto(
            id,
            employeeId,
            "GLR-" + employeeId,
            "Employee " + employeeId,
            "บุคคล",
            "ธนาคาร",
            "001-234-5678",
            money("40000.00"),
            money("1333.33"),
            money("166.67"),
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            money("40000.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            money("40000.00"),
            money("15000.00"),
            money("750.00"),
            money("480000.00"),
            money("100000.00"),
            BigDecimal.ZERO,
            money("380000.00"),
            money("9500.00"),
            money("500.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            money("8750.00"),
            money("10000.00"),
            money("30000.00"),
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
