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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * P5: proves the payslip PDF is rendered from the actually-persisted {@code hr.payroll_line} row,
 * not from a hand-built {@link PayrollLineDto} the way {@link PayslipRendererTest} exercises the
 * renderer in isolation. This drives the full chain against real Postgres: {@link
 * PayrollService#process} writes a period + line, then {@link PayrollService#payslipPdf} reads that
 * same row back and renders it -- the seam under test is the round trip through the database, which
 * Mockito-based unit tests never touch.
 *
 * <p>Uses the REAL {@link PayslipRenderer} (unlike most other seam tests in this batch, which mock
 * it) because the PDF content itself is the thing being verified.
 */
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
            mock(AttachmentRepository.class));
        // Leave -> payroll unpaid-day deduction (2026-07-23): mechanical constructor-arity fix --
        // PayrollService gained a LeaveRepository dependency for #suggestedInputs, unrelated to what
        // this test exercises.
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
            new th.co.glr.hr.config.AppProperties());
    }

    /**
     * Salary 40,000, January (monthsRemaining = 12), no overtime/commission/allowances/YTD.
     * Hand-derived expected figures (traced against {@link PayrollCalculator#calculate}):
     * grossEarnings 40,000.00; ssoWageBase caps at 17,500.00 so socialSecurity = 875.00;
     * projectedAnnualIncome = 480,000.00; taxExpenseDeduction caps at 100,000.00; the SSO
     * allowance component is exactly 875 x 12 = 10,500.00 (also the annual cap), so taxAllowanceTotal
     * = 60,000 (personal) + 10,500 = 70,500.00; taxableAnnualIncome = 480,000 - 100,000 - 70,500 =
     * 309,500.00; annualTax = 150,000@5% + 9,500@10% = 7,500 + 950 = 8,450.00; withholdingTax =
     * 8,450 / 12 = 704.1666... -> HALF_UP 704.17; totalDeductions = 875.00 + 704.17 = 1,579.17;
     * netPay = 40,000.00 - 1,579.17 = 38,420.83.
     */
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
        assertThat(processedLine.withholdingTax()).isEqualByComparingTo("704.17");
        assertThat(processedLine.totalDeductions()).isEqualByComparingTo("1579.17");
        assertThat(processedLine.netPay()).isEqualByComparingTo("38420.83");

        // Re-read from the DB via a fresh repository call -- a second, independent path back to the
        // same row -- and use THOSE values (not the pinned constants above) to build the PDF
        // assertions, so the test proves the PDF text traces back to whatever is actually stored,
        // not to values computed once and reused everywhere.
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

        assertThat(text).contains("สลิปเงินเดือน มกราคม 2569");
        assertThat(text).contains(storedLine.employeeName());
        assertThat(text).contains("รายได้รวม: " + fmt2(storedLine.grossEarnings()) + " บาท");
        assertThat(text).contains("ประกันสังคม: " + fmt2(storedLine.socialSecurity()) + " บาท");
        assertThat(text)
            .contains("ภาษีหัก ณ ที่จ่ายงวดนี้: " + fmt2(storedLine.withholdingTax()) + " บาท");
        assertThat(text).contains("เงินหักรวม: " + fmt2(storedLine.totalDeductions()) + " บาท");
        assertThat(text).contains("เงินโอนสุทธิ: " + fmt2(storedLine.netPay()) + " บาท");
        assertThat(text).doesNotContain("?????");
    }

    private String fmt2(BigDecimal value) {
        return String.format(Locale.US, "%,.2f", value);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, BigDecimal salary) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
    }
}
