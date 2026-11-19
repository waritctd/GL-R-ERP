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

/**
 * Real-Postgres proof that a deal's stage cannot claim more than the facts support.
 *
 * <p>Two gates, one theme:
 *
 * <ul>
 *   <li><b>The fact gate</b> ({@code TicketService.requireStageFactsHold}): a MANUAL
 *       {@code updateStage} into {@code ORDER_RECEIVED}, {@code DEPOSIT_RECEIVED},
 *       {@code DELIVERED} or {@code CLOSED_PAID} is refused unless the fact behind that stage is
 *       already recorded. Validating stage membership alone would let
 *       {@code LEAD_APPROACH -> CLOSED_PAID} succeed in one call for the price of a note.
 *   <li><b>The stock floor</b> ({@code TicketService.stockCoverageStageReached}): a stock-coverage
 *       declaration is refused below {@code ORDER_RECEIVED}, because it is uncorroborated (there
 *       is no inventory system) yet full coverage reroutes the deal past the whole import journey.
 * </ul>
 *
 * <p><b>Written wrong-way-round.</b> The tests that matter are the refusals, and each re-reads
 * {@code sales.ticket} (stage, payment/fulfilment track, {@code qty_from_stock}) and the
 * {@code sales.ticket_event} log straight out of Postgres afterwards to prove nothing moved — an
 * exception thrown while the write still landed is the failure mode that matters, and Mockito
 * cannot see it. "The stage moves once the fact holds" is necessary but is not the evidence.
 *
 * <p><b>The automatic path is deliberately NOT gated</b>, and {@link
 * #autoAdvanceStillReachesAllFourGatedStagesWhenTheFactBecomesTrue} is the test that proves it:
 * {@code autoAdvanceStage} fires <em>because</em> the fact just became true, so routing it through
 * the gate would be circular and would break every operational advance. That test drives the real
 * service methods end to end and is also the walk for the owner's all-from-stock route.
 *
 * <p><b>MUTATION-CHECK RECORD</b> — see the branch's PR body for the per-gate results (each gate
 * removed in turn, the red set recorded, then restored to a byte-identical file).
 *
 * <p>Modelled on {@link StockDeclarationAuthzIntegrationTest} and
 * {@link DealStageQuoteOwnerAndRouteIntegrationTest}. Note the suite-wide trap documented on
 * {@link AbstractPostgresIntegrationTest}: services are hand-wired with {@code new}, so
 * {@code @Transactional} is inert here and no rollback is ever exercised. Nothing below asserts
 * one — the "unmoved" assertions hold because each guard throws before any write.
 */
class StageFactGateIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private UserPrincipal ceo;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        // PricingRequestService is mocked exactly as in the two sibling classes: nothing exercised here calls it. The
        // stub on cancelOpenForTicket only guards against a future edit routing through markLost/cancel and NPE-ing on
        // Mockito's null default.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "stagegate-owner@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        ceo = principal(createEmployee(employees, "ซีอีโอ", "stagegate-ceo@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "ฝ่ายบัญชี", "stagegate-account@glr.co.th"), "account");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า", "stagegate-import@glr.co.th"), "import");
    }

    /**
     * The headline. S1 to S20 in a single call must not cost merely a note, a follow-up date and
     * one logged activity — the deal's facts must be consulted, and no stage is terminal, so the
     * reverse needs the same care.
     *
     * <p>The note is supplied on purpose: a refusal that only happened <em>without</em> one would
     * be {@code DealStage.requiresJustification} doing the work, not the fact gate. The follow-up
     * date and the activity are seeded too, so the tracking gate cannot be what refuses either.
     */
    @Test
    void leadApproachToClosedPaid_isRefusedEvenWithANote_andTheDealDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        assertThatThrownBy(() ->
            ticketService.updateStage(ticketId, DealStage.CLOSED_PAID, "ลูกค้าจ่ายครบแล้ว", ceo))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("CLOSED_PAID");
            });

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    @Test
    void quoteOwner_withNoQuotationToTheOwner_isRefused_andTheDealDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.QUOTE_DESIGN_SIDE);

        assertThatThrownBy(() ->
            ticketService.updateStage(ticketId, DealStage.QUOTE_OWNER, null, ownerRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** ORDER_RECEIVED claims a verified customer order. The payment track's single entry edge is confirmCustomer's
     * CUSTOMER_CONFIRMED write, so an untouched track is exactly "no order verified". */
    @Test
    void orderReceived_withNoVerifiedCustomerOrder_isRefused_andTheDealDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.NEGOTIATION);
        assertThat(paymentStatusOf(ticketId)).isNull();

        assertThatThrownBy(() ->
            ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, "ลูกค้าสั่งซื้อแล้ว", ownerRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);
        assertThat(paymentStatusOf(ticketId)).isNull();
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** A deposit notice is a document; the money is a separate event (confirmDepositPaid). DEPOSIT_NOTICE_ISSUED is
     * therefore the interesting refusal — the deal is demonstrably in the deposit half of the track and still has
     * received nothing. */
    @Test
    void depositReceived_withOnlyTheNoticeIssued_isRefused_andTheDealDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.ORDER_RECEIVED);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        for (UserPrincipal actor : List.of(accountActor, ceo)) {
            assertThatThrownBy(() -> {
                // H1 lockdown: account reaches updateStage only through the finance entrypoint.
                if ("account".equals(actor.role())) {
                    ticketService.financeUpdateStage(ticketId, DealStage.DEPOSIT_RECEIVED, "รับมัดจำแล้ว", actor);
                } else {
                    ticketService.updateStage(ticketId, DealStage.DEPOSIT_RECEIVED, "รับมัดจำแล้ว", actor);
                }
            })
                .describedAs("%s must not be able to claim a deposit that has not arrived", actor.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** CLOSED_PAID is the revenue-bearing one. AWAITING_FINAL_PAYMENT is the wrong-way-round case worth pinning:
     * the deposit is in and the goods are delivered, so everything except the balance is done — and the balance is
     * exactly what the stage claims. */
    @Test
    void closedPaid_withTheBalanceStillOutstanding_isRefused_andTheDealDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.DELIVERED);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.AWAITING_FINAL_PAYMENT);
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);

        assertThatThrownBy(() ->
            ticketService.financeUpdateStage(ticketId, DealStage.CLOSED_PAID, "ปิดงาน", accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.AWAITING_FINAL_PAYMENT);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** The case an earlier draft of this gate let through, and the reason it now tests BOTH halves: the money is
     * all in, but the customer does not have the goods. */
    @Test
    void closedPaid_fullyPaidButNotFullyDelivered_isRefused_andTheDealDoesNotMove() {
        for (String fulfilment : new String[] {null, FulfilmentStatus.FROM_STOCK,
                FulfilmentStatus.PARTIALLY_DELIVERED, FulfilmentStatus.GOODS_RECEIVED}) {
            long ticketId = readyToAdvanceFrom(DealStage.DELIVERY_SCHEDULING);
            tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.FULLY_PAID);
            if (fulfilment != null) {
                tickets.updateFulfillmentStatus(ticketId, fulfilment);
            }

            assertThatThrownBy(() ->
                ticketService.financeUpdateStage(ticketId, DealStage.CLOSED_PAID, "รับเงินครบแล้ว", accountActor))
                .describedAs("paid in full with fulfilment=%s must not reach CLOSED_PAID", fulfilment)
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

            assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
            assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
            assertThat(fulfilmentOf(ticketId)).isEqualTo(fulfilment);
            assertThat(stageChangedEvents(ticketId)).isZero();
        }
    }

    /** …and the manual gate now matches TicketService maybeAdvanceClosedPaid exactly: with delivery completed as
     * well, the move is accepted. The facts are then the only thing that can be permitting this, which is what the
     * test is for. */
    @Test
    void closedPaid_isAcceptedOnceBothPaymentAndDeliveryAreComplete() {
        long ticketId = readyToAdvanceFrom(DealStage.DELIVERED);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.FULLY_PAID);
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);

        ticketService.financeUpdateStage(ticketId, DealStage.CLOSED_PAID, null, accountActor);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
    }

    /** Keyed on the target, so a backward correction into a gated stage is gated identically. */
    @Test
    void aBackwardMoveIntoAGatedStage_isRefusedToo_andTheDealDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.CLOSED_PAID);

        assertThatThrownBy(() ->
            ticketService.updateStage(ticketId, DealStage.DELIVERED, "แก้สถานะย้อนหลัง", ceo))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** Case A — the full route, walked by hand with each fact recorded as it happens. The front half needs nothing;
     * the back half needs exactly the fact its stage claims, in the order the business produces them. */
    @Test
    void caseA_theFullRoute_walksEveryStageWithItsFactsInPlace() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        // The front half of case A, with each stage arriving the way the business produces it: the
        // three quote stages from quoting that party, the rest by hand.
        // S4 BEFORE S3 is case A's own order — the designer is quoted off the spec, then the spec
        // is signed off — and QUOTE_DESIGN_SIDE -> SPEC_APPROVED is the one routine backward pair,
        // still a manual hop needing no note.
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.PRESENTATION, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);

        ticketService.advanceStageForCustomerQuotationIssue(ticketId, QuotationRecipient.DESIGNER, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);

        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.SPEC_APPROVED);

        ticketService.advanceStageForCustomerQuotationIssue(ticketId, QuotationRecipient.OWNER, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);

        for (String stage : List.of(DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER)) {
            logAnActivityAndFollowUp(ticketId);
            ticketService.updateStage(ticketId, stage, null, ownerRep);
            assertThat(stageOf(ticketId)).isEqualTo(stage);
        }

        ticketService.advanceStageForCustomerQuotationIssue(ticketId, QuotationRecipient.BUYER, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_BUYER);

        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.NEGOTIATION, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, null, ownerRep);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_PAID);
        logAnActivityAndFollowUp(ticketId);
        ticketService.financeUpdateStage(ticketId, DealStage.DEPOSIT_RECEIVED, null, accountActor);

        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.PROCUREMENT, null, importActor);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.DELIVERY_SCHEDULING, null, ownerRep);

        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.DELIVERED, null, ownerRep);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.FULLY_PAID);
        logAnActivityAndFollowUp(ticketId);
        ticketService.financeUpdateStage(ticketId, DealStage.CLOSED_PAID, null, accountActor);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
    }

    /**
     * Wrong-way-round, and the reason the floor exists: {@code qty_from_stock} is a sales
     * declaration with nothing behind it (no stock ledger, no availability check), and full
     * coverage sets {@code FROM_STOCK} + jumps the deal to {@code DELIVERY_SCHEDULING}, which then
     * blocks {@code ISSUE_IMPORT_REQUEST}. Below {@code ORDER_RECEIVED} that would reroute an
     * untouched deal around the entire import journey on one person's word.
     *
     * <p>The one role the declaration is open to (the CEO) is driven, at every stage below the floor, and every
     * axis the call could have touched is re-read afterwards.
     */
    @Test
    void aStockDeclarationBelowOrderReceived_isRefused_andNothingMoves() {
        for (String stage : DealStage.ORDER.subList(0, DealStage.indexOf(DealStage.ORDER_RECEIVED))) {
            long ticketId = createDealWithOneItem();
            long itemId = onlyItemId(ticketId);
            tickets.updateSalesStage(ticketId, stage);

            // Only the CEO reaches the stage floor's 409; the owning rep and import are refused with a
            // 403 before the floor — see StockDeclarationAuthzIntegrationTest.
            for (UserPrincipal actor : List.of(ceo)) {
                assertThatThrownBy(() -> ticketService.reserveStock(ticketId, declare(itemId, "100.00"), actor))
                    .describedAs("%s must not declare stock coverage at %s", actor.role(), stage)
                    .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
            }

            assertThat(qtyFromStock(itemId)).isEqualByComparingTo("0.00");
            assertThat(stockNote(itemId)).isNull();
            assertThat(fulfilmentOf(ticketId)).isNull();
            assertThat(stageOf(ticketId)).isEqualTo(stage);
            assertThat(stockReservedEvents(ticketId)).isZero();
        }
    }

    /**
     * A deal parked at {@code stage} with the tracking fields the forward-advance gate demands
     * (a next follow-up date and one activity logged after the last stage change), so that a test
     * asserting the FACT gate fails on the fact gate and nothing else.
     */
    private long readyToAdvanceFrom(String stage) {
        long ticketId = createDealWithOneItem();
        tickets.updateSalesStage(ticketId, stage);
        logAnActivityAndFollowUp(ticketId);
        return ticketId;
    }

    private void logAnActivityAndFollowUp(long ticketId) {
        ticketService.updateTracking(ticketId,
            new TrackingUpdateRequest(null, null, null, null, LocalDate.now().plusDays(3)), ownerRep);
        ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null), ownerRep);
    }

    private long createDealWithOneItem() {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบขั้นตอน", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        return tickets.create(request, tickets.nextTicketCode(), ownerRepId, "เจ้าของดีล ทดสอบ");
    }

    private static StockReservationRequest declare(long itemId, String qtyFromStock) {
        return new StockReservationRequest(List.of(
            new StockReservationRequest.Line(itemId, new BigDecimal(qtyFromStock), "มีของในสต็อก")));
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
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

    private BigDecimal qtyFromStock(long itemId) {
        return jdbc.queryForObject("SELECT qty_from_stock FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", itemId), BigDecimal.class);
    }

    private String stockNote(long itemId) {
        return jdbc.queryForObject("SELECT stock_note FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", itemId), String.class);
    }

    private int stageChangedEvents(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STAGE_CHANGED), Integer.class);
    }

    private int stockReservedEvents(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STOCK_RESERVED), Integer.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-stagegate@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
