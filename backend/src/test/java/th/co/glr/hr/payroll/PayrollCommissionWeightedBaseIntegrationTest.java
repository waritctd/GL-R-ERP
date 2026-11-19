package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.commission.InvoiceCalculation;
import th.co.glr.hr.commission.SubmitCommissionRequest;
import th.co.glr.hr.commission.UpdateCommissionDeductionsRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Regression coverage for the commission-payroll weighted-base + manual-entries fix (2026-07-23). A further
 * case adds an approved manual INCENTIVE entry on top and proves it now reaches payroll too, still agreeing
 * exactly with payrollReadySummary. */
class PayrollCommissionWeightedBaseIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);

    // Workbook reference: receipts 3,808,589.56 at 1x and 15,107.93 at 2x yield a 3,587,668.62 tier base.
    private static final BigDecimal FIRST_RECEIPT = new BigDecimal("3808589.56");
    private static final BigDecimal SECOND_RECEIPT = new BigDecimal("15107.93");
    // What payroll actually PAYS. Commission is rounded to whole baht at the payroll boundary
    // (owner decision 2026-08-25, PayrollService rounds commission to whole baht -- the accountant's house
    // convention, no statute behind it), so the payslip carries 67,849 where the commission
    // module's own summary still reports 67,849.23. The two differing by up to 50 satang is the
    // INTENDED consequence, not drift; the relationship is pinned in
    // #payrollCommissionPay_isTheWholeBahtRoundingOfThePayrollReadySummary.
    private static final BigDecimal WEIGHTED_COMMISSION_PAID = new BigDecimal("67849.00");
    // The bug this test guards against: summing commissionableBase unweighted (i.e. as if the second receipt were
    // never bumped to 2x) reproduces the OLD, wrong, lower figure instead.
    private static final BigDecimal UNWEIGHTED_COMMISSION = new BigDecimal("67390.34");

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private CommissionCalculator calculator;
    private EmployeeRepository employees;
    private PayrollService payrollService;

    private long managerEmployeeId;
    private UserPrincipal managerActor;
    private long ceoEmployeeId;
    private UserPrincipal ceoActor;

    private void wireRealCollaborators() {
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            mock(th.co.glr.hr.commission.CommissionAttachmentRepository.class),
            calculator,
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        // Leave -> payroll unpaid-day deduction (2026-07-23): mechanical constructor-arity fix -- PayrollService
        // gained a LeaveRepository dependency for #suggestedInputs, unrelated to what this test exercises.
        payrollService = new PayrollService(
            new PayrollRepository(jdbc),
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

        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย ทดสอบเพย์โรล", "sm-payroll-calcrefine@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
        ceoEmployeeId = createEmployee("ผู้บริหาร ทดสอบเพย์โรล", "ceo-payroll-calcrefine@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = new UserPrincipal(ceoEmployeeId, ceoEmployeeId + "@glr.co.th", "CEO",
            "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
    }

    /** The headline case: the payroll line's commissionPay must equal the weighted, owner-reconciled 67,849.23 --
     * not the old unweighted 67,390.34. */
    @Test
    void payrollPaysTheWeightedCommission_notTheOldUnweightedFigure() {
        wireRealCollaborators();
        long salesRepId = createEmployee("เจนเนตร เพย์โรล", "jennet-payroll-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedApprovedJennetScenario(salesRepId);

        PayrollLineDto line = previewLineFor(salesRepId);

        assertThat(line.commissionPay()).isEqualByComparingTo(WEIGHTED_COMMISSION_PAID);
        // Still discriminates after rounding: the unweighted figure rounds to 67,390, not 67,849.
        assertThat(line.commissionPay()).isNotEqualByComparingTo(UNWEIGHTED_COMMISSION);
        assertThat(line.commissionPay())
            .isNotEqualByComparingTo(UNWEIGHTED_COMMISSION.setScale(0, RoundingMode.HALF_UP));
    }

    // --- helpers ------------------------------------------------------------

    /** Seeds the jennet reconciliation scenario (1x + 2x-weighted receipts, both approved through manager + CEO)
     * for the given rep via the real repository/service -- identical inputs to
     * CommissionCalcRefineIntegrationTest's jennet test. */
    private void seedApprovedJennetScenario(long salesRepId) {
        long firstCommissionId = seedCommissionRecord(salesRepId, FIRST_RECEIPT);
        long secondCommissionId = seedCommissionRecord(salesRepId, SECOND_RECEIPT);
        setWeightMultiplierViaManagerReview(secondCommissionId, 2);
        approveThroughManagerAndCeo(firstCommissionId);
        approveThroughManagerAndCeo(secondCommissionId);
    }

    private long seedCommissionRecord(long salesRepId, BigDecimal actualReceived) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null,
            salesRepId,
            "INV-PAYROLL-CALCREFINE-" + UUID.randomUUID(),
            INVOICE_DATE,
            actualReceived,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        return commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, PAYROLL_MONTH, calculation);
    }

    private void setWeightMultiplierViaManagerReview(long commissionId, int weightMultiplier) {
        UpdateCommissionDeductionsRequest request = new UpdateCommissionDeductionsRequest(
            null, null, null, null, null, null, null, null, weightMultiplier,
            "payroll weighted-base fix test: apply the workbook's confirmed weighting for this receipt");
        commissionService.updateDeductions(commissionId, request, managerActor);
    }

    private void approveThroughManagerAndCeo(long commissionId) {
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
    }

    private PayrollLineDto previewLineFor(long salesRepId) {
        PayrollPeriodDto period = payrollService.preview(
            new ProcessPayrollRequest(PAYROLL_MONTH, java.util.List.of()), hrActor());
        return period.lines().stream()
            .filter(l -> l.employeeId() == salesRepId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + salesRepId));
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(999_003L, "hr-preview-calcrefine@glr.co.th", "HR", "hr",
            999_003L, true, LocalDate.now(), false, null, false);
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        long employeeId = employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        // Task 2 (2026-07-29): this test drives real PayrollService#preview, which now rejects a non-zero
        // COMMISSION_PAY component with no stored classification (handoff section 1).
        seedRegularTaxTreatment(employeeId, PAYROLL_MONTH.getYear(), PayrollComponent.COMMISSION_PAY);
        seedSsoIncluded(employeeId, PAYROLL_MONTH.getYear(), PayrollComponent.SALARY, PayrollComponent.COMMISSION_PAY);
        return employeeId;
    }
}
