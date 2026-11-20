package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.payroll.export.KBankPctExporter;
import th.co.glr.hr.payroll.export.PayrollDetailExporter;
import th.co.glr.hr.payroll.export.PayrollExportKind;
import th.co.glr.hr.payroll.export.Pnd1Exporter;
import th.co.glr.hr.payroll.export.SsoExporter;

class PayrollServiceTest {
    private final PayrollRepository payrollRepository = mock(PayrollRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PayslipRenderer payslipRenderer = mock(PayslipRenderer.class);
    // Leave -> payroll unpaid-day deduction (2026-07-23): PayrollService gained a LeaveRepository dependency for
    // #suggestedInputs; this test class does not exercise that method, so an unused mock is sufficient here.
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
        new PayrollDetailExporter(),
        appProperties,
        mock(th.co.glr.hr.payroll.obligation.DeductionObligationService.class)
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
    void bulkPayslipZipForbiddenForRoleWithoutPayrollAccess() {
        assertThatThrownBy(() -> service.bulkPayslipZip(99L, salesUser()))
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

    // Fix B: statutory exports fail closed when employer registration is blank ------------- Wrong-way-round per
    // CLAUDE.md: these prove the export IS refused on a blank field, not merely that a fully-populated one works
    // (though that positive case is covered too, per kind).

    @Test
    void kbankExportRefusedWhenDebitAccountBlank() {
        AppProperties props = fullyConfiguredEmployerProps();
        props.getPayroll().getEmployer().setKbankDebitAccount("");
        PayrollService serviceUnderTest = serviceWithEmployer(props);
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));

        assertThatThrownBy(() ->
                serviceUnderTest.export(PayrollExportKind.KBANK, 99L, LocalDate.of(2026, 6, 26), hrUser()))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(exception.getMessage()).contains("APP_PAYROLL_KBANK_DEBIT_ACCOUNT");
            });
        verify(payrollRepository, never()).findExportRows(anyLong());
    }

    /** Builds a second PayrollService wired to the same test-scoped mocks as the class-level service field, but
     * with a caller-supplied AppProperties so a single test can flip one employer field at a time without
     * disturbing the shared fixture every other test in this class relies on. */
    private PayrollService serviceWithEmployer(AppProperties props) {
        return new PayrollService(
            payrollRepository,
            mock(PayrollCalculator.class),
            mock(CommissionService.class),
            auditService,
            payslipRenderer,
            mock(LeaveRepository.class),
            new KBankPctExporter(),
            new Pnd1Exporter(),
            new SsoExporter(),
            new PayrollDetailExporter(),
            props,
            mock(th.co.glr.hr.payroll.obligation.DeductionObligationService.class)
        );
    }

    /** Every field Fix B's guard requires (across all three kinds), populated -- so a test that blanks exactly one
     * field via its own setter is provably testing that ONE field, not incidentally relying on some other field
     * already being blank. */
    private static AppProperties fullyConfiguredEmployerProps() {
        AppProperties props = new AppProperties();
        AppProperties.Employer employer = props.getPayroll().getEmployer();
        employer.setCompanyNameTh("บริษัท ทดสอบ จำกัด");
        employer.setCompanyTaxId("0105542026329");
        employer.setPnd1Branch("0000");
        employer.setKbankDebitAccount("6001010598");
        employer.setSsoEmployerAccount("0000000000");
        employer.setSsoBranch("000000");
        employer.setEstablishmentName("บริษัท ทดสอบ establishment สาขา 1");
        return props;
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

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
