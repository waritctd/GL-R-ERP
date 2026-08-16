package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.ItemWeightMultiplierRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.StockReservationRequest;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * V148 (per-item stock-commission weighting) — real-DB coverage for the full pipeline: real
 * {@code sales.ticket_item} rows, the real {@link TicketService#reserveStock}/{@link
 * TicketService#setItemWeightMultipliers} writers, the real {@link
 * CommissionCalculator#itemDerivedWeight} blend, the real {@link
 * CommissionRepository#createCommissionRecord} freeze, and the real {@link
 * CommissionRepository#sumActiveWeightedActualReceived}/{@link
 * CommissionService#computeRepPayrollCommissions} aggregation. Per CLAUDE.md, this is why real
 * Postgres is required and Mockito cannot substitute: the point under test is that a real ticket's
 * items, read back through the real repository, actually drive the real weighted-base SQL — not
 * that some mocked collaborator returns a canned number.
 *
 * <p>Commission creation deliberately goes through {@link CommissionRepository#createInvoice}/
 * {@link CommissionRepository#createCommissionRecord} directly rather than {@link
 * CommissionService#submit}/{@code #createFromDeal} (which need a multipart invoice upload and a
 * CLOSED_PAID ticket stage) — mirroring {@code CommissionCalcRefineIntegrationTest}'s own
 * established pattern for testing the aggregation without the full deal-chain fixture. Each test
 * computes {@code effectiveWeightMultiplier} by calling the REAL {@link
 * CommissionCalculator#itemDerivedWeight} against REAL ticket items read back from Postgres, so
 * the freeze computation itself is exercised end to end — only the outer {@code submit}/{@code
 * createFromDeal} orchestration (call the calculator, thread the result into the INSERT) is not
 * re-exercised here; that wiring is a two-line change reviewed directly in {@code
 * CommissionService}, and {@code CommissionServiceTest} already covers {@code submit}'s
 * orchestration with mocks.
 */
class StockItemWeightedCommissionIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);

    private TicketRepository tickets;
    private TicketService ticketService;
    private CommissionRepository commissions;
    private CommissionCalculator calculator;
    private CommissionService commissionService;
    private EmployeeRepository employees;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal salesManager;
    private UserPrincipal ceoActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests);
        // tickets is the REAL repository (not mocked) — CommissionService's own
        // computeItemDerivedWeight needs it to read real ticket_item rows, exactly as it will in
        // production.
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            calculator,
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            tickets,
            mock(AttachmentRepository.class));

        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee("เจ้าของดีล ถ่วงน้ำหนัก", "owner-itemweight@glr.co.th", "SA", "แผนกขาย");
        owner = principal(ownerId, "sales");
        long managerId = createEmployee("ผู้จัดการฝ่ายขาย ถ่วงน้ำหนัก", "manager-itemweight@glr.co.th", "SA", "แผนกขาย");
        salesManager = principal(managerId, "sales_manager");
        long ceoId = createEmployee("ซีอีโอ ถ่วงน้ำหนัก", "ceo-itemweight@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = principal(ceoId, "ceo");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The workbook reconciliation, to the baht
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * One rep, June 2026: a "normal" (1x) unlinked receipt of 352,003.93, plus seven single-item,
     * FULLY stock-covered tickets weighted x2 (approved through the real {@code
     * setItemWeightMultipliers} endpoint) whose actualReceived sum to exactly 123,443.76 —
     * reproducing the owner's reconciled figures: normal base 444,343.64 ((352,003.93 +
     * 123,443.76) / 1.07 = 444,343.6355…, displays as 444,343.64 — i.e. what the base WOULD be if
     * the seven records stayed 1x), their incremental x2 contribution 115,368.00 (= 123,443.76 /
     * 1.07 EXACTLY — a deliberately clean input with no rounding remainder), weighted base
     * 559,711.64, and progressive-tier commission 625.00 + 1,250.00 + 447.84 = 2,322.84 (tiers 1-3
     * of {@code TierConfig.defaults()}: 250,000 @ 0.25%, 250,000 @ 0.50%, 59,711.64 @ 0.75%).
     *
     * <p>Every commission FIGURE is asserted EXACTLY, matching {@link
     * CommissionCalculatorTest#workbookReconciliation_suwannee_belowFloorIncentiveThreshold_noIncentive},
     * which pins the SAME base/commission pair at the pure-calculator level — this test proves the
     * REAL per-item pipeline reaches that exact same pair, not merely that the calculator can be
     * handed 559,711.64 by hand. A single fully-stock item at x2 yields effectiveWeight = 2.000000
     * exactly (asserted inline, per record, before ever creating the commission row) — so the
     * per-item model reconciles IDENTICALLY to the old record-level weight_multiplier = 2 in this
     * fully-covered case, exactly as the brief requires.
     */
    @Test
    void juneReconciliation_normalPlusSevenFullyStockedTwoXLines_reproducesWorkbookExactly() {
        long salesRepId = ownerId;

        seedUnlinkedRecord(salesRepId, new BigDecimal("352003.93"));
        // Seven single-item, fully-stock, x2-weighted tickets. Individual amounts are arbitrary —
        // only their SUM matters to the reconciliation — chosen to sum to exactly 123,443.76.
        List<String> stockReceipts = List.of(
            "16000.00", "16000.00", "16000.00", "16000.00", "16000.00", "16000.00", "27443.76");
        BigDecimal stockReceiptTotal = BigDecimal.ZERO;
        for (String receipt : stockReceipts) {
            BigDecimal amount = new BigDecimal(receipt);
            stockReceiptTotal = stockReceiptTotal.add(amount);
            createFullyStockedTwoXRecord(salesRepId, amount);
        }
        assertThat(stockReceiptTotal).isEqualByComparingTo("123443.76");

        // Live-estimate path (simulate(), which reads sumActiveWeightedActualReceived's broader
        // NOT IN ('VOID','REJECTED') filter — every record here is SUBMITTED, so this already sees
        // all eight).
        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);
        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo("559711.64");
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo("2322.84");

        // Real payroll path (APPROVED-only, computeRepPayrollCommissions / payrollCommissionTotalsByEmployee)
        // must reach the IDENTICAL figure — this is the number that actually gets paid.
        for (long id : allCommissionIdsFor(salesRepId)) {
            commissionService.approve(id, salesManager);
            commissionService.approve(id, ceoActor);
        }
        Map<Long, BigDecimal> totals = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);
        assertThat(totals.get(salesRepId)).isEqualByComparingTo("2322.84");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Historical immutability — the regression that would cost real money
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * An EXISTING (pre-V148-shaped) APPROVED record with {@code weight_multiplier = 2} and {@code
     * effective_weight_multiplier = NULL} (seeded via the pre-V148 6-arg {@code
     * createCommissionRecord} overload, exactly how every commission record in production looks
     * today) must still contribute DOUBLE to the weighted base — even though its linked ticket's
     * item sits at the column DEFAULTS ({@code weight_multiplier = 1}, {@code qty_from_stock = 0})
     * because this ticket was never touched by the new per-item feature. If payroll ever derived
     * weight from items instead of falling back to the frozen-or-plain {@code weightMultiplier},
     * this record would silently drop from 2x to 1x and already-approved commission would change —
     * exactly the failure mode CLAUDE.md names for this task.
     */
    @Test
    void historicalRecord_weightMultiplierTwo_defaultWeightItem_stillContributesDouble() {
        long salesRepId = ownerId;
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("999.0000"));
        // Deliberately NO reserveStock, NO setItemWeightMultipliers — this ticket's item stays at
        // the column defaults (qty_from_stock = 0, weight_multiplier = 1), exactly as a deal that
        // predates this feature and was never revisited would look.

        SubmitCommissionRequest request = zeroDeductionRequest(ticketId, salesRepId, new BigDecimal("100000.00"));
        InvoiceCalculation calculation = calculateInvoice(request);
        long invoiceId = commissions.createInvoice(request);
        // The PRE-V148 6-arg overload — effective_weight_multiplier is NULL, exactly like a real
        // production row created before this migration.
        long commissionId = commissions.createCommissionRecord(
            invoiceId, ticketId, salesRepId, salesRepId, PAYROLL_MONTH, calculation);
        // weight_multiplier = 2 set through the real, UNCHANGED manager-review path — matching how
        // a real historical 2x-weighted record was actually created.
        UpdateCommissionDeductionsRequest setWeight = new UpdateCommissionDeductionsRequest(
            null, null, null, null, null, null, null, null, 2,
            "historical-immutability test: apply the pre-V148 record-level weighting");
        commissionService.updateDeductions(commissionId, setWeight, salesManager);
        commissionService.approve(commissionId, salesManager);
        commissionService.approve(commissionId, ceoActor);

        CommissionRecord stored = commissions.findById(commissionId).orElseThrow();
        assertThat(stored.effectiveWeightMultiplier()).isNull();
        assertThat(stored.weightMultiplier()).isEqualTo(2);
        assertThat(stored.effectiveWeight()).isEqualByComparingTo("2");

        BigDecimal weighted = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        assertThat(weighted).isEqualByComparingTo("200000.00"); // 100,000 x 2, never x 1.

        BigDecimal expectedBase = calculator.monthlyTierBase(new BigDecimal("200000.00"));
        BigDecimal expectedCommission = calculator.progressiveCommission(expectedBase);
        Map<Long, BigDecimal> totals = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);
        assertThat(totals.get(salesRepId)).isEqualByComparingTo(expectedCommission);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Reviewer finding (2026-08-16): the manager's record-level override must still reach real
    // payroll money on a record created through the REAL production paths, not a hand-built
    // fixture that bypasses CommissionCalculator#itemDerivedWeight entirely. Both #submit and
    // #createFromDeal call the freeze (see CommissionService:141-142 and :297-298 respectively —
    // createFromDeal calls calculator.itemDerivedWeight directly rather than through the
    // computeItemDerivedWeight wrapper, since it already has the ticket loaded; a grep for the
    // wrapper's name alone would miss this call site).
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Drives the REAL {@link CommissionService#submit} — including a real invoice file upload
     * through real {@link FileStorageService}/{@link CommissionAttachmentRepository}/{@link
     * AttachmentRepository} (mocking any of those while {@code commissions} stays real risks a
     * real foreign-key violation on {@code invoice_details.invoice_attachment_id} — see V35) —
     * against an ORDINARY ticket: one item, fully priced, at the column DEFAULT weight (1x), no
     * stock declared. This is the overwhelmingly common case for a real deal.
     *
     * <p>Before the fix, {@code submit} would freeze a non-null {@code 1.000000} onto this exact
     * record (the defect {@link CommissionCalculator#itemDerivedWeight}'s Javadoc now documents in
     * full), and the manager's {@code weightMultiplier} override below would silently do nothing —
     * this test would have gone red without the fix, proving the fix on the path that actually
     * ships, not a fixture that already assumed the answer.
     */
    @Test
    void managersRecordLevelOverride_stillReachesPayroll_onARecordCreatedThroughRealSubmit() {
        long salesRepId = ownerId;
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("500.0000"));
        // Deliberately UNTOUCHED — no reserveStock, no setItemWeightMultipliers. submit() requires
        // CLOSED_PAID when sourceTicketId is set (resolveDealLinkage) — set directly, the same way
        // createSingleItemTicketAtOrderReceived already forces ORDER_RECEIVED, since this test is
        // about the commission freeze, not the deal-stage pipeline.
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);

        CommissionService realSubmitService = realCommissionService();
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-OVERRIDE-" + UUID.randomUUID(), INVOICE_DATE,
            new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        // submit()'s SUBMIT_ROLES is {account, sales_manager, ceo} -- "sales" was removed from it
        // entirely by Slice A2 (commission creation moved to the accountant's auto-create trigger).
        // salesRepId is passed explicitly in the request either way, so this attributes the
        // commission to the deal owner regardless of who submits it.
        CommissionRecord created = realSubmitService.submit(request, invoiceFile(), salesManager);

        // The freeze must NOT have captured a redundant, control-defeating 1.000000.
        assertThat(created.effectiveWeightMultiplier()).isNull();

        UpdateCommissionDeductionsRequest setWeight = new UpdateCommissionDeductionsRequest(
            null, null, null, null, null, null, null, null, 2,
            "manager applies the record-level override -- this ticket carries no per-item signal");
        realSubmitService.updateDeductions(created.id(), setWeight, salesManager);
        realSubmitService.approve(created.id(), salesManager);
        realSubmitService.approve(created.id(), ceoActor);

        LocalDate realPayrollMonth = INVOICE_DATE.withDayOfMonth(1).plusMonths(1); // FLAG-10, M+1
        BigDecimal weighted = commissions.sumActiveWeightedActualReceived(salesRepId, realPayrollMonth);
        assertThat(weighted).isEqualByComparingTo("200000.00"); // 100,000 x 2, not x 1.
        BigDecimal expectedCommission = calculator.progressiveCommission(calculator.monthlyTierBase(new BigDecimal("200000.00")));
        Map<Long, BigDecimal> totals = realSubmitService.payrollCommissionTotalsByEmployee(realPayrollMonth);
        assertThat(totals.get(salesRepId)).isEqualByComparingTo(expectedCommission);
    }

    /** Same proof, through {@link CommissionService#createFromDeal} — the second real creation
     * path, and the one a reviewer could not find calling the freeze via a name-only grep for
     * {@code computeItemDerivedWeight} (it calls {@code calculator.itemDerivedWeight} directly).
     * Closes that out with a positive result on the real path, not just a code-reading rebuttal. */
    @Test
    void managersRecordLevelOverride_stillReachesPayroll_onARecordCreatedThroughRealCreateFromDeal() {
        long salesRepId = ownerId; // createFromDeal always attributes to the ticket's own owner.
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("500.0000"));
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);

        CommissionService realService = realCommissionService();
        CommissionRecord created = realService.createFromDeal(
            ticketId, "INV-OVERRIDE-DEAL-" + UUID.randomUUID(), INVOICE_DATE,
            new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            invoiceFile(), accountActor());

        assertThat(created.effectiveWeightMultiplier()).isNull();

        UpdateCommissionDeductionsRequest setWeight = new UpdateCommissionDeductionsRequest(
            null, null, null, null, null, null, null, null, 3,
            "manager applies the record-level override -- this ticket carries no per-item signal");
        realService.updateDeductions(created.id(), setWeight, salesManager);
        realService.approve(created.id(), salesManager);
        realService.approve(created.id(), ceoActor);

        LocalDate realPayrollMonth = INVOICE_DATE.withDayOfMonth(1).plusMonths(1);
        BigDecimal weighted = commissions.sumActiveWeightedActualReceived(salesRepId, realPayrollMonth);
        assertThat(weighted).isEqualByComparingTo("300000.00"); // 100,000 x 3, not x 1.
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Wrong-way-round
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * An item with {@code qty_from_stock = 0} (nothing sourced from stock) must contribute
     * effectiveWeight = 1 EVEN THOUGH its {@code weight_multiplier} is stored at 3 — only
     * STOCK-sourced quantity ever earns credit. This is exactly the owner's own workbook case: a
     * row marked {@code *3} that was actually sourced against an import request, correctly NOT
     * credited.
     */
    @Test
    void itemWithZeroStockShare_storedThreeXMultiplier_contributesOnlyOneX() {
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("500.0000"));
        long itemId = onlyItemId(ticketId);
        ticketService.setItemWeightMultipliers(ticketId,
            new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, 3))), salesManager);
        // Deliberately no reserveStock call — qty_from_stock stays at the column default of 0.

        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items.get(0).weightMultiplier()).isEqualTo(3);
        assertThat(items.get(0).qtyFromStock()).isEqualByComparingTo("0.00");

        // Reviewer finding (2026-08-16): the blend is exactly 1 here (no genuine stock-earned
        // credit), so the real ticket state produces EMPTY, not a frozen 1.000000 -- see
        // CommissionCalculator#itemDerivedWeight's own Javadoc for the dead-control defect this
        // prevents (a non-null frozen 1.000000 would permanently defeat the manager's
        // record-level weightMultiplier override for this record).
        Optional<BigDecimal> weight = calculator.itemDerivedWeight(toInputs(items), new BigDecimal("100000.00"));
        assertThat(weight).isEmpty();
    }

    /**
     * Partial stock coverage yields the BLENDED weight, never the full item multiplier — a
     * half-stock line at x2 earns 1.5, not 2 (the brief's own worked example).
     */
    @Test
    void itemHalfCoveredFromStock_twoXMultiplier_yieldsOneAndAHalf() {
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("100.00"), new BigDecimal("500.0000"));
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(new StockReservationRequest.Line(itemId, new BigDecimal("50.00"), null))),
            owner);
        ticketService.setItemWeightMultipliers(ticketId,
            new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, 2))), salesManager);

        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        Optional<BigDecimal> weight = calculator.itemDerivedWeight(toInputs(items), new BigDecimal("100000.00"));
        assertThat(weight).isPresent();
        assertThat(weight.get()).isEqualByComparingTo("1.5");
    }

    /**
     * Unapproved money must never reach a payroll figure — the same invariant {@link
     * CommissionRepository#sumActiveStockActualReceived}'s own Javadoc records a real defect
     * against. Honoured here for the WEIGHTED base too: a SUBMITTED (never approved) record with a
     * frozen 2x-equivalent {@code effective_weight_multiplier} must be completely excluded from
     * {@link CommissionService#payrollCommissionTotalsByEmployee}, which reads only {@link
     * CommissionRepository#findApprovedRecordsByMonth}'s {@code status = 'APPROVED'} rows —
     * unchanged by this feature. The broader live-estimate path ({@link
     * CommissionRepository#sumActiveWeightedActualReceived}) DOES see it, unchanged behaviour from
     * before this feature — proving the payroll exclusion above is specifically the APPROVED-only
     * discipline, not a blanket "the frozen weight breaks visibility" bug.
     */
    @Test
    void submittedRecord_withFrozenItemWeight_neverReachesPayroll_untilApproved() {
        long salesRepId = ownerId;
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("500.0000"));
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(new StockReservationRequest.Line(itemId, new BigDecimal("10.00"), null))),
            owner);
        ticketService.setItemWeightMultipliers(ticketId,
            new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, 2))), salesManager);

        SubmitCommissionRequest request = zeroDeductionRequest(ticketId, salesRepId, new BigDecimal("50000.00"));
        InvoiceCalculation calculation = calculateInvoice(request);
        Optional<BigDecimal> effectiveWeight = calculator.itemDerivedWeight(
            toInputs(tickets.findById(ticketId).orElseThrow().items()), calculation.actualReceived());
        assertThat(effectiveWeight).isPresent();
        assertThat(effectiveWeight.get()).isEqualByComparingTo("2");
        long invoiceId = commissions.createInvoice(request);
        commissions.createCommissionRecord(invoiceId, ticketId, salesRepId, salesRepId, PAYROLL_MONTH,
            calculation, null, false, effectiveWeight.orElse(null));

        // Still SUBMITTED — never approved.
        Map<Long, BigDecimal> totalsBeforeApproval = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);
        assertThat(totalsBeforeApproval.get(salesRepId)).isNull();

        BigDecimal weighted = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        assertThat(weighted).isEqualByComparingTo("100000.00"); // 50,000 x 2 — visible to the live estimate.
    }

    /**
     * V148 extension of {@code CommissionCalcRefineIntegrationTest
     * #clawback_preservesOriginalsWeightMultiplier_soTheReversalMatchesTheOriginalContribution}: a
     * clawback of a record whose weight came from PER-ITEM blending (not the plain
     * weight_multiplier fallback) must copy the FROZEN {@code effective_weight_multiplier}
     * verbatim too, or the reversal would under-cancel the original's weighted contribution.
     */
    @Test
    void clawback_preservesFrozenItemDerivedWeight_soTheReversalMatchesExactly() {
        long salesRepId = ownerId;
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("500.0000"));
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(new StockReservationRequest.Line(itemId, new BigDecimal("10.00"), null))),
            owner);
        ticketService.setItemWeightMultipliers(ticketId,
            new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, 2))), salesManager);

        SubmitCommissionRequest request = zeroDeductionRequest(ticketId, salesRepId, new BigDecimal("100000.00"));
        InvoiceCalculation calculation = calculateInvoice(request);
        Optional<BigDecimal> effectiveWeight = calculator.itemDerivedWeight(
            toInputs(tickets.findById(ticketId).orElseThrow().items()), calculation.actualReceived());
        long invoiceId = commissions.createInvoice(request);
        long commissionId = commissions.createCommissionRecord(invoiceId, ticketId, salesRepId, salesRepId,
            PAYROLL_MONTH, calculation, null, false, effectiveWeight.orElse(null));
        commissionService.approve(commissionId, salesManager);
        commissionService.approve(commissionId, ceoActor);

        BigDecimal weightedBeforeClawback = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        assertThat(weightedBeforeClawback).isEqualByComparingTo("200000.00"); // 100,000 x 2

        CommissionRecord original = commissions.findById(commissionId).orElseThrow();
        assertThat(original.effectiveWeightMultiplier()).isNotNull();
        long clawbackId = commissions.createClawback(original, salesManager.id(), PAYROLL_MONTH, "test clawback");
        CommissionRecord clawback = commissions.findById(clawbackId).orElseThrow();

        assertThat(clawback.effectiveWeightMultiplier()).isEqualByComparingTo(original.effectiveWeightMultiplier());

        BigDecimal weightedAfterClawback = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        assertThat(weightedAfterClawback).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A {@link CommissionService} wired for a REAL {@code submit}/{@code createFromDeal} call —
     * every collaborator on the file-storage/attachment chain is real, not mocked, because {@code
     * commissions} (this class's shared {@link CommissionRepository}) is real: {@code
     * invoice_details.invoice_attachment_id} carries a plain (non-deferrable) foreign key to
     * {@code hr.file_attachment} (V35), so a MOCKED {@link CommissionAttachmentRepository}
     * returning a fabricated id would fail that constraint the moment {@link
     * CommissionRepository#attachInvoiceFile} runs for real. Mirrors {@code
     * CommissionAutoCreateIntegrationTest}'s own wiring for the identical reason. {@link
     * AuditService}/{@link NotificationService} stay mocked — side effects of an already-decided
     * outcome, not the money path under test.
     */
    private CommissionService realCommissionService() {
        return new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            calculator,
            new FileStorageService("/tmp/glr-item-weight-override-test-uploads"),
            mock(AuditService.class),
            mock(NotificationService.class),
            tickets,
            new AttachmentRepository(jdbc));
    }

    private MultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf-bytes".getBytes());
    }

    private UserPrincipal accountActor() {
        long accountId = createEmployee("ฝ่ายบัญชี น้ำหนักรายการ", "account-itemweight@glr.co.th", "AC", "แผนกบัญชี");
        return principal(accountId, "account");
    }

    /** Creates a fully stock-covered, x2-weighted, single-item ticket and its SUBMITTED
     * commission record — the repeating unit the reconciliation test sums seven of. Asserts the
     * "single fully-stock item at x2 -> effectiveWeight == 2 exactly" identity inline before ever
     * writing the commission row, so a failure here points straight at the formula, not the
     * aggregation. */
    private void createFullyStockedTwoXRecord(long salesRepId, BigDecimal actualReceived) {
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("500.0000"));
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(new StockReservationRequest.Line(itemId, new BigDecimal("10.00"), null))),
            owner);
        ticketService.setItemWeightMultipliers(ticketId,
            new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, 2))), salesManager);

        SubmitCommissionRequest request = zeroDeductionRequest(ticketId, salesRepId, actualReceived);
        InvoiceCalculation calculation = calculateInvoice(request);
        Optional<BigDecimal> effectiveWeight = calculator.itemDerivedWeight(
            toInputs(tickets.findById(ticketId).orElseThrow().items()), calculation.actualReceived());
        assertThat(effectiveWeight).isPresent();
        assertThat(effectiveWeight.get()).isEqualByComparingTo("2");

        long invoiceId = commissions.createInvoice(request);
        commissions.createCommissionRecord(invoiceId, ticketId, salesRepId, salesRepId, PAYROLL_MONTH,
            calculation, null, false, effectiveWeight.orElse(null));
    }

    private void seedUnlinkedRecord(long salesRepId, BigDecimal actualReceived) {
        SubmitCommissionRequest request = zeroDeductionRequest(null, salesRepId, actualReceived);
        InvoiceCalculation calculation = calculateInvoice(request);
        long invoiceId = commissions.createInvoice(request);
        // Unlinked (sourceTicketId = null) — 6-arg overload, effectiveWeightMultiplier stays NULL,
        // falls back to the column default weight_multiplier = 1 via COALESCE, exactly like every
        // pre-V148 unlinked record already does.
        commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, PAYROLL_MONTH, calculation);
    }

    private InvoiceCalculation calculateInvoice(SubmitCommissionRequest request) {
        return calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
    }

    private SubmitCommissionRequest zeroDeductionRequest(Long ticketId, long salesRepId, BigDecimal grossAmount) {
        return new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-ITEMWEIGHT-" + UUID.randomUUID(), INVOICE_DATE, grossAmount,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private List<CommissionCalculator.ItemStockWeightInput> toInputs(List<TicketItemDto> items) {
        return items.stream()
            .map(item -> new CommissionCalculator.ItemStockWeightInput(
                item.qty(), item.qtyFromStock(), item.approvedPrice(), item.proposedPrice(), item.weightMultiplier()))
            .toList();
    }

    /** A deal parked at {@link DealStage#ORDER_RECEIVED} (the stock-declaration stage floor) with
     * exactly one item, at the given qty/proposedPrice — mirrors {@code
     * StockDeclarationAuthzIntegrationTest#createTicketWithOneItem}. */
    private long createSingleItemTicketAtOrderReceived(BigDecimal qty, BigDecimal proposedPrice) {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบถ่วงน้ำหนัก", "NORMAL", "ลูกค้าทดสอบถ่วงน้ำหนัก", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                qty, null, "PIECE", null, null, null, proposedPrice, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ถ่วงน้ำหนัก");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        return ticketId;
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private List<Long> allCommissionIdsFor(long salesRepId) {
        return commissions.findRecords(salesRepId, PAYROLL_MONTH).stream().map(CommissionRecord::id).toList();
    }

    private CommissionSimulationDto simulateNoAdditionalReceipt(long salesRepId) {
        CommissionSimulatorRequest request = new CommissionSimulatorRequest(
            salesRepId, PAYROLL_MONTH, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return commissionService.simulate(request, principal(salesRepId, "sales"));
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-itemweight@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
