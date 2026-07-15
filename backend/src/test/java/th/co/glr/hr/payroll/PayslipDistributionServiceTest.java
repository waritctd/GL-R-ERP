package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
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
    void queueDistributionCountsAlreadySentLinesAndAuditsRequest() {
        PayrollPeriodDto period = period(List.of(line(123L, 42L), line(124L, 77L)));
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period));
        when(payrollRepository.findSentPayslipLineIds(99L)).thenReturn(Set.of(123L));
        UserPrincipal hr = hrUser();

        PayslipDistributionResponse response = service.queueDistribution(99L, hr);

        assertThat(response).isEqualTo(new PayslipDistributionResponse(99L, 2, 1, 1));
        verify(auditService).record(hr, "DISTRIBUTE_PAYSLIPS", "payroll_period", 99L, null, response);
    }

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
            eq("[GL&R HR] Payslip 2026-06-01"),
            any(String.class),
            eq("glr-payslip-2026-06-01-GLR-77.pdf"),
            eq("%PDF".getBytes()));
        verify(payrollRepository).markPayslipEmailSent(99L, pendingLine, "employee@glr.co.th");
        verify(payslipRenderer, never()).toPdf(sentLine, period);
    }

    @Test
    void sendPayslipsMarksMissingEmailAsFailedWithoutRendering() {
        PayrollLineDto line = line(123L, 42L);
        PayrollPeriodDto period = period(List.of(line));
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period));
        when(payrollRepository.findSentPayslipLineIds(99L)).thenReturn(Set.of());
        when(payrollRepository.findEmployeeEmailsByIds(Set.of(42L))).thenReturn(Map.of());

        service.sendPayslips(99L, hrUser());

        verify(payrollRepository).markPayslipEmailFailed(99L, line, null, "Employee has no email address");
        verifyNoInteractions(payslipRenderer);
        verifyNoInteractions(emailService);
    }

    @Test
    void nonHrCannotQueueDistribution() {
        assertThatThrownBy(() -> service.queueDistribution(99L, employeeUser()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private UserPrincipal hrUser() {
        return new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employeeUser() {
        return new UserPrincipal(8L, "employee@glr.co.th", "Employee", "employee", 42L, true, LocalDate.now(), false, null, false);
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
            null
        );
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
