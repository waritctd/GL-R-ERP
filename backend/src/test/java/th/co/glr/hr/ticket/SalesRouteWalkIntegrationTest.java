package th.co.glr.hr.ticket;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
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
