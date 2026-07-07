package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.common.ApiException;

class PayrollServiceTest {
    private final PayrollRepository payrollRepository = mock(PayrollRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PayrollService service = new PayrollService(
        payrollRepository,
        mock(PayrollCalculator.class),
        mock(CommissionRepository.class),
        mock(CommissionCalculator.class),
        auditService
    );

    @Test
    void bankExportLogsSensitiveSalaryExportWithoutSecretValues() {
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));
        ListAppender<ILoggingEvent> appender = attachAuditAppender();

        try {
            String body = service.bankExport(99L, hrUser());

            assertThat(body).contains("GLR_PAYROLL|2026-06-01|1|30000.00");
            assertThat(appender.list).anyMatch(event -> {
                String message = event.getFormattedMessage();
                return message.contains("EXPORT_PAYROLL_BANK_FILE")
                    && message.contains("actorId=7")
                    && message.contains("payrollPeriodId=99")
                    && message.contains("targetEmployeeIds=\"42\"")
                    && message.contains("fields=\"bank_account,net_pay\"")
                    && !message.contains("001-234-5678");
            });
        } finally {
            detachAuditAppender(appender);
        }
    }

    @Test
    void ceoCanViewCurrentOrPreview() {
        when(payrollRepository.findPeriodByMonth(LocalDate.of(2026, 6, 1))).thenReturn(Optional.of(period()));

        PayrollPeriodDto result = service.currentOrPreview(LocalDate.of(2026, 6, 1), ceoUser());

        assertThat(result.id()).isEqualTo(99L);
    }

    @Test
    void ceoCanBankExport() {
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));

        String body = service.bankExport(99L, ceoUser());

        assertThat(body).contains("GLR_PAYROLL|2026-06-01|1|30000.00");
    }

    @Test
    void hrProcessingPayrollRecordsAuditTrail() {
        when(payrollRepository.findActiveEmployees()).thenReturn(List.of());
        when(payrollRepository.findApprovedOvertimePayByEmployee(LocalDate.of(2026, 6, 1))).thenReturn(java.util.Map.of());
        when(payrollRepository.findYearToDateByEmployee(LocalDate.of(2026, 6, 1))).thenReturn(java.util.Map.of());
        when(payrollRepository.saveProcessedPeriod(eq(LocalDate.of(2026, 6, 1)), eq(42L), eq(List.of()))).thenReturn(99L);
        PayrollPeriodDto savedPeriod = period();
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(savedPeriod));
        ProcessPayrollRequest request = new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of());
        UserPrincipal hr = hrUser();

        PayrollPeriodDto result = service.process(request, hr);

        assertThat(result.id()).isEqualTo(99L);
        verify(auditService).record(hr, "PROCESS_PAYROLL", "payroll_period", 99L, null, savedPeriod);
    }

    @Test
    void ceoCannotProcessPayroll() {
        ProcessPayrollRequest request = new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of());

        assertThatThrownBy(() -> service.process(request, ceoUser()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void roleWithNoPayrollAccessIsForbiddenOnCurrentOrPreview() {
        assertThatThrownBy(() -> service.currentOrPreview(LocalDate.of(2026, 6, 1), salesUser()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private ListAppender<ILoggingEvent> attachAuditAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("th.co.glr.hr.audit")).addAppender(appender);
        return appender;
    }

    private void detachAuditAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("th.co.glr.hr.audit")).detachAppender(appender);
    }

    private UserPrincipal hrUser() {
        return new UserPrincipal(7L, "hr@glr.co.th", "HR", "hr", 42L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceoUser() {
        return new UserPrincipal(20L, "ceo@glr.co.th", "CEO", "ceo", 20L, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal salesUser() {
        return new UserPrincipal(30L, "sales@glr.co.th", "Sales", "sales", 30L, true, LocalDate.now(), false, null, false);
    }

    private PayrollPeriodDto period() {
        return new PayrollPeriodDto(
            99L,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 6, 30),
            "PROCESSED",
            OffsetDateTime.now(),
            7L,
            1,
            money("40000.00"),
            money("10000.00"),
            money("30000.00"),
            money("750.00"),
            money("500.00"),
            List.of(line())
        );
    }

    private PayrollLineDto line() {
        return new PayrollLineDto(
            123L,
            42L,
            "GLR-42",
            "HR",
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
