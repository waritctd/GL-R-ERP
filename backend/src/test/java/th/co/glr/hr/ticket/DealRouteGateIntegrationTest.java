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
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert;
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
import th.co.glr.hr.ticket.TicketResponses.StageDecisionDto;

/**
 * Real-Postgres proof that a deal's {@link EntryChannel} route (rung 6 of the stage-move
 * precedence ladder, design §12.1) is ENFORCED by the real {@code TicketService} against a real
 * {@code sales.ticket} row — not merely decided by {@link DealRoute}.
 *
 * <p>Authorization-adjacent (rung 6 decides which manual stage writes are accepted), so per
 * CLAUDE.md this goes through the real service and repository. <b>Nothing here may be inferred from
 * {@code mockApi.js}.</b>
 *
 * <p><b>Written wrong-way-round.</b> Each part leads with what a caller CANNOT reach — an
 * owner-direct deal refused a designer quote, a buyer-direct deal refused four stages — and every
 * refusal re-reads the row to prove it did not move and no {@code STAGE_CHANGED} event was written.
 * The two safety properties (design §13.1c / §13.2) get their own tests: an UNSPECIFIED deal is
 * refused NOTHING (the unrecoverable-brick case), and neither is a DESIGNER_LED deal.
 *
 * <p><b>The single most important detail (design §12.1): the gate tests the TARGET only, never the
 * current stage.</b> A deal may legitimately be SITTING on an off-route stage — it got there before
 * the channel was corrected — and gating the current stage would strand it with no legal move.
 *
 * <p>Trap, per {@link AbstractPostgresIntegrationTest}: services are hand-wired with {@code new},
 * so {@code @Transactional} is inert and no rollback is exercised. The "did not move" assertions
 * hold because the guard throws before any write.
 *
 * <p><b>Owner rules of 2026-10-05 (stage movement, M1-M4).</b> A hand move exists only onto ขั้น 1,
 * 2, 3, 6, 7 and 9; ขั้น 4, 5 and 8 are reached when a quotation is created for that party and are
 * refused for EVERYONE by hand, on-route or not; from ขั้น 10 the stage is system-only. This class's
 * subject is the route, so it keeps proving it on stages a hand move may still land on (ขั้น 6 and 7,
 * whose route refusal keeps its exact status and text) and states the route-independent refusals of ขั้น
 * 4/5/8 as "refused" only ({@link #assertHandMoveRefused}: {@code ApiException}, stage unchanged, no
 * {@code STAGE_CHANGED} event — which of the two refusals speaks first is not the route's to say).
 *
 * <p>Modelled on {@link StageDecisionIntegrationTest} and {@link SalesRouteWalkIntegrationTest}.
 */
class DealRouteGateIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * The party stages a hand move may still land on (ขั้น 6 and 7): the only ones whose route refusal is
     * still the refusal a rep sees, and so the only ones whose exact text this class keeps pinning.
     */
    private static final Set<String> HAND_SETTABLE_PARTY_STAGES =
        Set.of(DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER);

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private UserPrincipal accountActor;
    private UserPrincipal ceoActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "เจ้าของดีล เส้นทางดีล", "dealroute-owner@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        accountActor = principal(createEmployee(employees, "ฝ่ายบัญชี เส้นทางดีล", "dealroute-account@glr.co.th"), "account");
        ceoActor = principal(createEmployee(employees, "ซีอีโอ เส้นทางดีล", "dealroute-ceo@glr.co.th"), "ceo");
    }

    // ═══ Part A — what an owner-direct / buyer-direct deal CANNOT reach ═══════

    /**
     * No designer to quote: S4 is off the owner-direct route, and ขั้น 4 is a quotation's job anyway
     * (M2), so a hand move onto it is refused. It used to be pinned as the route's own 409 naming channel
     * + remedy; that status and text are no longer asserted, because which refusal speaks first is not
     * the route's to say (the route's own text is still pinned on ขั้น 6 and 7 below).
     */
    @Test
    void ownerDirect_isRefusedTheDesignerQuote_andTheRowDoesNotMove() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);

        assertHandMoveRefused(ticketId, DealStage.PRESENTATION,
            "an owner-direct deal must not be moved onto QUOTE_DESIGN_SIDE (ขั้น 4) by hand",
            () -> ticketService.updateStage(ticketId, DealStage.QUOTE_DESIGN_SIDE, "เหตุผล", ownerRep));

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /**
     * Buyer-direct: no designer, the project owner is not the counterparty, S7 off-path. ขั้น 6 and 7 are
     * still hand-settable, so their refusal is the ROUTE's and keeps its exact status and text; ขั้น 4 and
     * 5 are a quotation's job (M2), so for them only "refused" is asserted.
     */
    @Test
    void buyerDirect_isRefusedEachOfTheFourPartyStages() {
        for (String offRoute : List.of(DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
                DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER)) {
            long ticketId = dealAt(EntryChannel.BUYER_DIRECT, DealStage.PRESENTATION);

            if (HAND_SETTABLE_PARTY_STAGES.contains(offRoute)) {
                assertRefusedByRoute(ticketId, offRoute, "ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง");
            } else {
                assertHandMoveRefused(ticketId, DealStage.PRESENTATION,
                    "a buyer-direct deal must not be moved onto " + offRoute + " by hand",
                    () -> ticketService.updateStage(ticketId, offRoute, "เหตุผล", ownerRep));
            }

            assertThat(stageOf(ticketId)).as("deal must not move to %s", offRoute)
                .isEqualTo(DealStage.PRESENTATION);
            assertThat(stageChangedEvents(ticketId)).isZero();
        }
    }

    /**
     * Only the OFF-route stages are refused: on an owner-direct deal S3 (kept, only re-worded) and
     * the rest of the hand-settable route still move normally. Guards against a gate that over-reaches.
     * ขั้น 5 and 8 are ON this route but are a quotation's job (M2): they are not reached by hand, which
     * is asserted last — the old code accepted both moves, on fresh deals so one cannot mask the other.
     */
    @Test
    void ownerDirect_stillReachesTheHandSettableStagesOnItsRoute_butNotTheQuotationStages() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);

        for (String onRoute : List.of(DealStage.SPEC_APPROVED, DealStage.OWNER_SIGNOFF,
                DealStage.AWAITING_BUYER, DealStage.NEGOTIATION)) {
            logAnActivityAndFollowUp(ticketId);
            assertThatCode(() -> ticketService.updateStage(ticketId, onRoute, null, ownerRep))
                .as("owner-direct deal must reach on-route %s", onRoute).doesNotThrowAnyException();
            assertThat(stageOf(ticketId)).isEqualTo(onRoute);
        }

        long toQuoteOwner = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);
        long toQuoteBuyer = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);
        assertAll(
            () -> assertHandMoveRefused(toQuoteOwner, DealStage.PRESENTATION,
                "an owner-direct deal must not be moved onto QUOTE_OWNER (ขั้น 5, on its route) by hand",
                () -> ticketService.updateStage(toQuoteOwner, DealStage.QUOTE_OWNER, null, ownerRep)),
            () -> assertHandMoveRefused(toQuoteBuyer, DealStage.PRESENTATION,
                "an owner-direct deal must not be moved onto QUOTE_BUYER (ขั้น 8, on its route) by hand",
                () -> ticketService.updateStage(toQuoteBuyer, DealStage.QUOTE_BUYER, null, ownerRep)));
    }

    // ═══ Part B — the two safety properties: nothing is refused ═══════════════

    /**
     * Design §13.1c — the unrecoverable-brick case. Pre-V144 rows and quotation-first ghosts carry
     * UNSPECIFIED and GLA-156 removed the in-portal way to correct it, so a gate on it would brick
     * those deals. Every party stage must still be reachable, one after another: ขั้น 6 and 7 by hand, and
     * ขั้น 4, 5 and 8 — which are no longer reachable by hand (M2) — by the quotation's own advance,
     * which the route must not refuse for UNSPECIFIED either.
     */
    @Test
    void unspecified_isRefusedNothing_everyStageStillReachable() {
        long ticketId = dealAt(EntryChannel.UNSPECIFIED, DealStage.PRESENTATION);

        assertNoStageIsOffRoute(decisionsFor(ticketId));
        for (String stage : List.of(DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER)) {
            logAnActivityAndFollowUp(ticketId);
            assertThatCode(() -> ticketService.updateStage(ticketId, stage, null, ownerRep))
                .as("UNSPECIFIED deal must reach %s", stage).doesNotThrowAnyException();
            assertThat(stageOf(ticketId)).isEqualTo(stage);
        }

        for (Map.Entry<String, String> quotation : List.of(
                Map.entry(QuotationRecipient.DESIGNER, DealStage.QUOTE_DESIGN_SIDE),
                Map.entry(QuotationRecipient.OWNER, DealStage.QUOTE_OWNER),
                Map.entry(QuotationRecipient.BUYER, DealStage.QUOTE_BUYER))) {
            long quoted = dealAt(EntryChannel.UNSPECIFIED, DealStage.PRESENTATION);
            assertThatCode(() -> ticketService.advanceStageForCustomerQuotationIssue(
                quoted, quotation.getKey(), ownerRep))
                .as("a %s quotation on an UNSPECIFIED deal must not be refused", quotation.getKey())
                .doesNotThrowAnyException();
            assertThat(stageOf(quoted)).as("stage after a %s quotation", quotation.getKey())
                .isEqualTo(quotation.getValue());
        }
    }

    /** Design §13.2 — the majority route and the whole back catalogue see no behavioural change. */
    @Test
    void designerLed_isRefusedNothingByTheRoute_andOffersTheSameStagesAsUnspecified() {
        long designerLed = dealAt(EntryChannel.DESIGNER_LED, DealStage.PRESENTATION);
        long unspecified = dealAt(EntryChannel.UNSPECIFIED, DealStage.PRESENTATION);

        assertNoStageIsOffRoute(decisionsFor(designerLed));
        assertThat(allowedStages(decisionsFor(designerLed)))
            .isEqualTo(allowedStages(decisionsFor(unspecified)));

        for (String stage : List.of(DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER)) {
            logAnActivityAndFollowUp(designerLed);
            assertThatCode(() -> ticketService.updateStage(designerLed, stage, null, ownerRep))
                .as("designer-led deal must reach %s", stage).doesNotThrowAnyException();
        }
    }

    // ═══ Part C — the stranding case: the gate tests the TARGET, never the current stage ═══

    /**
     * A deal sitting ON an off-route stage (it got there, then the channel was corrected to
     * owner-direct) must still be able to move FORWARD. Gating the current stage would strand it.
     * The forward target is OWNER_SIGNOFF (ขั้น 6, on the owner-direct route): the target it used to be,
     * QUOTE_OWNER, is a quotation's job now (M2) and the stranding question is about the CURRENT stage,
     * not about which stage the deal then lands on.
     */
    @Test
    void aDealSittingOnAnOffRouteStage_canStillMoveForward() {
        long ticketId = dealAt(EntryChannel.DESIGNER_LED, DealStage.QUOTE_DESIGN_SIDE);
        tickets.updateEntryChannel(ticketId, EntryChannel.OWNER_DIRECT); // the correction

        assertThat(DealRoute.isOnRoute(EntryChannel.OWNER_DIRECT, stageOf(ticketId))).isFalse();
        assertThat(decision(decisionsFor(ticketId), DealStage.OWNER_SIGNOFF).allowed()).isTrue();

        logAnActivityAndFollowUp(ticketId);
        assertThatCode(() -> ticketService.updateStage(ticketId, DealStage.OWNER_SIGNOFF, null, ownerRep))
            .doesNotThrowAnyException();
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.OWNER_SIGNOFF);
    }

    /** …and along the routine S4 -> S3 backward pair too, which needs no reason. */
    @Test
    void aDealSittingOnAnOffRouteStage_canStillTakeTheRoutineBackwardStep() {
        long ticketId = dealAt(EntryChannel.DESIGNER_LED, DealStage.QUOTE_DESIGN_SIDE);
        tickets.updateEntryChannel(ticketId, EntryChannel.OWNER_DIRECT);

        assertThatCode(() -> ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, ownerRep))
            .doesNotThrowAnyException();
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.SPEC_APPROVED);
    }

    // ═══ Part D — the ladder: earlier rungs speak first ═══════════════════════

    /**
     * A hand move onto a money stage is never reported as a ROUTE refusal. This was "rung 5 (fact gate)
     * reports the FACT, not the route": a missing deposit surfaced as the fact's own 409. Since the owner
     * rules of 2026-10-05 (M4) ขั้น 11 is system-only whatever the fact, so the reason is the new rule's to
     * word — its status and text are not pinned — and what survives of this ladder test is the half that
     * never depended on the fact: an owner-direct deal asked for DEPOSIT_RECEIVED is refused, and never
     * with the route's remedy ("fix the deal channel"), because that stage is on every route. (Honest
     * limit, unchanged: no target can be both off-route and fact-gated, so this cannot distinguish the
     * two orderings.)
     */
    @Test
    void aHandMoveOntoDepositReceived_isRefused_andNeverReportedAsARouteRefusal() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.NEGOTIATION);

        // H1: account's money-stage moves go through the finance route now (and only in its list scope), so the
        // /tickets stage path is exercised with the CEO, who keeps it.
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.DEPOSIT_RECEIVED, "เหตุผล", ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getMessage()).doesNotContain("แก้ช่องทางดีล"));
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /**
     * Rung 4 (target == current) precedes the route: a deal sitting on an off-route stage asked to go
     * to that same stage. The stage is ขั้น 6 on a buyer-direct deal (off its route) rather than ขั้น 4 on
     * an owner-direct one, which this used to use: ขั้น 4 is a quotation's job now (M2), and the question
     * here is only which of the target-equals-current and the route refusals speaks first.
     */
    @Test
    void targetEqualToCurrent_isReportedAsSuch_evenOnAnOffRouteStage() {
        long ticketId = dealAt(EntryChannel.BUYER_DIRECT, DealStage.OWNER_SIGNOFF);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.OWNER_SIGNOFF, null, ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("อยู่ในขั้นตอน").doesNotContain("แก้ช่องทางดีล");
            });
    }

    /**
     * Rung 2 (role gate) precedes the route: account is 403 on a sales stage, not the route 409. ขั้น 6 on a
     * buyer-direct deal (off its route) stands in for ขั้น 4 on an owner-direct one — see
     * {@link #targetEqualToCurrent_isReportedAsSuch_evenOnAnOffRouteStage}.
     */
    @Test
    void aCallerWithNoWriteAccess_gets403_notTheRouteRefusal() {
        long ticketId = dealAt(EntryChannel.BUYER_DIRECT, DealStage.PRESENTATION);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.OWNER_SIGNOFF, "เหตุผล", accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ═══ Part E — stageDecisions / GET /api/tickets/{id}/actions ══════════════

    /**
     * The decisions are produced by running the real gate and catching, so an off-route target
     * arrives {@code allowed=false}, {@code onRoute=false} and carrying the refusal's own message —
     * and is therefore never advertised as an ADVANCE_STAGE action.
     */
    @Test
    void stageDecisions_markOffRouteTargetsNotAllowed_withOnRouteFalse() {
        long ticketId = dealAt(EntryChannel.BUYER_DIRECT, DealStage.PRESENTATION);

        List<StageDecisionDto> decisions = decisionsFor(ticketId);
        Set<String> offRoute = DealRoute.offRouteStages(EntryChannel.BUYER_DIRECT);
        assertThat(offRoute).hasSize(4);
        for (StageDecisionDto d : decisions) {
            if (offRoute.contains(d.stage())) {
                assertThat(d.onRoute()).as("onRoute for %s", d.stage()).isFalse();
                assertThat(d.allowed()).as("allowed for %s", d.stage()).isFalse();
                if (HAND_SETTABLE_PARTY_STAGES.contains(d.stage())) {
                    // ขั้น 6 and 7 are still hand-settable, so their blocked reason is the route's own text.
                    assertThat(d.blockedReason()).as("reason for %s", d.stage())
                        .contains("ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง")
                        .contains(DealRoute.stagePhrase(d.stage()))
                        .doesNotContain(d.stage())
                        .contains("แก้ช่องทางดีล");
                } else {
                    // ขั้น 4 and 5 are a quotation's job (M2): blocked with a reason, whichever sentence.
                    assertThat(d.blockedReason()).as("reason for %s", d.stage()).isNotBlank();
                }
            } else {
                assertThat(d.onRoute()).as("onRoute for %s", d.stage()).isTrue();
            }
        }
        Set<String> advertised = ticketService.actions(ticketId, ownerRep).availableActions().stream()
            .filter(a -> "ADVANCE_STAGE".equals(a.action()))
            .map(TicketResponses.TicketActionDto::targetStage).collect(Collectors.toSet());
        assertThat(advertised).doesNotContainAnyElementsOf(offRoute);
        assertThat(advertised).contains(DealStage.SPEC_APPROVED);
    }

    // ═══ Part F — auto-advance on an off-route target: never a throw, never left off the route (M3) ═

    /**
     * Design §5 Flow 10 — owner-direct deal at QUOTE_OWNER, then a DESIGNER quotation (target S4).
     * Stays put and throws nothing. (Backward, so forward-only holds it whichever way M3 is
     * implemented; what an off-route FORWARD quotation does is pinned at the service level, in
     * {@code DealQuotationRecipientIntegrationTest}, {@code CustomerQuotationIntegrationTest} and
     * {@code DealQuotationPricingRequestApprovalIntegrationTest}.)
     */
    @Test
    void autoAdvance_towardTheDesignerQuote_leavesAnOwnerDirectDealAtQuoteOwner_andThrowsNothing() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.QUOTE_OWNER);

        assertThatCode(() -> ticketService.advanceStageForCustomerQuotationIssue(
            ticketId, QuotationRecipient.DESIGNER, ownerRep)).doesNotThrowAnyException();

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /**
     * The discriminating case: a FORWARD target that is off the deal's route (a DESIGNER quotation on an
     * owner-direct deal, an OWNER quotation on a buyer-direct one). Under the owner rules of 2026-10-05
     * (M3) a quotation for a party that is not on the route CORRECTS the route instead of the move being
     * skipped; this used to pin the skip itself ("stage stays PRESENTATION / SPEC_APPROVED, zero
     * STAGE_CHANGED"), which is the behaviour M3 replaces.
     *
     * <p>Where the correction lives — inside {@code advanceStageForCustomerQuotationIssue}, or in the
     * quotation service before it calls the hook — is the developer's choice, so this HOOK-level test pins
     * only what holds under both: the hook throws nothing (its caller is quotation creation), and afterwards
     * the deal is never left on a stage that is off its route,
     * {@code DealRoute.isOnRoute(entryChannelAfter, stageAfter)}. The service-level pins of M3 are
     * {@code DealQuotationRecipientIntegrationTest} (a direct quotation) and the two pricing-request
     * creation paths, {@code CustomerQuotationIntegrationTest} and
     * {@code DealQuotationPricingRequestApprovalIntegrationTest}. Green today and after; that is intended.
     */
    @Test
    void autoAdvance_towardAForwardOffRouteStage_throwsNothing_andNeverLeavesTheDealOffItsRoute() {
        long ownerDirect = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);
        assertThatCode(() -> ticketService.advanceStageForCustomerQuotationIssue(
            ownerDirect, QuotationRecipient.DESIGNER, ownerRep)).doesNotThrowAnyException();
        assertThat(DealRoute.isOnRoute(entryChannelOf(ownerDirect), stageOf(ownerDirect)))
            .as("owner-direct deal after a DESIGNER quotation: stage %s on channel %s must be on its route",
                stageOf(ownerDirect), entryChannelOf(ownerDirect)).isTrue();

        long buyerDirect = dealAt(EntryChannel.BUYER_DIRECT, DealStage.SPEC_APPROVED);
        assertThatCode(() -> ticketService.advanceStageForCustomerQuotationIssue(
            buyerDirect, QuotationRecipient.OWNER, ownerRep)).doesNotThrowAnyException();
        assertThat(DealRoute.isOnRoute(entryChannelOf(buyerDirect), stageOf(buyerDirect)))
            .as("buyer-direct deal after an OWNER quotation: stage %s on channel %s must be on its route",
                stageOf(buyerDirect), entryChannelOf(buyerDirect)).isTrue();
    }

    /** The guard must not reach an ON-route auto-advance, nor an operational one. */
    @Test
    void autoAdvance_towardAnOnRouteStage_stillAdvances() {
        long buyerDirect = dealAt(EntryChannel.BUYER_DIRECT, DealStage.SPEC_APPROVED);
        ticketService.advanceStageForCustomerQuotationIssue(buyerDirect, QuotationRecipient.BUYER, ownerRep);
        assertThat(stageOf(buyerDirect)).isEqualTo(DealStage.QUOTE_BUYER);

        long unspecified = dealAt(EntryChannel.UNSPECIFIED, DealStage.PRESENTATION);
        ticketService.advanceStageForCustomerQuotationIssue(unspecified, QuotationRecipient.DESIGNER, ownerRep);
        assertThat(stageOf(unspecified)).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * How a NEW refusal of a hand move is asserted (owner rules of 2026-10-05): {@code ApiException}
     * thrown, {@code sales_stage} unchanged, no {@code STAGE_CHANGED} event written — and not the status
     * or the message. Where the OLD code accepted the call this is red before the rule exists; where it
     * refused for another reason (an off-route ขั้น 4/5) it stays green and simply stops pinning which.
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

    private void assertRefusedByRoute(long ticketId, String stage, String channelPhrase) {
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, stage, "เหตุผล", ownerRep))
            .as("a manual move into off-route %s must be refused", stage)
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains(channelPhrase)
                    .contains(DealRoute.stagePhrase(stage))
                    .doesNotContain(stage)
                    .contains("แก้ช่องทางดีล");
            });
    }

    private static void assertNoStageIsOffRoute(List<StageDecisionDto> decisions) {
        assertThat(decisions).hasSize(DealStage.ORDER.size());
        for (StageDecisionDto d : decisions) {
            assertThat(d.onRoute()).as("onRoute for %s", d.stage()).isTrue();
            assertThat(d.blockedReason() == null || !d.blockedReason().contains("แก้ช่องทางดีล"))
                .as("route refusal on %s: %s", d.stage(), d.blockedReason()).isTrue();
        }
    }

    private List<StageDecisionDto> decisionsFor(long ticketId) {
        return ticketService.actions(ticketId, ownerRep).stageDecisions();
    }

    private static Set<String> allowedStages(List<StageDecisionDto> decisions) {
        return decisions.stream().filter(StageDecisionDto::allowed)
            .map(StageDecisionDto::stage).collect(Collectors.toSet());
    }

    private static StageDecisionDto decision(List<StageDecisionDto> decisions, String stage) {
        return decisions.stream().filter(d -> d.stage().equals(stage)).findFirst()
            .orElseThrow(() -> new AssertionError("no decision for " + stage));
    }

    /** A live deal at {@code stage} with {@code channel}, tracking fields current so forward moves are ready. */
    private long dealAt(String channel, String stage) {
        long ticketId = createDealWithOneItem();
        tickets.updateSalesStage(ticketId, stage);
        tickets.updateEntryChannel(ticketId, channel);
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
            "ดีลทดสอบเส้นทางดีล", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                new BigDecimal("100.00"), null, "PIECE", null, null, null, null, "THB")));
        return tickets.create(request, tickets.nextTicketCode(), ownerRepId, "เจ้าของดีล เส้นทางดีล");
    }

    private String stageOf(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private String entryChannelOf(long ticketId) {
        return jdbc.queryForObject("SELECT entry_channel FROM sales.ticket WHERE ticket_id = :id",
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
        return new UserPrincipal(employeeId, role + "-dealroute@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
