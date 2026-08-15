package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import th.co.glr.hr.pricing.PriceCalcService;
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
 *       already recorded. Before this, {@code updateStage} validated stage membership only, so
 *       {@code LEAD_APPROACH -> CLOSED_PAID} succeeded in one call for the price of a note.
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
        // PriceCalcService/PricingRequestService are mocked exactly as in the two sibling classes:
        // nothing exercised here calls either. The stub on cancelOpenForTicket only guards against
        // a future edit routing through markLost/cancel and NPE-ing on Mockito's null default. The
        // two things under test — TicketService's gates and TicketRepository's SQL — are both real.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "stagegate-owner@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        ceo = principal(createEmployee(employees, "ซีอีโอ", "stagegate-ceo@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "ฝ่ายบัญชี", "stagegate-account@glr.co.th"), "account");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า", "stagegate-import@glr.co.th"), "import");
    }

    // ═══ Part A — the refusals: these are the evidence ═══════════════════════

    /**
     * The headline. S1 to S20 in a single call used to cost a note, a follow-up date and one logged
     * activity — nothing about the deal's state was consulted, and no stage is terminal, so the
     * reverse worked identically.
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

    /**
     * {@code ORDER_RECEIVED} claims a verified customer order. The payment track's single entry
     * edge is {@code confirmCustomer}'s {@code CUSTOMER_CONFIRMED} write, so an untouched track is
     * exactly "no order verified".
     */
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

    /**
     * A deposit <em>notice</em> is a document; the money is a separate event ({@code
     * confirmDepositPaid}). {@code DEPOSIT_NOTICE_ISSUED} is therefore the interesting refusal —
     * the deal is demonstrably in the deposit half of the track and still has received nothing.
     */
    @Test
    void depositReceived_withOnlyTheNoticeIssued_isRefused_andTheDealDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.ORDER_RECEIVED);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        for (UserPrincipal actor : List.of(accountActor, ceo)) {
            assertThatThrownBy(() ->
                ticketService.updateStage(ticketId, DealStage.DEPOSIT_RECEIVED, "รับมัดจำแล้ว", actor))
                .describedAs("%s must not be able to claim a deposit that has not arrived", actor.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /**
     * Every fulfilment state that is not {@code FULLY_DELIVERED}, including the one that reads
     * closest to "delivered" and is not: {@code GOODS_RECEIVED} means the goods reached GLR's own
     * warehouse (S17) and the customer has received nothing — the same distinction
     * {@code deliveryGateComplete} was tightened to make for the close gate.
     */
    @Test
    void delivered_onADealThatIsNotFullyDelivered_isRefused_andTheDealDoesNotMove() {
        for (String fulfilment : new String[] {null, FulfilmentStatus.IR_ISSUED,
                FulfilmentStatus.FROM_STOCK, FulfilmentStatus.PARTIALLY_DELIVERED,
                FulfilmentStatus.GOODS_RECEIVED}) {
            long ticketId = readyToAdvanceFrom(DealStage.DELIVERY_SCHEDULING);
            if (fulfilment != null) {
                tickets.updateFulfillmentStatus(ticketId, fulfilment);
            }

            assertThatThrownBy(() ->
                ticketService.updateStage(ticketId, DealStage.DELIVERED, "ส่งของแล้ว", ownerRep))
                .describedAs("fulfilment=%s must not be claimable as DELIVERED", fulfilment)
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

            assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
            assertThat(fulfilmentOf(ticketId)).isEqualTo(fulfilment);
            assertThat(stageChangedEvents(ticketId)).isZero();
        }
    }

    /**
     * {@code CLOSED_PAID} is the revenue-bearing one. {@code AWAITING_FINAL_PAYMENT} is the
     * wrong-way-round case worth pinning: the deposit is in and the goods are delivered, so
     * everything except the balance is done — and the balance is exactly what the stage claims.
     */
    @Test
    void closedPaid_withTheBalanceStillOutstanding_isRefused_andTheDealDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.DELIVERED);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.AWAITING_FINAL_PAYMENT);
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);

        assertThatThrownBy(() ->
            ticketService.updateStage(ticketId, DealStage.CLOSED_PAID, "ปิดงาน", accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.AWAITING_FINAL_PAYMENT);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /**
     * The case an earlier draft of this gate let through, and the reason it now tests BOTH halves:
     * the money is all in, but the customer does not have the goods. {@code maybeAdvanceClosedPaid}
     * has always required payment AND delivery, so a manual move that needed only {@code FULLY_PAID}
     * could claim a stage the automatic path would have refused — a manual bar lower than the
     * automatic one, which inverts the principle the whole gate rests on.
     *
     * <p>Driven across the fulfilment states a fully-paid deal can really be sitting in, including
     * {@code GOODS_RECEIVED} (goods at GLR's warehouse, customer has nothing) and {@code null} (a
     * deal never tracked for delivery at all).
     */
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
                ticketService.updateStage(ticketId, DealStage.CLOSED_PAID, "รับเงินครบแล้ว", accountActor))
                .describedAs("paid in full with fulfilment=%s must not reach CLOSED_PAID", fulfilment)
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

            assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
            assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
            assertThat(fulfilmentOf(ticketId)).isEqualTo(fulfilment);
            assertThat(stageChangedEvents(ticketId)).isZero();
        }
    }

    /**
     * …and the manual gate now matches {@link TicketService} {@code maybeAdvanceClosedPaid} exactly:
     * with delivery completed as well, the move is accepted.
     *
     * <p>Walked from {@code DELIVERED} rather than {@code DELIVERY_SCHEDULING} on purpose — that is
     * the adjacent move, so no MANDATORY stage is stepped over and no note is required. The facts
     * are then the only thing that can be permitting this, which is what the test is for.
     */
    @Test
    void closedPaid_isAcceptedOnceBothPaymentAndDeliveryAreComplete() {
        long ticketId = readyToAdvanceFrom(DealStage.DELIVERED);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.FULLY_PAID);
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);

        ticketService.updateStage(ticketId, DealStage.CLOSED_PAID, null, accountActor);

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

    // ═══ Part A — the grants: necessary, but not the evidence ════════════════

    /** Each gated stage becomes settable the moment its own fact is on the row, and not before. */
    @Test
    void eachGatedStageBecomesSettableOnceItsFactIsRecorded() {
        long order = readyToAdvanceFrom(DealStage.NEGOTIATION);
        tickets.updatePaymentStatusUnchecked(order, PaymentTrack.CUSTOMER_CONFIRMED);
        ticketService.updateStage(order, DealStage.ORDER_RECEIVED, null, ownerRep);
        assertThat(stageOf(order)).isEqualTo(DealStage.ORDER_RECEIVED);

        long deposit = readyToAdvanceFrom(DealStage.ORDER_RECEIVED);
        tickets.updatePaymentStatusUnchecked(deposit, PaymentTrack.DEPOSIT_PAID);
        ticketService.updateStage(deposit, DealStage.DEPOSIT_RECEIVED, null, accountActor);
        assertThat(stageOf(deposit)).isEqualTo(DealStage.DEPOSIT_RECEIVED);

        long delivered = readyToAdvanceFrom(DealStage.DELIVERY_SCHEDULING);
        tickets.updateFulfillmentStatus(delivered, FulfilmentStatus.FULLY_DELIVERED);
        ticketService.updateStage(delivered, DealStage.DELIVERED, null, ownerRep);
        assertThat(stageOf(delivered)).isEqualTo(DealStage.DELIVERED);

        // CLOSED_PAID is the one stage with TWO facts behind it — payment AND delivery, exactly as
        // maybeAdvanceClosedPaid requires. See closedPaid_fullyPaidButNotFullyDelivered_… for the
        // half-satisfied case.
        long closed = readyToAdvanceFrom(DealStage.DELIVERED);
        tickets.updatePaymentStatusUnchecked(closed, PaymentTrack.FULLY_PAID);
        tickets.updateFulfillmentStatus(closed, FulfilmentStatus.FULLY_DELIVERED);
        ticketService.updateStage(closed, DealStage.CLOSED_PAID, null, accountActor);
        assertThat(stageOf(closed)).isEqualTo(DealStage.CLOSED_PAID);
    }

    /**
     * The eleven ungated stages keep their manual fallback with no facts recorded at all —
     * {@code NEGOTIATION}, {@code PROCUREMENT} and {@code DELIVERY_SCHEDULING} above all, the
     * operational stages where a wrong value misreports nothing financial. A gate that quietly
     * swallowed these would be worse than the hole it closes.
     */
    @Test
    void theUngatedStagesKeepTheirManualFallbackWithNoFactsRecorded() {
        long negotiation = readyToAdvanceFrom(DealStage.QUOTE_BUYER);
        ticketService.updateStage(negotiation, DealStage.NEGOTIATION, null, ownerRep);
        assertThat(stageOf(negotiation)).isEqualTo(DealStage.NEGOTIATION);

        long procurement = readyToAdvanceFrom(DealStage.QUOTE_BUYER);
        ticketService.updateStage(procurement, DealStage.PROCUREMENT, "นำเข้าเริ่มแล้ว", importActor);
        assertThat(stageOf(procurement)).isEqualTo(DealStage.PROCUREMENT);

        long scheduling = readyToAdvanceFrom(DealStage.QUOTE_BUYER);
        ticketService.updateStage(scheduling, DealStage.DELIVERY_SCHEDULING, "ของพร้อมส่ง", ownerRep);
        assertThat(stageOf(scheduling)).isEqualTo(DealStage.DELIVERY_SCHEDULING);

        assertThat(paymentStatusOf(negotiation)).isNull();
        assertThat(fulfilmentOf(procurement)).isNull();
    }

    // ═══ The owner's routes must all still be walkable ═══════════════════════
    //
    // Cases A-D are the four in DealStage's own Javadoc; G (delivered, then paid) is the ordering
    // covered by the automatic walk below. E (per-line partial stock coverage) is separate work
    // and is exercised only as far as the mixed declaration below reaches; F is out of scope by
    // owner ruling. None of these routes touches a gated stage without its fact, which is the
    // whole point of gating on the fact rather than on the previous stage.

    /** Case C — a contractor arrives with a BOQ and a spec, so the deal opens straight at S8. */
    @Test
    void caseC_aDealThatOpensStraightAtQuoteBuyer_isUnaffected() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        ticketService.updateStage(ticketId, DealStage.QUOTE_BUYER, null, ownerRep);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_BUYER);
    }

    /** Case B — the owner buys directly, so S3, S4, S7 and S8 never happen. */
    @Test
    void caseB_theOwnerBuysDirect_isUnaffected() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);

        ticketService.updateStage(ticketId, DealStage.QUOTE_OWNER, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);

        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.OWNER_SIGNOFF, null, ownerRep);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.NEGOTIATION, null, ownerRep);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);
    }

    /**
     * Case A — the full route, walked by hand with each fact recorded as it happens. The front
     * half needs nothing; the back half needs exactly the fact its stage claims, in the order the
     * business produces them.
     */
    @Test
    void caseA_theFullRoute_walksEveryStageWithItsFactsInPlace() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        for (String stage : List.of(DealStage.PRESENTATION, DealStage.QUOTE_DESIGN_SIDE,
                DealStage.SPEC_APPROVED, DealStage.QUOTE_OWNER, DealStage.OWNER_SIGNOFF,
                DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER, DealStage.NEGOTIATION)) {
            logAnActivityAndFollowUp(ticketId);
            // QUOTE_DESIGN_SIDE -> SPEC_APPROVED is the one routine backward pair; the rest are
            // forward. Neither needs a note, and none of them is fact-gated.
            ticketService.updateStage(ticketId, stage, null, ownerRep);
            assertThat(stageOf(ticketId)).isEqualTo(stage);
        }

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, null, ownerRep);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_PAID);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.DEPOSIT_RECEIVED, null, accountActor);

        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.PROCUREMENT, null, importActor);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.DELIVERY_SCHEDULING, null, ownerRep);

        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.DELIVERED, null, ownerRep);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.FULLY_PAID);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.CLOSED_PAID, null, accountActor);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);
    }

    // ═══ The automatic path must be completely unaffected ════════════════════

    /**
     * <b>Required evidence.</b> {@code autoAdvanceStage} is the path that fires <em>because</em> a
     * fact just became true, so it must never be routed through the fact gate — that would be
     * circular and would brick every operational advance.
     *
     * <p>This drives the REAL service methods against real Postgres, from a deal at
     * {@code LEAD_APPROACH} with no facts at all, and asserts the deal reaches all four gated
     * stages by itself:
     *
     * <pre>
     * confirmCustomer     -> CUSTOMER_CONFIRMED  -> ORDER_RECEIVED      (S10)
     * recordPayment       -> DEPOSIT_PAID        -> DEPOSIT_RECEIVED    (S11)
     * reserveStock (full) -> FROM_STOCK          -> DELIVERY_SCHEDULING (S18, skipping PROCUREMENT)
     * completeDelivery    -> FULLY_DELIVERED     -> DELIVERED           (S19)
     * confirmFinalPayment -> FULLY_PAID          -> CLOSED_PAID         (S20)
     * </pre>
     *
     * <p>It doubles as the owner's all-from-stock route (Case D — no import journey, PROCUREMENT
     * never visited) and as Case G's ordering (delivered first, then paid).
     */
    @Test
    void autoAdvanceStillReachesAllFourGatedStagesWhenTheFactBecomesTrue() {
        long ticketId = createDealWithOneItem();
        long itemId = onlyItemId(ticketId);
        setApprovedPrice(itemId, "1000.00");          // payable = 100 x 1,000 = 100,000
        setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);

        ticketService.confirmCustomer(ticketId, ownerRep);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);

        ticketService.recordPayment(ticketId, new RecordPaymentRequest(
            "DEPOSIT", new BigDecimal("50000.00"), null, "รับมัดจำ", null, null, false), accountActor);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DEPOSIT_RECEIVED);

        ticketService.reserveStock(ticketId, declare(itemId, "100.00"), ownerRep);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);

        ticketService.completeDelivery(ticketId, new CompleteDeliveryRequest("ส่งครบ", "คุณลูกค้า"), importActor);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERED);

        ticketService.confirmFinalPayment(ticketId, accountActor);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.CLOSED_PAID);

        // Case D's defining property: a fully-stocked deal has no import journey.
        assertThat(stageChangedTo(ticketId, DealStage.PROCUREMENT)).isZero();
    }

    // ═══ Part B — the stock declaration's stage floor ════════════════════════

    /**
     * Wrong-way-round, and the reason the floor exists: {@code qty_from_stock} is a sales
     * declaration with nothing behind it (no stock ledger, no availability check), and full
     * coverage sets {@code FROM_STOCK} + jumps the deal to {@code DELIVERY_SCHEDULING}, which then
     * blocks {@code ISSUE_IMPORT_REQUEST}. Below {@code ORDER_RECEIVED} that would reroute an
     * untouched deal around the entire import journey on one person's word.
     *
     * <p>Every role the declaration is open to is driven, at every stage below the floor, and every
     * axis the call could have touched is re-read afterwards.
     */
    @Test
    void aStockDeclarationBelowOrderReceived_isRefused_andNothingMoves() {
        for (String stage : DealStage.ORDER.subList(0, DealStage.indexOf(DealStage.ORDER_RECEIVED))) {
            long ticketId = createDealWithOneItem();
            long itemId = onlyItemId(ticketId);
            tickets.updateSalesStage(ticketId, stage);

            for (UserPrincipal actor : List.of(ownerRep, importActor, ceo)) {
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
     * …and above the floor nothing changed: the same declaration is accepted and still routes the
     * deal exactly as before (owner ruling — the floor adds a precondition, it does not change what
     * happens above it).
     */
    @Test
    void aStockDeclarationAtOrAboveTheFloor_isAcceptedAndStillRoutesTheDeal() {
        for (String stage : List.of(DealStage.ORDER_RECEIVED, DealStage.DEPOSIT_RECEIVED)) {
            long ticketId = createDealWithOneItem();
            long itemId = onlyItemId(ticketId);
            tickets.updateSalesStage(ticketId, stage);

            ticketService.reserveStock(ticketId, declare(itemId, "100.00"), ownerRep);

            assertThat(qtyFromStock(itemId)).isEqualByComparingTo("100.00");
            assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FROM_STOCK);
            assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        }
    }

    /**
     * The floor must reach BOTH entry points. {@code reserveStock}'s own gate and
     * {@code canReserveStock} (which decides whether {@code actions} advertises RESERVE_STOCK) were
     * unified on one predicate by the 2026-08-13 widening; applying the floor to only one of them
     * would put the capability back to advertised-then-refused, or live-but-invisible.
     */
    @Test
    void actionsDoesNotAdvertiseReserveStockBelowTheFloor_andDoesAboveIt() {
        long below = createDealWithOneItem();
        tickets.updateSalesStage(below, DealStage.NEGOTIATION);
        assertThat(actionCodes(below, ownerRep)).doesNotContain("RESERVE_STOCK");
        assertThat(actionCodes(below, importActor)).doesNotContain("RESERVE_STOCK");
        // …and the advertiser agrees with the gate on the very same deal.
        assertThatThrownBy(() ->
            ticketService.reserveStock(below, declare(onlyItemId(below), "100.00"), ownerRep))
            .isInstanceOf(ApiException.class);

        long above = createDealWithOneItem();
        tickets.updateSalesStage(above, DealStage.ORDER_RECEIVED);
        assertThat(actionCodes(above, ownerRep)).contains("RESERVE_STOCK");
        assertThat(actionCodes(above, importActor)).contains("RESERVE_STOCK");
        assertThatCode(() ->
            ticketService.reserveStock(above, declare(onlyItemId(above), "100.00"), ownerRep))
            .doesNotThrowAnyException();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A deal parked at {@code stage} with the tracking fields the B1 forward-advance gate demands
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

    private List<String> actionCodes(long ticketId, UserPrincipal actor) {
        return ticketService.actions(ticketId, actor).availableActions().stream()
            .map(TicketResponses.TicketActionDto::action).toList();
    }

    /**
     * The last fallback of {@code TicketRepository.payableAmount} is
     * {@code SUM(approved_price * qty)}, so this is the smallest honest way to give a deal a
     * payable amount without dragging the whole pricing-request chain into a test about stages.
     */
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

    private int stageChangedTo(long ticketId, String stage) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind AND to_status = :stage
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STAGE_CHANGED, "stage", stage),
            Integer.class);
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
