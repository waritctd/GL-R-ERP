package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p><b>Owner rules of 2026-10-05 (stage movement, M1-M5).</b> What the screen is told follows the
 * rules the server enforces: a hand move exists only inside ขั้น 1-9 and only onto
 * {@link #HAND_SETTABLE}; ขั้น 4, 5 and 8 are reached when a quotation is created; from ขั้น 10 the
 * stage is system-only for every role, and a deal SITTING at ขั้น 10 or later cannot be moved by hand
 * at all. So {@code stageDecisions} never marks a forbidden stage {@code allowed}, and {@code actions}
 * never advertises ADVANCE_STAGE (or the generic UPDATE_STAGE) towards one — the screen only offers
 * what will work. The role gate's own refusals (403 on a wrong role, 400 on a missing reason, 409 on
 * a lost deal or an off-route ขั้น 6/7) are unchanged and keep their exact status and text; a NEW
 * refusal is asserted as {@code ApiException} + stage unchanged + no {@code STAGE_CHANGED} event,
 * without its status or message ({@link #assertHandMoveRefused}).
 *
 * <p>Modelled on {@link StageFactGateIntegrationTest} and
 * {@link DealStageQuoteOwnerAndRouteIntegrationTest}.
 */
class StageDecisionIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * M1: the six stages a hand move may land on (ขั้น 1, 2, 3, 6, 7, 9) — the only stages a decision may
     * ever mark {@code allowed} for any role. Everything else is either a quotation's job (ขั้น 4, 5, 8)
     * or the system's (ขั้น 10 on).
     */
    private static final Set<String> HAND_SETTABLE = Set.of(
        DealStage.LEAD_APPROACH, DealStage.PRESENTATION, DealStage.SPEC_APPROVED,
        DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER, DealStage.NEGOTIATION);

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
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

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
     * ฝ่ายบัญชี and ฝ่ายนำเข้า (the 403s below, unchanged) — and, since the owner rules of 2026-10-05,
     * is offered ONLY the hand-settable stages: of the twelve sales-gated stages the old payload
     * offered a fresh deal at PRESENTATION, ขั้น 4, 5, 8 (a quotation's job) and ขั้น 13 (the system's)
     * are gone, leaving ขั้น 1, 3, 6, 7 and 9. The exact set is asserted so that a stage creeping back
     * into the offer cannot slip past, together with the real write of each stage that left it: the
     * old code accepted every one of those four moves, so each refusal is the new rule's.
     *
     * <p>Each of the four refused moves gets its OWN fresh deal at PRESENTATION: on code that still
     * accepts a move, the deal leaves PRESENTATION, so four moves tried one after another on a single deal
     * would report the first one only (the other three would fail on "fixture: the deal sits at
     * PRESENTATION" and say nothing about ขั้น 5, 8 and 13). The decision assertions stay on the first deal.
     */
    @Test
    void theDealOwner_isOfferedOnlyTheHandSettableStages_andIsRefusedTheMoneyAndImportOnes() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);
        List<StageDecisionDto> decisions = decisionsFor(ticketId, ownerRep);

        // Unchanged: never the money or import stages, and a real 403 on each of them.
        assertThat(allowedStages(decisions))
            .doesNotContain(DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID, DealStage.PROCUREMENT);
        for (String forbidden : List.of(DealStage.DEPOSIT_RECEIVED, DealStage.CLOSED_PAID, DealStage.PROCUREMENT)) {
            assertThatThrownBy(() -> ticketService.updateStage(ticketId, forbidden, "เหตุผล", ownerRep))
                .describedAs("owner must not reach %s", forbidden)
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();

        // M1 / M2 / M4 — decision and enforcement together, every failing part reported. One fresh deal per
        // refused move, so that each case reports its own refusal (see the Javadoc).
        long toQuoteDesignSide = readyToAdvanceFrom(DealStage.PRESENTATION);
        long toQuoteOwner = readyToAdvanceFrom(DealStage.PRESENTATION);
        long toQuoteBuyer = readyToAdvanceFrom(DealStage.PRESENTATION);
        long toDeliveryScheduling = readyToAdvanceFrom(DealStage.PRESENTATION);
        assertAll(
            () -> assertThat(allowedStages(decisions))
                .containsExactlyInAnyOrderElementsOf(handSettableExcept(DealStage.PRESENTATION)),
            () -> assertHandMoveRefused(toQuoteDesignSide, DealStage.PRESENTATION,
                "the owning rep must not hand-move onto QUOTE_DESIGN_SIDE (ขั้น 4)",
                () -> ticketService.updateStage(toQuoteDesignSide, DealStage.QUOTE_DESIGN_SIDE, "เหตุผล", ownerRep)),
            () -> assertHandMoveRefused(toQuoteOwner, DealStage.PRESENTATION,
                "the owning rep must not hand-move onto QUOTE_OWNER (ขั้น 5)",
                () -> ticketService.updateStage(toQuoteOwner, DealStage.QUOTE_OWNER, "เหตุผล", ownerRep)),
            () -> assertHandMoveRefused(toQuoteBuyer, DealStage.PRESENTATION,
                "the owning rep must not hand-move onto QUOTE_BUYER (ขั้น 8)",
                () -> ticketService.updateStage(toQuoteBuyer, DealStage.QUOTE_BUYER, "เหตุผล", ownerRep)),
            () -> assertHandMoveRefused(toDeliveryScheduling, DealStage.PRESENTATION,
                "the owning rep must not hand-move onto DELIVERY_SCHEDULING (ขั้น 13)",
                () -> ticketService.updateStage(toDeliveryScheduling, DealStage.DELIVERY_SCHEDULING, "เหตุผล",
                    ownerRep)));
    }

    /**
     * Since the owner rules of 2026-10-05 neither ฝ่ายบัญชี nor ฝ่ายนำเข้า is OFFERED a stage at all: the two
     * money stages and the import stage are past ขั้น 10, so they are the system's. What survives is the
     * role gate on everything else — every sales stage is still a 403 for both, with the same text.
     * (This was {@code accountAndImport_areEachConfinedToTheirOwnStages}, which asserted that import was
     * offered exactly PROCUREMENT; the account half is unchanged in effect, and its facts-recorded cases
     * live in {@link #aFormerlyGatedStageIsStillNotAllowedOnceItsFactHolds_andTheWriteIsRefusedToo}.)
     */
    @Test
    void accountAndImport_areOfferedNothing_theirOwnStagesAreSystemOnlyNow() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);
        // H1 lockdown: account only reaches a deal inside its list scope, so its half of this test runs on
        // its own deal at ORDER_RECEIVED (S10). Import keeps the original PRESENTATION deal.
        long accountTicketId = readyToAdvanceFrom(DealStage.ORDER_RECEIVED);

        // account: nothing on offer, and every sales stage is still a 403 with the role-gate text.
        // The reason on a money stage is whatever the server now says (it is no longer "the fact is
        // missing"): only that it is blocked, with a reason, is pinned.
        List<StageDecisionDto> accountDecisions = decisionsFor(accountTicketId, accountActor);
        assertThat(allowedStages(accountDecisions)).isEmpty();
        assertThat(reasonFor(accountDecisions, DealStage.SPEC_APPROVED)).isEqualTo("ไม่มีสิทธิ์เข้าถึงรายการนี้");
        assertThat(allowedFor(accountDecisions, DealStage.DEPOSIT_RECEIVED)).isFalse();
        assertThat(reasonFor(accountDecisions, DealStage.DEPOSIT_RECEIVED)).isNotBlank();
        assertThatThrownBy(() -> ticketService.financeUpdateStage(accountTicketId, DealStage.SPEC_APPROVED, "x", accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // import: every sales stage is a 403 for it, exactly as before.
        List<StageDecisionDto> importDecisions = decisionsFor(ticketId, importActor);
        assertThat(reasonFor(importDecisions, DealStage.NEGOTIATION)).isEqualTo("ไม่มีสิทธิ์เข้าถึงรายการนี้");
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.NEGOTIATION, "x", importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageOf(accountTicketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(stageChangedEvents(ticketId)).isZero();
        assertThat(stageChangedEvents(accountTicketId)).isZero();

        // M4: PROCUREMENT was the one stage ฝ่ายนำเข้า was offered, with no fact behind it; it is the
        // system's now — not offered, not advertised, and the old-accepted hand move is refused.
        assertAll(
            () -> assertThat(allowedStages(importDecisions)).isEmpty(),
            () -> assertThat(advanceTargets(ticketId, importActor)).doesNotContain(DealStage.PROCUREMENT),
            () -> assertHandMoveRefused(ticketId, DealStage.PRESENTATION,
                "ฝ่ายนำเข้า must not hand-move onto PROCUREMENT (ขั้น 12)",
                () -> ticketService.updateStage(ticketId, DealStage.PROCUREMENT, "นำเข้าเริ่มแล้ว", importActor)));
    }

    /**
     * sales_manager passes the sales-gated stages on a deal it does not own (the deliberate,
     * user-approved exception noted on {@code TicketService}'s pipeline section) but must still be
     * refused the money and import stages (unchanged, 403). Both it and the CEO are now offered exactly
     * what the owning rep is — the hand-settable stages — and nothing the CEO could once reach beyond
     * them: the CEO's PROCUREMENT, ขั้น 4/5/8 and ขั้น 13 all left the offer.
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
        assertThat(allowedStages(decisionsFor(ticketId, ceo))).contains(DealStage.SPEC_APPROVED);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();

        // M1 / M2 / M4, for the two roles that may write sales stages on a deal they do not own.
        assertAll(
            () -> assertThat(allowedStages(decisionsFor(ticketId, salesManager)))
                .as("ผจก.ขาย is offered exactly the hand-settable stages")
                .containsExactlyInAnyOrderElementsOf(handSettableExcept(DealStage.PRESENTATION)),
            () -> assertThat(allowedStages(decisionsFor(ticketId, ceo)))
                .as("the CEO is offered exactly the hand-settable stages")
                .containsExactlyInAnyOrderElementsOf(handSettableExcept(DealStage.PRESENTATION)),
            () -> assertThat(advanceTargets(ticketId, ceo)).as("the CEO's ADVANCE_STAGE adverts")
                .containsExactlyInAnyOrderElementsOf(handSettableExcept(DealStage.PRESENTATION)),
            () -> assertHandMoveRefused(ticketId, DealStage.PRESENTATION,
                "the CEO must not hand-move onto PROCUREMENT (ขั้น 12)",
                () -> ticketService.updateStage(ticketId, DealStage.PROCUREMENT, "เหตุผล", ceo)));
    }

    // ═══ Part B — the four formerly fact-gated stages (#710) reach the decision, not just the write ═

    /**
     * The four formerly fact-gated stages come back not-allowed, with a reason, for the role that
     * <em>does</em> hold the write permission — so the client can say why instead of hiding the stage
     * or offering a click that is refused. Since the owner rules of 2026-10-05 (M4) the reason is no
     * longer "the fact is missing" (the stage is system-only from ขั้น 10 whatever the fact), so the
     * sentence is deliberately not pinned: only that the stage is blocked, that a reason is given, that
     * the advertised verb list agrees, and that the real write is refused with EXACTLY the text the
     * decision carried — the pairing this whole class exists to keep.
     *
     * <p>This is the case a role-only decision payload would have got wrong, and the case the
     * previous {@code addStageActions} did get wrong: it advertised ADVANCE_STAGE into all four.
     */
    @Test
    void theFourFormerlyFactGatedStages_areBlockedWithAReason_forTheRoleThatMayWriteThem() {
        long ticketId = readyToAdvanceFrom(DealStage.NEGOTIATION);
        // H1 lockdown: the two money stages are exercised by account on ITS OWN in-scope deal (S10).
        long accountTicketId = readyToAdvanceFrom(DealStage.ORDER_RECEIVED);

        Map<String, UserPrincipal> byPrincipal = Map.of(
            DealStage.ORDER_RECEIVED, ownerRep,
            DealStage.DELIVERED, ownerRep,
            DealStage.DEPOSIT_RECEIVED, accountActor,
            DealStage.CLOSED_PAID, accountActor);

        byPrincipal.forEach((stage, actor) -> {
            long dealId = "account".equals(actor.role()) ? accountTicketId : ticketId;
            List<StageDecisionDto> decisions = decisionsFor(dealId, actor);
            assertThat(allowedFor(decisions, stage)).as("%s must not be allowed", stage).isFalse();
            assertThat(reasonFor(decisions, stage)).as("reason for %s", stage).isNotBlank();
            // The advertised verb list agrees with the decision — no dead ADVANCE_STAGE.
            assertThat(advanceTargets(dealId, actor)).as("%s must not be advertised", stage)
                .doesNotContain(stage);
            // And the write really is refused, with the same message the decision carried.
            assertThatThrownBy(() -> updateStageAs(dealId, stage, "มีเหตุผลครบ", actor))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getMessage()).isEqualTo(reasonFor(decisionsFor(dealId, actor), stage)));
        });

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);
        assertThat(stageOf(accountTicketId)).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(stageChangedEvents(ticketId)).isZero();
        assertThat(stageChangedEvents(accountTicketId)).isZero();
    }

    /**
     * M4 and M5, the flip of "once the fact holds, the stage flips to allowed and the write goes
     * through": with the fact RECORDED the stage is STILL not allowed, is not advertised as an
     * ADVANCE_STAGE, and the hand move is refused — for every role that could write the stage (the
     * owning rep, ฝ่ายบัญชี through the finance entrypoint, the CEO) on all four stages. Each case is a
     * deal on which the old payload said {@code allowed: true} and the old code accepted the write.
     * Parameterised so each (stage, role) pair is reported on its own.
     */
    @ParameterizedTest(name = "[{index}] {0} for {1}, with its fact recorded")
    @MethodSource("theFormerlyGatedStagesAndEveryRoleThatCouldWriteThem")
    void aFormerlyGatedStageIsStillNotAllowedOnceItsFactHolds_andTheWriteIsRefusedToo(String stage, String role) {
        long ticketId = readyToAdvanceFrom(DealStage.ORDER.get(DealStage.indexOf(stage) - 1));
        recordTheFactBehind(ticketId, stage);
        UserPrincipal actor = actorFor(role);
        String parkedAt = DealStage.ORDER.get(DealStage.indexOf(stage) - 1);

        assertAll(
            () -> assertThat(allowedFor(decisionsFor(ticketId, actor), stage))
                .as("%s must not be allowed for %s although its fact is recorded", stage, role).isFalse(),
            () -> assertThat(advanceTargets(ticketId, actor))
                .as("%s must not be advertised to %s although its fact is recorded", stage, role)
                .doesNotContain(stage),
            () -> assertHandMoveRefused(ticketId, parkedAt,
                role + " must not hand-move onto " + stage + " although its fact is recorded",
                () -> updateStageAs(ticketId, stage, "ข้อเท็จจริงครบแล้ว", actor)));
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
     * M4 + M5 for a deal that already SITS at ขั้น 10 or later: it is offered no stage at all — not even
     * a backward one into ขั้น 1-9 — and neither ADVANCE_STAGE nor the generic UPDATE_STAGE is
     * advertised, for the owning rep, ผจก.ขาย, the CEO and ฝ่ายนำเข้า. (UPDATE_STAGE is advertised iff
     * some stage is allowed, so its absence is the whole screen's "there is nothing to do here".) The
     * old payload offered every backward sales stage to the first three and PROCUREMENT to ฝ่ายนำเข้า,
     * so each of these cases is red before the rule exists, except ฝ่ายนำเข้า on a deal already AT
     * PROCUREMENT, which had nothing to offer either way. ฝ่ายบัญชี's own cases carry the facts and
     * live in {@link #aFormerlyGatedStageIsStillNotAllowedOnceItsFactHolds_andTheWriteIsRefusedToo}.
     */
    @ParameterizedTest(name = "[{index}] sitting at {0}, as {1}")
    @MethodSource("everyStageFromOrderReceivedOnAndEveryRoleExceptAccount")
    void aDealSittingAtOrderReceivedOrLater_isOfferedNoStageAtAll_andNoStageVerbIsAdvertised(String sittingAt, String role) {
        long ticketId = readyToAdvanceFrom(sittingAt);
        UserPrincipal actor = actorFor(role);

        assertAll(
            () -> assertThat(allowedStages(decisionsFor(ticketId, actor)))
                .as("stages offered to %s on a deal sitting at %s", role, sittingAt).isEmpty(),
            () -> assertThat(actionNames(ticketId, actor))
                .as("stage verbs advertised to %s on a deal sitting at %s", role, sittingAt)
                .doesNotContain("ADVANCE_STAGE", "UPDATE_STAGE"));
    }

    static Stream<Arguments> everyStageFromOrderReceivedOnAndEveryRoleExceptAccount() {
        return DealStage.ORDER.subList(DealStage.indexOf(DealStage.ORDER_RECEIVED), DealStage.ORDER.size())
            .stream()
            .flatMap(stage -> Stream.of("sales", "sales_manager", "ceo", "import")
                .map(role -> Arguments.of(stage, role)));
    }

    // ═══ Part C — the note rule, and the routes it must not tax ══════════════

    /**
     * The bug this whole change removes, pinned on a real deal — re-expressed with the hand-settable
     * stages now that a hand move exists only onto ขั้น 1, 2, 3, 6, 7 and 9.
     *
     * <p>A jump that crosses only route-dependent stages (S1 -&gt; S7 steps over S2..S6, none of them
     * MANDATORY) must be offered with {@code requiresReason: false} — the frontend's stale copy
     * demanded a written reason for exactly this kind of move — and is accepted without a note. The
     * stages that used to carry the other half of this test are no longer offered to the rep at all:
     * QUOTE_BUYER and QUOTE_OWNER (a quotation's job, M2) and DELIVERY_SCHEDULING (the system's, M4).
     * {@code requiresReason} is still REPORTED for them (a stage that cannot be reached still says
     * what reaching it would cost — see {@link #requiresReasonIsReportedEvenForAStageTheCallerMayNotReach}),
     * so the one thing this class could only say about a hand move onto S13 — that skipping NEGOTIATION
     * needs a reason — survives as a reported fact and no longer as a refusal one can trigger.
     */
    @Test
    void skippingOnlyRouteDependentStagesNeedsNoReason_andTheStagesBeyondTheHandAreNotOffered() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);
        List<StageDecisionDto> decisions = decisionsFor(ticketId, ownerRep);

        // S1 -> S7 crosses S2..S6, none of which is MANDATORY: no reason, and on offer.
        assertThat(requiresReasonFor(decisions, DealStage.AWAITING_BUYER)).isFalse();
        assertThat(allowedFor(decisions, DealStage.AWAITING_BUYER)).isTrue();
        // The cost is reported even for stages that can no longer be reached by hand.
        assertThat(requiresReasonFor(decisions, DealStage.QUOTE_BUYER)).isFalse();
        assertThat(requiresReasonFor(decisions, DealStage.QUOTE_OWNER)).isFalse();
        assertThat(requiresReasonFor(decisions, DealStage.DELIVERY_SCHEDULING)).isTrue();

        // The decision is honoured on the write.
        ticketService.updateStage(ticketId, DealStage.AWAITING_BUYER, null, ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.AWAITING_BUYER);

        // M2 / M4: the three stages beyond the hand are not on offer to the owning rep. The old payload
        // said allowed: true for each of them on this very deal.
        assertAll(
            () -> assertThat(allowedFor(decisions, DealStage.QUOTE_BUYER))
                .as("QUOTE_BUYER (ขั้น 8) is a quotation's job, not on offer by hand").isFalse(),
            () -> assertThat(allowedFor(decisions, DealStage.QUOTE_OWNER))
                .as("QUOTE_OWNER (ขั้น 5) is a quotation's job, not on offer by hand").isFalse(),
            () -> assertThat(allowedFor(decisions, DealStage.DELIVERY_SCHEDULING))
                .as("DELIVERY_SCHEDULING (ขั้น 13) is the system's, not on offer by hand").isFalse());
    }

    /**
     * {@code requiresReason} is reported for blocked stages too. It answers "what would this move
     * cost?", which does not depend on whether the move is currently permitted — suppressing it
     * would make the field mean different things in different rows.
     *
     * <p>The deal sits at ขั้น 13, where since the owner rules of 2026-10-05 NO hand move is permitted
     * for anyone, so the text of the blocking reason on LEAD_APPROACH is the new rule's to choose and is
     * not pinned here: only that it is blocked, that it says why, and that the cost is still reported.
     */
    @Test
    void requiresReasonIsReportedEvenForAStageTheCallerMayNotReach() {
        long ticketId = readyToAdvanceFrom(DealStage.DELIVERY_SCHEDULING);

        // import may view the deal but may not write a sales stage, so LEAD_APPROACH is blocked —
        // and moving there from S18 is a backward move, which would need a written reason.
        List<StageDecisionDto> decisions = decisionsFor(ticketId, importActor);
        assertThat(allowedFor(decisions, DealStage.LEAD_APPROACH)).isFalse();
        assertThat(reasonFor(decisions, DealStage.LEAD_APPROACH)).isNotBlank();
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
     *
     * <p>The forward target is AWAITING_BUYER (ขั้น 7) from PRESENTATION: since the owner rules of
     * 2026-10-05 every forward hand move from ขั้น 9 on is the system's, so a deal at NEGOTIATION no
     * longer has a hand-settable forward stage to show the gate on. The tracking gate is the subject;
     * the target only has to be one a hand move may still land on.
     */
    @Test
    void withoutFollowUpOrActivity_forwardStagesAreBlockedOnTheTrackingGate_butBackwardOnesAreNot() {
        long ticketId = createDealWithOneItem();
        tickets.updateSalesStage(ticketId, DealStage.PRESENTATION);
        // Deliberately NO logAnActivityAndFollowUp() here.

        List<StageDecisionDto> decisions = decisionsFor(ticketId, ownerRep);
        assertThat(allowedFor(decisions, DealStage.AWAITING_BUYER)).isFalse();
        assertThat(reasonFor(decisions, DealStage.AWAITING_BUYER)).contains("ต้องระบุวันติดตามครั้งถัดไป");
        assertThat(allowedFor(decisions, DealStage.LEAD_APPROACH)).isTrue();

        assertThatThrownBy(() ->
            ticketService.updateStage(ticketId, DealStage.AWAITING_BUYER, "เหตุผล", ownerRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);

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

    // H1 lockdown: account reads its stage decisions through financeActions (same gates, no /tickets view
    // check) and writes a money stage through financeUpdateStage; every other role is unchanged.
    private List<StageDecisionDto> decisionsFor(long ticketId, UserPrincipal actor) {
        return actionsFor(ticketId, actor).stageDecisions();
    }

    private TicketActionsResponse actionsFor(long ticketId, UserPrincipal actor) {
        return "account".equals(actor.role())
            ? ticketService.financeActions(ticketId, actor)
            : ticketService.actions(ticketId, actor);
    }

    private void updateStageAs(long ticketId, String stage, String note, UserPrincipal actor) {
        if ("account".equals(actor.role())) {
            ticketService.financeUpdateStage(ticketId, stage, note, actor);
        } else {
            ticketService.updateStage(ticketId, stage, note, actor);
        }
    }

    /**
     * How a NEW refusal of a hand move is asserted (owner rules of 2026-10-05): {@code ApiException}
     * thrown, {@code sales_stage} unchanged, no {@code STAGE_CHANGED} event written — and not the status
     * or the message (403 and 409 are both acceptable). The callers' fixtures are ones the OLD code
     * accepted, so a refusal is evidence of the new rule and not of some unrelated gate.
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

    /** {@link #HAND_SETTABLE} minus the deal's own current stage (a decision never offers that one). */
    private static Set<String> handSettableExcept(String currentStage) {
        return HAND_SETTABLE.stream().filter(stage -> !stage.equals(currentStage)).collect(Collectors.toSet());
    }

    /** Records on the row the fact the OLD gate demanded for {@code stage} (CLOSED_PAID: payment AND delivery). */
    private void recordTheFactBehind(long ticketId, String stage) {
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
    }

    private UserPrincipal actorFor(String role) {
        return switch (role) {
            case "sales" -> ownerRep;
            case "sales_manager" -> salesManager;
            case "ceo" -> ceo;
            case "import" -> importActor;
            case "account" -> accountActor;
            default -> throw new IllegalArgumentException("no such actor in this class: " + role);
        };
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
