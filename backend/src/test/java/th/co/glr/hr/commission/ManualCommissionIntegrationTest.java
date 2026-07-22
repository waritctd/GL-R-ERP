package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Manual commission entries (feat/commission-manual-adjustments) — real-DB acceptance coverage.
 *
 * <p>Per CLAUDE.md's "permission changes must ship evidence": {@link
 * CommissionService#createManualCommission} is a NEW authorization boundary (only
 * {@code sales_manager}/{@code ceo} may create a manual entry), so this class proves it
 * wrong-way-round against the real {@link CommissionService} backed by a real {@link
 * CommissionRepository} on real Postgres — Mockito cannot prove the {@code chk_commission_kind}/
 * {@code chk_commission_manual_fields} CHECK constraints (V84) actually accept the new kinds, or
 * that the LEFT JOIN change in {@code RECORD_SELECT} correctly returns a null {@code
 * invoiceDetails} for a manual row instead of silently excluding it. {@link AuditService} and
 * {@link NotificationService} are mocked deliberately (side effects of an already-decided
 * authorization/business decision, same rationale as every other commission integration test in
 * this package); every collaborator that participates in the authz decision or its persistence
 * (real {@link CommissionRepository}, real {@link CommissionCalculator}, real {@link
 * EmployeeRepository}) is real.
 */
