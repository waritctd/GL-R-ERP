package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Defect fix (Opus review, 2026-07-29): {@code meal_allowance}, {@code per_diem_exempt}, {@code
 * per_diem_taxable} and {@code per_diem_basis} (V97) were read from the request, folded into
 * tax/SSO arithmetic, and then discarded -- persisted nowhere, read back from nowhere. Entering
 * ค่าอาหาร ฿1,680 with per-diem ฿700 exempt / ฿300 taxable used to store all four as zero/null while
 * still correctly taxing the money once (verified consequence in the review: {@code gross_amount}
 * and {@code non_taxable_income} were right, the four dedicated columns were not).
 *
 * <p>Driven through the real {@link PayrollService#process}/{@link PayrollRepository}, per
 * CLAUDE.md's requirement that business-logic changes get real-DB coverage.
 */
class PayrollMealAndPerDiemIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final int TAX_YEAR = 2026;

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
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));
    }

    @Test
    void mealAllowanceAndPerDiemRoundTripThroughAProcessedLine() {
        long employeeId = seedEmployee("MEAL-001", "อาหาร", "ทดสอบ", new BigDecimal("30000.00"));
        seedRegularTaxTreatment(employeeId, TAX_YEAR,
            PayrollComponent.MEAL_ALLOWANCE, PayrollComponent.PER_DIEM_TAXABLE);
        seedSsoIncluded(employeeId, TAX_YEAR, PayrollComponent.SALARY);

        PayrollEmployeeInputRequest input = mealAndPerDiemInput(
            employeeId, new BigDecimal("1680.00"),
            new BigDecimal("700.00"), new BigDecimal("300.00"), PerDiemBasis.FLAT_RATE_S42_2);

        PayrollPeriodDto processed = payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of(input)), hr());
        PayrollLineDto line = onlyLine(processed, employeeId);

        // Every one of the four previously write-only fields round-trips exactly.
        assertThat(line.mealAllowance()).isEqualByComparingTo("1680.00");
        assertThat(line.perDiemExempt()).isEqualByComparingTo("700.00");
        assertThat(line.perDiemTaxable()).isEqualByComparingTo("300.00");
        assertThat(line.perDiemBasis()).isEqualTo("FLAT_RATE_S42_2");

        // Both taxable additions land in gross; the exempt per-diem joins non-taxable income.
        // gross = 30,000 salary + 1,680 meal + 300 per-diem-taxable = 31,980.00
        assertThat(line.grossEarnings()).isEqualByComparingTo("31980.00");
        assertThat(line.nonTaxableIncome()).isEqualByComparingTo("700.00");

        // Re-reading straight from the DB (not the service's in-memory return value) confirms the
        // same thing survives the actual INSERT + SELECT round trip, not just the in-memory object.
        PayrollLineDto reread = onlyLine(payrollRepository.findPeriodById(processed.id()).orElseThrow(), employeeId);
        assertThat(reread.mealAllowance()).isEqualByComparingTo("1680.00");
        assertThat(reread.perDiemExempt()).isEqualByComparingTo("700.00");
        assertThat(reread.perDiemTaxable()).isEqualByComparingTo("300.00");
        assertThat(reread.perDiemBasis()).isEqualTo("FLAT_RATE_S42_2");
    }

    @Test
    void aReprocessedMonthStillCarriesMealAndPerDiemOnTheReplacedLine() {
        long employeeId = seedEmployee("MEAL-002", "รีโปรเซส", "ทดสอบ", new BigDecimal("30000.00"));
        seedRegularTaxTreatment(employeeId, TAX_YEAR,
            PayrollComponent.MEAL_ALLOWANCE, PayrollComponent.PER_DIEM_TAXABLE);
        seedSsoIncluded(employeeId, TAX_YEAR, PayrollComponent.SALARY);
        LocalDate month = LocalDate.of(2026, 2, 1);

        // First run: no meal/per-diem at all.
        payrollService.process(new ProcessPayrollRequest(month, List.of()), hr());

        // Second run (the DELETE+INSERT reprocess path, PayrollRepository#saveProcessedPeriod): now
        // with meal allowance and per-diem. Proves the UPDATE/reprocess path also persists the four
        // fields, not only a brand-new period's first insert.
        PayrollEmployeeInputRequest input = mealAndPerDiemInput(
            employeeId, new BigDecimal("1000.00"),
            BigDecimal.ZERO, new BigDecimal("500.00"), PerDiemBasis.REIMBURSED_S42_1);
        PayrollPeriodDto secondRun = payrollService.process(
            new ProcessPayrollRequest(month, List.of(input)), hr());

        PayrollLineDto reread = onlyLine(payrollRepository.findPeriodById(secondRun.id()).orElseThrow(), employeeId);
        assertThat(reread.mealAllowance()).isEqualByComparingTo("1000.00");
        assertThat(reread.perDiemTaxable()).isEqualByComparingTo("500.00");
        assertThat(reread.perDiemBasis()).isEqualTo("REIMBURSED_S42_1");
    }

    /**
     * {@code chk_payroll_line_per_diem_basis_present} (V97) must actually be reachable through the
     * real insert path, not just declared in the migration: an amount with a null basis is rejected.
     */
    @Test
    void insertingAPerDiemAmountWithNoBasisIsRejectedByTheDatabase() {
        long employeeId = seedEmployee("MEAL-003", "ไม่มีเบซิส", "ทดสอบ", new BigDecimal("30000.00"));

        PayrollLineDto lineWithNoBasis = malformedLineMissingPerDiemBasis(employeeId, "MEAL-003", "ไม่มีเบซิส ทดสอบ");

        assertThatThrownBy(() -> payrollRepository.saveProcessedPeriod(
            LocalDate.of(2026, 3, 1), employeeId, List.of(lineWithNoBasis)))
            .as("chk_payroll_line_per_diem_basis_present must reject a non-zero per-diem amount with no basis")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * F2 fix (Opus review, 2026-07-30): before this fix, the frontend had no basis selector at all
     * (so HR could never supply one) and {@link PayrollService#calculateLine} passed
     * {@code input.perDiemBasis()} straight through to the INSERT unvalidated -- Preview always
     * succeeded (it never writes a row) and Process 500'd on {@code
     * chk_payroll_line_per_diem_basis_present} the moment HR typed a per-diem amount. This drives
     * the REAL {@link PayrollService#process} (not the repository directly, unlike the DB-level test
     * above) and asserts the failure is now a clean {@link ApiException} 400 naming the employee and
     * the missing basis, thrown BEFORE any INSERT is attempted -- never a bare
     * {@link DataIntegrityViolationException} surfacing as a 500.
     */
    @Test
    void processRejectsAPerDiemAmountWithNoBasisAsACleanFourHundredNotAFiveHundred() {
        long employeeId = seedEmployee("MEAL-004", "ไม่ระบุฐาน", "ทดสอบ", new BigDecimal("30000.00"));

        PayrollEmployeeInputRequest input = mealAndPerDiemInput(
            employeeId, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300.00"), null);

        assertThatThrownBy(() -> payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 4, 1), List.of(input)), hr()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("MEAL-004")
            .hasMessageContaining("มาตรา 42");

        // Nothing was inserted -- the run was rejected up front, not partially committed.
        assertThat(payrollRepository.findPeriodByMonth(LocalDate.of(2026, 4, 1))).isEmpty();
    }

    /** The exempt-only amount must trip the same validation as the taxable-only case above. */
    @Test
    void processRejectsAnExemptOnlyPerDiemAmountWithNoBasisToo() {
        long employeeId = seedEmployee("MEAL-005", "ยกเว้นไม่ระบุฐาน", "ทดสอบ", new BigDecimal("30000.00"));

        PayrollEmployeeInputRequest input = mealAndPerDiemInput(
            employeeId, BigDecimal.ZERO, new BigDecimal("700.00"), BigDecimal.ZERO, null);

        assertThatThrownBy(() -> payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 5, 1), List.of(input)), hr()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("MEAL-005");
    }

    /**
     * Preview shares {@link PayrollService#calculateLine} with Process, so it now catches the same
     * missing-basis defect up front too -- fail fast rather than letting HR reach Process only to be
     * rejected there.
     */
    @Test
    void previewAlsoRejectsAPerDiemAmountWithNoBasisBeforeAnyDatabaseWriteIsEvenAttempted() {
        long employeeId = seedEmployee("MEAL-006", "พรีวิวไม่ระบุฐาน", "ทดสอบ", new BigDecimal("30000.00"));

        PayrollEmployeeInputRequest input = mealAndPerDiemInput(
            employeeId, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300.00"), null);

        assertThatThrownBy(() -> payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of(input)), hr()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** The happy path (amount + a chosen basis) must keep working through Process, unaffected. */
    @Test
    void processStillSucceedsWhenAPerDiemAmountHasAChosenBasis() {
        long employeeId = seedEmployee("MEAL-007", "ระบุฐานแล้ว", "ทดสอบ", new BigDecimal("30000.00"));
        seedRegularTaxTreatment(employeeId, TAX_YEAR, PayrollComponent.PER_DIEM_TAXABLE);
        seedSsoIncluded(employeeId, TAX_YEAR, PayrollComponent.SALARY);

        PayrollEmployeeInputRequest input = mealAndPerDiemInput(
            employeeId, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300.00"), PerDiemBasis.FLAT_RATE_S42_2);

        PayrollPeriodDto processed = payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 7, 1), List.of(input)), hr());

        assertThat(onlyLine(processed, employeeId).perDiemBasis()).isEqualTo("FLAT_RATE_S42_2");
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
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, :first, :last, :salary, TRUE)
            RETURNING employee_id
            """,
            java.util.Map.of("code", code, "first", firstNameTh, "last", lastNameTh, "salary", salary),
            Long.class);
    }

    private PayrollEmployeeInputRequest mealAndPerDiemInput(
        long employeeId, BigDecimal mealAllowance, BigDecimal perDiemExempt, BigDecimal perDiemTaxable, PerDiemBasis basis
    ) {
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
            null, // withholdingTaxOverride
            mealAllowance,
            perDiemExempt,
            perDiemTaxable,
            basis,
            zero, // bonusPay
            zero, // otherOneOffPay
            false, // customerReturnAlreadyEarned
            null, // garnishmentType
            null // parentCareCount
        );
    }

    /** A hand-crafted line with a non-zero per-diem-taxable amount but a null basis -- must be
     *  rejected by chk_payroll_line_per_diem_basis_present, not silently accepted. Every field is
     *  listed individually against {@link PayrollLineDto}'s canonical (53-arg) field order, since a
     *  positional record constructor gives no other way to check the mapping is right. */
    private PayrollLineDto malformedLineMissingPerDiemBasis(long employeeId, String code, String name) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollLineDto(
            null,                          // id
            employeeId,                    // employeeId
            code,                          // employeeCode
            name,                          // employeeName
            null,                          // departmentName
            null,                          // bankName
            null,                          // bankAccount
            new BigDecimal("30000.00"),    // baseSalary
            new BigDecimal("1000.0000"),   // dailyRate
            new BigDecimal("125.0000"),    // hourlyRate
            specialPays(),                 // specialPays
            zero,                          // specialPayTotal
            zero,                          // overtimePay
            zero,                          // commissionPay
            new BigDecimal("30300.00"),    // grossEarnings
            zero,                          // nonTaxableIncome
            zero,                          // unpaidLeaveDays
            zero,                          // unpaidLeaveDeduction
            new BigDecimal("30300.00"),    // grossTaxableIncome
            new BigDecimal("17500.00"),    // ssoWageBase
            new BigDecimal("875.00"),      // socialSecurity
            zero,                          // projectedAnnualIncome
            zero,                          // taxExpenseDeduction
            zero,                          // taxAllowanceTotal
            zero,                          // taxableAnnualIncome
            zero,                          // annualTax
            zero,                          // withholdingTax
            zero,                          // studentLoanDeduction
            zero,                          // legalExecutionDeduction
            zero,                          // otherPostTaxDeductions
            new BigDecimal("875.00"),      // totalDeductions
            new BigDecimal("29425.00"),    // netPay
            "malformed per-diem fixture",  // calculationNote
            zero,                          // directorRemuneration
            zero,                          // warningLetterDeduction
            zero,                          // customerReturnDeduction
            zero,                          // otherPretaxDeduction
            zero,                          // leaveRefundDays
            zero,                          // leaveDeductionRefund
            null,                          // withholdingTaxOverride
            zero,                          // bonusPay
            zero,                          // otherOneOffPay
            zero,                          // taxableIncomeRegularLimb
            zero,                          // taxableIncomeKnownLimb
            zero,                          // taxableIncomeCumulativeLimb
            zero,                          // withholdingTaxRegularLimb
            zero,                          // withholdingTaxCumulativeLimb
            false,                         // customerReturnAlreadyEarned
            "SALARY",                      // garnishmentType
            zero,                          // mealAllowance
            zero,                          // perDiemExempt
            new BigDecimal("300.00"),      // perDiemTaxable -- non-zero
            null                           // perDiemBasis -- MISSING: must be rejected
        );
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
            new PayrollSpecialPayDto("specialPay8", "พิเศษ 8", BigDecimal.ZERO),
            new PayrollSpecialPayDto("specialPay9", "พิเศษ 9", BigDecimal.ZERO));
    }
}
