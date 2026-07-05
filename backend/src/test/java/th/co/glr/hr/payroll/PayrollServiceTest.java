package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.config.AppProperties;

class PayrollServiceTest {
    private final PayrollRepository payrollRepository = mock(PayrollRepository.class);
    private final CommissionRepository commissionRepository = mock(CommissionRepository.class);
    private final PayrollService service = new PayrollService(
        payrollRepository,
        mock(PayrollCalculator.class),
        commissionRepository,
        mock(CommissionCalculator.class),
        new AppProperties()
    );

    @Test
    void previewPrefillsUnpaidLeaveFromAttendanceWhenHrHasNoManualInput() {
        // Real calculator so the derived unpaid days flow through to an actual deduction.
        PayrollService previewService = new PayrollService(
            payrollRepository, new PayrollCalculator(), commissionRepository,
            mock(CommissionCalculator.class), new AppProperties());
        long employeeId = 55L;
        when(payrollRepository.findActiveEmployees()).thenReturn(List.of(new PayrollEmployeeSnapshot(
            employeeId, "GLR-55", "ทดสอบ ขาดงาน", "คลัง", "ธนาคาร", "111-222", money("30000.00"))));
        when(payrollRepository.findApprovedOvertimePayByEmployee(any())).thenReturn(Map.of());
        when(payrollRepository.findYearToDateByEmployee(any())).thenReturn(Map.of());
        when(payrollRepository.findAutoUnpaidLeaveDaysByEmployee(any(), any(), anyList()))
            .thenReturn(Map.of(employeeId, new BigDecimal("2")));
        when(commissionRepository.findApprovedRecordsByMonth(any())).thenReturn(List.of());
        when(commissionRepository.findTiers()).thenReturn(List.of());

        PayrollPeriodDto preview = previewService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrUser());

        PayrollLineDto line = preview.lines().get(0);
        // 2 unpaid days x (30000 / 30) = 2000 deduction prefilled for HR to confirm.
        assertThat(line.unpaidLeaveDays()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(line.unpaidLeaveDeduction()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

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
