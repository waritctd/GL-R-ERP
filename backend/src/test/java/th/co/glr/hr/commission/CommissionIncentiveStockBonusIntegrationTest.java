package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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

/** Issue #405 — real-DB coverage for the auto-computed INCENTIVE ladder + STOCK_BONUS. */
class CommissionIncentiveStockBonusIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate JULY_2026 = LocalDate.of(2026, 7, 1);
    private static final LocalDate AUGUST_2026 = LocalDate.of(2026, 8, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 7, 15);
    // 3,210,000.00 / 1.07 = 3,000,000.00 exactly -- lands precisely on the first INCENTIVE threshold with no
    // rounding ambiguity.
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
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย อินเซนทีฟ", "sm-incentive@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
        ceoEmployeeId = createEmployee("ผู้บริหาร อินเซนทีฟ", "ceo-incentive@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = new UserPrincipal(ceoEmployeeId, ceoEmployeeId + "@glr.co.th", "CEO",
            "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
        hrActor = new UserPrincipal(999_555L, "hr-incentive@glr.co.th", "HR", "hr",
            999_555L, true, LocalDate.now(), false, null, false);
    }

    // Fix-forward: a payroll month before 2026-08-01 gets ZERO incentive (no matching generation), the same real
    // receipt in August gets the ladder. Proves the generation-selection SQL, not just the pure calculator method.

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

    // Stock share: proves the real GROUP BY ticket_id join actually pro-rates a mixed ticket, and that a
    // fully-imported ticket (qty_from_stock = 0) contributes zero.

    @Test
    void stockShareJoin_proRatesMixedTicket_andYieldsZeroForFullyImportedTicket() {
        wireService();
        enableStockBonus();
        long mixedRep = createEmployee("พนักงานขาย สต๊อคผสม", "mixed-stock@glr.co.th", "SA", "แผนกขาย");
        long importedRep = createEmployee("พนักงานขาย นำเข้าล้วน", "imported-stock@glr.co.th", "SA", "แผนกขาย");

        // 40 of 100 qty from stock -> stockShare 0.4. ฿500,000 received x 0.4 = ฿200,000 stock receipts ->
        // floor(200000 / 100000) x 1000 = ฿2,000.
        long mixedTicketId = createTicket(mixedRep);
        addTicketItem(mixedTicketId, new BigDecimal("100.00"), new BigDecimal("40.00"));
        seedApprovedStockLinkedCommission(mixedRep, mixedTicketId, new BigDecimal("500000.00"), AUGUST_2026);

        // qty_from_stock = 0 across the board -> stockShare 0 -> zero stock receipts regardless of how much was
        // received.
        long importedTicketId = createTicket(importedRep);
        addTicketItem(importedTicketId, new BigDecimal("100.00"), BigDecimal.ZERO);
        seedApprovedStockLinkedCommission(importedRep, importedTicketId, new BigDecimal("500000.00"), AUGUST_2026);

        SalesRepCommissionSummaryDto mixedSummary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), mixedRep);
        SalesRepCommissionSummaryDto importedSummary = repSummaryFor(commissionService.payrollReadySummary(AUGUST_2026, hrActor), importedRep);

        assertThat(mixedSummary.stockBonusAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(importedSummary.stockBonusAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Double-count guard (transition safeguard): an approved manual INCENTIVE/STOCK_BONUS for the rep-month
    // suppresses the auto-computed limb -- exactly one of each in the total, not two.

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

    // Helpers

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

    /** Seeds one real, fully APPROVED unlinked SALE commission — mirrors
     * CommissionCalcRefineIntegrationTest#seedCommissionRecord/ManualCommissionIntegrationTest#seedApprovedTierCommission,
     * parameterized by payroll month so the fix-forward test can seed the identical receipt into two different months. */
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

    /** Same as #seedApprovedTierCommission, but linked to a real ticket for the stock-share join to key off of
     * (source_ticket_id). */
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

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
