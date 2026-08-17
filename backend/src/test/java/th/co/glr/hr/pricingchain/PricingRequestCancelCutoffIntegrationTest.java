package th.co.glr.hr.pricingchain;

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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.RecordQuotationOutcomeRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Evidence for the <b>cancel cutoff</b> (owner ruling 2026-08-13): a pricing request is cancellable
 * up to and including {@code APPROVED_FOR_QUOTATION}, and <b>refused from {@code QUOTATION_ISSUED}
 * onward</b>.
 *
 * <p>Before this change {@link PricingRequestStatus}'s map declared CANCELLED from only DRAFT,
 * SUBMITTED, IMPORT_REVIEWING and AWAITING_FACTORY_RESPONSE, so a deal that died while the CEO held
 * the request — or after the CEO had approved a price but before anything reached the customer —
 * could not be cancelled at all. The widening and the refusal are one ruling and are tested as one.
 *
 * <p><b>Every guard here is written wrong-way-round</b>, asserting what a caller CANNOT do, because
 * "the happy path still works" is what a green suite proves whether or not any guard exists. Two
 * separate guards are pinned:
 *
 * <ol>
 *   <li><b>The status cutoff</b> — cancel is refused from QUOTATION_ISSUED and QUOTATION_ACCEPTED,
 *       with the row re-read afterwards to prove nothing moved.</li>
 *   <li><b>The role gate</b> — cancel is refused for a sales rep who does not own the deal and for
 *       Import, who drives the request's whole middle life and might plausibly be assumed to own
 *       it. Both re-read the row. The CEO override is asserted too, because it is a deliberate
 *       divergence from {@code TicketService.cancel} (owner-only, no CEO override) and an
 *       "obviously wrong" reading of the code would remove it.</li>
 * </ol>
 *
 * <p>The cascade half matters as much as the cutoff: every status the cutoff newly admits owns
 * CHILDREN, and cancelling a parent whose children stay live is worse than not cancelling at all. A
 * request cancelled from APPROVED_FOR_QUOTATION must take its factory quote, its costing, its
 * pricing decision and any DRAFT quotation with it — and must leave the chain's already-terminal
 * quotes exactly as they are.
 *
 * <p>Driven end to end through the real services against real Postgres, never hand-rolled SQL for
 * anything under test: the cutoff lives in a Java map but the cascade lives entirely in SQL
 * predicates, and a mocked repository "passes" while the {@code WHERE} clause does something else.
 * Even the terminal factory quote in the cascade test is produced the way production produces one
 * (a second {@code receive} supersedes the first), not inserted. The only mock is the collaborator
 * every other test in this package already mocks — {@link FactoryEmailService} (sends real email).
 *
 * <p><b>No assertion here depends on a rollback.</b> {@link AbstractPostgresIntegrationTest} builds
 * no Spring context, so services are hand-wired with {@code new} and {@code @Transactional} is
 * inert. Where this file re-reads a row after a rejected call, the call is rejected BEFORE it
 * writes anything — which is the property actually worth pinning, and the one a rollback assertion
 * would only appear to test.
 */
class PricingRequestCancelCutoffIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String FACTORY = "Factory Cancel-Cutoff";

    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;

    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;

    private long ticketId;
    private long catalogProductId;

    @BeforeEach
    void wireChainAndCreateDeal() {
        TicketRepository tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-cancel-cutoff-test-uploads");

        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        when(factoryEmail.send(anyLong(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCosts = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, new AppProperties(),
            landedCosts);

        PricingDecisionRepository decisions = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisions, pricingRequests,
            new PricingCostingRepository(jdbc), tickets, fxRates, notifications, landedCosts, formulaEngine);

        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);
        quotationService = new CustomerQuotationService(new CustomerQuotationRepository(jdbc), pricingRequests,
            decisions, tickets, ticketService, customers, new QuotationRenderer(), notifications);

        salesActor = actor(createEmployee(employees, "พนักงานขาย ยกเลิก", "sales-cancel@glr.co.th", "SALES", "แผนกขาย"), "sales");
        otherSalesActor = actor(createEmployee(employees, "พนักงานขาย อื่น", "sales-other-cancel@glr.co.th", "SALES", "แผนกขาย"), "sales");
        importActor = actor(createEmployee(employees, "ฝ่ายนำเข้า ยกเลิก", "import-cancel@glr.co.th", "PCIM", "ฝ่ายนำเข้า"), "import");
        ceoActor = actor(createEmployee(employees, "ผู้บริหาร ยกเลิก", "ceo-cancel@glr.co.th", "MD", "ผู้บริหาร"), "ceo");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:factory, 'factory-cancel@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factory", FACTORY));
        catalogProductId = insertCatalogProduct(FACTORY, "IT", "TEST-CANCEL-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Cancel Cutoff จำกัด", "0100000000031", "31 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0031");
        ProjectDto project = projects.create(customer.id(), "โครงการ Cancel Cutoff");
        TicketDto created = ticketService.create(new CreateTicketRequest(
            "ดีล Cancel Cutoff", "NORMAL", customer.name(), customer.id(), project.id(), null, null, null,
            List.of(ticketItem())), salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Guard 1 — the cutoff itself, wrong-way-round. This is the point of the change.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The line in the sand. Once a quotation has been issued the customer holds an offer, and
     * retracting an offer already made is a different commercial act from abandoning internal work.
     * It goes through the DEAL (markLost/cancel, whose cascade uses the {@code cancelForDeadDeal}
     * bypass) or through a customer-change revision — never through this endpoint.
     */
    @Test
    void cancel_fromQuotationIssued_isRefused_andTheRequestStaysExactlyWhereItWas() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(pricingRequestId);
        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        assertThatThrownBy(() -> pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Re-read from the DB, not from a returned DTO: the refusal is only worth anything if the
        // ROW did not move. cancelled_at/cancelled_by are checked too — a guard that returned 409
        // after already stamping the row would pass a status-only assertion.
        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
        assertThat(isCancelStamped(pricingRequestId)).isFalse();
    }

    /**
     * The CEO cannot buy their way past the cutoff either. The CEO override in
     * {@code PricingRequestService.cancel} is a ROLE gate — it decides WHO may cancel, and says
     * nothing about WHEN. Testing only the sales rep would leave a reading of the code in which the
     * override doubles as a status bypass, which is exactly the kind of privilege creep an
     * "admin can do anything" instinct produces.
     */
    @Test
    void cancel_fromQuotationIssued_isRefusedForTheCeoToo_soTheOverrideIsRoleOnly() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        issueQuotation(pricingRequestId);

        assertThatThrownBy(() -> pricingRequestService.cancel(pricingRequestId, cancelRequest(), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
        assertThat(isCancelStamped(pricingRequestId)).isFalse();
    }

    /**
     * QUOTATION_ACCEPTED is terminal and stays terminal. Once the customer has accepted, the deal is
     * moving to PO and fulfilment; unwinding it is an ORDER amendment and must not re-enter the
     * pricing chain — the same ruling that denies this status a SUPERSEDED edge.
     */
    @Test
    void cancel_fromQuotationAccepted_isRefused_andTheAcceptedDealStaysAccepted() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto issued = issueQuotation(pricingRequestId);
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", null, UUID.randomUUID().toString()), salesActor);
        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);

        assertThatThrownBy(() -> pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);
        assertThat(isCancelStamped(pricingRequestId)).isFalse();
        // The customer's accepted offer is untouched — the thing that would actually hurt.
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Guard 2 — the role gate, wrong-way-round.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Ownership is the gate. Another sales rep — same role, same division, no relationship to this
     * deal — must not be able to cancel it. Asserted at APPROVED_FOR_QUOTATION specifically: that is
     * a status the cutoff NEWLY admits, so this is where a widened map could quietly widen who may
     * act as well as when.
     *
     * <p><b>What this test does and does not pin, established by mutation-check.</b> Deleting the
     * ownership half of {@code PricingRequestService.cancel}'s own gate leaves this test GREEN: a
     * {@code sales} actor who does not own the ticket is already refused one step earlier, by
     * {@code requireViewable}'s own-deals-only scope filter. So this asserts the observable rule
     * (defence in depth — two independent guards agree) but is NOT evidence about cancel()'s gate.
     * {@link #cancel_byImport_isRefused_eventThoughImportDrivesTheRequestsWholeMiddleLife} is the
     * test that pins that line, because Import passes {@code requireViewable} and only the
     * ownership check stops them. Keep both: if the scope filter is ever relaxed, this one starts
     * carrying real weight, and the pair documents which guard is load-bearing today.
     */
    @Test
    void cancel_byASalesRepWhoDoesNotOwnTheDeal_isRefused_andTheRequestIsUntouched() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));

        assertThatThrownBy(() -> pricingRequestService.cancel(pricingRequestId, cancelRequest(), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);
        assertThat(isCancelStamped(pricingRequestId)).isFalse();
    }

    /**
     * Import drives this request's entire middle life — pickup, the factory conversation, marking it
     * ready — so "Import may cancel it" is the plausible-sounding wrong answer, not a strawman. They
     * may not: cancelling is the deal owner's call (or the CEO's).
     */
    @Test
    void cancel_byImport_isRefused_eventThoughImportDrivesTheRequestsWholeMiddleLife() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));

        assertThatThrownBy(() -> pricingRequestService.cancel(pricingRequestId, cancelRequest(), importActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);
        assertThat(isCancelStamped(pricingRequestId)).isFalse();
    }

    /**
     * The recorded deliberate divergence, pinned so it cannot be "tidied away" into consistency with
     * {@code TicketService.cancel} (owner-only, no CEO override). A manager must be able to unwind an
     * abandoned request without the original rep's session.
     */
    @Test
    void cancel_byTheCeoWhoDoesNotOwnTheDeal_isAllowed_theDeliberateDivergenceFromTicketCancel() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), ceoActor);

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.CANCELLED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The widening — the three statuses that could not be cancelled at all before.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void cancel_fromReadyForCeoReview_isNowAllowed() {
        long pricingRequestId = readyForCeoReviewPricingRequest(new BigDecimal("10"));
        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor);

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.CANCELLED);
    }

    /**
     * The status this gap hurt most: the CEO has the request open, the customer walks away, and
     * before this change there was nothing the deal owner could do about it short of killing the
     * whole ticket. The CEO's open DRAFT decision must go with it.
     */
    @Test
    void cancel_fromCeoReviewing_isNowAllowed_andRetiresTheCeosOpenDraftDecision() {
        long pricingRequestId = readyForCeoReviewPricingRequest(new BigDecimal("10"));
        decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        assertThat(decisionStatusOf(pricingRequestId)).isEqualTo("DRAFT");

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor);

        assertThat(statusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.CANCELLED);
        assertThat(decisionStatusOf(pricingRequestId)).isEqualTo("SUPERSEDED");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The cascade — every child the newly-admitted statuses can own.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The cascade gap the widening would otherwise have opened. At APPROVED_FOR_QUOTATION the
     * request's factory quote is {@code READY_FOR_COSTING} — a status the old
     * {@code ('DRAFT','REQUESTED')} predicate did not match — so cancelling would have left a live,
     * {@code is_current = TRUE} quote hanging off a CANCELLED request.
     *
     * <p>The SUPERSEDED quote in the same chain is the control: a cascade that cancelled everything
     * indiscriminately would destroy the record of what the factory first quoted. It is produced the
     * way production produces one (a second {@code receive} creates a revision and supersedes its
     * predecessor), not inserted, so the fixture cannot be more convenient than reality.
     */
    @Test
    void cancel_cancelsTheLiveFactoryQuote_andLeavesAnAlreadyTerminalOneAlone() {
        long pricingRequestId = approvedPricingRequestWithASupersededQuote();
        assertThat(quoteCountAt(pricingRequestId, FactoryQuoteStatus.READY_FOR_COSTING)).isEqualTo(1L);
        assertThat(quoteCountAt(pricingRequestId, FactoryQuoteStatus.SUPERSEDED)).isEqualTo(1L);

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor);

        assertThat(quoteCountAt(pricingRequestId, FactoryQuoteStatus.READY_FOR_COSTING)).isZero();
        assertThat(quoteCountAt(pricingRequestId, FactoryQuoteStatus.CANCELLED)).isEqualTo(1L);
        // Untouched, not swept up: the superseded revision is history, and history is not cancelled.
        assertThat(quoteCountAt(pricingRequestId, FactoryQuoteStatus.SUPERSEDED)).isEqualTo(1L);
        // chk_factory_quote_current_terminal permits is_current = FALSE only for SUPERSEDED/
        // CANCELLED, and uq_factory_quote_current_factory keys on it — so a cancelled quote that
        // stayed current would both misrepresent the row and block a later quote to the same
        // factory. The UPDATE sets both columns; this asserts it actually did.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote
             WHERE pricing_request_id = :id AND status = 'CANCELLED' AND is_current = TRUE
            """, Map.of("id", pricingRequestId), Long.class)).isZero();
    }

    /**
     * The other two children of a late cancel, and the one that is easiest to get wrong. V141 moved
     * costing to the CEO and {@code PricingCostingRepository} now inserts rows born directly at
     * {@code SUBMITTED} — so the cascade's historical {@code ('DRAFT','CALCULATED')} predicate
     * matched nothing a current system produces. That is a no-op invisible by construction: the
     * rows it was meant to catch never appear in the statuses it looked for.
     */
    @Test
    void cancel_fromApprovedForQuotation_retiresTheSubmittedCostingAndTheApprovedDecision() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        assertThat(costingStatusOf(pricingRequestId)).isEqualTo("SUBMITTED");
        assertThat(decisionStatusOf(pricingRequestId)).isEqualTo("APPROVED");

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor);

        assertThat(costingStatusOf(pricingRequestId)).isEqualTo("CANCELLED");
        assertThat(decisionStatusOf(pricingRequestId)).isEqualTo("SUPERSEDED");
    }

    /**
     * The customer-facing hole the cutoff would otherwise have opened, and the reason {@code DRAFT}
     * is in the quotation cascade's predicate.
     *
     * <p>A quotation may be created (as DRAFT) only while the request sits at
     * APPROVED_FOR_QUOTATION — a status cancel now admits. {@code CustomerQuotationService.issue}
     * gates on the QUOTATION's own {@code doc_status} and only moves the pricing request
     * {@code if} it is still APPROVED_FOR_QUOTATION — an {@code if}, not an assertion. So a DRAFT
     * quotation orphaned on a CANCELLED request would still ISSUE cleanly, silently skipping the
     * transition, and put a live offer in front of a customer whose deal was cancelled. Retiring
     * the draft in the cascade is what makes that unreachable, and this test asserts the
     * consequence rather than the mechanism.
     */
    @Test
    void cancel_retiresADraftQuotation_soItCanNeverBeIssuedToTheCustomerAfterwards() {
        long pricingRequestId = approvedPricingRequest(new BigDecimal("10"));
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()), salesActor);
        assertThat(draft.docStatus()).isEqualTo(QuotationStatus.DRAFT);

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor);

        assertThat(quotationService.get(draft.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        // The consequence that actually matters: no offer can reach the customer from here.
        assertThatThrownBy(() -> quotationService.issue(draft.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(quotationService.get(draft.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixtures — every precondition is driven through the real services, never SQL
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Drives a fresh single-item pricing request from DRAFT to APPROVED_FOR_QUOTATION. */
    private long approvedPricingRequest(BigDecimal quantity) {
        long pricingRequestId = readyForCeoReviewPricingRequest(quantity);
        approveCeoDecision(pricingRequestId);
        return pricingRequestId;
    }

    /** Drives a fresh single-item pricing request from DRAFT to READY_FOR_CEO_REVIEW. */
    private long readyForCeoReviewPricingRequest(BigDecimal quantity) {
        PricingRequestRequests.CreatePricingRequestRequest request =
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                new BigDecimal("5000.00"), "THB", "cancel cutoff walk", UUID.randomUUID().toString(),
                List.of(pricingItem(quantity)));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).get(0);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            factoryResponse(draft.items().get(0).pricingRequestItemId(), quantity), importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
    }

    /**
     * An APPROVED_FOR_QUOTATION request whose factory-quote chain contains BOTH a live
     * READY_FOR_COSTING quote and an already-SUPERSEDED one. The second {@code receive} is the
     * production path for a factory sending a revised price: it supersedes the previous revision and
     * pulls the request back to AWAITING_FACTORY_RESPONSE, which is then re-marked ready.
     */
    private long approvedPricingRequestWithASupersededQuote() {
        BigDecimal quantity = new BigDecimal("10");
        long pricingRequestId = readyForCeoReviewPricingRequest(quantity);
        FactoryQuoteDto current = factoryQuoteService.list(pricingRequestId, importActor).stream()
            .filter(FactoryQuoteDto::current)
            .findFirst()
            .orElseThrow();
        FactoryQuoteDto revised = factoryQuoteService.receive(current.id(),
            factoryResponse(current.items().get(0).pricingRequestItemId(), quantity), importActor);
        factoryQuoteService.markReadyForCosting(revised.id(), importActor);
        approveCeoDecision(pricingRequestId);
        return pricingRequestId;
    }

    private void approveCeoDecision(long pricingRequestId) {
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        }
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
    }

    private CustomerQuotationDto issueQuotation(long pricingRequestId) {
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()), salesActor);
        return quotationService.issue(draft.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
    }

    private PricingRequestRequests.CancelPricingRequestRequest cancelRequest() {
        return new PricingRequestRequests.CancelPricingRequestRequest("ลูกค้ายกเลิกโครงการ");
    }

    // ── Assertions read the DB directly: a refusal is only evidence if the ROW did not move ──

    private String statusOf(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), String.class);
    }

    /** True when either cancel stamp is set — catches a guard that 409s only after writing. */
    private boolean isCancelStamped(long pricingRequestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT cancelled_at IS NOT NULL OR cancelled_by IS NOT NULL
              FROM sales.pricing_request WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), Boolean.class));
    }

    private String decisionStatusOf(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT status FROM sales.pricing_decision WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), String.class);
    }

    private String costingStatusOf(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT status FROM sales.pricing_costing WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId), String.class);
    }

    private long quoteCountAt(long pricingRequestId, String status) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote
             WHERE pricing_request_id = :id AND status = :status
            """, Map.of("id", pricingRequestId, "status", status), Long.class);
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(BigDecimal quantity) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null, "SCG",
            "Tile Cancel", "SCG Tile Cancel", null, null, "60x60", FACTORY, quantity, quantity, "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
    }

    private ReceiveFactoryQuoteRequest factoryResponse(long pricingRequestItemId, BigDecimal quantity) {
        return new ReceiveFactoryQuoteRequest("REF-CANCEL-" + UUID.randomUUID(), "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quantity, "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Tile Cancel", "White", "Matte", "60x60", FACTORY,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
