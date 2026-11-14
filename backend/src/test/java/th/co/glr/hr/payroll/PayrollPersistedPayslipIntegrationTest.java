package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** proves the payslip PDF is rendered from the actually-persisted hr.payroll_line row, not from a hand-built
 * PayrollLineDto the way PayslipRendererTest exercises the renderer in isolation. */
class PayrollPersistedPayslipIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollService payrollService;
    private PayrollRepository payrollRepository;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        // Leave -> payroll unpaid-day deduction (2026-07-23): mechanical constructor-arity fix -- PayrollService
        // gained a LeaveRepository dependency for #suggestedInputs, unrelated to what this test exercises.
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            new PayslipRenderer(),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));
    }

    /** Salary 40,000, January (monthsRemaining = 12), no overtime/commission/allowances/YTD. */
    @Test
    void payslipPdfRendersThePersistedFiguresNotAHandBuiltLine() throws Exception {
        long employeeId = seedEmployee("PS-001", "สมหญิง", "ทดสอบสลิป", new BigDecimal("40000.00"));
        LocalDate payrollMonth = LocalDate.of(2026, 1, 1);

        PayrollPeriodDto processed = payrollService.process(
            new ProcessPayrollRequest(payrollMonth, List.of()), hr());
        long periodId = processed.id();
        PayrollLineDto processedLine = processed.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow();
        long lineId = processedLine.id();

        // Pinned expectations, independently derived above.
        assertThat(processedLine.grossEarnings()).isEqualByComparingTo("40000.00");
        assertThat(processedLine.socialSecurity()).isEqualByComparingTo("875.00");
        assertThat(processedLine.withholdingTax()).isEqualByComparingTo("704.00");
        assertThat(processedLine.totalDeductions()).isEqualByComparingTo("1579.00");
        assertThat(processedLine.netPay()).isEqualByComparingTo("38421.00");

        // Re-read from the DB via a fresh repository call -- a second, independent path back to the same row -- and
        // use THOSE values to build the PDF assertions, so the test proves the PDF text traces back to whatever is
        // actually stored, not to values computed once and reused everywhere.
        PayrollPeriodDto rereadFromDb = payrollRepository.findPeriodById(periodId).orElseThrow();
        PayrollLineDto storedLine = rereadFromDb.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow();

        byte[] pdf = payrollService.payslipPdf(periodId, lineId, hr());
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }

        // New two-column employer layout (matches slip.xls). Figures are asserted against the DB-reread storedLine,
        // and the two printed totals must foot to net pay by construction: รวมรายได้ (= grossEarnings +
        // nonTaxableIncome) − รวมรายหัก (= totalDeductions) == netPay.
        assertThat(text).contains("สลิปเงินเดือน");
        assertThat(text).contains("31/ม.ค./2569");
        assertThat(text).contains(storedLine.employeeName());
        assertThat(text).contains("รายการได้");
        assertThat(text).contains("รายการหัก");
        assertThat(text).contains("รวมรายได้");
        assertThat(text).contains(
            fmt2(storedLine.grossEarnings().add(storedLine.nonTaxableIncome())));
        assertThat(text).contains("ประกันสังคม");
        assertThat(text).contains(fmt2(storedLine.socialSecurity()));
        assertThat(text).contains("ภาษี");
        assertThat(text).contains(fmt2(storedLine.withholdingTax()));
        assertThat(text).contains("รวมรายหัก");
        assertThat(text).contains(fmt2(storedLine.totalDeductions()));
        assertThat(text).contains("เงินรับสุทธิ");
        assertThat(text).contains(fmt2(storedLine.netPay()));
        assertThat(text).contains("ผู้รับเงิน");
        assertThat(text).doesNotContain("?????");
    }

    private String fmt2(BigDecimal value) {
        return String.format(Locale.US, "%,.2f", value);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        long employeeId = jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
        // Task 2 (2026-07-29): SSO inclusion has no calculator-layer default -- seed it so socialSecurity comes out
        // non-zero, matching this file's pinned 875.00 expectation.
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
        return employeeId;
    }
}
