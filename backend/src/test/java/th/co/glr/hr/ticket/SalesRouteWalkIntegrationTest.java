package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Real-Postgres proof that the owner's real sales routes are actually WALKABLE, end to end, through the REAL
 * TicketService — not merely that sales_stage accepts every value in DealStage#ORDER. A symptom is only where
 * a broken route happened to be noticed first. */
class SalesRouteWalkIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private UserPrincipal accountActor;
    /** Per-line stock declaration is CEO-only; the from-stock jump below is driven by this actor. */
    private UserPrincipal ceo;
    /** V184: canWriteDelivery transferred stages 13-14 to {ceo, owning-rep} only, so every
     * completeDelivery/recordPartialDelivery call below uses #ownerRep (every deal here is created with
     * ownerRepId) rather than this actor — see DeliveryAuthzIntegrationTest for the dedicated authz pin. */
    private UserPrincipal importActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "พนักงานขาย เส้นทางดีล", "routewalk-owner@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        ceo = principal(createEmployee(employees, "ซีอีโอ เส้นทางดีล", "routewalk-ceo@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "ฝ่ายบัญชี เส้นทางดีล", "routewalk-account@glr.co.th"), "account");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า เส้นทางดีล", "routewalk-import@glr.co.th"), "import");
    }

    // ═══ Route A — designer -> owner -> contractor, the full spine ═══════════

    /** > S2 -> S4 -> S3 -> S5 -> S6 -> S7 -> S8 -> S9 -> S10 -> S11 -> S12…S17 -> S18 -> S19 -> S20. Every stage in
     * DealStage#ORDER is visited once, including the one routine backward pair (S4 -> S3). */
    @Test
    void routeA_designerOwnerContractor_fullSpine() {
        long ticketId = createOneItemDeal("ดีล A: ครบทุกขั้นตอน", "100.00", "1000.00"); // payable 100,000
        setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);

        advanceTo(ticketId, DealStage.PRESENTATION, null);
        advanceTo(ticketId, DealStage.QUOTE_DESIGN_SIDE, null);
        advanceTo(ticketId, DealStage.SPEC_APPROVED, null);      // S4 -> S3, routine backward
        advanceTo(ticketId, DealStage.QUOTE_OWNER, null);
        advanceTo(ticketId, DealStage.OWNER_SIGNOFF, null);
        advanceTo(ticketId, DealStage.AWAITING_BUYER, null);
        advanceTo(ticketId, DealStage.QUOTE_BUYER, null);
        advanceTo(ticketId, DealStage.NEGOTIATION, null);

        ticketService.confirmCustomer(ticketId, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.confirmDepositPaid(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DEPOSIT_RECEIVED);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_PAID);

        walkImportJourneyToDeliveryScheduling(ticketId); // PROCUREMENT -> ... -> DELIVERY_SCHEDULING

        ticketService.completeDelivery(ticketId,
            new CompleteDeliveryRequest("ส่งครบตามสัญญา", "คุณเจ้าของโครงการ"), ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        ticketService.confirmFinalPayment(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        // Issue #738 / #736: the served fields still hold on a fully walked deal — 100% at CLOSED_PAID (not a stale
        // override), and commissionRecorded honestly false since this walk never touched CommissionService.
        TicketSummaryDto finalState = ticketService.get(ticketId, ownerRep).summary();
        assertThat(finalState.effectiveWinProbability()).isEqualTo(100);
        assertThat(finalState.commissionRecorded()).isFalse();
    }

    // ═══ Route B — the owner buys directly ════════════════════════════════════

    /** Owner-direct: S3 (re-worded "the owner agreed the spec") IS visited; S4 is the only stage the route excludes
     * and a manual move into it is refused. S7/S8 are on-route but this walk takes the O-b branch (the owner buys
     * themselves) straight from S6 to S9. */
    @Test
    void routeB_ownerBuysDirect_keepsSpecApproval_andExcludesOnlyTheDesignerQuote() {
        long ticketId = createOneItemDeal("ดีล B: เจ้าของซื้อตรง", "100.00", "800.00"); // payable 80,000
        setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        ticketService.setEntryChannel(ticketId, EntryChannel.OWNER_DIRECT, null, ownerRep);
        assertThat(entryChannelOf(ticketId)).isEqualTo(EntryChannel.OWNER_DIRECT);

        advanceTo(ticketId, DealStage.PRESENTATION, null);
        // The route's defining refusal, through the real gate: no designer to quote.
        logAnActivityAndFollowUp(ticketId);
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.QUOTE_DESIGN_SIDE, null, ownerRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);

        advanceTo(ticketId, DealStage.SPEC_APPROVED, null); // S3 — kept, only re-worded
        advanceTo(ticketId, DealStage.QUOTE_OWNER, null);
        advanceTo(ticketId, DealStage.OWNER_SIGNOFF, null);
        advanceTo(ticketId, DealStage.NEGOTIATION, null);   // O-b: skips S7 + S8 (on-route, not visited)

        ticketService.confirmCustomer(ticketId, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.confirmDepositPaid(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DEPOSIT_RECEIVED);

        walkImportJourneyToDeliveryScheduling(ticketId);

        ticketService.completeDelivery(ticketId,
            new CompleteDeliveryRequest("ส่งครบ", "เจ้าของโครงการ"), ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);

        ticketService.confirmFinalPayment(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        // S3 happened exactly once — the reversal of the old "S3 never happens" assertion.
        assertThat(stageChangedToCount(ticketId, DealStage.SPEC_APPROVED)).isEqualTo(1);
        // The route's only exclusion never happened, and neither did the two O-b skips.
        assertStageNeverVisited(ticketId, DealStage.QUOTE_DESIGN_SIDE,
            DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER);
    }

    // ═══ Route C — a contractor arrives with a BOQ ════════════════════════════

    /** The deal OPENS at DealStage#QUOTE_BUYER (S8), skipping S1-S7 entirely — the first stage move ever made on
     * this deal lands on S8. Also the BUYER_DIRECT entry channel: route C IS that channel. */
    @Test
    void routeC_contractorWithBoq_opensAtQuoteBuyer() {
        long ticketId = createOneItemDeal("ดีล C: ผู้รับเหมามี BOQ", "50.00", "2000.00"); // payable 100,000
        setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);

        ticketService.setEntryChannel(ticketId, EntryChannel.BUYER_DIRECT,
            "ผู้รับเหมานำ BOQ และสเปกมาเองตั้งแต่ต้น", ownerRep);
        assertThat(entryChannelOf(ticketId)).isEqualTo(EntryChannel.BUYER_DIRECT);

        // The deal opens at S8 by QUOTING THE CONTRACTOR (owner ruling 2026-10-16,
        // .design/quote-stage-gates D-2) — a manual updateStage into ขั้น 8 is now refused until
        // that document exists, which is exactly the bypass the ruling closes. The route itself is
        // unchanged: still S1 straight to S8, skipping S2-S7.
        //
        // ⚠️ Coverage moved, not lost. This block also used to assert the NOTE rule for the long
        // jump ("crosses only route-dependent stages, none MANDATORY, so no note is required —
        // asserted, not assumed"). The document-driven advance never consults the note rule, so it
        // cannot assert that here any more. DealStageQuoteOwnerAndRouteIntegrationTest
        // #caseC_leadApproachStraightToQuoteBuyer_needsNoNote is where that rule is pinned now,
        // with a quotation fixture in place so the manual call is reachable.
        ticketService.advanceStageForCustomerQuotationIssue(ticketId, QuotationRecipient.BUYER, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_BUYER);

        advanceTo(ticketId, DealStage.NEGOTIATION, null);

        ticketService.confirmCustomer(ticketId, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.confirmDepositPaid(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DEPOSIT_RECEIVED);

        walkImportJourneyToDeliveryScheduling(ticketId);

        ticketService.completeDelivery(ticketId,
            new CompleteDeliveryRequest("ส่งครบ", "ผู้รับเหมา"), ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);

        ticketService.confirmFinalPayment(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        // The defining property: S1-S7 never appear as a STAGE_CHANGED target anywhere in this deal's history — its
        // very first recorded stage move landed on QUOTE_BUYER.
        assertStageNeverVisited(ticketId, DealStage.LEAD_APPROACH, DealStage.PRESENTATION,
            DealStage.SPEC_APPROVED, DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
            DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER);
    }

    // ═══ Route D — everything already in stock ════════════════════════════════

    /** reserveStock's full-coverage branch jumps the deal straight from DealStage#DEPOSIT_RECEIVED to
     * DealStage#DELIVERY_SCHEDULING — DealStage#PROCUREMENT (S12-S17) is never visited. */
    @Test
    void routeD_allFromStock_skipsProcurement() {
        long ticketId = createOneItemDeal("ดีล D: ของพร้อมจากสต็อกทั้งหมด", "100.00", "500.00"); // payable 50,000
        setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        long itemId = onlyItemId(ticketId);

        advanceTo(ticketId, DealStage.PRESENTATION, null);
        advanceTo(ticketId, DealStage.QUOTE_OWNER, null);
        advanceTo(ticketId, DealStage.OWNER_SIGNOFF, null);
        advanceTo(ticketId, DealStage.NEGOTIATION, null);

        ticketService.confirmCustomer(ticketId, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.confirmDepositPaid(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DEPOSIT_RECEIVED);

        // Full coverage on the deal's only line: jumps straight over PROCUREMENT.
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(itemId, new BigDecimal("100.00"), "มีของครบในสต็อก"))),
            ceo);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);

        ticketService.completeDelivery(ticketId,
            new CompleteDeliveryRequest("ส่งจากสต็อก", "ลูกค้า"), ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        ticketService.confirmFinalPayment(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        // The defining property: PROCUREMENT never appears as a STAGE_CHANGED target.
        assertStageNeverVisited(ticketId, DealStage.PROCUREMENT);
    }

    // ═══ Route E — partial stock, split per line item ═════════════════════════

    /** A two-item deal: item 1 (40 pcs) is declared fully from stock, item 2 (60 pcs) is genuinely imported. */
    @Test
    void routeE_partialStock_splitsPerLineItem() {
        long ticketId = createTwoItemDeal("ดีล E: สต็อกบางส่วน",
            "40.00", "1000.00",    // item 1: 40 pcs @ 1,000 = 40,000, fully from stock
            "60.00", "1000.00");   // item 2: 60 pcs @ 1,000 = 60,000, imported
        setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(2);
        long item1 = items.get(0).id();
        long item2 = items.get(1).id();

        advanceTo(ticketId, DealStage.PRESENTATION, null);
        advanceTo(ticketId, DealStage.QUOTE_OWNER, null);
        advanceTo(ticketId, DealStage.OWNER_SIGNOFF, null);
        advanceTo(ticketId, DealStage.NEGOTIATION, null);

        ticketService.confirmCustomer(ticketId, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.confirmDepositPaid(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DEPOSIT_RECEIVED);

        // Declare item 1 fully covered from stock; item 2 is left untouched (0). Coverage is PARTIAL, so this must NOT
        // auto-advance the stage or set FROM_STOCK.
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(item1, new BigDecimal("40.00"), "มีของครบในสต็อก"))),
            ceo);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DEPOSIT_RECEIVED);
        assertThat(fulfilmentOf(ticketId)).isNull();
        assertThat(qtyFromStockOf(item1)).isEqualByComparingTo("40.00");
        assertThat(qtyFromStockOf(item2)).isEqualByComparingTo("0.00");

        // fulfillment_status is still null (the partial declaration above did not set it), so the rest is procured for
        // real — same journey as routes A/B/C.
        walkImportJourneyToDeliveryScheduling(ticketId);

        // Deliver item 1 from stock first ...
        ticketService.recordPartialDelivery(ticketId,
            new RecordDeliveryRequest("STOCK", "ส่งจากสต็อกก่อน",
                List.of(new RecordDeliveryRequest.Line(item1, new BigDecimal("40.00"))), "ลูกค้า"),
            ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.PARTIALLY_DELIVERED);

        // The goods-received event keeps warehouse delivery available after the status becomes partially delivered.
        ticketService.completeDelivery(ticketId,
            new CompleteDeliveryRequest("ส่งส่วนนำเข้าที่เหลือ", "ลูกค้า"), ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        ticketService.confirmFinalPayment(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        // The defining property: BOTH halves of the split really happened, and they differ.
        assertThat(qtyFromStockOf(item1)).isEqualByComparingTo("40.00");
        assertThat(qtyFromStockOf(item2)).isEqualByComparingTo("0.00");
        assertThat(qtyFromStockOf(item1)).isNotEqualByComparingTo(qtyFromStockOf(item2));
    }

    // ═══ Route G — credit after delivery ═══════════════════════════════════════

    /** On CREDIT_CUSTOMER terms PaymentTrack's bypass path has no DEPOSIT_NOTICE_ISSUED/DEPOSIT_PAID state at all,
     * so DealStage#DEPOSIT_RECEIVED is skipped outright — not merely "not required", genuinely unreachable on this
     * policy. The goods go out first; the whole invoice is settled afterward. */
    @Test
    void routeG_creditAfterDelivery_skipsDepositAndPaysAfterDelivery() {
        long ticketId = createOneItemDeal("ดีล G: เครดิตหลังส่งของ", "100.00", "300.00"); // payable 30,000
        setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);

        advanceTo(ticketId, DealStage.PRESENTATION, null);
        advanceTo(ticketId, DealStage.QUOTE_OWNER, null);
        advanceTo(ticketId, DealStage.OWNER_SIGNOFF, null);
        advanceTo(ticketId, DealStage.NEGOTIATION, null);

        // Credit terms, decided before the order is confirmed: payment_status is still null here, so waiveDeposit's
        // own "no deposit invoice issued yet" guard holds.
        ticketService.waiveDeposit(ticketId, DepositPolicy.CREDIT_CUSTOMER,
            "ลูกค้าเครดิต 30 วัน ตามสัญญาก่อสร้าง", ownerRep);
        assertThat(depositPolicyOf(ticketId)).isEqualTo(DepositPolicy.CREDIT_CUSTOMER);

        ticketService.confirmCustomer(ticketId, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        // No deposit notice, no confirmDepositPaid: PaymentTrack.BYPASS_ALLOWED has no entry for
        // DEPOSIT_NOTICE_ISSUED/DEPOSIT_PAID at all. issueImportRequest's bypass clause (bypassesDepositNotice &&
        // CUSTOMER_CONFIRMED) is what actually lets this proceed.
        walkImportJourneyToDeliveryScheduling(ticketId);

        ticketService.completeDelivery(ticketId,
            new CompleteDeliveryRequest("ส่งของก่อนตามเครดิต", "ลูกค้า"), ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        // DELIVERED precedes CLOSED_PAID: the deal is not fully paid yet at this point.
        assertThat(paymentStatusOf(ticketId)).isNotEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(stageOf(ticketId)).isNotEqualTo(DealStage.CLOSED_PAID);

        ticketService.confirmFinalPayment(ticketId, accountActor);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        // The defining property: DEPOSIT_RECEIVED is skipped entirely.
        assertStageNeverVisited(ticketId, DealStage.DEPOSIT_RECEIVED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A plain forward (or, for the one routine pair, backward) hop through the ungated front
     * half of the pipeline: seeds the tracking gate's own preconditions (a next follow-up date and
     * one activity logged since the last stage change — {@code updateStage}'s forward-readiness
     * gate demands both) and then calls the real {@link TicketService#updateStage}, asserting the
     * landing stage so every route test reads as a clean list of hops.
     *
     * <p><b>The three quote stages are reached by QUOTING that party, not by a manual hop</b>
     * (owner ruling 2026-10-16, {@code .design/quote-stage-gates} D-2/D-3):
     * {@code requireStageFactsHold} refuses a manual {@code updateStage} into ขั้น 4/5/8 until the
     * deal holds a quotation addressed to that recipient, and
     * {@link TicketService#advanceStageForCustomerQuotationIssue} is the document-driven advance
     * that production actually uses. Routing them here rather than editing each route test is the
     * point: this suite's claim is that the owner's REAL routes are walkable the way the business
     * walks them, and the business reaches a quote stage by issuing the quotation. Every route
     * below therefore keeps reading as a clean list of hops, with no per-route fixture.
     *
     * <p>The note is deliberately rejected for those three rather than silently dropped: the
     * document-driven advance never consults the note rule, so passing one here would read as
     * tested when nothing had checked it. No caller does — every quote-stage hop in this file
     * passes {@code null} — and this keeps that true.
     */
    private void advanceTo(long ticketId, String stage, String note) {
        String recipient = TicketService.quotationRecipientForStage(stage);
        if (recipient != null) {
            assertThat(note).as("a quote stage arrives from its quotation, so %s cannot carry a note", stage)
                .isNull();
            ticketService.advanceStageForCustomerQuotationIssue(ticketId, recipient, ownerRep);
        } else {
            logAnActivityAndFollowUp(ticketId);
            ticketService.updateStage(ticketId, stage, note, ownerRep);
        }
        assertThat(stageOf(ticketId)).as("expected to land on %s", stage).isEqualTo(stage);
    }

    /** The real IR -> warehouse journey — issueImportRequest, markIrSent, markShipping, markGoodsReceived — so that
     * a later completeDelivery may legitimately source from WAREHOUSE. */
    private void walkImportJourneyToDeliveryScheduling(long ticketId) {
        ticketService.recordFirstImportRequestIssued(ticketId, 0L, importActor); // pre-V184 shape: IR_ISSUED, no rows
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PROCUREMENT);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.IR_ISSUED);

        ticketService.markIrSent(ticketId, importActor);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.IR_SENT);

        ticketService.markShipping(ticketId, importActor);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.SHIPPING);

        ticketService.markGoodsReceived(ticketId, importActor);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.GOODS_RECEIVED);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    private void logAnActivityAndFollowUp(long ticketId) {
        ticketService.updateTracking(ticketId,
            new TrackingUpdateRequest(null, null, null, null, LocalDate.now().plusDays(3)), ownerRep);
        ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null), ownerRep);
    }

    /** Every stage in {@code stages} must never appear as a {@code STAGE_CHANGED} target. */
    private void assertStageNeverVisited(long ticketId, String... stages) {
        for (String stage : stages) {
            assertThat(stageChangedToCount(ticketId, stage))
                .as("%s must never appear as a STAGE_CHANGED target for ticket %d", stage, ticketId)
                .isZero();
        }
    }

    private int stageChangedToCount(long ticketId, String stage) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind AND to_status = :stage
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STAGE_CHANGED, "stage", stage),
            Integer.class);
    }

    private long createTicketWithItems(String title, List<TicketItemRequest> items) {
        CreateTicketRequest request = new CreateTicketRequest(
            title, "NORMAL", "ลูกค้าเส้นทางขาย", null, null, null, null, null, items);
        return tickets.create(request, tickets.nextTicketCode(), ownerRepId, "พนักงานขาย เส้นทางดีล");
    }

    private long createOneItemDeal(String title, String qty, String approvedPrice) {
        long ticketId = createTicketWithItems(title, List.of(
            new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal(qty), null, "PIECE", null, null, null, null, "THB")));
        setApprovedPrice(onlyItemId(ticketId), approvedPrice);
        return ticketId;
    }

    private long createTwoItemDeal(String title, String qty1, String price1, String qty2, String price2) {
        long ticketId = createTicketWithItems(title, List.of(
            new TicketItemRequest("Brand", "รุ่น A", null, null, "60x60", "Factory A",
                new BigDecimal(qty1), null, "PIECE", null, null, null, null, "THB"),
            new TicketItemRequest("Brand", "รุ่น B", null, null, "80x80", "Factory B",
                new BigDecimal(qty2), null, "PIECE", null, null, null, null, "THB")));
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        setApprovedPrice(items.get(0).id(), price1);
        setApprovedPrice(items.get(1).id(), price2);
        return ticketId;
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    /** The last fallback of TicketRepository.payableAmount is SUM(approved_price * qty), so this is the smallest
     * honest way to give a deal a payable amount without dragging the whole pricing-request chain into a test
     * about routes. */
    private void setApprovedPrice(long itemId, String price) {
        jdbc.update("UPDATE sales.ticket_item SET approved_price = :price WHERE item_id = :id",
            new MapSqlParameterSource().addValue("price", new BigDecimal(price)).addValue("id", itemId));
    }

    private void setStatus(long ticketId, String status) {
        jdbc.update("UPDATE sales.ticket SET status = :status WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("status", status).addValue("id", ticketId));
    }

    private String stageOf(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String paymentStatusOf(long ticketId) {
        return jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String fulfilmentOf(long ticketId) {
        return jdbc.queryForObject("SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String depositPolicyOf(long ticketId) {
        return jdbc.queryForObject("SELECT deposit_policy FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String entryChannelOf(long ticketId) {
        return jdbc.queryForObject("SELECT entry_channel FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private BigDecimal qtyFromStockOf(long itemId) {
        return jdbc.queryForObject("SELECT qty_from_stock FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", itemId), BigDecimal.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-routewalk@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
