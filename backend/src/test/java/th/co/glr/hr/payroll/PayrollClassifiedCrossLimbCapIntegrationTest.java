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
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** REAL-DB coverage (fix, 2026-09-26) for the cross-limb withholding ceiling added to
 * PayrollCalculator#calculateClassified, driven through the real PayrollService#process and PayrollRepository
 * per CLAUDE.md's requirement for business-logic-changing fixes. */
class PayrollClassifiedCrossLimbCapIntegrationTest extends AbstractPostgresIntegrationTest {
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
    void aCommissionLimbIsNotDoubleChargedAfterTheRegularLimbAlreadyOverWithheldForTheYear() {
        long employeeId = seedEmployee("XLIMB-001", "ครอสลิมบ์", "ทดสอบ", new BigDecimal("30000.00"));
        payrollRepository.upsertComponentTaxTreatment(TAX_YEAR, List.of(
            new ComponentTaxTreatmentUpsertRequest(
                employeeId, PayrollComponent.SPECIAL_PAY_7, PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL)
        ), employeeId);

        // January: an HR withholding override of ฿150,000 -- far more than this employee's entire year's tax could
        // ever be (30,000/mo salary alone annualises to a liability of a few thousand baht; even with a later
        // commission added, nowhere near six figures).
        PayrollEmployeeInputRequest januaryInput = input(employeeId, BigDecimal.ZERO, new BigDecimal("150000.00"));
        PayrollPeriodDto januaryPeriod = payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 1, 1), List.of(januaryInput)), hr());
        PayrollLineDto januaryLine = onlyLine(januaryPeriod, employeeId);
        assertThat(januaryLine.withholdingTax()).isEqualByComparingTo("150000.00");
        assertThat(januaryLine.withholdingTaxRegularLimb()).isEqualByComparingTo("150000.00");

        // February: a ฿200,000 commission-like EXTRA_CUMULATIVE_ACTUAL payment.
        PayrollEmployeeInputRequest februaryInput = input(employeeId, new BigDecimal("200000.00"), null);
        PayrollPeriodDto februaryPeriod = payrollService.process(
            new ProcessPayrollRequest(LocalDate.of(2026, 2, 1), List.of(februaryInput)), hr());
        PayrollLineDto februaryLine = onlyLine(februaryPeriod, employeeId);

        // The fix: February's total withholding must be capped at zero -- annualTax (the full-year liability given the
        // commission) is far below the ฿150,000 already withheld in January, so collectableThisYear floors at zero and
        // no more may be withheld in ANY limb this period.
        assertThat(februaryLine.annualTax())
            .withFailMessage("test setup sanity: the full-year liability must genuinely be less than "
                + "January's ฿150,000 override for this to be a real over-withholding scenario, was %s",
                februaryLine.annualTax())
            .isLessThan(new BigDecimal("150000.00"));
        assertThat(februaryLine.withholdingTax())
            .withFailMessage("commission limb must not be double-charged after the regular limb already "
                + "over-withheld the whole year's liability -- expected 0.00, got %s (annualTax=%s)",
                februaryLine.withholdingTax(), februaryLine.annualTax())
            .isEqualByComparingTo("0.00");
        assertThat(februaryLine.withholdingTaxRegularLimb().add(februaryLine.withholdingTaxCumulativeLimb()))
            .withFailMessage("persisted limb columns must sum to the capped total")
            .isEqualByComparingTo(februaryLine.withholdingTax());

        // Re-read straight from the DB (not the service's in-memory return value) -- confirms the fix actually reaches
        // the INSERT, not just the in-memory calculation.
        PayrollLineDto reread = onlyLine(payrollRepository.findPeriodById(februaryPeriod.id()).orElseThrow(), employeeId);
        assertThat(reread.withholdingTax()).isEqualByComparingTo("0.00");
        assertThat(reread.withholdingTaxRegularLimb().add(reread.withholdingTaxCumulativeLimb()))
            .isEqualByComparingTo("0.00");
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
        seedSsoIncluded(employeeId, TAX_YEAR, PayrollComponent.SALARY);
        return employeeId;
    }

    /** Salary + an optional SPECIAL_PAY_7 (commission stand-in) + an optional withholding override, every other
     * component zeroed/nulled. */
    private PayrollEmployeeInputRequest input(long employeeId, BigDecimal specialPay7, BigDecimal withholdingTaxOverride) {
        BigDecimal zero = BigDecimal.ZERO;
        return new PayrollEmployeeInputRequest(
            employeeId,
            zero, zero, zero, zero, zero, zero, specialPay7, zero, zero, // specialPay1-9 (7th = commission stand-in)
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
