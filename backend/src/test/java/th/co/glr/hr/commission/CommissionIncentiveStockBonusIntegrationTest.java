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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
 * Issue #405 — real-DB coverage for the auto-computed INCENTIVE ladder + STOCK_BONUS. Per
 * CLAUDE.md, the config-generation-selection SQL ({@code effective_from <=} the payroll month)
 * and the stock-share join (a {@code GROUP BY ticket_id} subquery over
 * {@code sales.ticket_item}) cannot be proven by Mockito — a mocked repository would happily
 * "pass" while the SQL selected the wrong generation or mis-joined the stock share. Every test
 * here drives the real {@link CommissionRepository} against real Postgres and reads the result
 * back through the real {@link CommissionService#payrollReadySummary}, the same path HR's
 * payroll-ready screen and {@code PayrollService#commissionPayByEmployee} both use.
 */
class CommissionIncentiveStockBonusIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate JULY_2026 = LocalDate.of(2026, 7, 1);
    private static final LocalDate AUGUST_2026 = LocalDate.of(2026, 8, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 7, 15);
    // 3,210,000.00 / 1.07 = 3,000,000.00 exactly -- lands precisely on the first INCENTIVE
    // threshold with no rounding ambiguity.
    private static final BigDecimal ACTUAL_RECEIVED_AT_FIRST_THRESHOLD = new BigDecimal("3210000.00");

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
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย อินเซนทีฟ", "sm-incentive@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
        ceoEmployeeId = createEmployee("ผู้บริหาร อินเซนทีฟ", "ceo-incentive@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = new UserPrincipal(ceoEmployeeId, ceoEmployeeId + "@glr.co.th", "CEO",
            "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
        hrActor = new UserPrincipal(999_555L, "hr-incentive@glr.co.th", "HR", "hr",
            999_555L, true, LocalDate.now(), false, null, false);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fix-forward: a payroll month before 2026-08-01 gets ZERO incentive (no matching
    // generation), the same real receipt in August gets the ladder. Proves the
    // generation-selection SQL, not just the pure calculator method.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void julyMonth_getsZeroIncentive_augustMonthGetsLadder_forTheIdenticalReceipt() {
        wireService();
        long julyRep = createEmployee("พนักงานขาย กรกฎา", "july-rep-incentive@glr.co.th", "SA", "แผนกขาย");
        long augustRep = createEmployee("พนักงานขาย สิงหา", "august-rep-incentive@glr.co.th", "SA", "แผนกขาย");
        seedApprovedTierCommission(julyRep, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, JULY_2026);
        seedApprovedTierCommission(augustRep, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, AUGUST_2026);

        SalesRepCommissionSummaryDto julySummary = repSummaryFor(commissionService.payrollReadySummary(JULY_2026, hrActor), julyRep);
        SalesRepCommissionSummaryDto augustSummary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), augustRep);

        // Same tier base, same tier commission -- the tier math itself is untouched.
        assertThat(julySummary.commissionableBase()).isEqualByComparingTo(new BigDecimal("3000000.00"));
        assertThat(augustSummary.commissionableBase()).isEqualByComparingTo(new BigDecimal("3000000.00"));

        assertThat(julySummary.incentiveAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(augustSummary.incentiveAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(augustSummary.commissionAmount().subtract(julySummary.commissionAmount()))
            .isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Stock share: proves the real GROUP BY ticket_id join actually pro-rates a mixed ticket,
    // and that a fully-imported ticket (qty_from_stock = 0) contributes zero.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void stockShareJoin_proRatesMixedTicket_andYieldsZeroForFullyImportedTicket() {
        wireService();
        enableStockBonus();
        long mixedRep = createEmployee("พนักงานขาย สต๊อคผสม", "mixed-stock@glr.co.th", "SA", "แผนกขาย");
        long importedRep = createEmployee("พนักงานขาย นำเข้าล้วน", "imported-stock@glr.co.th", "SA", "แผนกขาย");

        // 40 of 100 qty from stock -> stockShare 0.4. ฿500,000 received x 0.4 = ฿200,000 stock
        // receipts -> floor(200000 / 100000) x 1000 = ฿2,000.
        long mixedTicketId = createTicket(mixedRep);
        addTicketItem(mixedTicketId, new BigDecimal("100.00"), new BigDecimal("40.00"));
        seedApprovedStockLinkedCommission(mixedRep, mixedTicketId, new BigDecimal("500000.00"), AUGUST_2026);

        // qty_from_stock = 0 across the board -> stockShare 0 -> zero stock receipts regardless
        // of how much was received.
        long importedTicketId = createTicket(importedRep);
        addTicketItem(importedTicketId, new BigDecimal("100.00"), BigDecimal.ZERO);
        seedApprovedStockLinkedCommission(importedRep, importedTicketId, new BigDecimal("500000.00"), AUGUST_2026);

        SalesRepCommissionSummaryDto mixedSummary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), mixedRep);
        SalesRepCommissionSummaryDto importedSummary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), importedRep);

        assertThat(mixedSummary.stockBonusAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(importedSummary.stockBonusAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Review fix (2026-08-02), wrong-way-round: a still-SUBMITTED receipt (nobody has approved
    // it) must NOT contribute to the stock bonus, even though it has a real linked ticket with a
    // real stock share. An earlier version of sumActiveStockActualReceived used a
    // NOT IN ('VOID','REJECTED') filter that let this leak through -- proven here with the exact
    // numbers the reviewer's real-DB probe found: ฿6,000 (wrong, includes the SUBMITTED receipt)
    // vs. ฿1,000 (correct, APPROVED-only).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void stockBonus_ignoresAStillSubmittedReceipt_reflectsOnlyTheApprovedOne() {
        wireService();
        enableStockBonus();
        long salesRepId = createEmployee("พนักงานขาย ยังไม่อนุมัติ", "submitted-stock@glr.co.th", "SA", "แผนกขาย");

        // APPROVED: ฿100,000 fully from stock -> ฿100,000 stock receipts -> exactly 1 block -> ฿1,000.
        long approvedTicketId = createTicket(salesRepId);
        addTicketItem(approvedTicketId, new BigDecimal("100.00"), new BigDecimal("100.00"));
        seedApprovedStockLinkedCommission(salesRepId, approvedTicketId, new BigDecimal("100000.00"), AUGUST_2026);

        // SUBMITTED (never approved by anyone): ฿500,000 fully from stock on a DIFFERENT ticket.
        // If this leaked in, receipts would be 600,000 -> 6 blocks -> ฿6,000. It must not.
        long submittedTicketId = createTicket(salesRepId);
        addTicketItem(submittedTicketId, new BigDecimal("100.00"), new BigDecimal("100.00"));
        seedSubmittedStockLinkedCommission(salesRepId, submittedTicketId, new BigDecimal("500000.00"), AUGUST_2026);

        SalesRepCommissionSummaryDto summary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), salesRepId);

        assertThat(summary.stockBonusAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(summary.stockBonusAmount()).isNotEqualByComparingTo(new BigDecimal("6000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Config-gated OFF by default: the seeded V108 row (enabled = FALSE) pays zero until the
    // CEO flips it, then pays once flipped -- same ticket/receipt, only the config row changes.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void stockBonus_staysZeroWithSeededDisabledConfig_andPaysOnceFlippedTrue() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ปิดสวิตช์", "toggle-stock@glr.co.th", "SA", "แผนกขาย");
        long ticketId = createTicket(salesRepId);
        addTicketItem(ticketId, new BigDecimal("100.00"), new BigDecimal("100.00")); // fully from stock
        seedApprovedStockLinkedCommission(salesRepId, ticketId, new BigDecimal("300000.00"), AUGUST_2026);

        // Seeded config is enabled = FALSE -- must pay zero even though the stock share (100%)
        // and receipts (฿300,000, three whole ฿100,000 blocks) would otherwise pay ฿3,000.
        SalesRepCommissionSummaryDto beforeEnable = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), salesRepId);
        assertThat(beforeEnable.stockBonusAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        enableStockBonus();

        SalesRepCommissionSummaryDto afterEnable = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), salesRepId);
        assertThat(afterEnable.stockBonusAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Double-count guard (transition safeguard): an approved manual INCENTIVE/STOCK_BONUS for
    // the rep-month suppresses the auto-computed limb -- exactly one of each in the total, not
    // two.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void manualIncentiveSuppression_exactlyOneIncentiveInTheTotal_notTwo() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย มีอินเซนทีฟมือ", "manual-incentive@glr.co.th", "SA", "แผนกขาย");
        seedApprovedTierCommission(salesRepId, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, AUGUST_2026);
        commissionService.createManualCommission(
            salesRepId, CommissionKind.INCENTIVE, new BigDecimal("15000.00"),
            "hand-entered before auto-compute shipped", AUGUST_2026, ceoActor); // CEO-created -> APPROVED immediately

        SalesRepCommissionSummaryDto summary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), salesRepId);

        // The auto-computed limb is suppressed (zero); only the manual 15,000 appears, once.
        assertThat(summary.incentiveAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.manualAdjustmentAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
        BigDecimal tierCommission = calculator.progressiveCommission(new BigDecimal("3000000.00"));
        assertThat(summary.commissionAmount()).isEqualByComparingTo(tierCommission.add(new BigDecimal("15000.00")));
    }

    @Test
    void manualStockBonusSuppression_exactlyOneStockBonusInTheTotal_notTwo() {
        wireService();
        enableStockBonus();
        long salesRepId = createEmployee("พนักงานขาย มีโบนัสสต๊อคมือ", "manual-stockbonus@glr.co.th", "SA", "แผนกขาย");
        long ticketId = createTicket(salesRepId);
        addTicketItem(ticketId, new BigDecimal("100.00"), new BigDecimal("100.00"));
        seedApprovedStockLinkedCommission(salesRepId, ticketId, new BigDecimal("300000.00"), AUGUST_2026);
        commissionService.createManualCommission(
            salesRepId, CommissionKind.STOCK_BONUS, new BigDecimal("1000.00"),
            "hand-entered override", AUGUST_2026, ceoActor);

        SalesRepCommissionSummaryDto summary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), salesRepId);

        // Auto-computed stock bonus (would be ฿3,000 here) is suppressed; only the manual
        // ฿1,000 appears.
        assertThat(summary.stockBonusAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.manualAdjustmentAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Review fix (2026-08-02): the double-count guard only suppresses the auto limb for a
    // STRICTLY POSITIVE summed manual amount (a replacement). A ZERO or NEGATIVE manual entry is
    // a CORRECTION layered on top -- the auto limb must still compute, and the correction adds to
    // it. The pre-fix guard treated "one exists" as suppression, so a -5,000 correction on top of
    // a real 15,000 auto incentive would have paid -5,000 total instead of the correct 10,000.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void negativeManualIncentive_isACorrection_autoLimbStillComputesAndCorrectionAdds() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย แก้ไขอินเซนทีฟ", "negative-manual-incentive@glr.co.th", "SA", "แผนกขาย");
        seedApprovedTierCommission(salesRepId, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, AUGUST_2026); // tier base exactly 3,000,000 -> auto incentive 15,000
        commissionService.createManualCommission(
            salesRepId, CommissionKind.INCENTIVE, new BigDecimal("-5000.00"),
            "correction: prior INCENTIVE overstated", AUGUST_2026, ceoActor);

        SalesRepCommissionSummaryDto summary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), salesRepId);

        // The auto limb is NOT suppressed -- it still computes the full 15,000.
        assertThat(summary.incentiveAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(summary.manualAdjustmentAmount()).isEqualByComparingTo(new BigDecimal("-5000.00"));
        BigDecimal tierCommission = calculator.progressiveCommission(new BigDecimal("3000000.00"));
        // Total = tier commission + auto incentive (15,000) + the -5,000 correction = tier + 10,000.
        assertThat(summary.commissionAmount())
            .isEqualByComparingTo(tierCommission.add(new BigDecimal("15000.00")).subtract(new BigDecimal("5000.00")));
        assertThat(summary.commissionAmount()).isNotEqualByComparingTo(tierCommission.subtract(new BigDecimal("5000.00")));
    }

    @Test
    void zeroManualIncentiveNote_isNotAReplacement_autoLimbStillComputesInFull() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย บันทึกศูนย์", "zero-manual-incentive@glr.co.th", "SA", "แผนกขาย");
        seedApprovedTierCommission(salesRepId, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, AUGUST_2026);
        commissionService.createManualCommission(
            salesRepId, CommissionKind.INCENTIVE, BigDecimal.ZERO,
            "note only, not a replacement", AUGUST_2026, ceoActor);

        SalesRepCommissionSummaryDto summary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), salesRepId);

        assertThat(summary.incentiveAmount()).isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    @Test
    void negativeManualStockBonus_isACorrection_autoLimbStillComputesAndCorrectionAdds() {
        wireService();
        enableStockBonus();
        long salesRepId = createEmployee("พนักงานขาย แก้ไขโบนัสสต๊อค", "negative-manual-stockbonus@glr.co.th", "SA", "แผนกขาย");
        long ticketId = createTicket(salesRepId);
        addTicketItem(ticketId, new BigDecimal("100.00"), new BigDecimal("100.00"));
        seedApprovedStockLinkedCommission(salesRepId, ticketId, new BigDecimal("300000.00"), AUGUST_2026); // auto stock bonus 3,000
        commissionService.createManualCommission(
            salesRepId, CommissionKind.STOCK_BONUS, new BigDecimal("-1000.00"),
            "correction: prior STOCK_BONUS overstated", AUGUST_2026, ceoActor);

        SalesRepCommissionSummaryDto summary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), salesRepId);

        assertThat(summary.stockBonusAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(summary.manualAdjustmentAmount()).isEqualByComparingTo(new BigDecimal("-1000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz regression: the new fields ride the existing payrollReadySummary response -- no new
    // role gate was added or widened. A non-payroll role still 403s.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void payrollReadySummary_stillForbiddenForNonPayrollRole_newFieldsOpenNoNewPath() {
        wireService();
        UserPrincipal salesActor = new UserPrincipal(
            ceoEmployeeId + 1, "sales-authz-405@glr.co.th", "Sales Rep", "sales",
            ceoEmployeeId + 1, true, LocalDate.now(), false, null, false);

        assertThatThrownBy(() -> commissionService.payrollReadySummary(AUGUST_2026, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private SalesRepCommissionSummaryDto repSummaryFor(PayrollCommissionSummaryDto summary, long salesRepId) {
        return summary.salesReps().stream()
            .filter(s -> s.salesRepId() == salesRepId)
            .findFirst()
            .orElseThrow();
    }

    /** Flips the seeded V108 stock_bonus_config row (effective_from 2026-08-01) to enabled. */
    private void enableStockBonus() {
        jdbc.update("""
            UPDATE sales.stock_bonus_config
               SET enabled = TRUE
             WHERE effective_from = '2026-08-01'
            """, Map.of());
    }

    private long createTicket(long salesRepId) {
        Long ticketId = jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, status, created_by)
            VALUES (:code, 'stock bonus test ticket', 'draft', :createdBy)
            RETURNING ticket_id
            """,
            new MapSqlParameterSource()
                // sales.ticket.code is VARCHAR(20) -- a full UUID does not fit.
                .addValue("code", "T-" + UUID.randomUUID().toString().substring(0, 8))
                .addValue("createdBy", salesRepId),
            Long.class);
        return ticketId;
    }

    private void addTicketItem(long ticketId, BigDecimal qty, BigDecimal qtyFromStock) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, qty, qty_from_stock)
            VALUES (:ticketId, 'stock bonus test item', :qty, :qtyFromStock)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("qty", qty)
                .addValue("qtyFromStock", qtyFromStock));
    }

    /**
     * Seeds one real, fully APPROVED (manager + CEO) unlinked SALE commission — mirrors {@code
     * CommissionCalcRefineIntegrationTest#seedCommissionRecord}/{@code
     * ManualCommissionIntegrationTest#seedApprovedTierCommission}, parameterized by payroll month
     * so the fix-forward test can seed the identical receipt into two different months.
     */
    private long seedApprovedTierCommission(long salesRepId, BigDecimal actualReceived, LocalDate payrollMonth) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-INCENTIVE-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        long commissionId = commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, payrollMonth, calculation);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    /** Same as {@link #seedApprovedTierCommission}, but linked to a real ticket for the
     * stock-share join to key off of ({@code source_ticket_id}). */
    private long seedApprovedStockLinkedCommission(long salesRepId, long ticketId, BigDecimal actualReceived, LocalDate payrollMonth) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-STOCKBONUS-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        long commissionId = commissions.createCommissionRecord(invoiceId, ticketId, salesRepId, salesRepId, payrollMonth, calculation);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    /**
     * Review fix (2026-08-02): same as {@link #seedApprovedStockLinkedCommission} but deliberately
     * left at {@code SUBMITTED} -- nobody has reviewed it -- so tests can prove it does NOT
     * contribute to {@link CommissionRepository#sumActiveStockActualReceived}'s APPROVED-only
     * aggregation.
     */
    private long seedSubmittedStockLinkedCommission(long salesRepId, long ticketId, BigDecimal actualReceived, LocalDate payrollMonth) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-STOCKBONUS-SUBMITTED-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        return commissions.createCommissionRecord(invoiceId, ticketId, salesRepId, salesRepId, payrollMonth, calculation);
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
