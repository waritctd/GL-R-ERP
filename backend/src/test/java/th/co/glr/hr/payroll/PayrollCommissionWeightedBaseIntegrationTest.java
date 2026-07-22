package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionKind;
import th.co.glr.hr.commission.CommissionRecord;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.commission.InvoiceCalculation;
import th.co.glr.hr.commission.PayrollCommissionSummaryDto;
import th.co.glr.hr.commission.SalesRepCommissionSummaryDto;
import th.co.glr.hr.commission.SubmitCommissionRequest;
import th.co.glr.hr.commission.UpdateCommissionDeductionsRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Regression coverage for the commission-payroll weighted-base + manual-entries fix (2026-07-23).
 *
 * <p>{@code PayrollService#commissionPayByEmployee} used to sum each APPROVED commission record's
 * already-2dp-rounded {@code commissionableBase} UNWEIGHTED, then run {@code progressiveCommission}
 * on that sum, and never added a rep's approved manual entries (ADJUSTMENT/MANAGER/STOCK_BONUS/
 * INCENTIVE, V84) at all. Both diverge from what HR sees in {@link
 * CommissionService#payrollReadySummary}, which uses the weighted, full-precision monthly tier base
 * ({@code SUM(actual_received * weight_multiplier) / 1.07}, rounded once) PLUS each rep's approved
 * manual-entry total -- see {@code CommissionCalcRefineIntegrationTest} for the owner-reconciled
 * weighted-tier reference figures this test reuses, and the real "jennet" June 2026 payroll
 * reconciliation (82,849.23 = weighted tier 67,849.23 + a 15,000 INCENTIVE) for the manual-entries
 * figure.
 *
 * <p>This drives the real chain end to end against real Postgres: seed two commission records for
 * one rep (one at the default 1x weight, one bumped to 2x through the real manager-review path,
 * {@link CommissionService#updateDeductions}), approve both through manager + CEO, then assert the
 * real {@link PayrollService}'s payroll preview pays the WEIGHTED owner-reconciled figure -- not
 * the old unweighted one -- and agrees exactly with {@link CommissionService#payrollReadySummary}
 * for the same rep/month. A further case adds an approved manual INCENTIVE entry on top and proves
 * it now reaches payroll too, still agreeing exactly with {@code payrollReadySummary}. Mockito
 * cannot reach this: the point under test is that the SQL aggregation and the two call paths
 * (payroll vs. payrollReadySummary) now compute the identical number, not that some mocked
 * collaborator returns a canned value.
 */
class PayrollCommissionWeightedBaseIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);

    // Owner-reconciled reference figures (2026-07-22), reused verbatim from
    // CommissionCalcRefineIntegrationTest#jennet_withOneTwoXWeightedReceipt_aboveThreeMillionTier_reproducesWorkbookCommission:
    // receipts 3,808,589.56 (1x) + 15,107.93 (2x) -> weighted monthly tier base 3,587,668.62,
    // exercising the >3,000,000 @ 3.25% high-roller tier.
    private static final BigDecimal FIRST_RECEIPT = new BigDecimal("3808589.56");
    private static final BigDecimal SECOND_RECEIPT = new BigDecimal("15107.93");
    private static final BigDecimal WEIGHTED_COMMISSION = new BigDecimal("67849.23");
    // The bug this test guards against: summing commissionableBase unweighted (i.e. as if the
    // second receipt were never bumped to 2x) reproduces the OLD, wrong, lower figure instead.
    private static final BigDecimal UNWEIGHTED_COMMISSION = new BigDecimal("67390.34");
    // Real payroll reconciliation (June 2026, owner-confirmed 2026-07-23): a rep's full commission
    // is the weighted tier commission PLUS their approved manual entries -- 67,849.23 + a 15,000
    // INCENTIVE = 82,849.23. This is the exact "jennet" case that revealed the old code excluded
    // manual entries from the payroll figure entirely.
    private static final BigDecimal MANUAL_INCENTIVE_AMOUNT = new BigDecimal("15000.00");
    private static final BigDecimal WEIGHTED_PLUS_MANUAL_COMMISSION = new BigDecimal("82849.23");

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
            mock(AttachmentRepository.class));
        payrollService = new PayrollService(
            new PayrollRepository(jdbc),
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class));

        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย ทดสอบเพย์โรล", "sm-payroll-calcrefine@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
        ceoEmployeeId = createEmployee("ผู้บริหาร ทดสอบเพย์โรล", "ceo-payroll-calcrefine@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = new UserPrincipal(ceoEmployeeId, ceoEmployeeId + "@glr.co.th", "CEO",
            "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
    }

    /**
     * The headline case: the payroll line's commissionPay must equal the weighted,
     * owner-reconciled 67,849.23 -- not the old unweighted 67,390.34.
     */
    @Test
    void payrollPaysTheWeightedCommission_notTheOldUnweightedFigure() {
        wireRealCollaborators();
        long salesRepId = createEmployee("เจนเนตร เพย์โรล", "jennet-payroll-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedApprovedJennetScenario(salesRepId);

        PayrollLineDto line = previewLineFor(salesRepId);

        assertThat(line.commissionPay()).isEqualByComparingTo(WEIGHTED_COMMISSION);
        assertThat(line.commissionPay()).isNotEqualByComparingTo(UNWEIGHTED_COMMISSION);
    }

    /**
     * The two call paths must now agree: what payroll actually pays and what HR sees in the
     * payroll-ready summary must be the identical number for the same rep/month.
     */
    @Test
    void payrollCommissionPay_agreesWithPayrollReadySummary_forTheSameRepAndMonth() {
        wireRealCollaborators();
        long salesRepId = createEmployee("เจนเนตร สรุปเพย์โรล", "jennet-summary-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedApprovedJennetScenario(salesRepId);

        PayrollLineDto line = previewLineFor(salesRepId);

        UserPrincipal hrActor = new UserPrincipal(999_002L, "hr-payroll-calcrefine@glr.co.th", "HR", "hr",
            999_002L, true, LocalDate.now(), false, null, false);
        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(PAYROLL_MONTH, hrActor);
        SalesRepCommissionSummaryDto repSummary = summary.salesReps().stream()
            .filter(s -> s.salesRepId() == salesRepId)
            .findFirst()
            .orElseThrow();

        assertThat(line.commissionPay()).isEqualByComparingTo(repSummary.commissionAmount());
        assertThat(line.commissionPay()).isEqualByComparingTo(WEIGHTED_COMMISSION);
    }

    /**
     * The gross figure on the payroll line has to move with the fix too, not just an internal
     * aggregate -- otherwise the corrected money never reaches the payslip.
     */
    @Test
    void payrollGrossEarningsReflectTheWeightedCommission() {
        wireRealCollaborators();
        long salesRepId = createEmployee("เจนเนตร ยอดรวม", "jennet-gross-calcrefine@glr.co.th", "SA", "แผนกขาย");
        PayrollLineDto beforeAnyCommission = previewLineFor(salesRepId);
        assertThat(beforeAnyCommission.commissionPay()).isEqualByComparingTo(BigDecimal.ZERO);

        seedApprovedJennetScenario(salesRepId);
        PayrollLineDto after = previewLineFor(salesRepId);

        assertThat(after.commissionPay()).isEqualByComparingTo(WEIGHTED_COMMISSION);
        assertThat(after.grossEarnings())
            .isEqualByComparingTo(beforeAnyCommission.grossEarnings().add(WEIGHTED_COMMISSION));
    }

    /**
     * Revision 1 headline case: real payroll pays each rep's FULL commission = weighted tier
     * commission + approved manual entries, not just the tier portion. Proof figure is the real
     * "jennet" June 2026 payroll reconciliation: 82,849.23 = weighted tier 67,849.23 + a 15,000
     * INCENTIVE. Also proves the payroll and payrollReadySummary paths still agree exactly once the
     * manual entry is in the mix, via the shared {@code CommissionService#payrollCommissionTotalsByEmployee}
     * / {@code computeRepPayrollCommissions} aggregation both now build on.
     */
    @Test
    void payrollPaysWeightedTierPlusApprovedManualEntries_matchingPayrollReadySummary() {
        wireRealCollaborators();
        long salesRepId = createEmployee("เจนเนตร อินเซนทีฟ", "jennet-incentive-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedApprovedJennetScenario(salesRepId);
        // CEO-created manual entries land APPROVED directly (no separate manager sign-off needed).
        commissionService.createManualCommission(
            salesRepId, CommissionKind.INCENTIVE, MANUAL_INCENTIVE_AMOUNT,
            "payroll weighted-base fix test: reconciled June 2026 incentive", PAYROLL_MONTH, ceoActor);

        PayrollLineDto line = previewLineFor(salesRepId);

        assertThat(line.commissionPay()).isEqualByComparingTo(WEIGHTED_PLUS_MANUAL_COMMISSION);

        UserPrincipal hrActor = new UserPrincipal(999_004L, "hr-payroll-incentive-calcrefine@glr.co.th", "HR", "hr",
            999_004L, true, LocalDate.now(), false, null, false);
        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(PAYROLL_MONTH, hrActor);
        SalesRepCommissionSummaryDto repSummary = summary.salesReps().stream()
            .filter(s -> s.salesRepId() == salesRepId)
            .findFirst()
            .orElseThrow();

        assertThat(line.commissionPay()).isEqualByComparingTo(repSummary.commissionAmount());
        assertThat(repSummary.manualAdjustmentAmount()).isEqualByComparingTo(MANUAL_INCENTIVE_AMOUNT);
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Seeds the jennet reconciliation scenario (1x + 2x-weighted receipts, both approved through
     * manager + CEO) for the given rep via the real repository/service -- identical inputs to
     * {@code CommissionCalcRefineIntegrationTest}'s jennet test.
     */
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
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }
}
