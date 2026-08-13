package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * Real-DB coverage for {@link CommissionService#monthlySummary} — {@code GET
 * /api/commissions/monthly-summary}, the new endpoint that replaces the frontend's former
 * client-side re-implementation of the commission tier math (the deleted
 * {@code commissionCalc.js}). Per CLAUDE.md, Mockito cannot prove any of this: the whole point is
 * that the tier config is read from {@code sales.tier_config} at call time, so a real Postgres row
 * update must be reflected in the very next call — a mocked repository would happily keep
 * returning whatever canned tiers the test wired in.
 *
 * <p>This is also a NEW authz surface (a new role gate plus a new row-scope), so per CLAUDE.md's
 * "permission changes must ship evidence" every authz case here is written the wrong-way-round:
 * "can rep A see rep B's figures" is the assertion that matters, not "can rep A see their own".
 * Wiring mirrors {@link CommissionListScopeIntegrationTest} / {@link
 * CommissionCalcRefineIntegrationTest} in this package: services are hand-wired with {@code new}
 * (no Spring context), {@code @Transactional} is inert here, so nothing below ever asserts on a
 * rollback.
 */
class CommissionMonthlySummaryIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);
    // The V108 INCENTIVE ladder's first generation is effective 2026-08-01, so any case that
    // needs a non-zero incentive must use a month at or after it.
    private static final LocalDate AUGUST_2026 = LocalDate.of(2026, 8, 1);

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private CommissionCalculator calculator;
    private EmployeeRepository employees;
    private long managerEmployeeId;
    private UserPrincipal managerActor;
    private long ceoEmployeeId;
    private UserPrincipal ceoActor;

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
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย สรุปเดือน", "sm-monthlysummary@glr.co.th", "SA", "แผนกขาย");
        managerActor = principal(managerEmployeeId, "sales_manager");
        ceoEmployeeId = createEmployee("ผู้บริหาร สรุปเดือน", "ceo-monthlysummary@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = principal(ceoEmployeeId, "ceo");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 1. The V81 test: a DB tier-rate change changes the very next call's figure.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void tierRateChange_changesTheReturnedFigure_provingItFollowsTheDatabase() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย เปลี่ยนอัตรา", "tier-rate-change@glr.co.th", "SA", "แผนกขาย");
        // 3,745,000.00 / 1.07 = 3,500,000.00 exactly -- comfortably past the tier-13 threshold
        // (>3,000,000), no rounding ambiguity.
        seedCommissionRecord(salesRepId, new BigDecimal("3745000.00"));

        CommissionMonthlySummaryDto before = monthlySummaryAsSelf(salesRepId);
        assertThat(before.commissionableBase()).isEqualByComparingTo(new BigDecimal("3500000.00"));
        // Tiers 1-12 (250k-wide bands, 0.25% step per tier) sum to 48,750.00; tier 13 (>3,000,000,
        // V81-corrected to 3.25%) on the remaining 500,000.00 = 16,250.00.
        assertThat(before.tierCommission()).isEqualByComparingTo(new BigDecimal("65000.00"));
        assertThat(before.totalCommission()).isEqualByComparingTo(new BigDecimal("65000.00"));

        jdbc.update("UPDATE sales.tier_config SET rate_percent = :rate WHERE tier_number = 13",
            Map.of("rate", new BigDecimal("5.0000")));

        CommissionMonthlySummaryDto after = monthlySummaryAsSelf(salesRepId);
        // Same base, same tiers 1-12 (48,750.00); tier 13 now 500,000.00 @ 5.00% = 25,000.00.
        assertThat(after.commissionableBase()).isEqualByComparingTo(new BigDecimal("3500000.00"));
        assertThat(after.tierCommission()).isEqualByComparingTo(new BigDecimal("73750.00"));
        assertThat(after.totalCommission()).isEqualByComparingTo(new BigDecimal("73750.00"));
        assertThat(after.tierCommission()).isNotEqualByComparingTo(before.tierCommission());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 2. Rows sum exactly to the total.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void tierRowsSumExactlyToTheTotal() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย รวมแถว", "tier-rows-sum@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("3745000.00"));

        CommissionMonthlySummaryDto dto = monthlySummaryAsSelf(salesRepId);

        BigDecimal sumOfRows = dto.tiers().stream()
            .map(CommissionTierRowDto::commission)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfRows).isEqualByComparingTo(dto.tierCommission());
        assertThat(dto.tiers()).hasSize(13);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 3. Matches the authoritative calculator directly -- proves the endpoint reports the
    //    engine's own answer, not a parallel computation that happens to agree today.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void tierCommissionMatchesTheAuthoritativeCalculatorDirectly() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ตรงกับเครื่องคำนวณ", "tier-matches-calc@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("1847966.93"));

        CommissionMonthlySummaryDto dto = monthlySummaryAsSelf(salesRepId);

        BigDecimal weighted = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        BigDecimal base = calculator.monthlyTierBase(weighted);
        BigDecimal expected = calculator.progressiveCommission(base, commissions.findTiers());
        assertThat(dto.tierCommission()).isEqualByComparingTo(expected);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 4. Below floor: a positive base under the monthly floor -> belowFloor true, zero commission.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void belowFloor_returnsTrueAndZeroTierCommission() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ต่ำกว่าเกณฑ์", "below-floor-monthlysummary@glr.co.th", "SA", "แผนกขาย");
        // 10,000.00 / 1.07 = 9,345.79... -- a genuinely POSITIVE base, comfortably below the
        // 50,000 monthly floor (proves belowFloor signals "positive but under floor", not merely
        // "zero receipts").
        seedCommissionRecord(salesRepId, new BigDecimal("10000.00"));

        CommissionMonthlySummaryDto dto = monthlySummaryAsSelf(salesRepId);

        assertThat(dto.commissionableBase()).isGreaterThan(BigDecimal.ZERO);
        assertThat(dto.belowFloor()).isTrue();
        assertThat(dto.tierCommission()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.totalCommission()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 5. Authz, wrong-way-round -- a NEW gate + a NEW row-scope, required per CLAUDE.md.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void salesActor_requestingAnotherRepsSalesRepId_isForbidden() {
        wireService();
        long repA = createEmployee("พนักงานขาย เอ สรุป", "monthlysummary-repa@glr.co.th", "SA", "แผนกขาย");
        long repB = createEmployee("พนักงานขาย บี สรุป", "monthlysummary-repb@glr.co.th", "SA", "แผนกขาย");

        assertThatThrownBy(() -> commissionService.monthlySummary(repB, PAYROLL_MONTH, principal(repA, "sales")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void salesActor_nullSalesRepId_getsOwnRepIdAndFigures_otherRepsReceiptsExcluded() {
        wireService();
        long repA = createEmployee("พนักงานขาย เอ ไม่รั่ว", "monthlysummary-leaka@glr.co.th", "SA", "แผนกขาย");
        long repB = createEmployee("พนักงานขาย บี ไม่รั่ว", "monthlysummary-leakb@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(repA, new BigDecimal("107000.00"));
        // A huge receipt on a DIFFERENT rep -- if this leaked into repA's base the assertion below
        // would obviously fail.
        seedCommissionRecord(repB, new BigDecimal("5000000.00"));

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(null, PAYROLL_MONTH, principal(repA, "sales"));

        assertThat(dto.salesRepId()).isEqualTo(repA);
        // 107,000.00 / 1.07 = 100,000.00 exactly.
        assertThat(dto.commissionableBase()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    void nonListViewerRoles_areForbidden() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ทดสอบสิทธิ์", "monthlysummary-authz@glr.co.th", "SA", "แผนกขาย");
        // Denied roles rejected at the role gate before any DB lookup -- synthetic ids, same
        // pattern as CommissionListScopeIntegrationTest. hr reads via payroll-ready instead
        // (PAYROLL_ROLES), account only ever does createFromDeal -- neither may call this.
        List<UserPrincipal> denied = List.of(
            principal(999_101L, "hr"),
            principal(999_102L, "account"),
            principal(999_103L, "import"),
            principal(999_104L, "employee"),
            principal(999_105L, "warehouse"),
            principal(999_106L, "qc"));
        for (UserPrincipal actor : denied) {
            assertThatThrownBy(() -> commissionService.monthlySummary(salesRepId, PAYROLL_MONTH, actor))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    @Test
    void salesManagerAndCeo_maySeeAnotherRepsFigures() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ให้ผู้จัดการดู", "monthlysummary-managerview@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("3745000.00"));

        CommissionMonthlySummaryDto managerView = commissionService.monthlySummary(salesRepId, PAYROLL_MONTH, managerActor);
        CommissionMonthlySummaryDto ceoView = commissionService.monthlySummary(salesRepId, PAYROLL_MONTH, ceoActor);

        assertThat(managerView.salesRepId()).isEqualTo(salesRepId);
        assertThat(managerView.commissionableBase()).isEqualByComparingTo(new BigDecimal("3500000.00"));
        assertThat(ceoView.salesRepId()).isEqualTo(salesRepId);
        assertThat(ceoView.commissionableBase()).isEqualByComparingTo(new BigDecimal("3500000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 6. Manual entries: an APPROVED manual ADJUSTMENT lands in manualTotal/totalCommission but
    //    never commissionableBase; a MANAGER_APPROVED (not yet CEO-signed) one counts for neither.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approvedManualAdjustment_landsInManualTotalAndTotalCommission_notInCommissionableBase() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ปรับปรุงมือ", "monthlysummary-manual@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("107000.00")); // base 100,000.00 exactly

        commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("2000.00"),
            "monthly-summary test: approved adjustment", PAYROLL_MONTH, ceoActor); // CEO-created -> APPROVED

        CommissionMonthlySummaryDto dto = monthlySummaryAsSelf(salesRepId);

        assertThat(dto.manualTotal()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(dto.totalCommission())
            .isEqualByComparingTo(dto.tierCommission().add(dto.incentiveAmount()).add(new BigDecimal("2000.00")));
        // Not in the base -- the base reflects only the seeded 107,000.00 receipt.
        assertThat(dto.commissionableBase()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    void managerApprovedManualEntry_countsForNeitherManualTotalNorTotalCommission() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย รอซีอีโอ", "monthlysummary-pending@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("107000.00"));

        commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("2000.00"),
            "monthly-summary test: manager-only approval", PAYROLL_MONTH, managerActor); // -> MANAGER_APPROVED

        CommissionMonthlySummaryDto dto = monthlySummaryAsSelf(salesRepId);

        assertThat(dto.manualTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.totalCommission()).isEqualByComparingTo(dto.tierCommission().add(dto.incentiveAmount()));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // 7. The INCENTIVE limb. monthlySummary carries its OWN copy of the 2026-08-02 reworked
    //    suppression rule (it does not call computeRepPayrollCommissions), so "the rule is proven
    //    elsewhere" is not evidence for THIS method -- these two cases assert it directly, and
    //    wrong-way-round: the case that matters is that a NEGATIVE manual entry does NOT suppress.
    //    Both use AUGUST_2026 because the V108 ladder's effective_from is 2026-08-01; the
    //    fix-forward gate itself (a July month sees an empty ladder -> zero) is proven for the
    //    shared repository/calculator path by CommissionIncentiveStockBonusIntegrationTest.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void positiveApprovedManualIncentive_suppressesTheAutoComputedLimb() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย แทนที่อินเซนทีฟ", "monthlysummary-incentive-replace@glr.co.th", "SA", "แผนกขาย");
        // 3,210,000.00 / 1.07 = 3,000,000.00 exactly -- lands on the ladder's first threshold.
        seedCommissionRecord(salesRepId, new BigDecimal("3210000.00"), AUGUST_2026);
        commissionService.createManualCommission(
            salesRepId, CommissionKind.INCENTIVE, new BigDecimal("15000.00"),
            "hand-entered replacement", AUGUST_2026, ceoActor); // CEO-created -> APPROVED

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(
            salesRepId, AUGUST_2026, principal(salesRepId, "sales"));

        // Suppressed: the rep must not be shown the incentive twice (auto + manual).
        assertThat(dto.incentiveAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.manualTotal()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(dto.totalCommission())
            .isEqualByComparingTo(dto.tierCommission().add(new BigDecimal("15000.00")));
    }

    @Test
    void negativeApprovedManualIncentive_isACorrection_andDoesNotSuppressTheAutoLimb() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย แก้ไขอินเซนทีฟ", "monthlysummary-incentive-correct@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("3210000.00"), AUGUST_2026);
        commissionService.createManualCommission(
            salesRepId, CommissionKind.INCENTIVE, new BigDecimal("-5000.00"),
            "correction: prior INCENTIVE overstated", AUGUST_2026, ceoActor);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(
            salesRepId, AUGUST_2026, principal(salesRepId, "sales"));

        // NOT suppressed -- a correction layers on top of the full auto limb. A "does one exist"
        // guard would wrongly zero the 15,000 here and show the rep -5,000 instead of +10,000.
        assertThat(dto.incentiveAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(dto.manualTotal()).isEqualByComparingTo(new BigDecimal("-5000.00"));
        assertThat(dto.totalCommission()).isEqualByComparingTo(
            dto.tierCommission().add(new BigDecimal("15000.00")).subtract(new BigDecimal("5000.00")));
        assertThat(dto.totalCommission())
            .isNotEqualByComparingTo(dto.tierCommission().subtract(new BigDecimal("5000.00")));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private CommissionMonthlySummaryDto monthlySummaryAsSelf(long salesRepId) {
        return commissionService.monthlySummary(salesRepId, PAYROLL_MONTH, principal(salesRepId, "sales"));
    }

    /**
     * Seeds one active (SUBMITTED) commission_record with the given real-cash amount and no
     * deductions (so {@code actualReceived == actualReceived} exactly) via the real repository --
     * mirrors {@code CommissionCalcRefineIntegrationTest#seedCommissionRecord}, without needing the
     * full ticket/deal/invoice-file fixture chain this endpoint doesn't touch. SUBMITTED (not
     * approved) is enough: {@code sumActiveWeightedActualReceived}'s {@code NOT IN
     * ('VOID','REJECTED')} filter already counts it, matching {@code monthlySummary}'s "live
     * estimate" semantics (the same filter {@code simulate()} uses).
     */
    private long seedCommissionRecord(long salesRepId, BigDecimal actualReceived) {
        return seedCommissionRecord(salesRepId, actualReceived, PAYROLL_MONTH);
    }

    private long seedCommissionRecord(long salesRepId, BigDecimal actualReceived, LocalDate payrollMonth) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-MONTHLYSUMMARY-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        return commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, payrollMonth, calculation);
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-" + employeeId + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
