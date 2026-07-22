package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollReconciliationDtos.YtdSeedUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * P6 + P7: drives {@link PayrollRepository#findYearToDateByEmployee} and the SSO annual-cap logic
 * through the real {@link PayrollService#preview}, against real Postgres -- not by constructing a
 * {@link PayrollYearToDate} object directly and handing it to {@link PayrollCalculator}, which is
 * all the existing unit tests (e.g. {@code PayrollCalculatorTest#ssoStopsContributingOnceYearToDateCapIsNearlyReached})
 * cover. The SQL merge of {@code payroll_year_to_date_seed} with prior {@code payroll_line} rows
 * (a UNION ALL + GROUP BY, equivalent to a FULL OUTER JOIN) is the seam under test.
 */
class PayrollYtdAndSsoIntegrationTest extends AbstractPostgresIntegrationTest {
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

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
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class));
    }

    // ---- P6: YTD merge drives withholding, and an empty YTD under-withholds -------------------

    /**
     * Salary 100,000/month, previewed for August (monthsRemaining = 5). One employee has real YTD
     * history: a hand-crafted prior processed line for June (grossTaxableIncome 100,000.00,
     * socialSecurity 875.00, withholdingTax 2,212.50) PLUS a {@code payroll_year_to_date_seed} row
     * (pre-system history: taxableIncome 200,000.00, socialSecurity 1,750.00, withholdingTax
     * 4,000.00) -- {@code findYearToDateByEmployee} must sum both into taxableIncome 300,000.00,
     * socialSecurity 2,625.00, withholdingTax 6,212.50.
     *
     * <p>Trace against {@link PayrollCalculator#calculate}: grossTaxableIncome 100,000.00;
     * ssoWageBase caps at 17,500 so socialSecurity = 875.00 (remaining annual SSO cap is
     * 10,500 - 2,625 = 7,875, so the cap doesn't bind here); projectedAnnualIncome =
     * 300,000 + 100,000 x 5 = 800,000.00; taxExpenseDeduction caps at 100,000.00; the SSO-allowance
     * component is min(10,500, 2,625 + 875 x 5) = min(10,500, 7,000) = 7,000.00, so taxAllowanceTotal
     * = 60,000 + 7,000 = 67,000.00; taxableAnnualIncome = 800,000 - 100,000 - 67,000 = 633,000.00;
     * annualTax = 150,000@5% + 200,000@10% + 133,000@15% = 7,500 + 20,000 + 19,950 = 47,450.00;
     * remainingAnnualTax = 47,450 - 6,212.50 = 41,237.50; withholdingTax = 41,237.50 / 5 = 8,247.50
     * exactly.
     *
     * <p>A second employee, identical salary and month but with NO YTD data at all, previews
     * withholdingTax = 2,212.50 (projectedAnnualIncome 500,000; taxExpenseDeduction 100,000;
     * SSO allowance 4,375; taxableAnnualIncome 335,625; annualTax 11,062.50; /5 = 2,212.50) -- less
     * than a third of the correctly-YTD-informed figure, demonstrating exactly the under-withholding
     * C2 exists to prevent: without the backfill, an employee who already earned significantly this
     * year is projected as if they had not, and pays far too little tax with each remaining payslip.
     */
    @Test
    void yearToDateMergeDrivesWithholdingAndAnEmptyYearToDateUnderWithholds() {
        LocalDate august = LocalDate.of(2026, 8, 1);
        long withHistory = seedEmployee("YTD-001", "มีประวัติ", "ทดสอบ", new BigDecimal("100000.00"));
        long withoutHistory = seedEmployee("YTD-002", "ไม่มีประวัติ", "ทดสอบ", new BigDecimal("100000.00"));

        payrollRepository.saveProcessedPeriod(LocalDate.of(2026, 6, 1), withHistory, List.of(
            priorLine(withHistory, "YTD-001", "มีประวัติ ทดสอบ",
                new BigDecimal("100000.00"), new BigDecimal("875.00"), new BigDecimal("2212.50"))));
        payrollRepository.upsertYtdSeed(2026, List.of(
            new YtdSeedUpsertRequest(withHistory,
                new BigDecimal("200000.00"), new BigDecimal("1750.00"), new BigDecimal("4000.00"),
                "pre-system history Jan-Feb")
        ), withHistory);

        Map<Long, PayrollYearToDate> ytd = payrollRepository.findYearToDateByEmployee(august);
        assertThat(ytd.get(withHistory).taxableIncome()).isEqualByComparingTo("300000.00");
        assertThat(ytd.get(withHistory).socialSecurity()).isEqualByComparingTo("2625.00");
        assertThat(ytd.get(withHistory).withholdingTax()).isEqualByComparingTo("6212.50");

        PayrollLineDto withHistoryLine = previewLineFor(august, withHistory);
        PayrollLineDto withoutHistoryLine = previewLineFor(august, withoutHistory);

        assertThat(withHistoryLine.socialSecurity()).isEqualByComparingTo("875.00");
        assertThat(withHistoryLine.withholdingTax()).isEqualByComparingTo("8247.50");

        assertThat(withoutHistoryLine.withholdingTax()).isEqualByComparingTo("2212.50");
        assertThat(withHistoryLine.withholdingTax())
            .isGreaterThan(withoutHistoryLine.withholdingTax().multiply(new BigDecimal("3")));
    }

    // ---- P7: SSO through preview, including the annual-cap cutoff -------------------------------

    /** Baseline: salary 40,000 (above the SSO ceiling), no YTD -- monthly SSO is 875.00. */
    @Test
    void monthlySocialSecurityThroughPreviewIsCappedAtTheMonthlyMaximum() {
        long employeeId = seedEmployee("SSO-001", "เอสเอสโอ", "หนึ่ง", new BigDecimal("40000.00"));
        LocalDate month = LocalDate.of(2026, 1, 1);

        PayrollLineDto line = previewLineFor(month, employeeId);

        assertThat(line.socialSecurity()).isEqualByComparingTo("875.00");
    }

    /**
     * 10,200 of the 10,500 annual SSO cap already withheld this year (via a YTD seed row): only
     * 300.00 remains, so this month's SSO is capped at 300.00 even though the wage base alone would
     * produce 875.00.
     */
    @Test
    void monthlySocialSecurityThroughPreviewIsLimitedByRemainingAnnualCap() {
        long employeeId = seedEmployee("SSO-002", "เอสเอสโอ", "สอง", new BigDecimal("40000.00"));
        LocalDate month = LocalDate.of(2026, 1, 1);
        payrollRepository.upsertYtdSeed(2026, List.of(
            new YtdSeedUpsertRequest(employeeId,
                BigDecimal.ZERO, new BigDecimal("10200.00"), BigDecimal.ZERO, "near the annual cap")
        ), employeeId);

        PayrollLineDto line = previewLineFor(month, employeeId);

        assertThat(line.socialSecurity()).isEqualByComparingTo("300.00");
    }

    /** The annual cap is already fully reached: this month contributes zero SSO, not a negative. */
    @Test
    void monthlySocialSecurityThroughPreviewIsZeroOnceTheAnnualCapIsFullyReached() {
        long employeeId = seedEmployee("SSO-003", "เอสเอสโอ", "สาม", new BigDecimal("40000.00"));
        LocalDate month = LocalDate.of(2026, 1, 1);
        payrollRepository.upsertYtdSeed(2026, List.of(
            new YtdSeedUpsertRequest(employeeId,
                BigDecimal.ZERO, new BigDecimal("10500.00"), BigDecimal.ZERO, "cap already reached")
        ), employeeId);

        PayrollLineDto line = previewLineFor(month, employeeId);

        assertThat(line.socialSecurity()).isEqualByComparingTo("0.00");
    }

    // --- helpers ------------------------------------------------------------

    private PayrollLineDto previewLineFor(LocalDate payrollMonth, long employeeId) {
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(payrollMonth, List.of()), hr());
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
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

    /**
     * A hand-crafted "already processed" payroll_line for an earlier month, used only to feed
     * {@code findYearToDateByEmployee}'s payroll_line half of the UNION ALL. Only
     * grossTaxableIncome/socialSecurity/withholdingTax matter for that query; the rest are
     * placeholders, following the same pattern as {@code PayrollRepositoryIntegrationTest#line}.
     */
    private PayrollLineDto priorLine(long employeeId, String code, String name,
            BigDecimal grossTaxableIncome, BigDecimal socialSecurity, BigDecimal withholdingTax) {
        return new PayrollLineDto(
            null, employeeId, code, name,
            null, null, null,
            grossTaxableIncome,             // baseSalary (placeholder)
            new BigDecimal("1000.0000"),    // dailyRate
            new BigDecimal("125.0000"),     // hourlyRate
            specialPays(),
            BigDecimal.ZERO,                // specialPayTotal
            BigDecimal.ZERO,                // overtimePay
            BigDecimal.ZERO,                // commissionPay
            grossTaxableIncome,             // grossEarnings
            BigDecimal.ZERO,                // nonTaxableIncome
            BigDecimal.ZERO,                // unpaidLeaveDays
            BigDecimal.ZERO,                // unpaidLeaveDeduction
            grossTaxableIncome,             // grossTaxableIncome
            grossTaxableIncome,             // ssoWageBase (placeholder)
            socialSecurity,
            BigDecimal.ZERO,                // projectedAnnualIncome
            BigDecimal.ZERO,                // taxExpenseDeduction
            BigDecimal.ZERO,                // taxAllowanceTotal
            BigDecimal.ZERO,                // taxableAnnualIncome
            BigDecimal.ZERO,                // annualTax
            withholdingTax,
            BigDecimal.ZERO,                // studentLoanDeduction
            BigDecimal.ZERO,                // legalExecutionDeduction
            BigDecimal.ZERO,                // otherPostTaxDeductions
            socialSecurity.add(withholdingTax), // totalDeductions
            grossTaxableIncome.subtract(socialSecurity).subtract(withholdingTax), // netPay
            "ytd-seed-helper " + code,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private List<PayrollSpecialPayDto> specialPays() {
        return List.of(
            new PayrollSpecialPayDto("specialPay1", "พิเศษ 1", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay2", "พิเศษ 2", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay3", "พิเศษ 3", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay4", "พิเศษ 4", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay5", "พิเศษ 5", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay6", "พิเศษ 6", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay7", "พิเศษ 7", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8", BigDecimal.ZERO));
    }
}
