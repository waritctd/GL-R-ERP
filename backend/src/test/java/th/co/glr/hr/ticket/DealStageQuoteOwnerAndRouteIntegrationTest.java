package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Real-Postgres proof for Part B (the S4/S5 split) and Part C (route-dependent stages need no
 * written justification), plus the authorization surface Part B widens.
 *
 * <p>Three things here are unreachable from a unit test:
 *
 * <ol>
 *   <li>the V143 CHECK constraint actually accepts {@code QUOTE_OWNER} — and is still a live
 *       constraint, not one the migration quietly dropped;
 *   <li>{@code requireStageWriteAccess} keys off {@code SALES_TARGET_STAGES}, so adding
 *       {@code QUOTE_OWNER} to that set is a PERMISSION change. Per {@code CLAUDE.md} the enforcing
 *       test must run through the real Java service against real Postgres, and it is written
 *       wrong-way-round: the assertion that matters is that account, import and a NON-owning sales
 *       rep are refused and the row does not move;
 *   <li>{@code updateStage}'s note rule composes with the tracking-field gate and the ownership
 *       gate, which a pure {@link DealStage} test cannot see.
 * </ol>
 *
 * <p><b>Owner rules of 2026-10-05 (stage movement, M1-M4) reshape Part C.</b> A hand move exists only
 * onto ขั้น 1, 2, 3, 6, 7 and 9: ขั้น 4, 5 and 8 are reached when a quotation is created for that
 * party, and from ขั้น 10 the stage is system-only for everyone. So the three "needs no note" routes
 * (a deal straight to QUOTE_BUYER, to QUOTE_OWNER, to DELIVERY_SCHEDULING) and the grant of
 * QUOTE_OWNER to the owning rep, ผจก.ขาย and the CEO are now refusals, and a refusal of a hand move is
 * asserted as {@code ApiException} + stage unchanged + no {@code STAGE_CHANGED} event, without its
 * status or message ({@link #assertHandMoveRefused}); every such fixture is one the OLD code accepted.
 * The tests that call {@code advanceStageForCustomerQuotationIssue} / {@code generateQuotation}
 * directly (the recipient -&gt; stage mapping) are untouched. This class wires no quotation service, so
 * "creating the quotation moves the deal" is pinned where one is wired:
 * {@code DealQuotationRecipientIntegrationTest}, {@code CustomerQuotationIntegrationTest} and
 * {@code DealQuotationPricingRequestApprovalIntegrationTest}.
 *
 * <p>Modelled on {@link DealTrackingAndActivityIntegrationTest}, which covers the tracking gate
 * itself. No assertion here depends on a rollback: {@code @Transactional} is inert in this suite.
 */
class DealStageQuoteOwnerAndRouteIntegrationTest extends AbstractPostgresIntegrationTest {

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
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerRepId = createEmployee(employees, "พนักงานขาย เจ้าของดีล", "sales-owner-s5@glr.co.th");
        ownerRep = principal(ownerRepId, "sales");
        otherRep = principal(createEmployee(employees, "พนักงานขาย คนอื่น", "sales-other-s5@glr.co.th"), "sales");
        salesManager = principal(createEmployee(employees, "ผจก.ขาย", "salesmgr-s5@glr.co.th"), "sales_manager");
        ceo = principal(createEmployee(employees, "ซีอีโอ", "ceo-s5@glr.co.th"), "ceo");
        accountActor = principal(createEmployee(employees, "บัญชี", "account-s5@glr.co.th"), "account");
        importActor = principal(createEmployee(employees, "ฝ่ายนำเข้า", "import-s5@glr.co.th"), "import");
    }

    // ── Part B: the schema, and the recipient routing ────────────────────────

    /**
     * V143 widened {@code chk_ticket_sales_stage} rather than dropping it. Both halves are asserted
     * because a migration that simply deleted the constraint would pass the first half alone.
     */
    @Test
    void theCheckConstraintAcceptsQuoteOwnerAndStillRejectsAnUnknownStage() {
        long ticketId = createTicket();

        assertThatCode(() -> tickets.updateSalesStage(ticketId, DealStage.QUOTE_OWNER))
            .doesNotThrowAnyException();
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);

        assertThatThrownBy(() -> tickets.updateSalesStage(ticketId, "QUOTE_SOMEONE_ELSE"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);
    }

    /**
     * The headline of Part B, written wrong-way-round: a quotation issued to the OWNER must NOT
     * land on {@code QUOTE_DESIGN_SIDE}. It did before — both recipients were mapped onto that one
     * stage, which is why {@code sales_stage} could not answer "has the owner been quoted yet?".
     *
     * <p>This drives {@code advanceStageForCustomerQuotationIssue}, the entry point
     * {@code CustomerQuotationService.issue} calls, so it is the production routing and not a
     * re-implementation of it.
     */
    @Test
    void aQuotationIssuedToTheOwnerLandsOnQuoteOwnerAndNeverOnQuoteDesignSide() {
        long ticketId = createTicket();

        ticketService.advanceStageForCustomerQuotationIssue(ticketId, QuotationRecipient.OWNER, ownerRep);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(stageOf(ticketId)).isNotEqualTo(DealStage.QUOTE_DESIGN_SIDE);
    }

    @Test
    void theOtherRecipientsKeepTheStagesTheyAlreadyHad() {
        long designerDeal = createTicket();
        ticketService.advanceStageForCustomerQuotationIssue(designerDeal, QuotationRecipient.DESIGNER, ownerRep);
        assertThat(stageOf(designerDeal)).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);

        long buyerDeal = createTicket();
        ticketService.advanceStageForCustomerQuotationIssue(buyerDeal, QuotationRecipient.BUYER, ownerRep);
        assertThat(stageOf(buyerDeal)).isEqualTo(DealStage.QUOTE_BUYER);

        // UNSPECIFIED (and anything unrecognised) advances nothing, exactly as the old if/else-if
        // chain did by falling off the end.
        long unspecifiedDeal = createTicket();
        ticketService.advanceStageForCustomerQuotationIssue(unspecifiedDeal, QuotationRecipient.UNSPECIFIED, ownerRep);
        assertThat(stageOf(unspecifiedDeal)).isEqualTo(DealStage.LEAD_APPROACH);
    }

    /** The same split through the legacy ticket-native quotation path. */
    @Test
    void generateQuotationToTheOwnerAlsoLandsOnQuoteOwner() {
        long ticketId = createTicket();
        jdbc.update("UPDATE sales.ticket SET status = 'approved' WHERE ticket_id = :id",
            Map.of("id", ticketId));

        ticketService.generateQuotation(ticketId, new GenerateQuotationRequest(
            QuotationRecipient.OWNER, null, null, null, null, null, null, null, null, null), ownerRep);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);
    }

    // ── Part B: the permission surface QUOTE_OWNER joins ─────────────────────

    /**
     * Wrong-way-round authz. {@code QUOTE_OWNER} is in {@code SALES_TARGET_STAGES}, so the money
     * roles and a rep who does not own this deal must be refused — and the stage must genuinely not
     * move, not merely throw.
     */
    @Test
    void quoteOwnerIsRefusedToAccountImportAndANonOwningRep() {
        long ticketId = readyToAdvanceFrom(DealStage.SPEC_APPROVED);

        for (UserPrincipal denied : List.of(accountActor, importActor, otherRep)) {
            assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.QUOTE_OWNER, null, denied))
                .as("%s must not reach QUOTE_OWNER", denied.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
            assertThat(stageOf(ticketId)).as("%s must not have moved the deal", denied.role())
                .isEqualTo(DealStage.SPEC_APPROVED);
        }
    }

    /**
     * M2: ขั้น 5 is never set by hand — not by the owning rep, not by ผจก.ขาย, not by the CEO (it used to
     * be writable by all three). The fixture is the one the old test used and the old code accepted: a
     * deal at SPEC_APPROVED with the tracking fields current, no note needed. Quotations reach ขั้น 5.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"sales", "sales_manager", "ceo"})
    void quoteOwnerIsNotWritableByHand_byTheOwningRepBySalesManagerOrByCeo(String role) {
        long ticketId = readyToAdvanceFrom(DealStage.SPEC_APPROVED);

        assertHandMoveRefused(ticketId, DealStage.SPEC_APPROVED,
            role + " must not move a deal onto QUOTE_OWNER (ขั้น 5) by hand",
            () -> ticketService.updateStage(ticketId, DealStage.QUOTE_OWNER, null, actorFor(role)));
    }

    /**
     * The money/import stages are unchanged by this branch — asserted so a future widening of
     * {@code SALES_TARGET_STAGES} cannot quietly hand a sales rep the payment or import stages.
     */
    @Test
    void theMoneyAndImportStagesStillBelongToTheirOwnRoles() {
        long ticketId = readyToAdvanceFrom(DealStage.NEGOTIATION);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.DEPOSIT_RECEIVED, "x", ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.PROCUREMENT, "x", ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.NEGOTIATION);
    }

    // ── Part C: the business's real routes — by hand only inside ขั้น 1-9 ─────

    /** Case C — a contractor arrives with a BOQ and a spec, so the deal opens straight at S8. */
    @Test
    void caseC_leadApproachStraightToQuoteBuyer_cannotBeDoneByHand_theBuyerQuotationDoesIt() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        // ขั้น 8 is reached when a quotation is CREATED for the buyer, never by hand (M2). This class
        // wires no quotation service, so the move itself is pinned in DealQuotationRecipientIntegrationTest
        // (a direct quotation, each recipient) and in CustomerQuotationIntegrationTest /
        // DealQuotationPricingRequestApprovalIntegrationTest (a pricing-request quotation).
        assertHandMoveRefused(ticketId, DealStage.LEAD_APPROACH,
            "the owning rep must not move a deal onto QUOTE_BUYER (ขั้น 8) by hand",
            () -> ticketService.updateStage(ticketId, DealStage.QUOTE_BUYER, null, ownerRep));
    }

    /** Case B — the owner buys directly, so S3 and S4 never happen. */
    @Test
    void caseB_presentationStraightToQuoteOwner_cannotBeDoneByHand_theOwnerQuotationDoesIt() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);

        // Same as case C, for ขั้น 5 and the owner quotation (M2).
        assertHandMoveRefused(ticketId, DealStage.PRESENTATION,
            "the owning rep must not move a deal onto QUOTE_OWNER (ขั้น 5) by hand",
            () -> ticketService.updateStage(ticketId, DealStage.QUOTE_OWNER, null, ownerRep));
    }

    /** Case D — everything is in stock, so PROCUREMENT is skipped. */
    @Test
    void caseD_depositReceivedStraightToDeliveryScheduling_cannotBeDoneByHand() {
        long ticketId = readyToAdvanceFrom(DealStage.DEPOSIT_RECEIVED);

        // From ขั้น 10 the stage is the system's (M4): the all-in-stock route reaches ขั้น 13 through
        // reserveStock's full-coverage jump, walked by StageFactGateIntegrationTest#autoAdvanceStill… and
        // SalesRouteWalkIntegrationTest#routeD_…, not by a rep clicking it.
        assertHandMoveRefused(ticketId, DealStage.DEPOSIT_RECEIVED,
            "the owning rep must not move a deal onto DELIVERY_SCHEDULING (ขั้น 13) by hand",
            () -> ticketService.updateStage(ticketId, DealStage.DELIVERY_SCHEDULING, null, ownerRep));
    }

    /**
     * M4: the order is the customer's and the system's, not a hand move's. QUOTE_BUYER -&gt;
     * ORDER_RECEIVED used to be refused only for want of a note (it steps over NEGOTIATION, a MANDATORY
     * stage) and accepted with one; the stage is now refused WITH or WITHOUT a note, although the
     * customer's order is verified. The note rule for a forward skip can no longer be triggered by a hand
     * move at all (the only mandatory stage inside ขั้น 1-9 is the hand-settable NEGOTIATION itself).
     * The first call below — no note — was refused before and is refused now, so it comes first; the
     * second, with a note, is the one the old code accepted.
     */
    @Test
    void aHandMoveOntoOrderReceived_isRefusedWithAndWithoutANote_andTheStageDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.QUOTE_BUYER);
        // CUSTOMER_CONFIRMED is what confirmCustomer writes: the fact behind ORDER_RECEIVED holds, so the
        // only thing that can be refusing the second call is the new rule.
        jdbc.update("UPDATE sales.ticket SET payment_status = :status WHERE ticket_id = :id",
            new MapSqlParameterSource()
                .addValue("status", PaymentTrack.CUSTOMER_CONFIRMED).addValue("id", ticketId));

        assertHandMoveRefused(ticketId, DealStage.QUOTE_BUYER,
            "no note: the owning rep must not move a deal onto ORDER_RECEIVED by hand",
            () -> ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, null, ownerRep));
        assertHandMoveRefused(ticketId, DealStage.QUOTE_BUYER,
            "with a note: the owning rep must not move a deal onto ORDER_RECEIVED by hand either",
            () -> ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, "ลูกค้าสั่งซื้อทันที", ownerRep));
    }

    /** Backward moves are untouched by Part C: still a reason, still the backward message. */
    @Test
    void aGenuineBackwardMoveStillDemandsAReasonAndSaysSo() {
        long ticketId = readyToAdvanceFrom(DealStage.QUOTE_OWNER);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(400);
                assertThat(e.getMessage()).isEqualTo("การย้อนสถานะกลับต้องระบุเหตุผล");
            });
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);
    }

    /** The one routine backward pair still needs nothing — S4 → S3 is the everyday path. */
    @Test
    void quoteDesignSideBackToSpecApproved_stillNeedsNoNote() {
        long ticketId = readyToAdvanceFrom(DealStage.QUOTE_DESIGN_SIDE);

        ticketService.updateStage(ticketId, DealStage.SPEC_APPROVED, null, ownerRep);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.SPEC_APPROVED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A deal parked at {@code stage} with the tracking fields the B1 forward-advance gate demands
     * (a next follow-up date and one activity logged after the last stage change), so that a test
     * asserting the NOTE rule fails on the note rule and nothing else.
     */
    private long readyToAdvanceFrom(String stage) {
        long ticketId = createTicket();
        jdbc.update("UPDATE sales.ticket SET sales_stage = :stage WHERE ticket_id = :id",
            new MapSqlParameterSource().addValue("stage", stage).addValue("id", ticketId));
        ticketService.updateTracking(ticketId,
            new TrackingUpdateRequest(null, null, null, null, LocalDate.now().plusDays(3)), ownerRep);
        ticketService.addActivity(ticketId,
            new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null), ownerRep);
        return ticketId;
    }

    /**
     * How a NEW refusal of a hand move is asserted (owner rules of 2026-10-05): {@code ApiException}
     * thrown, {@code sales_stage} unchanged, no {@code STAGE_CHANGED} event written — and not the status
     * or the message. The callers' fixtures are ones the OLD code accepted, so a refusal is evidence of
     * the new rule and not of some unrelated gate.
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

    private UserPrincipal actorFor(String role) {
        return switch (role) {
            case "sales" -> ownerRep;
            case "sales_manager" -> salesManager;
            case "ceo" -> ceo;
            default -> throw new IllegalArgumentException("no such actor in this class: " + role);
        };
    }

    private int stageChangedEvents(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event
             WHERE ticket_id = :ticketId AND kind = :kind
            """,
            Map.of("ticketId", ticketId, "kind", TicketEventKind.STAGE_CHANGED), Integer.class);
    }

    private long createTicket() {
        return tickets.create(
            new CreateTicketRequest("ดีลทดสอบขั้นตอน", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), ownerRepId, "พนักงานขาย เจ้าของดีล");
    }

    private String stageOf(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-s5@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
