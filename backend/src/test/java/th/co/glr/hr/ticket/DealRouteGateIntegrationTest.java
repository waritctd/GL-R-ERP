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
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p>Modelled on {@link StageDecisionIntegrationTest} and {@link SalesRouteWalkIntegrationTest}.
 */
class DealRouteGateIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private TicketService ticketService;

    private long ownerRepId;
    private UserPrincipal ownerRep;
    private UserPrincipal accountActor;

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
    }

    // ═══ Part A — what an owner-direct / buyer-direct deal CANNOT reach ═══════

    /** No designer to quote: S4 is off the owner-direct route. Refused 409, naming channel + remedy. */
    @Test
    void ownerDirect_isRefusedTheDesignerQuote_andTheRowDoesNotMove() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);

        assertRefusedByRoute(ticketId, DealStage.QUOTE_DESIGN_SIDE, "เจ้าของติดต่อโดยตรง");

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** Buyer-direct: no designer, the project owner is not the counterparty, S7 off-path. */
    @Test
    void buyerDirect_isRefusedEachOfTheFourPartyStages() {
        for (String offRoute : List.of(DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
                DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER)) {
            long ticketId = dealAt(EntryChannel.BUYER_DIRECT, DealStage.PRESENTATION);

            assertRefusedByRoute(ticketId, offRoute, "ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง");

            assertThat(stageOf(ticketId)).as("deal must not move to %s", offRoute)
                .isEqualTo(DealStage.PRESENTATION);
            assertThat(stageChangedEvents(ticketId)).isZero();
        }
    }

    /**
     * Only the OFF-route stages are refused: on an owner-direct deal S3 (kept, only re-worded) and
     * the rest of the route still move normally. Guards against a gate that over-reaches.
     */
    @Test
    void ownerDirect_stillReachesTheStagesOnItsRoute() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);

        for (String onRoute : List.of(DealStage.SPEC_APPROVED, DealStage.QUOTE_OWNER,
                DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER,
                DealStage.NEGOTIATION)) {
            logAnActivityAndFollowUp(ticketId);
            assertThatCode(() -> ticketService.updateStage(ticketId, onRoute, null, ownerRep))
                .as("owner-direct deal must reach on-route %s", onRoute).doesNotThrowAnyException();
            assertThat(stageOf(ticketId)).isEqualTo(onRoute);
        }
    }

    // ═══ Part B — the two safety properties: nothing is refused ═══════════════

    /**
     * Design §13.1c — the unrecoverable-brick case. Pre-V144 rows and quotation-first ghosts carry
     * UNSPECIFIED and GLA-156 removed the in-portal way to correct it, so a gate on it would brick
     * those deals. Every party stage must be reachable, actually moved into, one after another.
     */
    @Test
    void unspecified_isRefusedNothing_everyStageStillReachable() {
        long ticketId = dealAt(EntryChannel.UNSPECIFIED, DealStage.PRESENTATION);

        assertNoStageIsOffRoute(decisionsFor(ticketId));
        for (String stage : List.of(DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
                DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER)) {
            logAnActivityAndFollowUp(ticketId);
            assertThatCode(() -> ticketService.updateStage(ticketId, stage, null, ownerRep))
                .as("UNSPECIFIED deal must reach %s", stage).doesNotThrowAnyException();
            assertThat(stageOf(ticketId)).isEqualTo(stage);
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

        for (String stage : List.of(DealStage.QUOTE_DESIGN_SIDE, DealStage.QUOTE_OWNER,
                DealStage.OWNER_SIGNOFF, DealStage.AWAITING_BUYER, DealStage.QUOTE_BUYER)) {
            logAnActivityAndFollowUp(designerLed);
            assertThatCode(() -> ticketService.updateStage(designerLed, stage, null, ownerRep))
                .as("designer-led deal must reach %s", stage).doesNotThrowAnyException();
        }
    }

    // ═══ Part C — the stranding case: the gate tests the TARGET, never the current stage ═══

    /**
     * A deal sitting ON an off-route stage (it got there, then the channel was corrected to
     * owner-direct) must still be able to move FORWARD. Gating the current stage would strand it.
     */
    @Test
    void aDealSittingOnAnOffRouteStage_canStillMoveForward() {
        long ticketId = dealAt(EntryChannel.DESIGNER_LED, DealStage.QUOTE_DESIGN_SIDE);
        tickets.updateEntryChannel(ticketId, EntryChannel.OWNER_DIRECT); // the correction

        assertThat(DealRoute.isOnRoute(EntryChannel.OWNER_DIRECT, stageOf(ticketId))).isFalse();
        assertThat(decision(decisionsFor(ticketId), DealStage.QUOTE_OWNER).allowed()).isTrue();

        logAnActivityAndFollowUp(ticketId);
        assertThatCode(() -> ticketService.updateStage(ticketId, DealStage.QUOTE_OWNER, null, ownerRep))
            .doesNotThrowAnyException();
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);
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
     * Rung 5 (fact gate) reports the FACT, not the route. Honest limit: because
     * {@link DealRoute#ROUTE_VARIABLE} keeps every fact-gated stage on EVERY route, no target can be
     * both off-route and fact-gated today, so this cannot distinguish the two orderings — it pins
     * that a missing deposit on an owner-direct deal never surfaces as a route message.
     */
    @Test
    void aMissingDeposit_reportsTheFactGate_notTheRoute() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.NEGOTIATION);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.DEPOSIT_RECEIVED, "เหตุผล", accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ยังไม่ได้รับชำระมัดจำ").doesNotContain("แก้ช่องทางดีล");
            });
    }

    /** Rung 4 (target == current) precedes the route: a deal sitting on S4 asked to go to S4. */
    @Test
    void targetEqualToCurrent_isReportedAsSuch_evenOnAnOffRouteStage() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.QUOTE_DESIGN_SIDE);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.QUOTE_DESIGN_SIDE, null, ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("อยู่ในขั้นตอน").doesNotContain("แก้ช่องทางดีล");
            });
    }

    /** Rung 2 (role gate) precedes the route: account is 403 on a sales stage, not the route 409. */
    @Test
    void aCallerWithNoWriteAccess_gets403_notTheRouteRefusal() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.QUOTE_DESIGN_SIDE, "เหตุผล", accountActor))
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
                assertThat(d.blockedReason()).as("reason for %s", d.stage())
                    .contains("ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง")
                    .contains(DealRoute.stagePhrase(d.stage()))
                    .doesNotContain(d.stage())
                    .contains("แก้ช่องทางดีล");
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

    // ═══ Part F — auto-advance precedence: silent no-op on an off-route target ═

    /**
     * Design §5 Flow 10 — owner-direct deal at QUOTE_OWNER, then a DESIGNER quotation (target S4).
     * Stays put and throws nothing. (Backward, so monotonicity would also hold it: the next test is
     * the one that isolates the route guard.)
     */
    @Test
    void autoAdvance_towardTheDesignerQuote_leavesAnOwnerDirectDealAtQuoteOwner_andThrowsNothing() {
        long ticketId = dealAt(EntryChannel.OWNER_DIRECT, DealStage.QUOTE_OWNER);

        assertThatCode(() -> ticketService.advanceStageForCustomerQuotationIssue(
            ticketId, QuotationRecipient.DESIGNER, ownerRep)).doesNotThrowAnyException();

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(stageChangedEvents(ticketId)).isZero();
    }

    /** The discriminating case: a FORWARD off-route target. Without the guard this would move to S4. */
    @Test
    void autoAdvance_towardAForwardOffRouteStage_isASilentNoOp() {
        long ownerDirect = dealAt(EntryChannel.OWNER_DIRECT, DealStage.PRESENTATION);
        assertThatCode(() -> ticketService.advanceStageForCustomerQuotationIssue(
            ownerDirect, QuotationRecipient.DESIGNER, ownerRep)).doesNotThrowAnyException();
        assertThat(stageOf(ownerDirect)).isEqualTo(DealStage.PRESENTATION);
        assertThat(stageChangedEvents(ownerDirect)).isZero();

        long buyerDirect = dealAt(EntryChannel.BUYER_DIRECT, DealStage.SPEC_APPROVED);
        assertThatCode(() -> ticketService.advanceStageForCustomerQuotationIssue(
            buyerDirect, QuotationRecipient.OWNER, ownerRep)).doesNotThrowAnyException();
        assertThat(stageOf(buyerDirect)).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(stageChangedEvents(buyerDirect)).isZero();
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
