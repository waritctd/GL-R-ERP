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
import org.springframework.http.HttpStatus;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.payroll.export.KBankPctExporter;
import th.co.glr.hr.payroll.export.PayrollDetailExporter;
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
    void payrollDetailExportProducesAnXlsxWorkbookWithHeaderKnownRowAndCorrectTotals() throws Exception {
        PayrollLineDto first = line(123L, 42L, "HR หนึ่ง");
        PayrollLineDto second = line(124L, 43L, "HR สอง");
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period(List.of(first, second))));
        when(payrollRepository.findLines(99L)).thenReturn(List.of(first, second));
        when(payrollRepository.findDetailIdentity(java.util.Set.of(42L, 43L))).thenReturn(Map.of());

        PayrollExportFile file = service.export(PayrollExportKind.PAYROLL_DETAIL, 99L, LocalDate.of(2026, 6, 26), hrUser());

        assertThat(file.fileName()).isEqualTo("PayrollDetail260626.xlsx");
        assertThat(file.kind().contentType())
            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(file.content()))) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
            Map<String, Integer> columnIndex = new java.util.HashMap<>();
            for (org.apache.poi.ss.usermodel.Cell cell : headerRow) {
                columnIndex.put(cell.getStringCellValue(), cell.getColumnIndex());
            }
            // Thai headers survive real UTF-8 (xlsx), unlike the CP874 text exporters.
            assertThat(columnIndex).containsKeys(
                "รหัสพนักงาน", "ชื่อ", "เงินเดือน", "พิเศษ 7 (คอมมิชชั่น)", "ค่าคอมมิชชั่น (ระบบขาย)",
                "รวมรายได้ที่ต้องคิดภาษี (A)", "คงเหลือจ่ายจริง (A-B-C+D)");

            org.apache.poi.ss.usermodel.Row dataRow = sheet.getRow(1);
            assertThat(dataRow.getCell(columnIndex.get("รหัสพนักงาน")).getStringCellValue()).isEqualTo("GLR-42");
            assertThat(dataRow.getCell(columnIndex.get("ชื่อ")).getStringCellValue()).isEqualTo("HR หนึ่ง");
            assertThat(dataRow.getCell(columnIndex.get("เงินเดือน")).getNumericCellValue()).isEqualTo(40000.00);
            assertThat(dataRow.getCell(columnIndex.get("คงเหลือจ่ายจริง (A-B-C+D)")).getNumericCellValue())
                .isEqualTo(30000.00);

            int totalsRowIdx = sheet.getLastRowNum();
            org.apache.poi.ss.usermodel.Row totalsRow = sheet.getRow(totalsRowIdx);
            assertThat(totalsRow.getCell(columnIndex.get("เงินเดือน")).getNumericCellValue())
                .as("totals row must equal the sum of the two data rows' เงินเดือน")
                .isEqualTo(80000.00);
            assertThat(totalsRow.getCell(columnIndex.get("คงเหลือจ่ายจริง (A-B-C+D)")).getNumericCellValue())
                .isEqualTo(60000.00);
        }
    }

    @Test
    void payrollDetailExportOnAVoidedPeriodRecomputesRatherThanTrustingStaleLinesNo500() {
        // No lines at all (the empty-employees case, matching hrProcessingPayrollRecordsAuditTrail's
        // stubbing below) is enough to prove the reconstruct-and-recompute path (see
        // PayrollService#export's PAYROLL_DETAIL/VOID branch) doesn't 500 -- the meaningful "stale
        // figures get discarded, not trusted" case is covered end-to-end against real Postgres by
        // PayrollDetailExportVoidRecomputeIntegrationTest.
        PayrollPeriodDto voided = new PayrollPeriodDto(
            99L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 6, 30), "VOID", OffsetDateTime.now(), 7L, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(voided));
        when(payrollRepository.findActiveEmployees()).thenReturn(List.of());
        when(payrollRepository.findApprovedOvertimePayByEmployee(LocalDate.of(2026, 6, 1))).thenReturn(Map.of());
        when(payrollRepository.findYearToDateByEmployee(LocalDate.of(2026, 6, 1))).thenReturn(Map.of());
        when(payrollRepository.findDetailIdentity(java.util.Set.of())).thenReturn(Map.of());

        PayrollExportFile file = service.export(PayrollExportKind.PAYROLL_DETAIL, 99L, LocalDate.of(2026, 6, 26), hrUser());

        assertThat(file.fileName()).isEqualTo("PayrollDetail260626.xlsx");
    }

    @Test
    void exportDetailPreviewComputesFromTheSameLivePreviewPathWithNoPersistedPeriod() {
        // Owner requirement (2026-07-30): July 2026 is live and unprocessed, so HR must be able to
        // download the detail workbook for a month that has never been saved at all -- no periodId
        // exists to key a GET off. This goes straight through the private preview() computation, so
        // stubs mirror hrProcessingPayrollRecordsAuditTrail's (a live preview with zero employees).
        when(payrollRepository.findActiveEmployees()).thenReturn(List.of());
        when(payrollRepository.findApprovedOvertimePayByEmployee(LocalDate.of(2026, 7, 1))).thenReturn(Map.of());
        when(payrollRepository.findYearToDateByEmployee(LocalDate.of(2026, 7, 1))).thenReturn(Map.of());
        when(payrollRepository.findDetailIdentity(java.util.Set.of())).thenReturn(Map.of());

        ProcessPayrollRequest request = new ProcessPayrollRequest(LocalDate.of(2026, 7, 1), List.of());
        PayrollExportFile file = service.exportDetailPreview(request, LocalDate.of(2026, 7, 26), hrUser());

        assertThat(file.fileName()).isEqualTo("PayrollDetail260726.xlsx");
        assertThat(file.kind()).isEqualTo(PayrollExportKind.PAYROLL_DETAIL);
        // No period was ever persisted -- confirms this truly went through preview(), not a
        // findPeriodById/findLines lookup (verifyNoInteractions would be too strong since
        // findActiveEmployees/findApprovedOvertimePayByEmployee/findYearToDateByEmployee ARE real
        // interactions on payrollRepository; this instead asserts the one call that would only ever
        // happen for a persisted period never happened).
        org.mockito.Mockito.verify(payrollRepository, org.mockito.Mockito.never()).findPeriodById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void bulkPayslipZipContainsOnePdfPerLineNamedByEmployeeCodeAndMatchingTheSingleDownload() throws Exception {
        PayrollLineDto lineA = line(123L, 42L, "พนักงาน หนึ่ง");
        PayrollLineDto lineB = line(124L, 43L, "พนักงาน สอง");
        PayrollPeriodDto processed = period(List.of(lineA, lineB));
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(processed));
        when(payslipRenderer.toPdf(lineA, processed)).thenReturn("%PDF-A".getBytes());
        when(payslipRenderer.toPdf(lineB, processed)).thenReturn("%PDF-B".getBytes());

        byte[] zipBytes = service.bulkPayslipZip(99L, hrUser());

        Map<String, byte[]> entries = readZipEntries(zipBytes);
        // Both lines share employeeCode "GLR-42" in this fixture (line() hardcodes it), so the
        // second entry must be disambiguated rather than silently overwriting the first -- exactly
        // two archive entries, one at the plain name, both PDF bodies present somewhere in the zip.
        // The exact disambiguation scheme (suffix, counter, ...) is an implementation detail, so it
        // is not asserted here beyond "a distinct second name exists".
        assertThat(entries).hasSize(2);
        assertThat(entries).containsKey("glr-payslip-GLR-42.pdf");
        assertThat(entries.values()).extracting(String::new).containsExactlyInAnyOrder("%PDF-A", "%PDF-B");

        // Byte-identical to the single-payslip endpoint for the same line -- the whole point of
        // reusing PayslipRenderer#toPdf rather than a second renderer.
        byte[] singleA = service.payslipPdf(99L, 123L, hrUser());
        assertThat(singleA).isEqualTo("%PDF-A".getBytes());
    }

    @Test
    void bulkPayslipZipRefusesANonProcessedPeriod() {
        PayrollPeriodDto preview = new PayrollPeriodDto(
            99L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 6, 30), "VOID", OffsetDateTime.now(), 7L, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(preview));

        assertThatThrownBy(() -> service.bulkPayslipZip(99L, hrUser()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void bulkPayslipZipForbiddenForRoleWithoutPayrollAccess() {
        assertThatThrownBy(() -> service.bulkPayslipZip(99L, salesUser()))
            .isInstanceOfSatisfying(ApiException.class, exception ->
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(payrollRepository);
    }

    private Map<String, byte[]> readZipEntries(byte[] zipBytes) throws java.io.IOException {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
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

    // ---- Fix B: statutory exports fail closed when employer registration is blank -------------
    // Wrong-way-round per CLAUDE.md: these prove the export IS refused on a blank field, not merely
    // that a fully-populated one works (though that positive case is covered too, per kind).

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

    @Test
    void kbankExportRefusedWhenCompanyNameBlank() {
        AppProperties props = fullyConfiguredEmployerProps();
        props.getPayroll().getEmployer().setCompanyNameTh("");
        PayrollService serviceUnderTest = serviceWithEmployer(props);
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));

        assertThatThrownBy(() ->
                serviceUnderTest.export(PayrollExportKind.KBANK, 99L, LocalDate.of(2026, 6, 26), hrUser()))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(exception.getMessage()).contains("APP_PAYROLL_COMPANY_NAME_TH");
            });
    }

    @Test
    void kbankExportProceedsWhenFullyConfigured() {
        PayrollService serviceUnderTest = serviceWithEmployer(fullyConfiguredEmployerProps());
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));
        when(payrollRepository.findExportRows(99L)).thenReturn(List.of(exportRow()));

        PayrollExportFile file =
            serviceUnderTest.export(PayrollExportKind.KBANK, 99L, LocalDate.of(2026, 6, 26), hrUser());

        assertThat(file.fileName()).isEqualTo("PCT260626.txt");
    }

    @Test
    void pnd1ExportRefusedWhenCompanyTaxIdBlank() {
        AppProperties props = fullyConfiguredEmployerProps();
        props.getPayroll().getEmployer().setCompanyTaxId("");
        PayrollService serviceUnderTest = serviceWithEmployer(props);
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));

        assertThatThrownBy(() ->
                serviceUnderTest.export(PayrollExportKind.PND1, 99L, LocalDate.of(2026, 6, 26), hrUser()))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(exception.getMessage()).contains("APP_PAYROLL_COMPANY_TAX_ID");
            });
        verify(payrollRepository, never()).findExportRows(anyLong());
    }

    @Test
    void pnd1ExportRefusedWhenBranchBlank() {
        AppProperties props = fullyConfiguredEmployerProps();
        props.getPayroll().getEmployer().setPnd1Branch("");
        PayrollService serviceUnderTest = serviceWithEmployer(props);
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));

        assertThatThrownBy(() ->
                serviceUnderTest.export(PayrollExportKind.PND1, 99L, LocalDate.of(2026, 6, 26), hrUser()))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(exception.getMessage()).contains("APP_PAYROLL_PND1_BRANCH");
            });
    }

    @Test
    void pnd1ExportProceedsWhenFullyConfigured() {
        PayrollService serviceUnderTest = serviceWithEmployer(fullyConfiguredEmployerProps());
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));
        when(payrollRepository.findExportRows(99L)).thenReturn(List.of(exportRow()));

        PayrollExportFile file =
            serviceUnderTest.export(PayrollExportKind.PND1, 99L, LocalDate.of(2026, 6, 26), hrUser());

        assertThat(file.fileName()).isEqualTo("Pnd1260626.txt");
    }

    @Test
    void ssoExportRefusedWhenBranchBlank() {
        AppProperties props = fullyConfiguredEmployerProps();
        props.getPayroll().getEmployer().setSsoBranch("");
        PayrollService serviceUnderTest = serviceWithEmployer(props);
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));

        assertThatThrownBy(() ->
                serviceUnderTest.export(PayrollExportKind.SSO, 99L, LocalDate.of(2026, 6, 26), hrUser()))
            .isInstanceOfSatisfying(ApiException.class, exception -> {
                assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(exception.getMessage()).contains("APP_PAYROLL_SSO_BRANCH");
            });
    }

    /**
     * The SSO workbook (2026-08-31) renders no employer identity at all -- no account number, no
     * establishment name -- so blanking every one of those fields must NOT block the export. This
     * is the wrong-way-round half of {@link #ssoExportRefusedWhenBranchBlank}: together they pin
     * that the guard requires the branch and nothing else.
     */
    @Test
    void ssoExportNotBlockedByBlankEmployerAccountOrEstablishmentName() {
        AppProperties props = fullyConfiguredEmployerProps();
        props.getPayroll().getEmployer().setSsoEmployerAccount("");
        props.getPayroll().getEmployer().setEstablishmentName("");
        props.getPayroll().getEmployer().setCompanyNameTh("");
        PayrollService serviceUnderTest = serviceWithEmployer(props);
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));
        when(payrollRepository.findExportRows(99L)).thenReturn(List.of(exportRow()));

        PayrollExportFile file =
            serviceUnderTest.export(PayrollExportKind.SSO, 99L, LocalDate.of(2026, 6, 26), hrUser());

        assertThat(file.fileName()).isEqualTo("SPS1-10260626.xlsx");
    }

    /** The สปส.1-10 export is an xlsx workbook, not the CP874 .txt it was until 2026-08-31. */
    @Test
    void ssoExportIsAnXlsxWorkbook() {
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));
        when(payrollRepository.findExportRows(99L)).thenReturn(List.of(exportRow()));

        PayrollExportFile file =
            service.export(PayrollExportKind.SSO, 99L, LocalDate.of(2026, 6, 26), hrUser());

        assertThat(file.fileName()).isEqualTo("SPS1-10260626.xlsx");
        assertThat(PayrollExportKind.SSO.contentType())
            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        // PK\x03\x04 -- a real zip container (xlsx), not CP874 text.
        assertThat(file.content()[0]).isEqualTo((byte) 0x50);
        assertThat(file.content()[1]).isEqualTo((byte) 0x4B);
    }

    @Test
    void payrollDetailExportNotBlockedByACompletelyBlankEmployer() {
        // PAYROLL_DETAIL never reads employer (see PayrollService#export's switch) so the guard
        // must not gate it at all, even when every employer field is at its blank default.
        PayrollService serviceUnderTest = serviceWithEmployer(new AppProperties());
        when(payrollRepository.findPeriodById(99L)).thenReturn(Optional.of(period()));
        when(payrollRepository.findDetailIdentity(java.util.Set.of(42L))).thenReturn(Map.of());

        PayrollExportFile file = serviceUnderTest.export(
            PayrollExportKind.PAYROLL_DETAIL, 99L, LocalDate.of(2026, 6, 26), hrUser());

        assertThat(file.fileName()).isEqualTo("PayrollDetail260626.xlsx");
    }

    /** Builds a second {@link PayrollService} wired to the same test-scoped mocks as the class-level
     * {@code service} field, but with a caller-supplied {@link AppProperties} so a single test can
     * flip one employer field at a time without disturbing the shared fixture every other test in
     * this class relies on. */
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

    /** Every field Fix B's guard requires (across all three kinds), populated -- so a test that
     * blanks exactly one field via its own setter is provably testing that ONE field, not
     * incidentally relying on some other field already being blank. */
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
            money("30000.00"), money("30000.00"), money("500.00"), money("15000.00"), null, money("750.00"), null);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
