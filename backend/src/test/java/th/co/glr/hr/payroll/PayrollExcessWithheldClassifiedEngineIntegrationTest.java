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
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * F3 fix (Opus review, 2026-07-30): {@link PayrollService#calculateLine} used to hardcode {@code
 * excessWithheldToDate} to {@link BigDecimal#ZERO} on every line processed through {@link
 * PayrollCalculator#calculateClassified} -- the ONLY engine {@code calculateLine} actually calls (see
 * that method's own comment) -- making {@link PayslipRenderer}'s over-withholding notice (line 158)
 * permanently unreachable and losing the figure spec section 8 requires be kept "for the ภ.ง.ด.1
 * working papers and reconciliation". {@link PayrollCalculator} now computes it as the classified
 * engine's own analogue of the legacy {@code #calculate()}'s field of the same name: the running total
 * ACTUALLY withheld this tax year (prior periods' persisted total, plus this period's own
 * POST-OVERRIDE withholding) exceeding the TRUE annual liability given everything known so far this
 * year (F1's carried-known-limb {@code annualTaxWithCumulativeYtd}, not the narrower reported
 * {@code taxableAnnualIncome}/{@code annualTax} fields, which deliberately exclude a settled
 * known-limb payment from later months).
 *
 * <p>Driven through the real {@link PayrollService#process}/{@link PayrollRepository}, per CLAUDE.md's
 * requirement that business-logic changes get real-DB coverage.
 */
class PayrollExcessWithheldClassifiedEngineIntegrationTest extends AbstractPostgresIntegrationTest {
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
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
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

    @Test
    void normalProcessingWithNoOverrideLeavesExcessWithheldToDateAtZero() {
        // ฿10,000/mo -- annual income (120,000) never clears the ฿150,000 exempt bracket even before
        // the personal/SSO allowances are subtracted, so the true annual liability is genuinely zero
        // and nothing should ever read as "excess".
        long employeeId = seedEmployee("EXCESS-001", "ปกติ", "ทดสอบ", new BigDecimal("10000.00"));

        PayrollPeriodDto processed = payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of(input(employeeId, null))), hr());

        assertThat(onlyLine(processed, employeeId).excessWithheldToDate()).isEqualByComparingTo("0.00");
    }

    @Test
    void anHrOverrideThatExceedsTheTrueAnnualLiabilityProducesAPositiveExcessWithheldToDate() {
        long employeeId = seedEmployee("EXCESS-002", "หักเกิน", "ทดสอบ", new BigDecimal("10000.00"));

        // The true annual liability for this employee is zero (see the test above). An HR override of
        // ฿5,000 this period is therefore ENTIRELY in excess of what could ever be owed.
        PayrollPeriodDto processed = payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of(input(employeeId, new BigDecimal("5000.00")))),
            hr());
        PayrollLineDto line = onlyLine(processed, employeeId);

        assertThat(line.withholdingTax()).isEqualByComparingTo("5000.00");
        assertThat(line.excessWithheldToDate())
            .withFailMessage("annual liability is 0.00 and 5,000.00 was withheld by override -- the whole amount is excess")
            .isEqualByComparingTo("5000.00");

        // Re-read straight from the DB (not the service's in-memory return value) -- confirms the F3
        // fix actually reaches the INSERT, not just the in-memory calculation.
        PayrollLineDto reread = onlyLine(payrollRepository.findPeriodById(processed.id()).orElseThrow(), employeeId);
        assertThat(reread.excessWithheldToDate()).isEqualByComparingTo("5000.00");
    }

    @Test
    void theExcessCarriesForwardAndCompoundsAcrossPeriodsWhenTheOverrideRepeats() {
        long employeeId = seedEmployee("EXCESS-003", "สะสม", "ทดสอบ", new BigDecimal("10000.00"));

        payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of(input(employeeId, new BigDecimal("2000.00")))),
            hr());
        PayrollPeriodDto february = payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 2, 1), List.of(input(employeeId, new BigDecimal("2000.00")))),
            hr());

        // January's ฿2,000 (all excess, true liability still 0.00) plus February's own ฿2,000.
        assertThat(onlyLine(february, employeeId).excessWithheldToDate()).isEqualByComparingTo("4000.00");
    }

    // --- helpers ------------------------------------------------------------

    private PayrollLineDto onlyLine(PayrollPeriodDto period, long employeeId) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
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
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
        return employeeId;
    }

    /** Salary-only request (every non-salary component stays zero, so nothing needs classifying),
     *  with an optional per-run withholding override. */
    private PayrollEmployeeInputRequest input(long employeeId, BigDecimal withholdingTaxOverride) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            zero, zero, zero, zero, zero, zero, zero, zero, zero, // specialPay1-9
            zero, // nonTaxableIncome
            zero, // unpaidLeaveDays
            zero, // studentLoanDeduction
            zero, // legalExecutionDeduction
            zero, // otherPostTaxDeductions
            zero, zero, zero, zero, zero, // spouse..maternity
            zero, zero, zero, zero, zero, // life..ssf
            zero, zero, zero, zero, zero, zero, // pension..political
            zero, // warningLetterDeduction
            zero, // customerReturnDeduction
            zero, // otherPretaxDeduction
            withholdingTaxOverride,
            zero, // mealAllowance (V97)
            zero, // perDiemExempt (V97)
            zero, // perDiemTaxable (V97)
            null, // perDiemBasis -- none paid, so no basis to record
            zero, // bonusPay
            zero, // otherOneOffPay
            false, // customerReturnAlreadyEarned
            null, // garnishmentType
            null // parentCareCount
        );
    }
}
