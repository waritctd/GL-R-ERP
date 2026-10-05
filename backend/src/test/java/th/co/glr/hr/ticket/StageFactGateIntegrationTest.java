package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
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
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * <p><b>Owner rules of 2026-10-05 (stage movement, M1-M4) supersede the "grants" below.</b> A hand
 * move exists only inside ขั้น 1-9 and only onto ขั้น 1, 2, 3, 6, 7 and 9; ขั้น 4, 5 and 8 are reached
 * when a quotation is created for that party; from ขั้น 10 the stage is system-only for EVERY role
 * (the owning rep, ผจก.ขาย, the CEO, ฝ่ายนำเข้า through {@code updateStage}, ฝ่ายบัญชี through
 * {@code financeUpdateStage}) whether or not the fact behind the stage holds — and a deal that
 * already SITS at ขั้น 10 or later cannot be hand-moved at all, not even back. So "the stage becomes
 * settable once its fact is recorded" is now "still refused although its fact is recorded", and the
 * refusal tests of Part A, which hold under both readings, are left as they were. A NEW refusal is
 * asserted as: {@code ApiException} thrown, {@code sales_stage} unchanged, no {@code STAGE_CHANGED}
 * event written — never its status or message ({@link #assertHandMoveRefused}). Every fixture below
 * is one in which the OLD code accepted the move (right role, tracking fields current, a note
 * supplied, the fact recorded), so a refusal can only be the new rule's.
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
    private UserPrincipal salesManager;
    private UserPrincipal ceo;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        // PricingRequestService is mocked exactly as in the two sibling classes: nothing
        // exercised here calls it. The stub on cancelOpenForTicket only guards against a future
        // edit routing through markLost/cancel and NPE-ing on Mockito's null default. The two
        // things under test — TicketService's gates and TicketRepository's SQL — are both real.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "stagegate-owner@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        salesManager = principal(createEmployee(employees, "ผู้จัดการขาย", "stagegate-mgr@glr.co.th"), "sales_manager");
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
            ticketService.financeUpdateStage(ticketId, DealStage.CLOSED_PAID, "ปิดงาน", accountActor))
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

    /**
     * …and still refused when BOTH facts are in (M4): paid in full AND fully delivered is exactly the
     * bar {@link TicketService} {@code maybeAdvanceClosedPaid} clears, and it is the SYSTEM that
     * moves the deal to CLOSED_PAID when it does — {@link
     * #autoAdvanceStillReachesAllFourGatedStagesWhenTheFactBecomesTrue} walks that. This used to
     * assert the opposite: that ฝ่ายบัญชี could claim the stage by hand once the facts matched.
     *
     * <p>Walked from {@code DELIVERED} rather than {@code DELIVERY_SCHEDULING} on purpose — that is
     * the adjacent move, so no MANDATORY stage is stepped over and no note is required: the old code
     * accepted this exact call, so a refusal here can only be the "system-only from ขั้น 10" rule.
     */
    @Test
    void closedPaid_isRefusedByHand_evenOnceBothPaymentAndDeliveryAreComplete() {
        long ticketId = readyToAdvanceFrom(DealStage.DELIVERED);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.FULLY_PAID);
        tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        assertHandMoveRefused(ticketId, DealStage.DELIVERED,
            "ฝ่ายบัญชี must not claim CLOSED_PAID by hand although the deal is paid and delivered",
            () -> ticketService.financeUpdateStage(ticketId, DealStage.CLOSED_PAID, null, accountActor));
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

    // ═══ Part A — the former grants, flipped by the owner rules of 2026-10-05 ═

    /**
     * M4, the headline flip. This used to be "each gated stage becomes settable the moment its own
     * fact is on the row"; the owner's rule is that from ขั้น 10 the stage is system-only, so the hand
     * move into ORDER_RECEIVED, DEPOSIT_RECEIVED, DELIVERED and CLOSED_PAID is refused for every role
     * that could write it — the owning rep, ฝ่ายบัญชี through the finance entrypoint, and the CEO —
     * with the fact behind the stage RECORDED, a note supplied and the tracking fields current. Each
     * case is a deal on which the old code accepted exactly this call (the old test asserted it), so
     * the refusal is the new rule's and nothing else's.
     *
     * <p>Parameterised so that each (stage, role) pair is its own result: a single loop would stop at
     * the first pair and say nothing about the other seven.
     */
    @ParameterizedTest(name = "[{index}] {0} by {1}, with its fact recorded")
    @MethodSource("theFormerlyGatedStagesAndEveryRoleThatCouldWriteThem")
    void eachFormerlyGatedStageIsStillRefusedByHand_evenOnceItsFactIsRecorded(String stage, String role) {
        long ticketId = dealWithTheFactRecorded(stage);
        String parkedAt = DealStage.ORDER.get(DealStage.indexOf(stage) - 1);

        assertHandMoveRefused(ticketId, parkedAt,
            role + " must not move a deal onto " + stage + " by hand, even though its fact is recorded",
            () -> handMove(ticketId, stage, role));
    }

    static Stream<Arguments> theFormerlyGatedStagesAndEveryRoleThatCouldWriteThem() {
        return Stream.of(
            Arguments.of(DealStage.ORDER_RECEIVED, "sales"),
            Arguments.of(DealStage.ORDER_RECEIVED, "ceo"),
            Arguments.of(DealStage.DEPOSIT_RECEIVED, "account"),
            Arguments.of(DealStage.DEPOSIT_RECEIVED, "ceo"),
            Arguments.of(DealStage.DELIVERED, "sales"),
            Arguments.of(DealStage.DELIVERED, "ceo"),
            Arguments.of(DealStage.CLOSED_PAID, "account"),
            Arguments.of(DealStage.CLOSED_PAID, "ceo"));
    }

    /**
     * Of the stages the old gate left alone, only NEGOTIATION (ขั้น 9) keeps its manual fallback — it
     * is inside ขั้น 1-9 and one of the six hand-settable ones. PROCUREMENT (ขั้น 12) and
     * DELIVERY_SCHEDULING (ขั้น 13) are past ขั้น 10, so they are system-only: ฝ่ายนำเข้า cannot claim
     * PROCUREMENT and the owning rep cannot claim DELIVERY_SCHEDULING, with no facts recorded and
     * with the facts recorded alike. This was {@code theUngatedStagesKeepTheirManualFallback…}.
     *
     * <p>The green half runs first, so a failure below it cannot be mistaken for a broken NEGOTIATION.
     * The two refusals sit in {@code assertAll} so that BOTH are reported rather than the first only.
     * Their fixtures are the old ones (a note supplied, tracking current): the old code accepted both.
     */
    @Test
    void negotiationKeepsItsManualFallback_whileProcurementAndDeliverySchedulingAreSystemOnly() {
        long negotiation = readyToAdvanceFrom(DealStage.QUOTE_BUYER);
        ticketService.updateStage(negotiation, DealStage.NEGOTIATION, null, ownerRep);
        assertThat(stageOf(negotiation)).isEqualTo(DealStage.NEGOTIATION);
        assertThat(paymentStatusOf(negotiation)).isNull();

        long procurement = readyToAdvanceFrom(DealStage.QUOTE_BUYER);
        long scheduling = readyToAdvanceFrom(DealStage.QUOTE_BUYER);
        assertAll(
            () -> assertHandMoveRefused(procurement, DealStage.QUOTE_BUYER,
                "ฝ่ายนำเข้า must not claim PROCUREMENT by hand",
                () -> ticketService.updateStage(procurement, DealStage.PROCUREMENT, "นำเข้าเริ่มแล้ว", importActor)),
            () -> assertHandMoveRefused(scheduling, DealStage.QUOTE_BUYER,
                "the owning rep must not claim DELIVERY_SCHEDULING by hand",
                () -> ticketService.updateStage(scheduling, DealStage.DELIVERY_SCHEDULING, "ของพร้อมส่ง", ownerRep)));
    }

    // ═══ The owner's routes: by hand only inside ขั้น 1-9, the rest is the system's ═══
    //
    // Cases A-D are the four in DealStage's own Javadoc; G (delivered, then paid) is the ordering
    // covered by the automatic walk below. E (per-line partial stock coverage) is separate work
    // and is exercised only as far as the mixed declaration below reaches; F is out of scope by
    // owner ruling.
    //
    // This class wires NO quotation service (and must not be made to just for this): ขั้น 4, 5 and 8
    // are reached when a quotation is CREATED for that party, and that move is pinned where the
    // quotation services are wired — DealQuotationRecipientIntegrationTest (a direct quotation, each
    // recipient, plus the route correction), CustomerQuotationIntegrationTest and
    // DealQuotationPricingRequestApprovalIntegrationTest (a pricing-request quotation). Here the deal
    // is only POSITIONED on those three stages, with the repository, and the hand move onto them is
    // asserted refused.

    /** Case C — a contractor arrives with a BOQ and a spec, so the deal opens straight at S8. */
    @Test
    void caseC_aHandMoveStraightOntoQuoteBuyer_isRefused_theBuyerQuotationIsWhatMovesTheDeal() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        assertHandMoveRefused(ticketId, DealStage.LEAD_APPROACH,
            "the owning rep must not move a deal onto QUOTE_BUYER (ขั้น 8) by hand",
            () -> ticketService.updateStage(ticketId, DealStage.QUOTE_BUYER, null, ownerRep));
    }

    /** Case B — the owner buys directly, so S3, S4, S7 and S8 never happen. */
    @Test
    void caseB_theOwnerBuysDirect_signoffAndNegotiationAreByHand_butTheOwnerQuotationIsNot() {
        // The hand-settable remainder of the route, from a deal POSITIONED on QUOTE_OWNER (ขั้น 5):
        // S5 -> S6 -> S9 are accepted today and stay accepted (M1).
        long ticketId = readyToAdvanceFrom(DealStage.QUOTE_OWNER);
        ticketService.updateStage(ticketId, DealStage.OWNER_SIGNOFF, null, ownerRep);
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, DealStage.NEGOTIATION, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);

        // ...but ขั้น 5 itself is reached by the owner quotation, never by hand (M2).
        long fromPresentation = readyToAdvanceFrom(DealStage.PRESENTATION);
        assertHandMoveRefused(fromPresentation, DealStage.PRESENTATION,
            "the owning rep must not move a deal onto QUOTE_OWNER (ขั้น 5) by hand",
            () -> ticketService.updateStage(fromPresentation, DealStage.QUOTE_OWNER, null, ownerRep));
    }

    /**
     * Case A — the full route. The hand walk covers ขั้น 1-9 with only the hand-settable stages
     * (S2, S3, S6, S7, S9); ขั้น 4, 5 and 8 are positioned with the repository, standing in for the
     * quotation that really takes the deal there. Then, with the customer's order verified, the hand
     * move to ORDER_RECEIVED is refused: the tail of the route belongs to the system, and {@link
     * #autoAdvanceStillReachesAllFourGatedStagesWhenTheFactBecomesTrue} is the test that walks it.
     */
    @Test
    void caseA_theFullRoute_handWalksTheHandSettableStages_thenTheOrderCannotBeClaimedByHand() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        handStep(ticketId, DealStage.PRESENTATION);
        positionedAt(ticketId, DealStage.QUOTE_DESIGN_SIDE);   // S4: a quotation's job
        handStep(ticketId, DealStage.SPEC_APPROVED);           // S4 -> S3, the routine backward pair
        positionedAt(ticketId, DealStage.QUOTE_OWNER);         // S5: a quotation's job
        handStep(ticketId, DealStage.OWNER_SIGNOFF);
        handStep(ticketId, DealStage.AWAITING_BUYER);
        positionedAt(ticketId, DealStage.QUOTE_BUYER);         // S8: a quotation's job
        handStep(ticketId, DealStage.NEGOTIATION);

        // The facts for ขั้น 10 are in, and the tracking fields are current — the old code accepted this.
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);
        logAnActivityAndFollowUp(ticketId);
        assertHandMoveRefused(ticketId, DealStage.NEGOTIATION,
            "the owning rep must not claim ORDER_RECEIVED by hand although the customer's order is verified",
            () -> ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, null, ownerRep));
    }

    /**
     * M4, the one shape nothing else here pins: a deal that already SITS at ขั้น 10 or later cannot be
     * moved by hand at all — not even BACK into ขั้น 1-9, with a reason, by the owning rep, ผจก.ขาย or
     * the CEO. NEGOTIATION is a stage all three may write, a note is supplied (a backward move needs
     * one), the stage is on the route and the fact gates do not touch it, so the old code accepted
     * every one of these 18 moves: the refusal is the new rule's and nothing else's.
     */
    @ParameterizedTest(name = "[{index}] sitting at {0}, moved back to NEGOTIATION by {1}")
    @MethodSource("everyStageFromOrderReceivedOnAndEveryRoleThatMayWriteSalesStages")
    void aDealSittingAtOrderReceivedOrLater_cannotBeMovedBackByHandIntoTheFrontHalf(String sittingAt, String role) {
        long ticketId = readyToAdvanceFrom(sittingAt);

        assertHandMoveRefused(ticketId, sittingAt,
            role + " must not hand-move a deal sitting at " + sittingAt + " back to NEGOTIATION",
            () -> handMove(ticketId, DealStage.NEGOTIATION, role, "ลูกค้าขอทบทวนข้อเสนอใหม่"));
    }

    static Stream<Arguments> everyStageFromOrderReceivedOnAndEveryRoleThatMayWriteSalesStages() {
        return DealStage.ORDER.subList(DealStage.indexOf(DealStage.ORDER_RECEIVED), DealStage.ORDER.size())
            .stream()
            .flatMap(stage -> Stream.of("sales", "sales_manager", "ceo").map(role -> Arguments.of(stage, role)));
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
     * confirmDepositPaid  -> DEPOSIT_PAID        -> DEPOSIT_RECEIVED    (S11)
     * reserveStock (full) -> FROM_STOCK          -> DELIVERY_SCHEDULING (S18, skipping PROCUREMENT)
     * completeDelivery    -> FULLY_DELIVERED     -> DELIVERED           (S19)
     * confirmFinalPayment -> FULLY_PAID          -> CLOSED_PAID         (S20)
     * </pre>
     *
     * <p>The deposit is confirmed the one way there is (C2): ฝ่ายบัญชี's {@code confirmDepositPaid}, once the
     * notice has been issued — its effect is stamped here, as {@code SalesRouteWalkIntegrationTest} does. A
     * receipt recorded through {@code recordPayment} is not a deposit confirmation. The deal is a deposit
     * deal explicitly (C1): its accepted quotation asks 50%, written before the order is confirmed.
     *
     * <p>It doubles as the owner's all-from-stock route (Case D — no import journey, PROCUREMENT
     * never visited) and as Case G's ordering (delivered first, then paid).
     */
    @Test
    void autoAdvanceStillReachesAllFourGatedStagesWhenTheFactBecomesTrue() {
        long ticketId = createDealWithOneItem();
        long itemId = onlyItemId(ticketId);
        setApprovedPrice(itemId, "1000.00");          // payable = 100 x 1,000 + 7% VAT = 107,000
        insertDepositQuotation(ticketId, 50);
        setStatus(ticketId, TicketStatus.QUOTATION_ISSUED);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.LEAD_APPROACH);

        ticketService.confirmCustomer(ticketId, ownerRep);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        ticketService.confirmDepositPaid(ticketId, accountActor);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DEPOSIT_RECEIVED);

        ticketService.reserveStock(ticketId, declare(itemId, "100.00"), ownerRep);
        assertThat(fulfilmentOf(ticketId)).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);

        // V184: completeDelivery's gate (canWriteDelivery) transferred to {ceo, owning-rep} only --
        // ownerRep, not importActor. See DeliveryAuthzIntegrationTest for the authz pin itself.
        ticketService.completeDelivery(ticketId, new CompleteDeliveryRequest("ส่งครบ", "คุณลูกค้า"), ownerRep);
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

            // Only the two roles that CAN declare (owning rep + CEO) reach the stage floor's 409;
            // import is no longer a declarer at all (owner decision 2026-09-28) and is refused with a
            // 403 before the floor — see StockDeclarationAuthzIntegrationTest.
            for (UserPrincipal actor : List.of(ownerRep, ceo)) {
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
     *
     * <p>Routing to {@code DELIVERY_SCHEDULING} needs the deposit to be ready (C4 of the owner rules of
     * 2026-10-05: a stock declaration holds the stage until ฝ่ายบัญชี has confirmed the deposit; pinned in
     * {@code StockDeclarationDepositHoldIntegrationTest}). So each deal is made deposit-ready here, to
     * keep this test about the floor and nothing else.
     */
    @Test
    void aStockDeclarationAtOrAboveTheFloor_isAcceptedAndStillRoutesTheDeal() {
        for (String stage : List.of(DealStage.ORDER_RECEIVED, DealStage.DEPOSIT_RECEIVED)) {
            long ticketId = createDealWithOneItem();
            long itemId = onlyItemId(ticketId);
            tickets.updateSalesStage(ticketId, stage);
            tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_PAID);

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
        assertThat(actionCodes(below, ceo)).doesNotContain("RESERVE_STOCK");
        // …and the advertiser agrees with the gate on the very same deal.
        assertThatThrownBy(() ->
            ticketService.reserveStock(below, declare(onlyItemId(below), "100.00"), ownerRep))
            .isInstanceOf(ApiException.class);

        long above = createDealWithOneItem();
        tickets.updateSalesStage(above, DealStage.ORDER_RECEIVED);
        assertThat(actionCodes(above, ownerRep)).contains("RESERVE_STOCK");
        assertThat(actionCodes(above, ceo)).contains("RESERVE_STOCK");
        // import lost RESERVE_STOCK entirely on 2026-09-28 (S18 is Sales's) — not offered it even
        // above the floor.
        assertThat(actionCodes(above, importActor)).doesNotContain("RESERVE_STOCK");
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

    /**
     * How a NEW refusal of a hand move is asserted (owner rules of 2026-10-05): {@code ApiException}
     * is thrown, {@code sales_stage} is unchanged and no {@code STAGE_CHANGED} event was written —
     * and nothing about the status or the message, because 403 and 409 are both acceptable. The
     * callers' fixtures are ones the OLD code accepted, which is what makes this red before the rule
     * exists and keeps it from passing for an unrelated reason.
     */
    private void assertHandMoveRefused(long ticketId, String stillAt, String why,
                                       ThrowableAssert.ThrowingCallable move) {
        assertThat(stageOf(ticketId)).as("fixture: the deal sits at %s", stillAt).isEqualTo(stillAt);
        int eventsBefore = stageChangedEvents(ticketId);

        // catchThrowable + a described assertThat, not assertThatThrownBy(...).as(why): the latter fails with a
        // bare "Expecting code to raise a throwable" BEFORE the description is applied, and says nothing
        // about which case was accepted.
        Throwable thrown = catchThrowable(move);
        assertThat(thrown).as("%s -- the move must be REFUSED with an ApiException", why)
            .isInstanceOf(ApiException.class);

        assertThat(stageOf(ticketId)).as("%s — and the stage must not have moved", why).isEqualTo(stillAt);
        assertThat(stageChangedEvents(ticketId)).as("%s — and no STAGE_CHANGED may be written", why)
            .isEqualTo(eventsBefore);
    }

    /** The hand move a given role would make: account only through the finance entrypoint (H1 lockdown). */
    private void handMove(long ticketId, String stage, String role) {
        handMove(ticketId, stage, role, "ย้ายขั้นตอนเพราะข้อเท็จจริงของดีลครบแล้ว");
    }

    private void handMove(long ticketId, String stage, String role, String note) {
        switch (role) {
            case "sales" -> ticketService.updateStage(ticketId, stage, note, ownerRep);
            case "sales_manager" -> ticketService.updateStage(ticketId, stage, note, salesManager);
            case "ceo" -> ticketService.updateStage(ticketId, stage, note, ceo);
            case "import" -> ticketService.updateStage(ticketId, stage, note, importActor);
            case "account" -> ticketService.financeUpdateStage(ticketId, stage, note, accountActor);
            default -> throw new IllegalArgumentException("no such actor in this class: " + role);
        }
    }

    /**
     * A deal on which the OLD gate accepted the hand move into {@code stage}: parked on the stage
     * just below it, tracking fields current, and the fact behind {@code stage} recorded on the row.
     * CLOSED_PAID is the one stage with TWO facts behind it — payment AND delivery.
     */
    private long dealWithTheFactRecorded(String stage) {
        long ticketId = readyToAdvanceFrom(DealStage.ORDER.get(DealStage.indexOf(stage) - 1));
        switch (stage) {
            case DealStage.ORDER_RECEIVED ->
                tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);
            case DealStage.DEPOSIT_RECEIVED ->
                tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_PAID);
            case DealStage.DELIVERED ->
                tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);
            case DealStage.CLOSED_PAID -> {
                tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.FULLY_PAID);
                tickets.updateFulfillmentStatus(ticketId, FulfilmentStatus.FULLY_DELIVERED);
            }
            default -> throw new IllegalArgumentException(stage + " is not one of the four fact-gated stages");
        }
        return ticketId;
    }

    /** One hand-settable hop of the owning rep: tracking made current, no note, landing asserted. */
    private void handStep(long ticketId, String stage) {
        logAnActivityAndFollowUp(ticketId);
        ticketService.updateStage(ticketId, stage, null, ownerRep);
        assertThat(stageOf(ticketId)).as("expected to land on %s by hand", stage).isEqualTo(stage);
    }

    /** ขั้น 4, 5 and 8 are a quotation's job, not a hand move's: here the deal is only POSITIONED there. */
    private void positionedAt(long ticketId, String stage) {
        tickets.updateSalesStage(ticketId, stage);
        assertThat(stageOf(ticketId)).isEqualTo(stage);
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

    /**
     * C1: the deal's accepted quotation — an approved direct-deal row asking {@code depositPercent} (whole
     * percent) — written BEFORE the customer's order is confirmed, so the deal is a deposit deal (50) or a
     * 0% deal explicitly rather than by the deal-level switch's default.
     */
    private void insertDepositQuotation(long ticketId, int depositPercent) {
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version,
                                         origin, deposit_percent)
            VALUES (:ticketId, :number, :by, 'APPROVED', 1, 'DEAL_DIRECT', :depositPercent)
            """, new MapSqlParameterSource().addValue("ticketId", ticketId)
                .addValue("number", "QTD-STG-" + ticketId).addValue("by", ownerRepId)
                .addValue("depositPercent", (short) depositPercent));
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
