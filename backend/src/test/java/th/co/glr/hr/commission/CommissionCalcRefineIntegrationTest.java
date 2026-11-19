package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Commission redesign calc-refine slice. */
class CommissionCalcRefineIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private CommissionCalculator calculator;
    private EmployeeRepository employees;
    private long managerEmployeeId;
    private UserPrincipal managerActor;
    private long ceoEmployeeId;
    private UserPrincipal ceoActor;
    private UserPrincipal salesActorFor(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Sales Rep " + employeeId, "sales",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private void wireService() {
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            calculator,
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย ทดสอบ", "sm-calcrefine@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
        ceoEmployeeId = createEmployee("ผู้บริหาร ทดสอบ", "ceo-calcrefine@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = new UserPrincipal(ceoEmployeeId, ceoEmployeeId + "@glr.co.th", "CEO",
            "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
    }

    // Workbook reconciliation — five real rep/month totals, hand-checked by the owner (2026-07-22) against the
    // real commission policy Excel. Every commission figure below is asserted EXACTLY, not "close to".

    @Test
    void chanida_singleUnweightedReceipt_reproducesWorkbookCommission() {
        wireService();
        long salesRepId = createEmployee("ชนิดา ทดสอบ", "chanida-calcrefine@glr.co.th", "SA", "แผนกขาย");
        // actual_received chosen so the full-precision monthly TIER BASE lands at exactly 801,204.00 (857288.28 / 1.07
        // = 801204.00 exactly) — clean, no rounding ambiguity, and safely inside tier 4 (750,000-1,000,000 @ 1.00%).
        seedCommissionRecord(salesRepId, new BigDecimal("857288.28"));

        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);

        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo(new BigDecimal("801204.00"));
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("4262.04"));
    }

    @Test
    void jennet_withOneTwoXWeightedReceipt_aboveThreeMillionTier_reproducesWorkbookCommission() {
        wireService();
        long salesRepId = createEmployee("เจนเนตร ทดสอบ", "jennet-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("3808589.56"));
        long weightedCommissionId = seedCommissionRecord(salesRepId, new BigDecimal("15107.93"));

        setWeightMultiplierViaManagerReview(weightedCommissionId, 2);

        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);

        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo(new BigDecimal("3587668.62"));
        // Exercises the >3,000,000 @ 3.25% (V81-corrected) high-roller tier on top of weighting.
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("67849.23"));
    }

    // (a) Full-precision base differs from (and replaces) the old per-receipt-rounded sum.

    @Test
    void manySmallReceipts_fullPrecisionBase_differsFromOldPerReceiptRoundedSum() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย เศษสตางค์", "rounding-calcrefine@glr.co.th", "SA", "แผนกขาย");
        int receiptCount = 20;
        for (int i = 0; i < receiptCount; i++) {
            // 1.00 / 1.07 = 0.9345794... which the OLD per-record commissionable_base column stores rounded to 0.93
            // (HALF_UP truncates the .0045794 remainder away). Twenty of these accumulate a real, measurable gap between
            // "sum of twenty already-rounded 0.93s" and "sum twenty 1.00s, THEN divide once".
            seedCommissionRecord(salesRepId, new BigDecimal("1.00"));
        }

        BigDecimal oldPerReceiptRoundedSum = jdbc.queryForObject("""
            SELECT COALESCE(SUM(commissionable_base), 0)
              FROM sales.commission_record
             WHERE sales_rep_id = :salesRepId AND payroll_month = :payrollMonth
            """,
            Map.of("salesRepId", salesRepId, "payrollMonth", PAYROLL_MONTH),
            BigDecimal.class);
        assertThat(oldPerReceiptRoundedSum).isEqualByComparingTo(new BigDecimal("18.60"));

        BigDecimal weightedActualReceived = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        BigDecimal newFullPrecisionBase = calculator.monthlyTierBase(weightedActualReceived);

        // The two aggregation strategies disagree — proving the change is load-bearing, not cosmetic.
        assertThat(newFullPrecisionBase).isNotEqualByComparingTo(oldPerReceiptRoundedSum);
        assertThat(newFullPrecisionBase).isEqualByComparingTo(new BigDecimal("18.6915887850"));

        // And the SERVICE (simulate(), the same call path submit()/updateDeductions() drive through
        // CommissionCalculator) returns the NEW full-precision-derived figure, not the old rounded-column sum —
        // displayed at 2dp per CommissionService#simulate.
        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);
        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo(new BigDecimal("18.69"));
        assertThat(dto.existingMonthlyBase()).isNotEqualByComparingTo(oldPerReceiptRoundedSum);
    }

    // Helpers

    /** Seeds one active commission_record with the given real-cash amount and no deductions via the real repository
     * — mirroring exactly what CommissionService#submit/#createFromDeal persist FOR AN UNLINKED COMMISSION,
     * without needing the full ticket/deal fixture chain this slice doesn't touch. */
    private long seedCommissionRecord(long salesRepId, BigDecimal actualReceived) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null,
            salesRepId,
            "INV-CALCREFINE-" + UUID.randomUUID(),
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

    /** Sets a commission's tier-base weight multiplier through the real manager-review path. */
    private void setWeightMultiplierViaManagerReview(long commissionId, int weightMultiplier) {
        UpdateCommissionDeductionsRequest request = new UpdateCommissionDeductionsRequest(
            null, null, null, null, null, null, null, null, weightMultiplier,
            "calc-refine test: apply the workbook's confirmed weighting for this receipt");
        commissionService.updateDeductions(commissionId, request, managerActor);
    }

    /** Runs simulate() with a zero-amount addition, so the returned figures reflect only what was already seeded
     * for the rep/month — a read-only probe into the current aggregate. */
    private CommissionSimulationDto simulateNoAdditionalReceipt(long salesRepId) {
        CommissionSimulatorRequest request = new CommissionSimulatorRequest(
            salesRepId,
            PAYROLL_MONTH,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        );
        return commissionService.simulate(request, salesActorFor(salesRepId));
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