class ManualCommissionIntegrationTest extends AbstractPostgresIntegrationTest {
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
    private UserPrincipal hrActor;

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
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย แมนนวล", "sm-manual@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
        ceoEmployeeId = createEmployee("ผู้บริหาร แมนนวล", "ceo-manual@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = new UserPrincipal(ceoEmployeeId, ceoEmployeeId + "@glr.co.th", "CEO",
            "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
        hrActor = new UserPrincipal(999_777L, "hr-manual@glr.co.th", "HR", "hr",
            999_777L, true, LocalDate.now(), false, null, false);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // AUTHZ — wrong-way-round: a sales actor and a plain rep must NOT be able to create a
    // manual commission entry. Zero rows created, not just a 403 status code.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void salesActor_cannotCreateManualCommission_zeroRowsCreated() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย แมนนวล", "sales-manual@glr.co.th", "SA", "แผนกขาย");
        UserPrincipal salesActor = new UserPrincipal(salesRepId, salesRepId + "@glr.co.th", "Sales Rep", "sales",
            salesRepId, true, LocalDate.now(), false, null, false);
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createManualCommission(
                salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("5000.00"), "trying to self-approve", PAYROLL_MONTH, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    @Test
    void plainRepRole_cannotCreateManualCommission_zeroRowsCreated() {
        wireService();
        // A plain rep with none of the special-division roles (sales/sales_manager/ceo/account/
        // hr/import) — the DivisionAccessPolicy fallback role. Explicitly NOT in
        // CommissionService.MANUAL_CREATE_ROLES.
        long employeeId = createEmployee("พนักงานทั่วไป แมนนวล", "employee-manual@glr.co.th", "WH", "คลังสินค้า");
        UserPrincipal employeeActor = new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Warehouse Staff",
            "employee", employeeId, true, LocalDate.now(), false, null, false);
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createManualCommission(
                employeeId, CommissionKind.MANAGER, new BigDecimal("1000.00"), "should never work", PAYROLL_MONTH, employeeActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Positive-side sales_manager/ceo access, both are allowed (right-way-round sanity check
    // alongside the wrong-way-round tests above).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void managerAndCeo_canCreateManualCommission() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย เข้าถึงได้", "sales-allowed-manual@glr.co.th", "SA", "แผนกขาย");

        CommissionRecord byManager = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"), "manager-created", PAYROLL_MONTH, managerActor);
        assertThat(byManager.status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);

        CommissionRecord byCeo = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"), "ceo-created", PAYROLL_MONTH, ceoActor);
        assertThat(byCeo.status()).isEqualTo(CommissionStatus.APPROVED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Positive ADJUSTMENT: manager creates it (MANAGER_APPROVED, not yet in payroll), CEO
    // approves it via the EXISTING approve() chain (reused, not reimplemented), and payroll
    // rises by EXACTLY the manual amount, on top of the rep's tier commission.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void managerCreatesPositiveAdjustment_ceoApproves_addsExactlyToPayrollOnTopOfTierCommission() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ปรับเพิ่ม", "adjust-plus@glr.co.th", "SA", "แผนกขาย");
        // Tier baseline: a real APPROVED SALE commission so there is a genuine non-zero tier
        // commission to add the manual amount "on top of".
        long saleCommissionId = seedApprovedTierCommission(salesRepId, new BigDecimal("857288.28"));

        BigDecimal beforeCommission = repSummaryFor(payrollReadySummary(), salesRepId).commissionAmount();
        BigDecimal beforeBase = repSummaryFor(payrollReadySummary(), salesRepId).commissionableBase();
        assertThat(beforeCommission).isGreaterThan(BigDecimal.ZERO);

        CommissionRecord created = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("5000.00"),
            "takeover credit split — interim manual entry", PAYROLL_MONTH, managerActor);
        assertThat(created.status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);
        assertThat(created.manualAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(created.invoiceDetails()).isNull();
        assertThat(created.sourceTicketId()).isNull();

        // Not yet APPROVED -- must NOT count toward payroll yet.
        BigDecimal stillPending = repSummaryFor(payrollReadySummary(), salesRepId).commissionAmount();
        assertThat(stillPending).isEqualByComparingTo(beforeCommission);

        commissionService.approve(created.id(), ceoActor); // MANAGER_APPROVED -> APPROVED, reused chain
        CommissionRecord approved = commissions.findById(created.id()).orElseThrow();
        assertThat(approved.status()).isEqualTo(CommissionStatus.APPROVED);

        SalesRepCommissionSummaryDto after = repSummaryFor(payrollReadySummary(), salesRepId);
        assertThat(after.commissionAmount().subtract(beforeCommission)).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(after.manualAdjustmentAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        // The tier base itself is untouched by the manual entry -- only the final commission total
        // gained the manual amount.
        assertThat(after.commissionableBase()).isEqualByComparingTo(beforeBase);
        assertThat(saleCommissionId).isPositive();
    }

    // The two additional manual kinds (owner: "manual across the UI for now, until the
    // CEO-confirmed config lands") ride the identical path — this proves the V84 CHECK accepts
    // their literal string values and they feed payroll exactly like ADJUSTMENT/MANAGER, so no
    // per-kind logic diverged when the set was widened.
    @Test
    void stockBonusAndIncentive_manualKinds_acceptedAndFeedPayrollOnceApproved() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย โบนัส", "bonus@glr.co.th", "SB", "แผนกขาย");

        CommissionRecord stock = commissionService.createManualCommission(
            salesRepId, CommissionKind.STOCK_BONUS, new BigDecimal("1000.00"),
            "stock-sale bonus — interim manual entry", PAYROLL_MONTH, managerActor);
        assertThat(stock.manualAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(stock.invoiceDetails()).isNull();
        commissionService.approve(stock.id(), ceoActor);

        CommissionRecord incentive = commissionService.createManualCommission(
            salesRepId, CommissionKind.INCENTIVE, new BigDecimal("15000.00"),
            "performance incentive — interim manual entry", PAYROLL_MONTH, managerActor);
        commissionService.approve(incentive.id(), ceoActor);

        SalesRepCommissionSummaryDto after = repSummaryFor(payrollReadySummary(), salesRepId);
        assertThat(after.commissionAmount()).isEqualByComparingTo(new BigDecimal("16000.00"));
        assertThat(after.manualAdjustmentAmount()).isEqualByComparingTo(new BigDecimal("16000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Negative ADJUSTMENT: reduces the payroll total by exactly the (negative) manual amount.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void negativeAdjustment_reducesPayrollTotalByExactAmount() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ปรับลด", "adjust-minus@glr.co.th", "SA", "แผนกขาย");
        seedApprovedTierCommission(salesRepId, new BigDecimal("857288.28"));
        BigDecimal beforeCommission = repSummaryFor(payrollReadySummary(), salesRepId).commissionAmount();

        CommissionRecord created = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("-2000.00"),
            "correction: overpaid last cycle", PAYROLL_MONTH, managerActor);
        commissionService.approve(created.id(), ceoActor);

        BigDecimal afterCommission = repSummaryFor(payrollReadySummary(), salesRepId).commissionAmount();
        assertThat(beforeCommission.subtract(afterCommission)).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void managerKindEntry_cannotBeNegative() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ทดสอบลบ", "manager-negative@glr.co.th", "SA", "แผนกขาย");
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createManualCommission(
                salesRepId, CommissionKind.MANAGER, new BigDecimal("-500.00"), "should be rejected", PAYROLL_MONTH, ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    @Test
    void blankReason_isRejected() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ไม่มีเหตุผล", "no-reason@glr.co.th", "SA", "แผนกขาย");

        assertThatThrownBy(() -> commissionService.createManualCommission(
                salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"), "   ", PAYROLL_MONTH, managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // MANAGER-kind entry: targets the manager and lands in payroll the same way. Created
    // directly by the CEO -- lands APPROVED immediately, no manager-review step needed.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void managerKindEntry_forTheManager_landsInPayroll() {
        wireService();
        // managerEmployeeId has NO SALE commission this month -- the manual MANAGER entry is
        // their only commission this month, so payrollReadySummary must synthesize a summary row
        // (base 0.00) rather than only handling reps who already have a tier-calc row.
        CommissionRecord created = commissionService.createManualCommission(
            managerEmployeeId, CommissionKind.MANAGER, new BigDecimal("15000.00"),
            "July team/manager commission", PAYROLL_MONTH, ceoActor);
        assertThat(created.status()).isEqualTo(CommissionStatus.APPROVED);

        SalesRepCommissionSummaryDto summary = repSummaryFor(payrollReadySummary(), managerEmployeeId);
        assertThat(summary.commissionableBase()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.commissionAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(summary.manualAdjustmentAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Manual entries never touch the tier calc: no invoice required, amount stored verbatim,
    // and the weighted tier-base sum (the input to simulate()/progressiveCommission) is
    // completely unaffected by a manual entry, even while it is active (MANAGER_APPROVED).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void manualEntry_neverFeedsTierCalc_amountStoredVerbatim() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ไม่มีใบกำกับ", "no-invoice@glr.co.th", "SA", "แผนกขาย");
        BigDecimal weightedBefore = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        assertThat(weightedBefore).isEqualByComparingTo(BigDecimal.ZERO);

        CommissionRecord created = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("12345.67"),
            "interim stock incentive, applied by hand", PAYROLL_MONTH, managerActor);

        CommissionRecord reloaded = commissions.findById(created.id()).orElseThrow();
        assertThat(reloaded.manualAmount()).isEqualByComparingTo(new BigDecimal("12345.67"));
        assertThat(reloaded.manualReason()).isEqualTo("interim stock incentive, applied by hand");
        assertThat(reloaded.invoiceDetails()).isNull();
        assertThat(reloaded.sourceTicketId()).isNull();
        assertThat(reloaded.actualReceived()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reloaded.commissionableBase()).isEqualByComparingTo(BigDecimal.ZERO);

        // The weighted tier-base sum (what simulate()/payrollReadySummary's tier math consumes)
        // must be completely unaffected by this manual entry, active or not.
        BigDecimal weightedAfter = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        assertThat(weightedAfter).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private PayrollCommissionSummaryDto payrollReadySummary() {
        return commissionService.payrollReadySummary(PAYROLL_MONTH, hrActor);
    }

    private SalesRepCommissionSummaryDto repSummaryFor(PayrollCommissionSummaryDto summary, long salesRepId) {
        return summary.salesReps().stream()
            .filter(s -> s.salesRepId() == salesRepId)
            .findFirst()
            .orElseThrow();
    }

    /**
     * Seeds one real, fully APPROVED (manager + CEO) SALE commission via the real repository —
     * mirroring what {@code CommissionService#submit} persists, without the full ticket/deal
     * fixture chain this task doesn't touch. Copied from the same pattern used by {@code
     * CommissionCalcRefineIntegrationTest#seedCommissionRecord}/{@code approveThroughManagerAndCeo}.
     */
    private long seedApprovedTierCommission(long salesRepId, BigDecimal actualReceived) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-MANUAL-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        long commissionId = commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, PAYROLL_MONTH, calculation);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    private int countCommissionRecords() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales.commission_record", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }
}
