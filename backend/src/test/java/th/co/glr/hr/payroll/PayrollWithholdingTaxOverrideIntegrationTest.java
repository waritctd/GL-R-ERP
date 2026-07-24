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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * V88 withholding-tax override, proven through the real {@link PayrollService}/{@link
 * PayrollRepository} + Postgres seam (not the calculator in isolation): the two-layer "Both" model.
 *
 * <p>GUARDRAIL asserted throughout: the override only SUBSTITUTES the final withheld amount. The
 * progressive-tax figures ({@code annualTax}, {@code taxableAnnualIncome}, {@code
 * projectedAnnualIncome}) are still computed and reported UNCHANGED. Baseline for this salary
 * (40,000, January, no YTD, no stored allowance) is pinned elsewhere in the suite: taxableAnnualIncome
 * 309,500.00, annualTax 8,450.00, computed withholdingTax 704.17 (see
 * PayrollAllowanceDirectorNonTaxableIntegrationTest).
 */
class PayrollWithholdingTaxOverrideIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final BigDecimal COMPUTED_WITHHOLDING = new BigDecimal("704.17");

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
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.config.AppProperties());
    }

    /**
     * Layer 1: the standing per-employee override on hr.employee flows through preview and substitutes
     * the withheld amount, while the progressive-tax computation stays intact and is still reported.
     * The per-run typed field on the line stays null (the standing value is not a per-run entry).
     */
    @Test
    void standingEmployeeOverrideSubstitutesWithheldAmountButLeavesComputationIntact() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("WHT-STAND", "มาตรฐาน", "ทดสอบ", new BigDecimal("40000.00"));

        // Baseline: no override -> computed withholding.
        PayrollLineDto computed = previewLineFor(month, employeeId);
        assertThat(computed.withholdingTax()).isEqualByComparingTo(COMPUTED_WITHHOLDING);
        assertThat(computed.withholdingTaxOverride()).isNull();

        // Set the standing override to 500.00 and re-preview.
        setStandingOverride(employeeId, new BigDecimal("500.00"));
        PayrollLineDto overridden = previewLineFor(month, employeeId);

        // Withheld tax is substituted...
        assertThat(overridden.withholdingTax()).isEqualByComparingTo("500.00");
        // ...but the computation is untouched and still reported.
        assertThat(overridden.annualTax()).isEqualByComparingTo(computed.annualTax());
        assertThat(overridden.taxableAnnualIncome()).isEqualByComparingTo(computed.taxableAnnualIncome());
        assertThat(overridden.projectedAnnualIncome()).isEqualByComparingTo(computed.projectedAnnualIncome());
        // The line's per-run typed field is null: the standing value is not a per-run entry, so it must
        // not be persisted onto the line or carried forward.
        assertThat(overridden.withholdingTaxOverride()).isNull();
        // Net pay reflects the smaller withheld amount exactly.
        assertThat(overridden.netPay())
            .isEqualByComparingTo(computed.netPay().add(COMPUTED_WITHHOLDING).subtract(new BigDecimal("500.00")));
    }

    /**
     * Layer 2 precedence: a per-run HR-typed override WINS over the standing employee override. Same
     * employee, standing = 500.00, per-run = 250.00 -> withheld = 250.00, and the per-run typed value
     * is what gets stored on the line (so it can carry forward), not the standing value.
     */
    @Test
    void perRunOverrideWinsOverStandingOverrideAndIsTheValueStoredOnTheLine() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("WHT-PERRUN", "รายรอบ", "ทดสอบ", new BigDecimal("40000.00"));
        setStandingOverride(employeeId, new BigDecimal("500.00"));

        // Process the run with a per-run override of 250.00 so the persisted line can be re-read.
        PayrollPeriodDto processed = payrollService.process(
            new ProcessPayrollRequest(month, List.of(inputWithWithholdingOverride(employeeId, new BigDecimal("250.00")))),
            hr());
        PayrollLineDto line = lineFor(processed, employeeId);

        // Per-run 250.00 beats standing 500.00.
        assertThat(line.withholdingTax()).isEqualByComparingTo("250.00");
        // The per-run TYPED value (250.00) is persisted on the line -- not the standing 500.00.
        assertThat(line.withholdingTaxOverride()).isEqualByComparingTo("250.00");
        // Computation still intact.
        assertThat(line.annualTax()).isEqualByComparingTo("8450.00");

        // Re-read from the DB to prove the per-run value survived the round trip into payroll_line.
        PayrollLineDto reread = lineFor(
            payrollRepository.findPeriodById(processed.id()).orElseThrow(), employeeId);
        assertThat(reread.withholdingTax()).isEqualByComparingTo("250.00");
        assertThat(reread.withholdingTaxOverride()).isEqualByComparingTo("250.00");

        // And the per-run typed override carries forward as a suggestion for the following month, while
        // the standing employee value is NOT carried (it re-applies from the employee record on its own).
        PayrollCarryForwardDtos.SuggestedInputsResponse suggestions =
            payrollService.suggestedInputs(LocalDate.of(2026, 2, 1), hr());
        PayrollCarryForwardDtos.SuggestedInputRow row = suggestions.suggestions().stream()
            .filter(s -> s.employeeId() == employeeId)
            .findFirst()
            .orElseThrow();
        assertThat(row.withholdingTaxOverride()).isEqualByComparingTo("250.00");
    }

    /**
     * Zero is a meaningful per-run override (withhold nothing) and must be honoured, distinct from "no
     * override" -- proving the null-vs-zero distinction survives the JSON/DB round trip.
     */
    @Test
    void perRunOverrideOfZeroForcesZeroWithheldTaxThroughTheService() {
        LocalDate month = LocalDate.of(2026, 1, 1);
        long employeeId = seedEmployee("WHT-ZERO", "ศูนย์", "ทดสอบ", new BigDecimal("40000.00"));

        PayrollPeriodDto preview = payrollService.preview(
            new ProcessPayrollRequest(month, List.of(inputWithWithholdingOverride(employeeId, BigDecimal.ZERO))),
            hr());
        PayrollLineDto line = lineFor(preview, employeeId);

        assertThat(line.withholdingTax()).isEqualByComparingTo("0.00");
        assertThat(line.withholdingTaxOverride()).isEqualByComparingTo("0.00");
        // Projection still computed and still positive -- only the withheld amount is zeroed.
        assertThat(line.annualTax()).isEqualByComparingTo("8450.00");
    }

    // --- helpers ------------------------------------------------------------

    private PayrollLineDto previewLineFor(LocalDate payrollMonth, long employeeId) {
        return lineFor(payrollService.preview(new ProcessPayrollRequest(payrollMonth, List.of()), hr()), employeeId);
    }

    private PayrollLineDto lineFor(PayrollPeriodDto period, long employeeId) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private void setStandingOverride(long employeeId, BigDecimal value) {
        jdbc.update(
            "UPDATE hr.employee SET withholding_tax_override = :value WHERE employee_id = :id",
            Map.of("value", value, "id", employeeId));
    }

    private PayrollEmployeeInputRequest inputWithWithholdingOverride(long employeeId, BigDecimal withholdingTaxOverride) {
        return new PayrollEmployeeInputRequest(
            employeeId,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // specialPay1-4
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, // specialPay5-8
            BigDecimal.ZERO, // nonTaxableIncome
            BigDecimal.ZERO, // unpaidLeaveDays
            BigDecimal.ZERO, // studentLoanDeduction
            BigDecimal.ZERO, // legalExecutionDeduction
            BigDecimal.ZERO, // otherPostTaxDeductions
            BigDecimal.ZERO, // spouseAllowance
            BigDecimal.ZERO, // childAllowance
            BigDecimal.ZERO, // parentCareAllowance
            BigDecimal.ZERO, // disabledCareAllowance
            BigDecimal.ZERO, // maternityAllowance
            BigDecimal.ZERO, // lifeInsuranceAllowance
            BigDecimal.ZERO, // healthInsuranceAllowance
            BigDecimal.ZERO, // parentHealthInsuranceAllowance
            BigDecimal.ZERO, // rmfAllowance
            BigDecimal.ZERO, // ssfAllowance
            BigDecimal.ZERO, // pensionInsuranceAllowance
            BigDecimal.ZERO, // thaiEsgAllowance
            BigDecimal.ZERO, // homeLoanInterestAllowance
            BigDecimal.ZERO, // educationDonation
            BigDecimal.ZERO, // generalDonation
            BigDecimal.ZERO, // politicalDonation
            BigDecimal.ZERO, // warningLetterDeduction
            BigDecimal.ZERO, // customerReturnDeduction
            BigDecimal.ZERO, // otherPretaxDeduction
            withholdingTaxOverride);
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
