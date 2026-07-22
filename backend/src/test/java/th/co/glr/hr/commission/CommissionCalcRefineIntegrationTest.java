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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Commission redesign calc-refine slice — real-DB reconciliation coverage against the owner's
 * hand-checked real-workbook totals (2026-07-22), plus the two behavior changes that make them
 * reproducible: (1) 2x/3x tier-base weighting (workbook column O, V82) and (2) rounding only the
 * FINAL {@code progressiveCommission} total, never the intermediate monthly TIER BASE.
 *
 * <p>Every reconciliation test seeds real {@code sales.commission_record}/{@code
 * sales.invoice_details} rows via the real {@link CommissionRepository} (no submit()/deal-chain
 * fixture needed — this slice doesn't touch creation, only the aggregation and the manager-review
 * weight field), drives the 2x weighting through the REAL manager-review path ({@link
 * CommissionService#updateDeductions}), and reads the resulting monthly commission back through
 * the real {@link CommissionService#simulate} — the same aggregation
 * ({@code CommissionRepository#sumActiveWeightedActualReceived} /
 * {@code CommissionCalculator#monthlyTierBase}) that a real submission or payroll run would use.
 *
 * <p>Per CLAUDE.md, this is why real Postgres is required and Mockito cannot substitute: the
 * point under test is that {@code SUM(actual_received * weight_multiplier)} and the single
 * full-precision VAT division actually happen in the database/repository layer, not that some
 * mocked method returns a canned number.
 */
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
            mock(AttachmentRepository.class));
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย ทดสอบ", "sm-calcrefine@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
        ceoEmployeeId = createEmployee("ผู้บริหาร ทดสอบ", "ceo-calcrefine@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = new UserPrincipal(ceoEmployeeId, ceoEmployeeId + "@glr.co.th", "CEO",
            "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Workbook reconciliation — five real rep/month totals, hand-checked by the owner
    // (2026-07-22) against the real commission policy Excel. Every commission figure below is
    // asserted EXACTLY, not "close to".
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void chanida_singleUnweightedReceipt_reproducesWorkbookCommission() {
        wireService();
        long salesRepId = createEmployee("ชนิดา ทดสอบ", "chanida-calcrefine@glr.co.th", "SA", "แผนกขาย");
        // actual_received chosen so the full-precision monthly TIER BASE lands at exactly
        // 801,204.00 (857288.28 / 1.07 = 801204.00 exactly) — clean, no rounding ambiguity, and
        // safely inside tier 4 (750,000-1,000,000 @ 1.00%).
        seedCommissionRecord(salesRepId, new BigDecimal("857288.28"));

        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);

        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo(new BigDecimal("801204.00"));
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("4262.04"));
    }

    @Test
    void suwannee_withOneTwoXWeightedReceipt_reproducesWorkbookCommission() {
        wireService();
        long salesRepId = createEmployee("สุวรรณี ทดสอบ", "suwannee-calcrefine@glr.co.th", "SA", "แผนกขาย");
        // Row 1 (1x): the rest of the month's receipts. Row 2 (weighted to 2x below, via the real
        // manager-review path): the one receipt the owner flagged as double-weighted.
        seedCommissionRecord(salesRepId, new BigDecimal("1847966.93"));
        long weightedCommissionId = seedCommissionRecord(salesRepId, new BigDecimal("39059.07"));

        setWeightMultiplierViaManagerReview(weightedCommissionId, 2);

        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);

        // Adjusted tier base = SUM(net) + SUM(weighted_extra) ~= 1,800,079.50 per the workbook
        // (own recomputation lands a fraction of a satang away at full precision — expected, see
        // handoff — the commission figure it produces is exact and unaffected by that).
        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo(new BigDecimal("1800079.50"));
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("18501.59"));
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

    @Test
    void praphatsorn_singleUnweightedReceipt_reproducesWorkbookCommission() {
        wireService();
        long salesRepId = createEmployee("ประภัสสร ทดสอบ", "praphatsorn-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("748142.93"));

        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);

        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo(new BigDecimal("699199.00"));
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("3368.99"));
    }

    @Test
    void adisak_singleUnweightedReceipt_reproducesWorkbookCommission() {
        wireService();
        long salesRepId = createEmployee("อดิศักดิ์ ทดสอบ", "adisak-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("752977.19"));

        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);

        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo(new BigDecimal("703717.00"));
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("3402.88"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // (a) Full-precision base differs from (and replaces) the old per-receipt-rounded sum.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void manySmallReceipts_fullPrecisionBase_differsFromOldPerReceiptRoundedSum() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย เศษสตางค์", "rounding-calcrefine@glr.co.th", "SA", "แผนกขาย");
        int receiptCount = 20;
        for (int i = 0; i < receiptCount; i++) {
            // 1.00 / 1.07 = 0.9345794... which the OLD per-record commissionable_base column
            // stores rounded to 0.93 (HALF_UP truncates the .0045794 remainder away). Twenty of
            // these accumulate a real, measurable gap between "sum of twenty already-rounded
            // 0.93s" and "sum twenty 1.00s, THEN divide once".
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

        // The two aggregation strategies disagree — proving the change is load-bearing, not
        // cosmetic.
        assertThat(newFullPrecisionBase).isNotEqualByComparingTo(oldPerReceiptRoundedSum);
        assertThat(newFullPrecisionBase).isEqualByComparingTo(new BigDecimal("18.6915887850"));

        // And the SERVICE (simulate(), the same call path submit()/updateDeductions() drive
        // through CommissionCalculator) returns the NEW full-precision-derived figure, not the
        // old rounded-column sum — displayed at 2dp per CommissionService#simulate.
        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);
        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo(new BigDecimal("18.69"));
        assertThat(dto.existingMonthlyBase()).isNotEqualByComparingTo(oldPerReceiptRoundedSum);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // (b) Separation: unweighted actual-receipts total (real cash) stays distinct from the
    // weighted tier base once any receipt carries a multiplier above 1x.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void weightedTierBase_divergesFromUnweightedActualReceivedTotal_whenATwoXReceiptExists() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย แยกยอด", "separation-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("100.00"));
        long weightedCommissionId = seedCommissionRecord(salesRepId, new BigDecimal("50.00"));

        // Before weighting: real cash (150.00) and the weighted aggregate (150.00) agree —
        // weighting hasn't diverged them yet.
        BigDecimal unweightedBefore = commissions.sumActiveActualReceived(salesRepId, PAYROLL_MONTH);
        BigDecimal weightedBefore = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        assertThat(unweightedBefore).isEqualByComparingTo(weightedBefore);

        setWeightMultiplierViaManagerReview(weightedCommissionId, 2);

        BigDecimal unweightedActualReceived = commissions.sumActiveActualReceived(salesRepId, PAYROLL_MONTH);
        BigDecimal weightedActualReceived = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);

        // Real cash received never changes because of weighting.
        assertThat(unweightedActualReceived).isEqualByComparingTo(new BigDecimal("150.00"));
        // The tier-base aggregate now counts the 50.00 receipt twice: 100 + 2*50 = 200.
        assertThat(weightedActualReceived).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(weightedActualReceived).isNotEqualByComparingTo(unweightedActualReceived);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // (c) Mutation-check: the multiplier is load-bearing. Without it (all rows left at the 1x
    // default, i.e. what the DB would do if a manager never reviewed the weighting), the สุวรรณี
    // and เจนเนตร reconciliation figures above do NOT reproduce — proving the weighting step
    // (not some other coincidental input) is what makes those two tests pass.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void suwannee_withoutTheTwoXReview_understatesTheWorkbookCommission() {
        wireService();
        long salesRepId = createEmployee("สุวรรณี ไม่ถ่วงน้ำหนัก", "suwannee-noweight-calcrefine@glr.co.th", "SA", "แผนกขาย");
        // Identical receipts to the สุวรรณี reconciliation test, but the manager NEVER reviews the
        // second row, so weight_multiplier stays at its 1x default.
        seedCommissionRecord(salesRepId, new BigDecimal("1847966.93"));
        seedCommissionRecord(salesRepId, new BigDecimal("39059.07"));

        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);

        assertThat(dto.projectedMonthlyCommission()).isNotEqualByComparingTo(new BigDecimal("18501.59"));
        // 1,887,026.00 / 1.07 = 1,763,575.7009... (unweighted) instead of the 2x-weighted
        // 1,800,079.50 -> a materially different, LOWER commission.
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("17771.51"));
    }

    @Test
    void jennet_withoutTheTwoXReview_understatesTheWorkbookCommission() {
        wireService();
        long salesRepId = createEmployee("เจนเนตร ไม่ถ่วงน้ำหนัก", "jennet-noweight-calcrefine@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("3808589.56"));
        seedCommissionRecord(salesRepId, new BigDecimal("15107.93"));

        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);

        assertThat(dto.projectedMonthlyCommission()).isNotEqualByComparingTo(new BigDecimal("67849.23"));
        // 3,823,697.49 / 1.07 = 3,573,549.0560... (unweighted) instead of the 2x-weighted
        // 3,587,668.62 -> a materially different, LOWER commission.
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("67390.34"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // payrollReadySummary: the other monthly-base aggregation path (HR's payroll-ready view)
    // must use the same weighted, full-precision logic as simulate() — not the old per-record
    // commissionable_base sum.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void payrollReadySummary_usesWeightedFullPrecisionBase_matchingSimulate() {
        wireService();
        long salesRepId = createEmployee("ชนิดา บัญชีเงินเดือน", "chanida-payroll-calcrefine@glr.co.th", "SA", "แผนกขาย");
        long commissionId = seedCommissionRecord(salesRepId, new BigDecimal("857288.28"));
        approveThroughManagerAndCeo(commissionId);

        UserPrincipal hrActor = new UserPrincipal(999_001L, "hr-calcrefine@glr.co.th", "HR", "hr",
            999_001L, true, LocalDate.now(), false, null, false);
        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(PAYROLL_MONTH, hrActor);

        SalesRepCommissionSummaryDto repSummary = summary.salesReps().stream()
            .filter(s -> s.salesRepId() == salesRepId)
            .findFirst()
            .orElseThrow();
        assertThat(repSummary.commissionableBase()).isEqualByComparingTo(new BigDecimal("801204.00"));
        assertThat(repSummary.commissionAmount()).isEqualByComparingTo(new BigDecimal("4262.04"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Clawback correctness under weighting: a clawback must reverse the ORIGINAL's weighted
    // contribution exactly, not silently fall back to 1x.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void clawback_preservesOriginalsWeightMultiplier_soTheReversalMatchesTheOriginalContribution() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย เคลมคืน", "clawback-calcrefine@glr.co.th", "SA", "แผนกขาย");
        long commissionId = seedCommissionRecord(salesRepId, new BigDecimal("100000.00"));
        setWeightMultiplierViaManagerReview(commissionId, 2);
        approveThroughManagerAndCeo(commissionId);

        BigDecimal weightedBeforeClawback = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        assertThat(weightedBeforeClawback).isEqualByComparingTo(new BigDecimal("200000.00"));

        CommissionRecord original = commissions.findById(commissionId).orElseThrow();
        long clawbackId = commissions.createClawback(original, managerEmployeeId, PAYROLL_MONTH, "test clawback");
        CommissionRecord clawback = commissions.findById(clawbackId).orElseThrow();

        assertThat(clawback.weightMultiplier()).isEqualTo(2);

        BigDecimal weightedAfterClawback = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        // Fully reversed: the clawback's own 2x-weighted negative contribution exactly cancels
        // the original's 2x-weighted positive one.
        assertThat(weightedAfterClawback).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Seeds one active (SUBMITTED) commission_record with the given real-cash amount and no
     * deductions (so {@code actualReceived == grossAmount} exactly) via the real repository —
     * mirroring exactly what {@code CommissionService#submit}/{@code #createFromDeal} persist,
     * without needing the full ticket/deal fixture chain this slice doesn't touch.
     */
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

    private void approveThroughManagerAndCeo(long commissionId) {
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
    }

    /** Runs simulate() with a zero-amount addition, so the returned figures reflect only what
     * was already seeded for the rep/month — a read-only probe into the current aggregate. */
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
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }
}
