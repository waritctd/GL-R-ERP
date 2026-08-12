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
import th.co.glr.hr.pricing.PriceCalcService;
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
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications, mock(PriceCalcService.class),
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests);

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

    @Test
    void quoteOwnerIsWritableByTheOwningRepBySalesManagerAndByCeo() {
        long byOwner = readyToAdvanceFrom(DealStage.SPEC_APPROVED);
        ticketService.updateStage(byOwner, DealStage.QUOTE_OWNER, null, ownerRep);
        assertThat(stageOf(byOwner)).isEqualTo(DealStage.QUOTE_OWNER);

        long byManager = readyToAdvanceFrom(DealStage.SPEC_APPROVED);
        ticketService.updateStage(byManager, DealStage.QUOTE_OWNER, null, salesManager);
        assertThat(stageOf(byManager)).isEqualTo(DealStage.QUOTE_OWNER);

        long byCeo = readyToAdvanceFrom(DealStage.SPEC_APPROVED);
        ticketService.updateStage(byCeo, DealStage.QUOTE_OWNER, null, ceo);
        assertThat(stageOf(byCeo)).isEqualTo(DealStage.QUOTE_OWNER);
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

    // ── Part C: the business's real routes need no written justification ─────

    /** Case C — a contractor arrives with a BOQ and a spec, so the deal opens straight at S8. */
    @Test
    void caseC_leadApproachStraightToQuoteBuyer_needsNoNote() {
        long ticketId = readyToAdvanceFrom(DealStage.LEAD_APPROACH);

        ticketService.updateStage(ticketId, DealStage.QUOTE_BUYER, null, ownerRep);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_BUYER);
    }

    /** Case B — the owner buys directly, so S3 and S4 never happen. */
    @Test
    void caseB_presentationStraightToQuoteOwner_needsNoNote() {
        long ticketId = readyToAdvanceFrom(DealStage.PRESENTATION);

        ticketService.updateStage(ticketId, DealStage.QUOTE_OWNER, null, ownerRep);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_OWNER);
    }

    /** Case D — everything is in stock, so PROCUREMENT is skipped. */
    @Test
    void caseD_depositReceivedStraightToDeliveryScheduling_needsNoNote() {
        long ticketId = readyToAdvanceFrom(DealStage.DEPOSIT_RECEIVED);

        ticketService.updateStage(ticketId, DealStage.DELIVERY_SCHEDULING, null, ownerRep);

        assertThat(stageOf(ticketId)).isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    /**
     * …and the wrong-way-round half: stepping over a MANDATORY stage still costs a sentence.
     * QUOTE_BUYER -> ORDER_RECEIVED steps over NEGOTIATION.
     */
    @Test
    void skippingAMandatoryStage_isStillRefusedWithoutANoteAndTheStageDoesNotMove() {
        long ticketId = readyToAdvanceFrom(DealStage.QUOTE_BUYER);

        assertThatThrownBy(() -> ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, null, ownerRep))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(400);
                assertThat(e.getMessage()).isEqualTo("การข้ามขั้นตอนต้องระบุเหตุผล");
            });
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.QUOTE_BUYER);

        ticketService.updateStage(ticketId, DealStage.ORDER_RECEIVED, "ลูกค้าสั่งซื้อทันที", ownerRep);
        assertThat(stageOf(ticketId)).isEqualTo(DealStage.ORDER_RECEIVED);
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
