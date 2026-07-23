package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.payroll.export.KBankPctExporter;
import th.co.glr.hr.payroll.export.PayrollExportFile;
import th.co.glr.hr.payroll.export.PayrollExportKind;
import th.co.glr.hr.payroll.export.PayrollExportRow;
import th.co.glr.hr.payroll.export.Pnd1Exporter;
import th.co.glr.hr.payroll.export.SsoExporter;

class PayrollServiceTest {
    private final PayrollRepository payrollRepository = mock(PayrollRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PayslipRenderer payslipRenderer = mock(PayslipRenderer.class);
    // Leave -> payroll unpaid-day deduction (2026-07-23): PayrollService gained a LeaveRepository
    // dependency for #suggestedInputs; this test class does not exercise that method, so an unused
    // mock is sufficient here.
    private final AppProperties appProperties = employerConfig();
    private final PayrollService service = new PayrollService(
        payrollRepository,
        mock(PayrollCalculator.class),
        mock(CommissionService.class),
        auditService,
        payslipRenderer,
        mock(LeaveRepository.class),
        new KBankPctExporter(),
        new Pnd1Exporter(),
        new SsoExporter(),
        appProperties
    );

    private static AppProperties employerConfig() {
        AppProperties props = new AppProperties();
        AppProperties.Employer employer = props.getPayroll().getEmployer();
        employer.setCompanyNameTh("บริษัท ทดสอบ จำกัด");
        employer.setCompanyTaxId("0105542026329");
        employer.setKbankDebitAccount("6001010598");
        employer.setSsoEmployerAccount("0000000000");
        return props;
    }

    @Test
    void kbankExportLogsSensitiveSalaryExportWithoutSecretValues() {
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));
        when(payrollRepository.findExportRows(99L)).thenReturn(List.of(exportRow()));
        ListAppender<ILoggingEvent> appender = attachAuditAppender();

        try {
            PayrollExportFile file = service.export(PayrollExportKind.KBANK, 99L, LocalDate.of(2026, 6, 26), hrUser());

            assertThat(file.fileName()).isEqualTo("PCT260626.txt");
            String body = new String(file.content(), th.co.glr.hr.payroll.export.Cp874.CHARSET);
            assertThat(body).startsWith("HPCT");
            assertThat(body).contains("0952555944");   // the employee's KBank account is in the file
            assertThat(appender.list).anyMatch(event -> {
                String message = event.getFormattedMessage();
                return message.contains("EXPORT_PAYROLL_KBANK")
                    && message.contains("actorId=7")
                    && message.contains("payrollPeriodId=99")
                    && message.contains("targetEmployeeIds=\"42\"")
                    && message.contains("fields=\"bank_account,net_pay\"")
                    && !message.contains("0952555944"); // but the audit log must not leak it
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
    void ceoCanExportAndDefaultDateFallsBackToTransferDay() {
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));
        when(payrollRepository.findExportRows(99L)).thenReturn(List.of(exportRow()));

        // null effectiveDate → configured default transfer day (26th) of the payroll month.
        PayrollExportFile file = service.export(PayrollExportKind.KBANK, 99L, null, ceoUser());

        assertThat(file.fileName()).isEqualTo("PCT260626.txt");
        assertThat(new String(file.content(), th.co.glr.hr.payroll.export.Cp874.CHARSET)).startsWith("HPCT");
    }

    @Test
    void exportForbiddenForRoleWithoutPayrollAccess() {
        assertThatThrownBy(() -> service.export(PayrollExportKind.PND1, 99L, null, salesUser()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(payrollRepository);
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
    void payslipPdfRendersRequestedLineAndRecordsAuditTrail() {
        PayrollPeriodDto period = period();
        PayrollLineDto line = line();
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period));
        when(payslipRenderer.toPdf(line, period)).thenReturn("%PDF-line".getBytes());
        UserPrincipal hr = hrUser();

        byte[] pdf = service.payslipPdf(99L, 123L, hr);

        assertThat(pdf).isEqualTo("%PDF-line".getBytes());
        verify(payslipRenderer).toPdf(line, period);
        verify(auditService).record(eq(hr), eq("VIEW_PAYSLIP_PDF"), eq("payroll_line"), eq(123L), eq(null), any());
    }

    @Test
    void ownPayslipPdfResolvesOnlyTheSessionEmployeesLine() {
        PayrollLineDto ownLine = line(123L, 42L, "HR");
        PayrollPeriodDto period = period(List.of(
            ownLine,
            line(124L, 77L, "Other")
        ));
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period));
        when(payslipRenderer.toPdf(ownLine, period)).thenReturn("%PDF-own".getBytes());
        UserPrincipal hr = hrUser();

        byte[] pdf = service.ownPayslipPdf(99L, hr);

        assertThat(pdf).isEqualTo("%PDF-own".getBytes());
        verify(payslipRenderer).toPdf(ownLine, period);
        verify(auditService).record(eq(hr), eq("VIEW_OWN_PAYSLIP_PDF"), eq("payroll_line"), eq(123L), eq(null), any());
    }

    @Test
    void ownPayslipPdfDoesNotReturnAnotherEmployeesLine() {
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period(List.of(line(124L, 77L, "Other")))));

        assertThatThrownBy(() -> service.ownPayslipPdf(99L, hrUser()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(payslipRenderer);
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
        return period(List.of(line()));
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
            1,
            money("40000.00"),
            money("10000.00"),
            money("30000.00"),
            money("750.00"),
            money("500.00"),
            lines
        );
    }

    private PayrollLineDto line() {
        return line(123L, 42L, "HR");
    }

    private PayrollLineDto line(Long id, long employeeId, String employeeName) {
        return new PayrollLineDto(
            id,
            employeeId,
            "GLR-42",
            employeeName,
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

    private PayrollExportRow exportRow() {
        return new PayrollExportRow(
            42L, "GLR-42", "นาง", "กัลยาณี", "ฐิตญาดา", "gullayanee",
            "3100902988046", "1002818495", "3100902988046", "0952555944",
            "99", " ถ.บางนา", "10260",
            money("30000.00"), money("30000.00"), money("500.00"), money("15000.00"), money("750.00"));
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
