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
import java.util.Set;
import java.util.stream.Collectors;
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
import th.co.glr.hr.ticket.TicketResponses.StageDecisionDto;
import th.co.glr.hr.ticket.TicketResponses.TicketActionDto;
import th.co.glr.hr.ticket.TicketResponses.TicketActionsResponse;

/**
 * Real-Postgres proof that the per-stage decisions {@code TicketService.actions} ships to a client
 * say the same thing {@code TicketService.updateStage} would do if the client acted on them.
 *
 * <p><b>Why this test exists.</b> The frontend used to answer "which stages may I move this deal
 * to?" from its own copy of the write gate, held in {@code features/tickets/stageMeta.js}. A copy
 * of an authorization rule has no guard — nothing compared it to the Java service — and it had
 * gone stale in both directions by the time it was found. {@code actions} now answers the question
 * server-side and the copy is deleted, so this class is what stands behind every option the update-
 * stage modal offers. <b>Nothing here may be inferred from {@code mockApi.js}</b>, whose stage
 * gates are an explicit approximation.
 *
 * <p><b>Written wrong-way-round.</b> Every part below leads with what a role must <em>not</em>
 * reach. "The owner may set their own sales stages" is not evidence; "a second sales rep is
 * offered nothing at all on someone else's deal, and is refused when they try anyway" is.
 *
 * <p><b>The decision and the enforcement are asserted together, on the same deal, in every
 * case.</b> That pairing is the whole point: a decision payload that is merely self-consistent
 * would be a second implementation of the gate, which is the defect this replaces. Each refusal
 * therefore also calls {@code updateStage} and asserts the real 403/409/400 — and then re-reads
 * {@code sales.ticket} to prove the row did not move, because an exception thrown after a write
 * has landed is the failure mode Mockito cannot see.
 *
 * <p><b>Trap, per {@link AbstractPostgresIntegrationTest}:</b> services here are hand-wired with
 * {@code new}, so {@code @Transactional} is inert and no rollback is ever exercised. Nothing below
 * asserts a rollback; the "did not move" assertions hold because each guard throws before any
 * write.
 *
 * <p>Modelled on {@link StageFactGateIntegrationTest} and
 * {@link DealStageQuoteOwnerAndRouteIntegrationTest}.
 */
class StageDecisionIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private UserPrincipal otherRep;
    private UserPrincipal salesManager;
    private UserPrincipal ceo;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        // PricingRequestService is mocked exactly as in the sibling stage classes: nothing
        // exercised here calls it. The two things under test — TicketService's gates and
        // TicketRepository's SQL — are both real.
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "เจ้าของดีล ทดสอบ", "decide-owner@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        otherRep = principal(createEmployee(employees, "พนักงานขายอื่น", "decide-other@glr.co.th"), "sales");
        salesManager = principal(createEmployee(employees, "ผู้จัดการขาย", "decide-mgr@glr.co.th"), "sales_manager");
        ceo = principal(createEmployee(employees, "ซีอีโอ", "decide-ceo@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "ฝ่ายบัญชี", "decide-account@glr.co.th"), "account");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า", "decide-import@glr.co.th"), "import");
    }

    // ═══ Part A — the role gate: what each role may NOT reach ════════════════

    /**
     * The headline refusal, and the one the deleted frontend copy was responsible for.
     *
     * <p>A sales rep who did not create the deal never receives a decision payload at all:
     * {@code actions} runs {@code requireViewAccess} first, and a sales rep is scoped to their own
     * rows, so the whole endpoint 403s. That is a <em>stronger</em> guarantee than an all-blocked
     * list, and it is worth pinning explicitly because the frontend's deleted copy answered this
     * question locally — it would happily have computed and rendered an option list for a deal the
     * server refuses to describe.
     *
     * <p>The write is refused independently, at {@code requireStageWriteAccess}, so removing the
     * view gate alone would not open it.
     */
    @Test
    void aSalesRepOnSomeoneElsesDeal_cannotEvenAskForTheDecisions_andTheWriteIsRefusedToo() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);

        assertThatThrownBy(() -> ticketService.actions(ticketId, otherRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, otherRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /**
     * The deal owner must not reach the money stages or the import stage — those belong to
     * ฝ่ายบัญชี and ฝ่ายนำเข้า. Asserted as an exact set so a widening of
     * {@code DealStage.SALES_TARGET_STAGES} cannot slip past: the sales rep's offer is exactly the
     * twelve sales-gated stages, minus the one the deal is already on.
     */
    @Test
    void theDealOwner_isNeverOfferedTheMoneyOrImportStages_andIsRefusedOnEachOfThem() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);

        assertThat(allowedStages(decisionsFor(ticketId, ownerRep)))
            .doesNotContain(DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID, DealStage.PROCUREMENT)
            .containsExactlyInAnyOrderElementsOf(
                DealStage.SALES_TARGET_STAGES.stream()
                    .filter(stage -> !DealStage.PRESENTATION.equals(stage))
                    // ORDER_RECEIVED and DELIVERED are sales-gated but fact-gated (Part B), so a
                    // fresh deal cannot reach them; they are excluded here and asserted there.
                    .filter(stage -> !DealStage.ORDER_RECEIVED.equals(stage))
                    .filter(stage -> !DealStage.DELIVERED.equals(stage))
                    .collect(Collectors.toSet()));

        for (String forbidden : List.of(DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID, DealStage.PROCUREMENT)) {
            assertThatThrownBy(() -> ticketService.updateStage(ticketId, forbidden, "เหตุผล", ownerRep))
                .describedAs("owner must not reach %s", forbidden)
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** ฝ่ายบัญชี is confined to the two money stages, and ฝ่ายนำเข้า to the single import stage. */
    @Test
    void accountAndImport_areEachConfinedToTheirOwnStages() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);

        // account: DEPOSIT_RECEIVED and CLOSED_PAID are both fact-gated and the facts do not hold,
        // so what account is OFFERED here is nothing — but the reason must be the fact, not a 403,
        // and every sales stage must still be a 403.
        List<StageDecisionDto> accountDecisions = decisionsFor(ticketId, accountActor);
        assertThat(allowedStages(accountDecisions)).isEmpty();
        assertThat(reasonFor(accountDecisions, DealStage.SPEC_APPROVED)).isEqualTo("ไม่มีสิทธิ์เข้าถึงรายการนี้");
        assertThat(reasonFor(accountDecisions, DealStage.DEPOSIT_RECEIVED)).contains("ยังไม่ได้รับชำระมัดจำ");
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, "x", accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // import: PROCUREMENT is not fact-gated, so it is genuinely the only thing on offer.
        List<StageDecisionDto> importDecisions = decisionsFor(ticketId, importActor);
        assertThat(allowedStages(importDecisions)).containsExactly(DealStage.PROCUREMENT);
        assertThat(reasonFor(importDecisions, DealStage.NEGOTIATION)).isEqualTo("ไม่มีสิทธิ์เข้าถึงรายการนี้");
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.NEGOTIATION, "x", importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /**
     * sales_manager passes the sales-gated stages on a deal it does not own (the deliberate,
     * user-approved exception noted on {@code TicketService}'s pipeline section) but must still be
     * refused the money and import stages. ceo passes everything the facts allow.
     */
    @Test
    void salesManagerPassesSalesStagesOnAnotherRepsDeal_butStillNotTheMoneyOrImportOnes() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);

        assertThat(allowedStages(decisionsFor(ticketId, salesManager)))
            .contains(DealStage.SPEC_APPROVED, DealStage.NEGOTIATION)
            .doesNotContain(DealStage.DEPOSIT_RECEIVED, DealStage.PROCUREMENT, DealStage.CLOSED_PAID);
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.PROCUREMENT, "x", salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(allowedStages(decisionsFor(ticketId, ceo)))
            .contains(DealStage.SPEC_APPROVED, DealStage.PROCUREMENT);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    // ═══ Part B — the fact gates (#710) reach the decision, not just the write ═

    /**
     * The four fact-gated stages come back not-allowed with the fact as the reason, for the role
     * that <em>does</em> hold the write permission — so the client can say "you may, but the deal
     * cannot yet" instead of hiding the stage or offering a click that 409s.
     *
     * <p>This is the case a role-only decision payload would have got wrong, and the case the
     * previous {@code addStageActions} did get wrong: it advertised ADVANCE_STAGE into all four.
     */
    @Test
    void theFourFactGatedStages_areBlockedWithTheFactAsTheReason_forTheRoleThatMayWriteThem() {
        long ticketId = readyToAdvanceFrom(DealStage.NEGOTIATION);

        Map<String, UserPrincipal> byPrincipal = Map.of(
            DealStage.ORDER_RECEIVED, ownerRep,
            DealStage.DELIVERED, ownerRep,
            DealStage.DEPOSIT_RECEIVED, accountActor,
            DealStage.CLOSED_PAID, accountActor);
        Map<String, String> expectedFragment = Map.of(
            DealStage.ORDER_RECEIVED, "ยังไม่ได้ยืนยันคำสั่งซื้อของลูกค้า",
            DealStage.DELIVERED, "ยังส่งมอบสินค้าไม่ครบ",
            DealStage.DEPOSIT_RECEIVED, "ยังไม่ได้รับชำระมัดจำ",
            DealStage.CLOSED_PAID, "ต้องรับชำระเงินครบและส่งมอบสินค้าครบก่อน");

        byPrincipal.forEach((stage, actor) -> {
            List<StageDecisionDto> decisions = decisionsFor(ticketId, actor);
            assertThat(allowedFor(decisions, stage)).as("%s must not be allowed", stage).isFalse();
            assertThat(reasonFor(decisions, stage)).as("reason for %s", stage)
                .contains(expectedFragment.get(stage));
            // The advertised verb list agrees with the decision — no dead ADVANCE_STAGE.
            assertThat(advanceTargets(ticketId, actor)).as("%s must not be advertised", stage)
                .doesNotContain(stage);
            // And the write really is refused, with the same message the decision carried.
            assertThatThrownBy(() -> ticketService.updateStage(ticketId, stage, "มีเหตุผลครบ", actor))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo(reasonFor(decisionsFor(ticketId, actor), stage));
                });
        });

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /**
     * Once the fact holds, the same stage flips to allowed and the write goes through — the
     * decision tracks state, not just role. Without this the test above would pass on a payload
     * that simply always said no.
     */
    @Test
    void aFactGatedStageFlipsToAllowedOnceTheFactHolds_andTheWriteThenSucceeds() {
        long ticketId = readyToAdvanceFrom(DealStage.NEGOTIATION);
        assertThat(allowedFor(decisionsFor(ticketId, ownerRep), DealStage.ORDER_RECEIVED)).isFalse();

        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);

        assertThat(allowedFor(decisionsFor(ticketId, ownerRep), DealStage.ORDER_RECEIVED)).isTrue();
        assertThat(advanceTargets(ticketId, ownerRep)).contains(DealStage.ORDER_RECEIVED);
        ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
    }

    // ═══ Part C — the note rule, and the routes it must not tax ══════════════

    /**
     * The bug this whole change removes, pinned on a real deal.
     *
     * <p>Case C (a contractor arrives with a BOQ and spec in hand, so the deal opens at S8) skips
     * only route-dependent stages, and must be offered with {@code requiresReason: false} — the
     * frontend's stale copy demanded a written reason for exactly this move. Skipping a MANDATORY
     * stage still carries {@code requiresReason: true}, and the write really is refused without
     * one.
     */
    @Test
    void skippingOnlyRouteDependentStagesNeedsNoReason_whileSkippingAMandatoryOneStillDoes() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        List<StageDecisionDto> decisions = decisionsFor(ticketId, ownerRep);
        // Case C: S1 -> S8 crosses S2..S7, none of which is MANDATORY.
        assertThat(requiresReasonFor(decisions, DealStage.QUOTE_BUYER)).isFalse();
        assertThat(allowedFor(decisions, DealStage.QUOTE_BUYER)).isTrue();
        // Case B (owner buys direct) and Case D (all from stock) cross nothing mandatory either.
        assertThat(requiresReasonFor(decisions, DealStage.QUOTE_OWNER)).isFalse();
        assertThat(requiresReasonFor(decisions, DealStage.AWAITING_BUYER)).isFalse();
        // …but stepping over NEGOTIATION (S9), which every route has in common, does.
        assertThat(requiresReasonFor(decisions, DealStage.DELIVERY_SCHEDULING)).isTrue();

        // The decision is honoured on the write, in both directions.
        ticketService.updateStage(ticketId, DealStage.QUOTE_BUYER, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_BUYER);

        logAnActivityAndFollowUp(ticketId);
        assertThatThrownBy(() ->
            ticketService.updateStage(ticketId, DealStage.DELIVERY_SCHEDULING, null, ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).isEqualTo("การข้ามขั้นตอนต้องระบุเหตุผล");
            });
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_BUYER);
    }

    /**
     * {@code requiresReason} is reported for blocked stages too. It answers "what would this move
     * cost?", which does not depend on whether the move is currently permitted — suppressing it
     * would make the field mean different things in different rows.
     */
    @Test
    void requiresReasonIsReportedEvenForAStageTheCallerMayNotReach() {
        long ticketId = readyToAdvanceFrom(DealStage.DELIVERY_SCHEDULING);

        // import may view the deal but may not write a sales stage, so LEAD_APPROACH is blocked —
        // and moving there from S18 is a backward move, which would need a written reason.
        List<StageDecisionDto> decisions = decisionsFor(ticketId, importActor);
        assertThat(allowedFor(decisions, DealStage.LEAD_APPROACH)).isFalse();
        assertThat(reasonFor(decisions, DealStage.LEAD_APPROACH)).isEqualTo("ไม่มีสิทธิ์เข้าถึงรายการนี้");
        assertThat(requiresReasonFor(decisions, DealStage.LEAD_APPROACH)).isTrue();
    }

    // ═══ Part D — lifecycle, current stage, and the readiness gate ═══════════

    /**
     * A deal that is not ACTIVE, or is CLOSED_LOST, offers nothing — with the lifecycle as the
     * reason rather than a permission one, so the UI can say why instead of showing an empty list.
     * The current stage is likewise never on offer, and carries the same 409 copy
     * {@code updateStage} throws.
     */
    @Test
    void aNonActiveDealOffersNothing_andTheCurrentStageIsNeverOnOffer() {
        long ticketId = readyToAdvanceFrom(DealStage.NEGOTIATION);

        List<StageDecisionDto> active = decisionsFor(ticketId, ownerRep);
        assertThat(allowedFor(active, DealStage.NEGOTIATION)).isFalse();
        assertThat(reasonFor(active, DealStage.NEGOTIATION)).contains("อยู่ในขั้นตอน NEGOTIATION อยู่แล้ว");

        ticketService.markLost(ticketId, DealLostReason.PRICE, null, ownerRep);

        List<StageDecisionDto> lost = decisionsFor(ticketId, ownerRep);
        assertThat(allowedStages(lost)).isEmpty();
        assertThat(reasonFor(lost, DealStage.SPEC_APPROVED)).contains("ทำเครื่องหมายเสียงานแล้ว");
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, "x", ownerRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);
    }

    /**
     * The Slice B1 readiness gate is a forward-only gate, and the decision reflects that
     * asymmetry: with no follow-up date and no logged activity, every forward stage is blocked on
     * the tracking message while backward ones stay open.
     *
     * <p>The asymmetry is the assertion that matters. A decision builder that ran the readiness
     * gate unconditionally would look right on the forward half and would wrongly close the
     * backward half — which is exactly how a rep walks a deal back out of a wrong stage.
     */
    @Test
    void withoutFollowUpOrActivity_forwardStagesAreBlockedOnTheTrackingGate_butBackwardOnesAreNot() {
        long ticketId = createDealWithOneItem();
        tickets.updateSalesStage(ticketId, DealStage.NEGOTIATION);
        // Deliberately NO logAnActivityAndFollowUp() here.

        List<StageDecisionDto> decisions = decisionsFor(ticketId, ownerRep);
        assertThat(allowedFor(decisions, DealStage.DELIVERY_SCHEDULING)).isFalse();
        assertThat(reasonFor(decisions, DealStage.DELIVERY_SCHEDULING)).contains("ต้องระบุวันติดตามครั้งถัดไป");
        assertThat(allowedFor(decisions, DealStage.SPEC_APPROVED)).isTrue();

        assertThatThrownBy(() ->
            ticketService.updateStage(ticketId, DealStage.DELIVERY_SCHEDULING, "เหตุผล", ownerRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);

        // The backward move the decision said was open really is open, and needs no note (the one
        // routine pair) — proving the readiness gate was not applied to it.
        tickets.updateSalesStage(ticketId, DealStage.QUOTE_DESIGN_SIDE);
        ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.SPEC_APPROVED);
    }

    // ═══ Part E — the walk: every allowed decision is actually writable ══════

    /**
     * The cross-check that makes the rest of this class mean something: for the deal owner, on a
     * fresh deal, <b>every</b> stage the payload marks allowed is accepted by {@code updateStage},
     * and every stage it marks blocked is refused. Run over all fifteen, on a fresh deal each time
     * so one move cannot mask the next.
     *
     * <p>Without this, a payload that under-offered (say, one that dropped a stage the service
     * would have accepted) would sail through every other test here. That direction is the one
     * that silently breaks a real business route.
     */
    @Test
    void everyAllowedDecisionIsAccepted_andEveryBlockedOneIsRefused() {
        for (String target : DealStage.ORDER) {
            long ticketId = readyToAdvanceFrom(DealStage.NEGOTIATION);
            List<StageDecisionDto> decisions = decisionsFor(ticketId, ownerRep);
            boolean allowed = allowedFor(decisions, target);
            // A reason is always supplied when blocked, never when allowed.
            assertThat(reasonFor(decisions, target) == null).as("reason presence for %s", target)
                .isEqualTo(allowed);

            if (allowed) {
                ticketService.updateStage(ticketId, target, "เหตุผลประกอบ", ownerRep);
                assertThat(stageOf(ticketId)).as("%s was allowed and must be written", target)
                    .isEqualTo(target);
            } else {
                assertThatThrownBy(() -> ticketService.updateStage(ticketId, target, "เหตุผลประกอบ", ownerRep))
                    .as("%s was blocked and must be refused", target)
                    .isInstanceOf(ApiException.class);
                assertThat(stageOf(ticketId)).as("%s was blocked and must not move the deal", target)
                    .isEqualTo(DealStage.NEGOTIATION);
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<StageDecisionDto> decisionsFor(long ticketId, UserPrincipal actor) {
        return ticketService.actions(ticketId, actor).stageDecisions();
    }

    private TicketActionsResponse actionsFor(long ticketId, UserPrincipal actor) {
        return ticketService.actions(ticketId, actor);
    }

    private Set<String> actionNames(long ticketId, UserPrincipal actor) {
        return actionsFor(ticketId, actor).availableActions().stream()
            .map(TicketActionDto::action).collect(Collectors.toSet());
    }

    private Set<String> advanceTargets(long ticketId, UserPrincipal actor) {
        return actionsFor(ticketId, actor).availableActions().stream()
            .filter(a -> "ADVANCE_STAGE".equals(a.action()))
            .map(TicketActionDto::targetStage).collect(Collectors.toSet());
    }

    private static Set<String> allowedStages(List<StageDecisionDto> decisions) {
        return decisions.stream().filter(StageDecisionDto::allowed)
            .map(StageDecisionDto::stage).collect(Collectors.toSet());
    }

    private static StageDecisionDto decision(List<StageDecisionDto> decisions, String stage) {
        return decisions.stream().filter(d -> d.stage().equals(stage)).findFirst()
            .orElseThrow(() -> new AssertionError("no decision for " + stage));
    }

    private static boolean allowedFor(List<StageDecisionDto> decisions, String stage) {
        return decision(decisions, stage).allowed();
    }

    private static boolean requiresReasonFor(List<StageDecisionDto> decisions, String stage) {
        return decision(decisions, stage).requiresReason();
    }

    private static String reasonFor(List<StageDecisionDto> decisions, String stage) {
        return decision(decisions, stage).blockedReason();
    }

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
            "ดีลทดสอบการตัดสินขั้นตอน", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        return tickets.create(request, tickets.nextTicketCode(), ownerRepId, "เจ้าของดีล ทดสอบ");
    }

    private String stageOf(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private int stageChangedEvents(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STAGE_CHANGED), Integer.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-decide@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
