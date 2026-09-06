package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteAttachmentDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.SendFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.StartNegotiationRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketStatus;

class PricingFactoryQuoteCostingIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteRepository factoryQuoteRepository;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;
    // V141 ("CEO owns costing"): the CEO path — Import's costing create/recalculate/submit is
    // gone, so every test that used to build a costing via costingService now drives it through
    // PricingDecisionService#startReview/recalculateCost instead.
    private PricingDecisionService pricingDecisionService;
    private LandedCostCalculator landedCostCalculator;
    private NotificationRepository notificationRepository;

    private long salesRepId;
    private long importUserId;
    private long secondImportUserId;
    private long ceoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal secondImportActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;
    private long ticketId;
    // Financial-integrity review Finding A (commit 3): submit() now requires every item's
    // catalog snapshot to be fully resolved — see pricingItem() below.
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;
    // "Factory C" / "TestLand" carries an all-zero price_calc_config (freight/insurance/inland/
    // duty all 0), so landedCostPerUnitThb == goodsCostThb exactly for every line costed
    // against it — used by the unit-normalization test matrix below so those assertions are
    // not diluted by unrelated cost components.
    private long catalogProductIdFactoryC;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
        notificationRepository = notifications;
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        // V152 (V109 engine wiring): shared by FactoryQuoteService's markReadyForCosting
        // auto-advance check and PricingDecisionService's startReview/recalculateCost.
        landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications,
            new FileStorageService("/tmp/glr-pricing-test-uploads"), landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing
        // create/recalculate/submit is gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);
        pricingDecisionService = new PricingDecisionService(new PricingDecisionRepository(jdbc), pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales-step2@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า เอ", "import-a@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        secondImportUserId = createEmployee(employees, "ฝ่ายนำเข้า บี", "import-b@glr.co.th", "OPS", "ฝ่ายปฏิบัติการ");
        ceoUserId = createManagingDirector(employees, "ผู้บริหาร ทดสอบ", "ceo-step2@glr.co.th");
        accountUserId = createEmployee(employees, "บัญชี ทดสอบ", "account-step2@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager-step2@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        secondImportActor = actor(secondImportUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");
        // V152 (V109 engine wiring): factory country must be one of V109's seeded origin
        // countries (Italy/Spain/China) or LandedCostCalculator's freight lookup 422s ("ไม่พบ
        // อัตราค่าขนส่ง") — 'Thailand'/'TestLand' (pre-V109) are no longer costable. Every
        // catalogProductIdFactory* below uses insertCatalogProduct's 6-arg overload, which
        // defaults thickness_mm to 10 (inside Italy's seeded [8,12) band, whose top band is
        // open-ended, so ANY quantity resolves — see that helper's own comment).
        //
        // V163 merged sales.factory_config (email/unit) onto price_catalog.factories and dropped
        // the former table, so the RFQ email/unit fixture is now an UPDATE on the row
        // insertCatalogProduct creates (by name), run right after it, instead of a separate INSERT.
        catalogProductIdFactoryA = insertCatalogProduct("Factory A", "IT", "TEST-A-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B", "IT", "TEST-B-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        jdbc.update("""
            UPDATE price_catalog.factories SET email = 'factory-a@example.com', unit = 'piece'
             WHERE name = 'Factory A'
            """, Map.of());
        jdbc.update("""
            UPDATE price_catalog.factories SET email = 'factory-b@example.com', unit = 'piece'
             WHERE name = 'Factory B'
            """, Map.of());

        catalogProductIdFactoryC = insertCatalogProduct("Factory C", "IT", "TEST-C-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        jdbc.update("""
            UPDATE price_catalog.factories SET email = 'factory-c@example.com', unit = 'piece'
             WHERE name = 'Factory C'
            """, Map.of());

        CustomerDto customer = customers.create(
            "บริษัท Step 2 จำกัด", "0100000000001", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0001");
        ProjectDto project = projects.create(customer.id(), "โครงการ Step 2");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Step 2", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A", "Factory A"), ticketItem("Cotto", "Tile B", "Factory B"))),
            salesActor);
        ticketId = created.summary().id();
    }

    @Test
    void revisedScenario_importControlsReadinessRecalculationAndSubmitWithoutMovingTheDeal() {
        String ticketStatusBefore = jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        String salesStageBefore = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);

        long pricingRequestId = pricingRequestService.createDraft(ticketId, pricingRequest(), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(drafts).hasSize(2);
        FactoryQuoteDto factoryA = quoteFor(drafts, "Factory A");
        FactoryQuoteDto factoryB = quoteFor(drafts, "Factory B");

        factoryQuoteService.send(factoryA.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", null, null), secondImportActor);
        factoryQuoteService.send(factoryB.id(),
            new SendFactoryQuoteRequest("factory-b@example.com", null, null), secondImportActor);
        // send() only enqueues; the outbox worker (simulated here by draining the queue directly)
        // is what actually calls the mail provider and finalizes quote/pricing-request state.
        drainDispatches();
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(2L);

        FactoryQuoteDto factoryARevision1 = factoryQuoteService.receive(factoryA.id(),
            response("REF-A-1", "THB", "120.00", factoryA.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryARevision1.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(factoryQuoteService.list(pricingRequestId, importActor))
            .filteredOn(q -> "Factory A".equals(q.factoryName()))
            .hasSize(1);
        // Factory B has not answered yet: the request must still read as awaiting factory
        // response, not costing-in-progress, even though Factory A already responded.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        FactoryQuoteDto negotiating = factoryQuoteService.startNegotiation(factoryARevision1.id(),
            new StartNegotiationRequest("ขอต่อรองราคา"), importActor);
        assertThat(negotiating.status()).isEqualTo(FactoryQuoteStatus.NEGOTIATING);

        FactoryQuoteDto factoryARevision2 = factoryQuoteService.receive(negotiating.id(),
            response("REF-A-2", "THB", "110.00", negotiating.items().get(0).pricingRequestItemId()), importActor);
        List<FactoryQuoteDto> factoryARevisions = factoryQuoteService.list(pricingRequestId, importActor).stream()
            .filter(q -> "Factory A".equals(q.factoryName()))
            .toList();
        assertThat(factoryARevisions).extracting(FactoryQuoteDto::revisionNo).containsExactly(1, 2);
        assertThat(factoryARevisions).filteredOn(q -> q.revisionNo() == 1).singleElement()
            .extracting(FactoryQuoteDto::status).isEqualTo(FactoryQuoteStatus.SUPERSEDED);
        assertThat(factoryARevision2.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);

        factoryQuoteService.markReadyForCosting(factoryARevision2.id(), importActor);
        // V141 ("CEO owns costing"): Factory A alone being ready must not advance the request —
        // Factory B has not answered at all yet, so LandedCostCalculator.isFullyResolvable is
        // false and markReadyForCosting's auto-advance does not fire.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        FactoryQuoteDto factoryBResponse = factoryQuoteService.receive(factoryB.id(),
            response("REF-B-1", "THB", "200.00", factoryB.items().get(0).pricingRequestItemId()), importActor);
        factoryQuoteService.markReadyForCosting(factoryBResponse.id(), importActor);
        // The LAST factory quote becoming ready auto-advances the request straight to
        // READY_FOR_CEO_REVIEW — there is no Import-driven costing draft/recalculate/submit step
        // any more (PricingCostingService is read-only; see its own javadoc).
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // A mid-flight revision arriving while READY_FOR_CEO_REVIEW pulls the REQUEST back to
        // AWAITING_FACTORY_RESPONSE — the direct replacement for the old "costing goes stale"
        // assertion: there is no submitted-costing row sitting around any more to go stale, since
        // the cost is computed fresh, once, at CEO-review time (see PricingRequestStatus's
        // READY_FOR_CEO_REVIEW -> AWAITING_FACTORY_RESPONSE back-edge).
        FactoryQuoteDto factoryARevision3 = factoryQuoteService.receive(factoryARevision2.id(),
            response("REF-A-3", "THB", "100.00", factoryARevision2.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryARevision3.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(factoryARevision3.revisionNo()).isEqualTo(3);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        // The CEO cannot open a review whose price is about to change under them until Import
        // re-marks this revision ready.
        assertThatThrownBy(() -> pricingDecisionService.startReview(pricingRequestId,
                new StartPricingDecisionRequest(null, "THB", null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        factoryQuoteService.markReadyForCosting(factoryARevision3.id(), importActor);
        // Factory B's quote was untouched by A's revision and is still READY_FOR_COSTING, so
        // re-marking A ready re-advances the request straight back to READY_FOR_CEO_REVIEW.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // CEO opens review — computes + persists the costing itself, from whichever factory
        // quote is CURRENT right now (Factory A's rev 3 at 100.00, Factory B's rev 1 at 200.00).
        PricingDecisionDto decision = pricingDecisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(null, "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        PricingCostingDto submitted = costingService.get(decision.pricingCostingId(), ceoActor);
        assertThat(submitted.items()).extracting(item -> item.factoryQuoteRevisionNo()).contains(3);
        assertThat(submitted.items())
            .extracting(item -> item.rawUnitPrice())
            .contains(new BigDecimal("100.0000"), new BigDecimal("200.0000"));
        assertThat(factoryQuoteService.list(pricingRequestId, importActor))
            .filteredOn(q -> q.items().stream().anyMatch(item -> item.rawUnitPrice() != null))
            .isNotEmpty();

        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> costingService.get(submitted.id(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(ticketStatusBefore);
        assertThat(jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(salesStageBefore);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
               AND (raw_price IS NOT NULL OR calced_cost IS NOT NULL OR proposed_price IS NOT NULL OR approved_price IS NOT NULL)
            """, Map.of("ticketId", ticketId), Long.class))
            .isZero();
    }

    @Test
    void startReviewRejectsClientRequestReplayAcrossPricingRequests() {
        // V141 ("CEO owns costing"): the clientRequestId idempotency guard moves onto
        // PricingDecisionService#startReview — Import's costing createDraft (the old home of this
        // guard) is gone.
        long firstPricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("11111111-1111-4111-8111-111111111111"), salesActor).summary().id();
        pricingRequestService.submit(firstPricingRequestId, salesActor);
        pricingRequestService.pickup(firstPricingRequestId, importActor);
        markAllFactoriesReady(firstPricingRequestId);

        String startReviewClientRequestId = "99999999-9999-4999-8999-999999999999";
        PricingDecisionDto firstDecision = pricingDecisionService.startReview(firstPricingRequestId,
            new StartPricingDecisionRequest(null, "THB", null, startReviewClientRequestId), ceoActor);
        assertThat(firstDecision.pricingRequestId()).isEqualTo(firstPricingRequestId);

        long secondPricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("22222222-2222-4222-8222-222222222222"), salesActor).summary().id();
        pricingRequestService.submit(secondPricingRequestId, salesActor);
        pricingRequestService.pickup(secondPricingRequestId, importActor);
        markAllFactoriesReady(secondPricingRequestId);

        assertThatThrownBy(() -> pricingDecisionService.startReview(secondPricingRequestId,
                new StartPricingDecisionRequest(null, "THB", null, startReviewClientRequestId), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // Assert absence, not merely the status code: the second pricing request gained no
        // decision (and therefore no costing) from the rejected replay attempt.
        assertThat(pricingDecisionService.list(secondPricingRequestId, importActor)).isEmpty();
        assertThat(pricingRequestService.get(secondPricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    @Test
    void firstFactoryResponseOnMultiFactoryRequestMovesToAwaitingFactoryResponse_andMarkingItReadyAloneDoesNotAdvanceEither() {
        // Regression for the review finding, extended for V141 ("CEO owns costing"): a single
        // factory answering (and even being marked READY_FOR_COSTING) on a multi-factory pricing
        // request must not advance the whole request while other factories (Factory B here) are
        // still pending. The auto-advance in FactoryQuoteService.markReadyForCosting only fires
        // once LandedCostCalculator.isFullyResolvable is true for EVERY item — see
        // bothFactoriesReadyThenAutoAdvanceToReadyForCeoReview below for the LAST-quote-ready case.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("44444444-4444-4444-8444-444444444444"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            response("REF-DIRECT", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);

        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
    }

    @Test
    void bothFactoriesReadyThenAutoAdvanceToReadyForCeoReview() {
        // V141 ("CEO owns costing"): the LAST factory quote becoming ready auto-advances the
        // request straight to READY_FOR_CEO_REVIEW — there is no Import-driven "create a costing
        // draft" step any more to separately trigger the old COSTING_IN_PROGRESS transition.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        markAllFactoriesReady(pricingRequestId);

        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    /**
     * How long a racer waits at the rendezvous below for the other one to reach the same point.
     * Paid ONCE, and only on the correct (serialized) path — see the test's javadoc.
     */
    private static final long MARK_READY_RENDEZVOUS_MILLIS = 3_000;

    /**
     * THE GAP THIS TEST CLOSES: {@code FactoryQuoteService#markReadyForCosting} takes a
     * {@code pg_advisory_xact_lock} on the pricing request (via {@code
     * PricingRequestRepository#lockPricingRequest}) and its own comment explains exactly what that
     * lock is for — "two Import users marking the LAST two factories ready simultaneously would
     * BOTH evaluate isFullyResolvable to false under READ COMMITTED, neither would advance, and
     * the request would sit at AWAITING_FACTORY_RESPONSE with every quote ready". Nothing
     * exercised two callers racing, so that lock was unguarded: delete it and every existing test
     * stays green while production acquires a stuck pricing request recoverable only by
     * re-negotiating a quote back out of READY_FOR_COSTING.
     *
     * <h2>Two traps this test has to dodge, or it would be theatre</h2>
     *
     * <p><b>1. The lock has to actually be held.</b> {@link AbstractPostgresIntegrationTest} has no
     * Spring context and hand-wires every service with {@code new}, so {@code @Transactional} is
     * inert and {@code pg_advisory_xact_lock} — which is transaction-scoped — would be released at
     * the end of its own auto-committed {@code SELECT} instead of spanning the call. A racing test
     * written the obvious way would therefore pass with or without the lock, proving nothing. Both
     * racers here go through {@link AbstractPostgresIntegrationTest#transactional}, the real AOP
     * proxy built from the production annotation, so the lock genuinely spans
     * {@code markReadyForCosting}. {@code TransactionalHarnessIntegrationTest} pins that
     * distinction directly.
     *
     * <p><b>2. A bare race is not reproducible.</b> Two threads launched at the same instant only
     * <i>sometimes</i> interleave the damaging way, so a red would be a coin flip and a green would
     * mean nothing. The {@link CountDownLatch} rendezvous inside the spied {@code markReady} forces
     * the interleaving instead of hoping for it: each racer, having written its own
     * {@code READY_FOR_COSTING} but not yet committed it, waits for the other to reach the same
     * point before evaluating {@code isFullyResolvable}.
     *
     * <ul>
     *   <li><b>Lock present (production):</b> the second racer is still blocked in
     *       {@code pg_advisory_xact_lock} and never reaches the rendezvous, so the first times out
     *       after {@link #MARK_READY_RENDEZVOUS_MILLIS}, commits, and releases the lock; the second
     *       then runs with the first's write COMMITTED and visible, sees every quote ready, and
     *       advances. Cost: one bounded wait.</li>
     *   <li><b>Lock removed (the mutation):</b> both reach the rendezvous, both proceed with the
     *       other's write still uncommitted and invisible under READ COMMITTED, both evaluate
     *       {@code isFullyResolvable} to false, neither advances — and the status assertion below
     *       goes red deterministically rather than occasionally.</li>
     * </ul>
     *
     * <p>The spy is a <b>rendezvous point, not the system under test</b>: it delegates to the real
     * {@code markReady} and returns its real row count. Everything asserted is real Postgres state.
     */
    @Test
    void markReadyForCostingSerializesConcurrentCallersOnTheLastTwoQuotes_soTheAutoAdvanceIsNotMissed()
            throws Exception {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("dddddddd-3333-4333-8333-dddddddddddd"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draftA = quoteFor(drafts, "Factory A");
        FactoryQuoteDto draftB = quoteFor(drafts, "Factory B");
        FactoryQuoteDto respondedA = factoryQuoteService.receive(draftA.id(),
            response("REF-RACE-A", "THB", "100.00", draftA.items().get(0).pricingRequestItemId()), importActor);
        FactoryQuoteDto respondedB = factoryQuoteService.receive(draftB.id(),
            response("REF-RACE-B", "THB", "200.00", draftB.items().get(0).pricingRequestItemId()), importActor);
        // Neither quote is ready yet, so from its own transaction's point of view EACH racer is
        // marking "the last outstanding factory" ready — the exact situation the lock exists for.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        CountDownLatch bothMarkedReady = new CountDownLatch(2);
        FactoryQuoteRepository racingQuotes = spy(new FactoryQuoteRepository(jdbc));
        doAnswer(invocation -> {
            Object rowsUpdated = invocation.callRealMethod();
            bothMarkedReady.countDown();
            bothMarkedReady.await(MARK_READY_RENDEZVOUS_MILLIS, TimeUnit.MILLISECONDS);
            return rowsUpdated;
        }).when(racingQuotes).markReady(ArgumentMatchers.anyLong());
        // landedCostCalculator is deliberately the UNSPIED one: isFullyResolvable must read the
        // database exactly as production does, through this thread's own transaction.
        FactoryQuoteService racing = transactional(new FactoryQuoteService(racingQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notificationRepository,
            new FileStorageService("/tmp/glr-pricing-test-uploads"), landedCostCalculator));

        Callable<Long> firstImportUser = () -> racing.markReadyForCosting(respondedA.id(), importActor).id();
        Callable<Long> secondImportUser = () -> racing.markReadyForCosting(respondedB.id(), secondImportActor).id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Long> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<Long> futureA = executor.submit(firstImportUser);
            Future<Long> futureB = executor.submit(secondImportUser);
            for (Future<Long> future : List.of(futureA, futureB)) {
                try {
                    successes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // ── Anti-vacuity, before the claim: the race really happened, and both halves of it won ──
        assertThat(failures)
            .as("neither racer may fail — unlike the approve()/createCustomerChangeRevision races "
                + "elsewhere in this suite, marking two DIFFERENT factories' quotes ready is not a "
                + "contended write, so serialization must cost a wait and nothing else")
            .isEmpty();
        assertThat(successes).hasSize(2);
        verify(racingQuotes, times(2)).markReady(ArgumentMatchers.anyLong());
        assertThat(bothMarkedReady.getCount())
            .as("both racers must have reached the rendezvous inside markReady — otherwise the "
                + "interleaving was never forced and this test proves nothing about the lock")
            .isZero();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote
             WHERE pricing_request_id = :id AND is_current = TRUE AND status = 'READY_FOR_COSTING'
            """, Map.of("id", pricingRequestId), Long.class))
            .as("both quotes really are READY_FOR_COSTING, so the auto-advance's precondition is "
                + "genuinely satisfied and the status below is about the lock, not about the data")
            .isEqualTo(2L);

        // ── The claim ────────────────────────────────────────────────────────────────────────
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .as("the LAST quote becoming ready must advance the request. Without "
                + "lockPricingRequest, both racers evaluate isFullyResolvable against the other's "
                + "uncommitted write, both see false, and the request is stranded at "
                + "AWAITING_FACTORY_RESPONSE with every quote ready — recoverable only by "
                + "re-negotiating a quote back out of READY_FOR_COSTING")
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_COSTING_SUBMITTED'
            """, Map.of("id", pricingRequestId), Long.class))
            .as("exactly one racer may advance the request: the transition is a compare-and-set on "
                + "AWAITING_FACTORY_RESPONSE, so a second advance would mean the audit trail "
                + "recorded a transition that never happened")
            .isEqualTo(1L);
    }

    @Test
    void factoryQuoteReceiveReplaysLostResponseWithoutDuplicatingRevisionOrSideEffects() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        markAllFactoriesReady(pricingRequestId);
        // V141 ("CEO owns costing"): markAllFactoriesReady auto-advances the request all the way
        // to READY_FOR_CEO_REVIEW (both quotes ready) — there is no standalone Import costing to
        // build as scaffolding any more.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        FactoryQuoteDto factoryA = quoteFor(factoryQuoteService.list(pricingRequestId, importActor), "Factory A");

        String replayClientRequestId = "cccccccc-3333-4333-8333-cccccccccccc";
        FactoryQuoteDto firstAttempt = factoryQuoteService.receive(factoryA.id(),
            response("REF-REVISED", "THB", "90.00", factoryA.items().get(0).pricingRequestItemId(), replayClientRequestId),
            importActor);
        assertThat(firstAttempt.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(firstAttempt.revisionNo()).isEqualTo(2);
        // V141: a revision arriving while READY_FOR_CEO_REVIEW pulls the REQUEST back to
        // AWAITING_FACTORY_RESPONSE — the replacement for the old markOpenCostingsStale side
        // effect (there is no costing row sitting around any more to mark stale).
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        // Simulate the HTTP response for the call above being lost: Import retries with the
        // exact same request against the same (now-superseded) factoryQuoteId.
        FactoryQuoteDto replay = factoryQuoteService.receive(factoryA.id(),
            response("REF-REVISED", "THB", "90.00", factoryA.items().get(0).pricingRequestItemId(), replayClientRequestId),
            importActor);

        assertThat(replay.id()).isEqualTo(firstAttempt.id());
        assertThat(factoryQuoteService.list(pricingRequestId, importActor))
            .filteredOn(q -> "Factory A".equals(q.factoryName()))
            .extracting(FactoryQuoteDto::revisionNo)
            .containsExactly(1, 2);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote
             WHERE pricing_request_id = :id
               AND factory_name_snapshot = 'Factory A'
               AND status = 'SUPERSEDED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_RESPONSE_REVISED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.notification
             WHERE link = :link
               AND type = 'FACTORY_RESPONSE_REVISED'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote_response_receipt
             WHERE client_request_id = CAST(:clientRequestId AS uuid)
            """, Map.of("clientRequestId", replayClientRequestId), Long.class)).isEqualTo(1L);
        // The replay must not have re-triggered the pull-back a second time: the request must
        // still read exactly AWAITING_FACTORY_RESPONSE, not have bounced status again.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
    }

    @Test
    void factoryQuoteReceiveConcurrentRetryWithSameClientRequestIdBothCallersGetTheSameReplayedResult() throws Exception {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("dddddddd-4444-4444-8444-dddddddddddd"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        String raceClientRequestId = "eeeeeeee-5555-4555-8555-eeeeeeeeeeee";
        long itemId = draft.items().get(0).pricingRequestItemId();

        // AbstractPostgresIntegrationTest wires FactoryQuoteService directly with `new`, so there
        // is no Spring ApplicationContext/proxy and @Transactional is never applied here — every
        // JDBC call the repository makes would otherwise auto-commit individually, which means
        // lockResponseIdempotencyKey's pg_advisory_xact_lock would be acquired and released
        // within its own single-statement transaction, serializing nothing. To get REAL
        // transactional coverage for that lock (matching what Spring's @Transactional proxy does
        // in production — one connection, one transaction, held for the whole method), each
        // racing call below is wrapped in an explicit TransactionTemplate bound to the same
        // DataSource the test's `jdbc` uses, so the advisory lock genuinely spans the whole
        // receive() call and the second caller really blocks on it.
        var txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Callable<FactoryQuoteDto> task = () -> txTemplate.execute(status -> factoryQuoteService.receive(draft.id(),
            response("REF-RACE", "THB", "100.00", itemId, raceClientRequestId), importActor));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FactoryQuoteDto> first = executor.submit(task);
            Future<FactoryQuoteDto> second = executor.submit(task);

            FactoryQuoteDto firstResult = first.get(10, TimeUnit.SECONDS);
            FactoryQuoteDto secondResult = second.get(10, TimeUnit.SECONDS);

            // Both callers succeed (a lost-response retry must never surface a 409 to Import) and
            // resolve to the exact same response — one of them raced into the receipt lookup after
            // blocking on the advisory lock and returned the winner's already-recorded result.
            assertThat(firstResult.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
            assertThat(secondResult.id()).isEqualTo(firstResult.id());
            assertThat(secondResult.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote
             WHERE pricing_request_id = :id
               AND factory_name_snapshot = 'Factory A'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote_response_receipt
             WHERE client_request_id = CAST(:clientRequestId AS uuid)
            """, Map.of("clientRequestId", raceClientRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_RESPONSE_RECEIVED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.notification
             WHERE link = :link
               AND type = 'FACTORY_RESPONSE_RECEIVED'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void factoryQuoteReceiveConcurrentRevisionRetryWithSameClientRequestIdCreatesExactlyOneRevision() throws Exception {
        // The first-response path is safe from a concurrent retry even without the advisory
        // lock: it UPDATEs an existing row, so Postgres row locking on that row alone forces
        // the loser to block, see the winner's committed receipt, and return it gracefully.
        // The REVISION path has no such row to block on — supersede()+createRevision() INSERTs
        // a brand-new row — so lockResponseIdempotencyKey is the ONLY thing preventing two
        // racing retries of the same clientRequestId from both creating a new revision. This
        // test targets exactly that path.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("aaaaaaaa-7777-4777-8777-aaaaaaaaaaaa"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        long itemId = draft.items().get(0).pricingRequestItemId();

        // Move the quote to RESPONSE_RECEIVED first (a first response, not part of the race) so
        // the two racing calls below land on the revision branch.
        FactoryQuoteDto firstResponse = factoryQuoteService.receive(draft.id(),
            response("REF-A-1", "THB", "120.00", itemId, UUID.randomUUID().toString()), importActor);
        assertThat(firstResponse.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);

        String raceClientRequestId = "bbbbbbbb-8888-4888-8888-bbbbbbbbbbbb";
        // Same real-transaction rationale as the first-response concurrency test above: this
        // test harness has no Spring proxy, so each racing call is wrapped in its own explicit
        // transaction bound to the same DataSource `jdbc` uses, giving the advisory lock genuine
        // cross-statement coverage for the whole receive() call.
        var txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
        Callable<FactoryQuoteDto> task = () -> txTemplate.execute(status -> factoryQuoteService.receive(firstResponse.id(),
            response("REF-A-2", "THB", "110.00", itemId, raceClientRequestId), importActor));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FactoryQuoteDto> first = executor.submit(task);
            Future<FactoryQuoteDto> second = executor.submit(task);

            FactoryQuoteDto firstResult = first.get(10, TimeUnit.SECONDS);
            FactoryQuoteDto secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
            assertThat(firstResult.revisionNo()).isEqualTo(2);
            assertThat(secondResult.id()).isEqualTo(firstResult.id());
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote
             WHERE pricing_request_id = :id
               AND factory_name_snapshot = 'Factory A'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote_response_receipt
             WHERE client_request_id = CAST(:clientRequestId AS uuid)
            """, Map.of("clientRequestId", raceClientRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_RESPONSE_REVISED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM hr.notification
             WHERE link = :link
               AND type = 'FACTORY_RESPONSE_REVISED'
            """, Map.of("link", "/pricing-requests/" + pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void factoryQuoteReceiveRejectsClientRequestIdReusedAgainstADifferentFactoryQuote() {
        // Regression for the review finding: the receipt lookup must be scoped to the QUOTE
        // CHAIN, not the pricing request. A pricing request has one quote per factory, so
        // reusing a clientRequestId against a different factory's quote in the same pricing
        // request must 409 — silently returning Factory A's receipt would discard Factory B's
        // response with a 200.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("ffffffff-6666-4666-8666-ffffffffffff"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto factoryA = quoteFor(drafts, "Factory A");
        FactoryQuoteDto factoryB = quoteFor(drafts, "Factory B");

        String reusedClientRequestId = "11111111-9999-4999-8999-111111111111";
        FactoryQuoteDto responseA = factoryQuoteService.receive(factoryA.id(),
            response("REF-A", "THB", "100.00", factoryA.items().get(0).pricingRequestItemId(), reusedClientRequestId),
            importActor);
        assertThat(responseA.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);

        ReceiveFactoryQuoteRequest reusedForB = response("REF-B", "THB", "200.00",
            factoryB.items().get(0).pricingRequestItemId(), reusedClientRequestId);
        assertThatThrownBy(() -> factoryQuoteService.receive(factoryB.id(), reusedForB, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        FactoryQuoteDto factoryBAfter = factoryQuoteService.get(factoryB.id(), importActor);
        assertThat(factoryBAfter.status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        assertThat(factoryBAfter.items())
            .allSatisfy(item -> assertThat(item.rawUnitPrice()).isNull());
    }

    @Test
    void factoryQuoteAttachmentsAreRawQuoteOnlyAndVisibleToCeo() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("55555555-5555-4555-8555-555555555555"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        FactoryQuoteAttachmentDto attachment = factoryQuoteService.uploadAttachment(draft.id(),
            new MockMultipartFile("file", "factory-a.pdf", "application/pdf", "quote".getBytes()),
            importActor);

        assertThat(factoryQuoteService.get(draft.id(), ceoActor).attachments())
            .extracting(FactoryQuoteAttachmentDto::id)
            .contains(attachment.id());
        // Wrong-way-round: raw supplier evidence stays Import/CEO only — Sales and Sales Manager
        // (who both otherwise have oversight visibility on the pricing request itself) must not
        // be able to reach it via list/get/download.
        assertThatThrownBy(() -> factoryQuoteService.getAttachment(attachment.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.getAttachment(attachment.id(), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── Factory quote attachment deletion hardening (V69, review remediation COMMIT 4) ──
    //
    // The audit defect this hardens: deletion used to be permitted whenever the parent pricing
    // request was in a broad "mutable" set that included READY_FOR_CEO_REVIEW, and physically
    // removed the row and file. Each test below isolates exactly ONE of the three independent
    // guards deleteAttachment now applies, by constructing state where the OTHER two guards do
    // not (and could not) fire — see each test's own comment for how.

    @Test
    void factoryQuoteAttachmentDeletion_tombstonesInsteadOfPhysicallyDeletingWhenPermitted() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0001-4001-8001-a10000000001"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        FactoryQuoteAttachmentDto attachment = factoryQuoteService.uploadAttachment(draft.id(),
            new MockMultipartFile("file", "factory-a.pdf", "application/pdf", "quote".getBytes()), importActor);
        // V134 storage-durability fix: factory-quote attachments are database-backed now (see
        // FactoryQuoteService#uploadAttachment) -- the old "file survives a tombstone" assertion
        // checked disk existence; the equivalent check is now that the blob row survives.
        assertThat(new th.co.glr.hr.attachment.FileAttachmentBlobRepository(jdbc).findContent(attachment.id()))
            .as("the attachment's bytes must exist right after upload")
            .isPresent();

        factoryQuoteService.deleteAttachment(attachment.id(), "duplicate upload", importActor);

        // The row and its bytes are both KEPT — this is the tombstone, not a hard delete.
        assertThat(new th.co.glr.hr.attachment.FileAttachmentBlobRepository(jdbc).findContent(attachment.id()))
            .as("the attachment's bytes must survive a refused/tombstoned delete")
            .isPresent();
        FactoryQuoteAttachmentDto tombstoned = factoryQuoteService.get(draft.id(), ceoActor).attachments().stream()
            .filter(a -> a.id() == attachment.id()).findFirst().orElseThrow();
        assertThat(tombstoned.deletedAt()).isNotNull();
        assertThat(tombstoned.deletedBy()).isEqualTo(importActor.id());
        assertThat(tombstoned.deleteReason()).isEqualTo("duplicate upload");
        // A second delete attempt on an already-tombstoned attachment must not succeed again.
        assertThatThrownBy(() -> factoryQuoteService.deleteAttachment(attachment.id(), "again", importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * MUTATION-CHECKABLE GUARD 1: {@code FactoryQuoteService.ATTACHMENT_DELETE_STATUSES}
     * deliberately excludes READY_FOR_CEO_REVIEW (unlike the broader upload-time
     * MUTABLE_STATUSES). Isolated from the other two guards by attaching to an OLD quote
     * revision that was superseded BEFORE ever being marked ready or used by any costing — so
     * neither "quote is READY_FOR_COSTING" nor "referenced by a SUBMITTED costing" can
     * coincidentally also be why deletion is refused.
     */
    @Test
    void factoryQuoteAttachmentDeletion_isRefusedOncePricingRequestReachesReadyForCeoReview() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0002-4002-8002-a10000000002"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        FactoryQuoteDto revision1 = factoryQuoteService.receive(draft.id(),
            response("REF-A-1", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);
        FactoryQuoteAttachmentDto attachmentOnRevision1 = factoryQuoteService.uploadAttachment(revision1.id(),
            new MockMultipartFile("file", "revision1.pdf", "application/pdf", "quote".getBytes()), importActor);
        // Revise BEFORE marking ready: revision1 becomes SUPERSEDED, never READY_FOR_COSTING,
        // never referenced by any costing (only revision2 is).
        FactoryQuoteDto revision2 = factoryQuoteService.receive(revision1.id(),
            response("REF-A-2", "THB", "95.00", revision1.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryQuoteService.get(revision1.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.SUPERSEDED);
        // V141 ("CEO owns costing"): this is the ONLY factory quote on this (single-factory)
        // pricing request, so marking it ready auto-advances the request straight to
        // READY_FOR_CEO_REVIEW — there is no separate Import costing draft/submit step any more.
        factoryQuoteService.markReadyForCosting(revision2.id(), importActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // Preconditions that prove this test isolates guard 1 alone:
        assertThat(factoryQuoteService.get(revision1.id(), importActor).status())
            .isEqualTo(FactoryQuoteStatus.SUPERSEDED); // not READY_FOR_COSTING — guard 2 cannot fire
        assertThat(factoryQuoteRepository.existsSubmittedCostingReferencingQuote(revision1.id()))
            .isFalse(); // the submitted costing references revision2, not revision1 — guard 3 cannot fire

        assertThatThrownBy(() -> factoryQuoteService.deleteAttachment(attachmentOnRevision1.id(), "cleanup", importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * MUTATION-CHECKABLE GUARD 2: the quote ITSELF has reached READY_FOR_COSTING. Isolated from
     * the other two guards: no costing has ever been created for this pricing request, so guard
     * 3 cannot fire, and the pricing request's own status stays within ATTACHMENT_DELETE_STATUSES
     * so guard 1 cannot fire either. V141 ("CEO owns costing") forces this test onto the
     * TWO-factory {@code pricingRequest()} fixture (not the single-factory one the other two
     * guard tests use): marking the ONLY factory on a single-factory request ready would
     * auto-advance the request straight to READY_FOR_CEO_REVIEW — which is NOT in
     * ATTACHMENT_DELETE_STATUSES, so guard 1 would fire too and the isolation this test exists
     * for would break. Leaving Factory B unanswered here keeps
     * LandedCostCalculator.isFullyResolvable false, so the auto-advance never fires and the
     * request stays at AWAITING_FACTORY_RESPONSE while Factory A alone reaches READY_FOR_COSTING.
     */
    @Test
    void factoryQuoteAttachmentDeletion_isRefusedOnceTheQuoteItselfIsReadyForCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("a1000000-0003-4003-8003-a10000000003"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            response("REF-A", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);
        FactoryQuoteAttachmentDto attachment = factoryQuoteService.uploadAttachment(responded.id(),
            new MockMultipartFile("file", "factory-a.pdf", "application/pdf", "quote".getBytes()), importActor);

        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isIn(PricingRequestStatus.AWAITING_FACTORY_RESPONSE, PricingRequestStatus.IMPORT_REVIEWING); // guard 1 cannot fire
        assertThat(factoryQuoteRepository.existsSubmittedCostingReferencingQuote(responded.id())).isFalse(); // guard 3 cannot fire

        assertThatThrownBy(() -> factoryQuoteService.deleteAttachment(attachment.id(), "cleanup", importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * MUTATION-CHECKABLE GUARD 3: this exact quote revision is referenced by a costing that has
     * already been SUBMITTED. Isolated from guard 2 by revising the quote AGAIN after the CEO
     * returns the request to Import (permitted — AWAITING_FACTORY_RESPONSE is back within
     * FactoryQuoteService.receive's RESPONSE_STATUSES): the old, costed revision becomes
     * SUPERSEDED (not READY_FOR_COSTING), so only guard 3 can be why its attachment stays
     * undeletable. Isolated from guard 1 the same way, for free: V141 ("CEO owns costing") means
     * {@code PricingDecisionService.returnToImport} — the real, now-only reopen path (Import's
     * old {@code createDraft} that this test used to reach via a raw-SQL shortcut is gone) —
     * lands exactly on AWAITING_FACTORY_RESPONSE, which is already inside
     * ATTACHMENT_DELETE_STATUSES. No SQL shortcut needed any more.
     */
    @Test
    void factoryQuoteAttachmentDeletion_isRefusedOnceReferencedByASubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0004-4004-8004-a10000000004"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        FactoryQuoteDto revision1 = factoryQuoteService.receive(draft.id(),
            response("REF-A-1", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);
        // Single-factory request: marking revision1 ready auto-advances straight to
        // READY_FOR_CEO_REVIEW (still inside MUTABLE_STATUSES, so uploading below is unaffected).
        factoryQuoteService.markReadyForCosting(revision1.id(), importActor);
        FactoryQuoteAttachmentDto attachmentOnRevision1 = factoryQuoteService.uploadAttachment(revision1.id(),
            new MockMultipartFile("file", "revision1.pdf", "application/pdf", "quote".getBytes()), importActor);

        // CEO opens review — computes + persists a SUBMITTED costing referencing revision1 in
        // the same action that creates the decision.
        PricingDecisionDto decision = pricingDecisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(null, "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(factoryQuoteRepository.existsSubmittedCostingReferencingQuote(revision1.id())).isTrue();

        // CEO returns the request — the ONE named reopen path now — sending it to
        // AWAITING_FACTORY_RESPONSE, which puts revision1 back within RESPONSE_STATUSES for a
        // new revision AND within ATTACHMENT_DELETE_STATUSES (isolating guard 1).
        pricingDecisionService.returnToImport(decision.id(),
            new th.co.glr.hr.pricingdecision.PricingDecisionRequests.ReturnPricingDecisionRequest("ราคาคลาดเคลื่อน"),
            ceoActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        // Revise AGAIN: revision1 (the already-costed one) becomes SUPERSEDED — not
        // READY_FOR_COSTING, so guard 2 cannot fire either. The SUBMITTED costing from startReview
        // above is untouched by both the return and this new revision — it still references
        // revision1's factory_quote_id, which is exactly what guard 3 checks.
        factoryQuoteService.receive(revision1.id(),
            response("REF-A-2", "THB", "90.00", revision1.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryQuoteService.get(revision1.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.SUPERSEDED);

        assertThat(factoryQuoteRepository.existsSubmittedCostingReferencingQuote(revision1.id())).isTrue();

        assertThatThrownBy(() -> factoryQuoteService.deleteAttachment(attachmentOnRevision1.id(), "cleanup", importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // factoryEmailDispatch_attachesPricingRequestAttachmentsMarkedIncludeInFactoryEmail was
    // removed: it pinned that Pricing Request attachments marked include-in-factory-email were
    // threaded onto the mail provider call FactoryQuoteService.attemptSend made. That whole path
    // (dispatch, FactoryEmailService, attachment-forwarding) was deleted when factory RFQ email
    // became manual-only — the human composing the email now attaches whatever they choose
    // themselves, in their own mail client, so there is nothing left in this service for that
    // behaviour to live in.

    @Test
    void unitConversionRejectsMissingBoxConversionBeforeCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("66666666-6666-4666-8666-666666666666"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        ReceiveFactoryQuoteRequest badBox = new ReceiveFactoryQuoteRequest("REF-BOX", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("1.00"),
                "PER_BOX", "PER_BOX", new BigDecimal("120.00"), "THB", null, null, null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());

        assertThatThrownBy(() -> factoryQuoteService.receive(draft.id(), badBox, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    // The seed bug (PricingRequestDetailPage.jsx's defaultResponseItems used ONE variable to seed
    // both quotedUnit and unitBasis) had a backend-side twin: validateAndNormalizeResponseItems
    // ran quotedUnit through the SAME UnitBasis.canonicalize as unitBasis, which forces its input
    // onto one of the four PER_ codes and 422s on anything else. That meant a real display unit
    // (ตร.ม., which is exactly what FactoryQuoteRepository.insertDraftItems seeds quoted_unit with
    // from the request's own requested_unit) could never actually be SAVED as quotedUnit — only a
    // basis code, or an English synonym that happens to canonicalize to one, could pass. Fixing the
    // frontend seed alone would have turned "silently stores the wrong thing" into "422s on save"
    // for the common case, not fixed it. This pins the fix: a real unit string round-trips as-is.
    @Test
    void receiveFactoryQuote_storesQuotedUnitAsTheRealDisplayTextNotForcedOntoABasisCode() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("d0000000-1111-4111-8111-d00000000001"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-UNIT-TEXT", "THB", "30 days",
            "45 days", "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("1.00"),
                "ตร.ม.", "PER_SQM", new BigDecimal("120.00"), "THB", null, new BigDecimal("0.36"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());

        FactoryQuoteDto received = factoryQuoteService.receive(draft.id(), response, importActor);

        assertThat(received.items().get(0).quotedUnit()).isEqualTo("ตร.ม.");
        assertThat(received.items().get(0).unitBasis()).isEqualTo("PER_SQM");
        // Round-trips through a fresh read too, not just the write-path return value.
        FactoryQuoteDto reloaded = factoryQuoteService.get(draft.id(), importActor);
        assertThat(reloaded.items().get(0).quotedUnit()).isEqualTo("ตร.ม.");
    }

    @Test
    void receiveFactoryQuote_stillRejectsABlankQuotedUnit() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("d0000000-1111-4111-8111-d00000000002"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        ReceiveFactoryQuoteRequest blankUnit = new ReceiveFactoryQuoteRequest("REF-BLANK-UNIT", "THB", "30 days",
            "45 days", "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("1.00"),
                "   ", "PER_PIECE", new BigDecimal("120.00"), "THB", null, null, null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());

        assertThatThrownBy(() -> factoryQuoteService.receive(draft.id(), blankUnit, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // submit()'s catalog handling, through the REAL PricingRequestService +
    // PricingRequestRepository + real Postgres — not Mockito. PricingRequestServiceTest
    // (Mockito-based) covers the branch being chosen with hand-built PricingRequestItemDto
    // stubs; it cannot prove PricingRequestRepository.snapshotCatalogSelections's actual SQL
    // (a join across price_catalog.product_prices / price_list_versions / factories) resolves
    // correctly — a mocked repository would pass happily even if that SQL joined on the wrong
    // column.
    //
    // The "Finding A" completeness gate that used to live here was REMOVED on 2026-08-11 (owner
    // request): a คำขอราคา may legitimately name a product that is not in the catalogue yet. What
    // these tests now pin is that the removal was a NARROWING, not a hole — a free-text line
    // submits, while a DANGLING catalog reference (a product_id whose price list is not ACTIVE)
    // is still rejected by findUnresolvableCatalogItemIds. That pair is the whole point: if
    // someone later deletes the surviving gate too, the second test goes red on its own.
    // Both still assert the persisted database row, not merely the thrown exception.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void submitCatalogGate_acceptsFreeTextItemAndAdvancesRequestToSubmitted() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "catalog gate test", UUID.randomUUID().toString(),
                List.of(freeTextPricingItem("Free-text tile, no catalog product"))),
            salesActor).summary().id();

        pricingRequestService.submit(pricingRequestId, salesActor);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.SUBMITTED);
        // The line reaches Import with a NULL catalog snapshot — stated explicitly, because it is
        // the deliberate cost of the removal: Import receives this item un-anchored to any price.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_item
             WHERE pricing_request_id = :id AND catalog_price_id IS NULL AND catalog_base_price IS NULL
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void submitCatalogGate_rejectsItemPointingAtNonActivePriceListVersion() {
        long archivedProductId = insertCatalogProduct("Factory Archived", "IT", "ARCHIVED-001",
            new BigDecimal("50.00"), "THB", "per_piece", "ARCHIVED");
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "catalog gate test", UUID.randomUUID().toString(),
                List.of(new PricingRequestRequests.PricingRequestItemRequest(
                    null, archivedProductId, null, "Brand", "Model", "Brand Model", null, null, "60x60",
                    "Factory Archived", new BigDecimal("1"), new BigDecimal("1"), "piece", UnitBasis.PER_PIECE,
                    QuantityType.CONFIRMED, null, null, null))),
            salesActor).summary().id();

        assertThatThrownBy(() -> pricingRequestService.submit(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.DRAFT);
    }

    @Test
    void submitCatalogGate_acceptsItemPointingAtActiveCatalogPriceAndPersistsAllSixSnapshotColumns() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "catalog gate test", UUID.randomUUID().toString(),
                List.of(pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10")))),
            salesActor).summary().id();

        pricingRequestService.submit(pricingRequestId, salesActor);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.SUBMITTED);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT catalog_price_id, price_list_version_id, catalog_base_price, catalog_currency,
                   resolved_factory_id, resolved_factory_name
              FROM sales.pricing_request_item
             WHERE pricing_request_id = :id
            """, Map.of("id", pricingRequestId));
        assertThat(row.get("catalog_price_id")).isEqualTo(catalogProductIdFactoryA);
        assertThat(row.get("price_list_version_id")).isNotNull();
        assertThat(((java.math.BigDecimal) row.get("catalog_base_price"))).isEqualByComparingTo("100.00");
        assertThat(row.get("catalog_currency")).isEqualTo("THB");
        assertThat(row.get("resolved_factory_id")).isNotNull();
        assertThat(row.get("resolved_factory_name")).isEqualTo("Factory A");
    }

    // Was submitCatalogGate_reportsEveryFailingLineNumberAcrossMultipleItems, which asserted
    // "รายการที่ 2, 3" and a blocked submit. A mixed request — one catalog-backed line plus two
    // free-text ones — is now valid, and the catalog-backed line must STILL get its snapshot:
    // removing the gate must not have disturbed snapshotCatalogSelections itself.
    @Test
    void submitCatalogGate_acceptsAMixOfCatalogBackedAndFreeTextItemsAndStillSnapshotsTheCatalogLine() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "catalog gate test", UUID.randomUUID().toString(),
                List.of(
                    pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10")),
                    freeTextPricingItem("Free-text line 2"),
                    freeTextPricingItem("Free-text line 3"))),
            salesActor).summary().id();

        pricingRequestService.submit(pricingRequestId, salesActor);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_item
             WHERE pricing_request_id = :id AND catalog_price_id IS NOT NULL
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Import fills in a blank factory (PricingRequestService#setItemFactory).
    //
    // The hole these close: since 2026-08-11 a line may be SUBMITTED with no factory (see
    // submitCatalogGate_acceptsFreeTextItem... above), and the factory check was deferred to
    // FactoryQuoteService#groupByFactory. But nothing could then SUPPLY the value — updateDraft is
    // DRAFT-only and sales-owner-only, the request is past DRAFT by then, and V140 deleted the
    // MORE_INFO_REQUIRED send-back — so สร้างร่างอีเมล 422'd forever and the only escape was
    // cancelling the request. setItemFactory is that missing step, and it is an AUTHORIZATION
    // change (a new write, Import-only), so per CLAUDE.md it is pinned here: through the real
    // service AND the real repository, against real Postgres, with the refusals asserted
    // wrong-way-round and every assertion made against the PERSISTED ROW, never the thrown
    // exception alone.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void setItemFactory_importFillsTheBlankAndTheFactoryEmailStepThenSucceeds() {
        long pricingRequestId = pricingRequestWithOneBlankFactoryLine();
        long blankItemId = blankFactoryItemId(pricingRequestId);

        // Before: partial draft generation (owner decision, manual-RFQ redesign) — line 1
        // (Factory A) resolves and gets its draft; the still-blank row 2 is skipped rather than
        // blocking the whole batch, so this no longer throws at all.
        List<FactoryQuoteDto> beforeDrafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(beforeDrafts).extracting(FactoryQuoteDto::factoryName).containsExactly("Factory A");

        pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest("  Factory B  "), importActor);

        // The persisted row, trimmed — not merely the DTO the call returned.
        assertThat(jdbc.queryForObject(
            "SELECT factory FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), String.class)).isEqualTo("Factory B");
        // resolved_factory_name is the CATALOG snapshot and must stay untouched: a hand-typed name
        // is not a catalog resolution.
        assertThat(jdbc.queryForObject(
            "SELECT resolved_factory_name FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), String.class)).isNull();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_REQUEST_ITEM_FACTORY_SET'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);

        // After: the step that was blocked now runs, and routes the line to the named factory.
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(drafts).extracting(FactoryQuoteDto::factoryName)
            .containsExactlyInAnyOrder("Factory A", "Factory B");
    }

    @Test
    void setItemFactory_isRefusedForTheSalesRepWhoOwnsTheDeal_andForTheCeo() {
        long pricingRequestId = pricingRequestWithOneBlankFactoryLine();
        long blankItemId = blankFactoryItemId(pricingRequestId);

        // Wrong-way-round: the two roles that can otherwise READ this request in full — its own
        // sales rep, and the CEO — must not be able to write this field. Import owns the routing
        // decision because Import is who emails the factory.
        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory B"), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory B"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory B"), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject(
            "SELECT factory FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), String.class)).isNull();
    }

    @Test
    void setItemFactory_refusesToReRouteALineThatAlreadyNamesAFactory() {
        long pricingRequestId = pricingRequestWithOneBlankFactoryLine();
        long catalogBackedItemId = jdbc.queryForObject("""
            SELECT pricing_request_item_id FROM sales.pricing_request_item
             WHERE pricing_request_id = :id AND resolved_factory_name = 'Factory A'
            """, Map.of("id", pricingRequestId), Long.class);

        // A fill, never a re-route: sales.factory_quote rows are grouped by factory NAME, so
        // moving a line after its quote exists would strand that quote's item list.
        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, catalogBackedItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory B"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(jdbc.queryForObject(
            "SELECT resolved_factory_name FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", catalogBackedItemId), String.class)).isEqualTo("Factory A");
    }

    @Test
    void setItemFactory_refusesBeforeImportHasPickedTheRequestUp() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, blankFactoryRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        long blankItemId = blankFactoryItemId(pricingRequestId);

        // SUBMITTED, not yet picked up — outside FACTORY_ROUTING_STATUSES, so no Import user may
        // start editing a request nobody has taken responsibility for.
        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory B"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(jdbc.queryForObject(
            "SELECT factory FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), String.class)).isNull();
    }

    @Test
    void setItemFactory_cannotReachAnItemBelongingToAnotherPricingRequest() {
        long targetRequestId = pricingRequestWithOneBlankFactoryLine();
        long victimRequestId = pricingRequestWithOneBlankFactoryLine();
        long victimItemId = blankFactoryItemId(victimRequestId);

        assertThatThrownBy(() -> pricingRequestService.setItemFactory(targetRequestId, victimItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory B"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        // And the same scoping again at the REPOSITORY level, bypassing the service's own check —
        // this is the half a mocked repository could never prove: that pricing_request_id really
        // reaches the WHERE clause and no row is written.
        assertThat(pricingRequests.fillItemFactory(targetRequestId, victimItemId, "Factory B")).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT factory FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", victimItemId), String.class)).isNull();
    }

    /**
     * BLOCKER 1b (review remediation): the companion guard to {@code
     * FactoryQuoteService#generateDrafts}'s own BLOCKER 1a fix. Once Factory A's RFQ has actually
     * been sent, its quote's item set is exactly what went out and must not silently change under
     * Import — so filling the still-blank line onto Factory A must now be refused HERE, at the
     * moment of the decision, instead of silently succeeding and permanently stranding the line
     * (the pre-fix sequence: generateDrafts would skip Factory A forever because it "already has a
     * quote", setItemFactory would 409 on any attempt to move the line elsewhere because it
     * "already has a factory", and receive's item-set check meant the sent quote could never grow
     * to cover it either).
     */
    @Test
    void setItemFactory_refusesWhenTheTargetFactorysQuoteHasAlreadyAdvancedPastDraft() {
        long pricingRequestId = pricingRequestWithOneBlankFactoryLine();
        long blankItemId = blankFactoryItemId(pricingRequestId);
        FactoryQuoteDto factoryADraft = quoteFor(
            factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        factoryQuoteService.send(factoryADraft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", null, null), importActor);

        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory A"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject(
            "SELECT factory FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), String.class)).isNull();

        // The guard is per-factory, not a blanket refusal the moment ANY quote on the request has
        // been sent: a factory with no current quote at all must still be fillable.
        pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory C"), importActor);
        assertThat(jdbc.queryForObject(
            "SELECT factory FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), String.class)).isEqualTo("Factory C");
    }

    /**
     * Partial draft generation (owner decision, manual-RFQ redesign): a request with SOME lines
     * unresolved no longer blocks the WHOLE batch on a 422 — {@link
     * FactoryQuoteService#groupByFactory} now simply skips a blank line, and {@link
     * FactoryQuoteService#generateDrafts} throws only when NOTHING resolves at all (see the
     * sibling test below). Here line 1 (Factory A) resolves; lines 2 and 3 do not — exactly one
     * draft is created, for Factory A alone, and it contains only the resolved item.
     */
    @Test
    void generateDrafts_someLinesUnresolved_stillCreatesDraftsForTheFactoriesThatDidResolve() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "partial factory test", UUID.randomUUID().toString(),
                List.of(
                    pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10")),
                    factorylessPricingItem("Blank line two"),
                    factorylessPricingItem("Blank line three"))),
            salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);

        assertThat(drafts).extracting(FactoryQuoteDto::factoryName).containsExactly("Factory A");
        assertThat(drafts.get(0).items()).hasSize(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.factory_quote WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
    }

    /**
     * The one case that must still 422: NOTHING resolves, so generating zero drafts while
     * returning 200 would be a silent no-op — worse than refusing outright. Keeps the informative
     * message the batch-blocking behaviour used to always produce: EVERY offending line in ONE
     * message, each by the row position the detail page renders and the product name that page
     * shows — not the first line only, and not a primary key that appears nowhere on screen.
     */
    @Test
    void generateDrafts_everyLineUnresolved_throws422NamingEachBlankLineByItsRowPosition() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "all blank factory test", UUID.randomUUID().toString(),
                List.of(
                    factorylessPricingItem("Blank line one"),
                    factorylessPricingItem("Blank line two"))),
            salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        assertThatThrownBy(() -> factoryQuoteService.generateDrafts(pricingRequestId, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage())
                    .contains("รายการที่ 1 (Blank line one)")
                    .contains("รายการที่ 2 (Blank line two)");
            });
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.factory_quote WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), Long.class)).isZero();
    }

    /**
     * BLOCKER 1a (review remediation). Sequence pulled straight from the defect report: line 1
     * resolves to Factory A immediately; line 2 starts blank, so the first {@code generateDrafts}
     * skips it and drafts only Factory A. Import then fills line 2's factory in via {@code
     * setItemFactory} — onto the SAME factory that already has a DRAFT quote, which is allowed
     * because nothing has been sent yet. Re-running {@code generateDrafts} used to skip Factory A
     * outright (it "already has a quote") and silently strand line 2 forever. Now it must ADD line
     * 2 to the SAME existing draft — no second quote for Factory A, no lost line — and the result
     * must be genuinely usable: receiving a response covering BOTH items must succeed, proving the
     * previously-stranded line is actually reachable through the ordinary factory-quote lifecycle,
     * not merely present in a list.
     */
    @Test
    void generateDrafts_reRunAfterFillingABlankLineOntoAFactoryWithAnExistingDraft_addsToItInsteadOfStranding() {
        long pricingRequestId = pricingRequestWithOneBlankFactoryLine();
        long blankItemId = blankFactoryItemId(pricingRequestId);

        FactoryQuoteDto firstDraft = quoteFor(
            factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        assertThat(firstDraft.status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        assertThat(firstDraft.items()).hasSize(1);
        long firstItemId = firstDraft.items().get(0).pricingRequestItemId();

        pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest("Factory A"), importActor);

        List<FactoryQuoteDto> secondRun = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(secondRun).extracting(FactoryQuoteDto::factoryName).containsExactly("Factory A");
        FactoryQuoteDto updated = secondRun.get(0);
        assertThat(updated.id()).as("must be the SAME draft, not a second quote for Factory A")
            .isEqualTo(firstDraft.id());
        assertThat(updated.status()).isEqualTo(FactoryQuoteStatus.DRAFT);
        assertThat(updated.items()).extracting(FactoryQuoteItemDto::pricingRequestItemId)
            .containsExactlyInAnyOrder(firstItemId, blankItemId);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_quote
             WHERE pricing_request_id = :id AND factory_name_snapshot = 'Factory A'
            """, Map.of("id", pricingRequestId), Long.class))
            .as("exactly one factory_quote row for Factory A — the add must not create a second")
            .isEqualTo(1L);
        // The add is its own audited event, distinct from the original draft-creation event.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_READY'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(2L);

        // Proves the previously-stranded line is genuinely reachable, not merely listed: a
        // response covering BOTH items is accepted, and the request can proceed.
        FactoryQuoteDto received = factoryQuoteService.receive(updated.id(), new ReceiveFactoryQuoteRequest(
                "REF-A", "THB", "30 days", "45 days", "revision", "note",
                List.of(
                    new ReceiveFactoryQuoteItemRequest(firstItemId, null, null, new BigDecimal("1.00"),
                        "piece", "piece", new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"),
                        null, null, "45 days", null, null),
                    new ReceiveFactoryQuoteItemRequest(blankItemId, null, null, new BigDecimal("1.00"),
                        "piece", "piece", new BigDecimal("120.00"), "THB", null, new BigDecimal("1.00"),
                        null, null, "45 days", null, null)),
                UUID.randomUUID().toString()),
            importActor);
        assertThat(received.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(received.items()).hasSize(2);
    }

    /**
     * A re-run must still leave a factory's quote ALONE once it has advanced past DRAFT — this is
     * the other half of the same invariant {@link
     * #setItemFactory_refusesWhenTheTargetFactorysQuoteHasAlreadyAdvancedPastDraft} pins from the
     * write side. {@code setItemFactory} normally prevents this situation from arising at all, so
     * this test reaches the item straight through the repository (bypassing that guard) to prove
     * {@code generateDrafts} itself is defensive here too, and does not mutate a REQUESTED quote's
     * item set even if a line somehow ends up routed to it.
     */
    @Test
    void generateDrafts_reRunNeverTouchesAFactoryQuoteThatHasAlreadyBeenSent() {
        long pricingRequestId = pricingRequestWithOneBlankFactoryLine();
        long blankItemId = blankFactoryItemId(pricingRequestId);
        FactoryQuoteDto factoryADraft = quoteFor(
            factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        factoryQuoteService.send(factoryADraft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", null, null), importActor);

        // Bypasses the service-level setItemFactory guard on purpose (see this test's Javadoc).
        assertThat(pricingRequests.fillItemFactory(pricingRequestId, blankItemId, "Factory A")).isEqualTo(1);

        FactoryQuoteDto afterRerun = quoteFor(
            factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");
        assertThat(afterRerun.id()).isEqualTo(factoryADraft.id());
        assertThat(afterRerun.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(afterRerun.items()).hasSize(1);
        assertThat(afterRerun.items().get(0).pricingRequestItemId())
            .isEqualTo(factoryADraft.items().get(0).pricingRequestItemId());
    }

    /**
     * HIGH 4 (review remediation): {@code include_in_factory_email} had no caller since {@code
     * attemptSend} (the old dispatch worker) was deleted — Import could still tick "include this
     * attachment in the factory email" and nothing would ever attach it. The fix appends a short
     * plain list of the marked attachments' file names to the generated draft body.
     */
    @Test
    void generateDrafts_listsAttachmentsMarkedIncludeInFactoryEmailInTheDraftBody() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest(UUID.randomUUID().toString()), salesActor).summary().id();
        var uploaded = pricingRequestService.uploadAttachment(pricingRequestId,
            new MockMultipartFile("file", "spec-sheet.pdf", "application/pdf", "spec".getBytes()), salesActor);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        pricingRequestService.setAttachmentIncludeInFactoryEmail(uploaded.id(),
            new PricingRequestRequests.UpdatePricingRequestAttachmentRequest(true), importActor);

        FactoryQuoteDto draft = quoteFor(
            factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        assertThat(draft.emailBody()).contains("spec-sheet.pdf");
    }

    /** The companion case: an attachment exists but was never marked, so the generated draft must
     * carry no attachment section referencing it at all — the feature must stay invisible until
     * Import deliberately opts an attachment in. */
    @Test
    void generateDrafts_omitsTheAttachmentSectionEntirelyWhenNothingIsMarkedForInclusion() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest(UUID.randomUUID().toString()), salesActor).summary().id();
        pricingRequestService.uploadAttachment(pricingRequestId,
            new MockMultipartFile("file", "unrelated.pdf", "application/pdf", "spec".getBytes()), salesActor);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        FactoryQuoteDto draft = quoteFor(
            factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        assertThat(draft.emailBody()).doesNotContain("unrelated.pdf");
    }

    /** A blank recipient must be allowed — factory RFQ email is manual-only, and the human sending
     * it from their own mail client may have no on-file factory contact to prefill. "Free Text
     * Factory" (unlike "Factory A"/"B"/"C") is never seeded into price_catalog.factories by this
     * class's fixture, so factoryConfigs.findByName resolves to nothing and emailTo really is
     * null at draft time — not merely unasserted. */
    @Test
    void send_withANullRecipient_succeedsAndRequestsTheQuote() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            new PricingRequestRequests.CreatePricingRequestRequest(
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", "no factory contact on file", UUID.randomUUID().toString(),
                List.of(freeTextPricingItem("Unlisted factory item"))),
            salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(
            factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Free Text Factory");
        assertThat(draft.emailTo()).isNull();

        FactoryQuoteDto sent = factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest(null, "Subject only", "Body only"), importActor);

        assertThat(sent.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        assertThat(sent.emailTo()).isNull();
        assertThat(sent.emailSubject()).isEqualTo("Subject only");
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
    }

    /**
     * Manual-only send's idempotency mechanism: once REQUESTED, calling send() again is a no-op
     * that returns the quote unchanged, rather than the old clientRequestId-keyed dispatch guard
     * (there is no out-of-band worker left to protect against replaying ahead of).
     */
    @Test
    void send_calledAgainOnceAlreadyRequested_isANoOpAndDoesNotDuplicateTheAuditTrail() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest(UUID.randomUUID().toString()), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        FactoryQuoteDto first = factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-a@example.com", "Subject", "Body"), importActor);
        FactoryQuoteDto second = factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("a-different-address@example.com", "Different subject", "Different body"),
            importActor);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(FactoryQuoteStatus.REQUESTED);
        // The second call's (different) payload must NOT have overwritten the first send's values —
        // it short-circuited on current status before touching anything.
        assertThat(second.emailTo()).isEqualTo("factory-a@example.com");
        assertThat(second.emailSubject()).isEqualTo("Subject");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class))
            .as("re-sending an already-REQUESTED quote must not duplicate the audit event")
            .isEqualTo(1L);
    }

    /** A deal-active, Import-owned request whose line 1 is catalog-backed and line 2 has no factory at all. */
    private long pricingRequestWithOneBlankFactoryLine() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, blankFactoryRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        return pricingRequestId;
    }

    private PricingRequestRequests.CreatePricingRequestRequest blankFactoryRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "blank factory test", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10")),
                factorylessPricingItem("Blank factory line")));
    }

    private long blankFactoryItemId(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT pricing_request_item_id FROM sales.pricing_request_item
             WHERE pricing_request_id = :id
               AND COALESCE(NULLIF(BTRIM(resolved_factory_name), ''), NULLIF(BTRIM(factory), '')) IS NULL
            """, Map.of("id", pricingRequestId), Long.class);
    }

    /**
     * Unlike {@link #freeTextPricingItem}, which names "Free Text Factory", this line has NO
     * factory on EITHER side — no catalog product to snapshot one from, and no free text. That is
     * the shape the whole section above is about, and nothing else in this file produced it.
     */
    private PricingRequestRequests.PricingRequestItemRequest factorylessPricingItem(String description) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, null, null, null, null, description,
            null, null, "60x60", null, new BigDecimal("1"), new BigDecimal("1"), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
    }

    private PricingRequestRequests.PricingRequestItemRequest freeTextPricingItem(String description) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, null, null, null, null, description,
            null, null, "60x60", "Free Text Factory", new BigDecimal("1"), new BigDecimal("1"), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Finding B (financial-integrity review, commit 3): unit-normalization test matrix.
    //
    // All five "should succeed" cases below are built so the SAME physical quantity (200
    // pieces) at the SAME per-piece price (50 THB/piece) is expressed through five different
    // quote-basis / request-basis combinations. Every one of them must land on the exact same
    // total: 10,000.0000 THB — that identity IS the correctness property being tested: the
    // total must depend only on the physical quantity and price, never on which unit either
    // side happened to be expressed in. This is also the review's own worked example (case 1
    // below): factory quotes 1,000 THB/box, 20 pieces/box, customer wants 10 boxes -> the
    // pre-fix code computed 1000/20*10 = 500 (treating "10" as if it were already a piece
    // count); the fix computes 1000/20*(10*20) = 10,000.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void unitConversion_quotePerBoxRequestInBoxes_worksedExampleGoodsComponentIs10000NotThe500ThePreFixCodeProduced() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00",
            new BigDecimal("0.5"), new BigDecimal("20"), null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200.000000");
        // V152 (V109 engine wiring): 200 pieces / 100 sqm total at Italy [8,12)mm — hand-verified
        // (see PricingFormulaEngineTest and LandedCostCalculatorFormulaIntegrationTest for the
        // full step-by-step derivation): C=10000, i=55.3725, F=50000 (qty band [1,101)),
        // cif=60055.3725, duty(TILE 30%)=18016.6118, (cif+duty)*1.07=83537.0232,
        // +clearance(qty band [1,101))=8000 => TC=91537.0232, rounding through the per-piece
        // column back to 91537.0200 total. goodsCostThb (per piece, "50.0000" above) is
        // UNCHANGED by the V109 buffers — it never was affected by freight/insurance/duty/
        // clearance, only totalLandedCostThb (which now includes all of them) moved.
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("91537.0200");
    }

    @Test
    void unitConversion_quotePerBoxRequestInPieces() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("200"), UnitBasis.PER_PIECE,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00",
            new BigDecimal("0.5"), new BigDecimal("20"), null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200");
        // V152 (V109 engine wiring): 200 pieces / 100 sqm total at Italy [8,12)mm — hand-verified
        // (see PricingFormulaEngineTest and LandedCostCalculatorFormulaIntegrationTest for the
        // full step-by-step derivation): C=10000, i=55.3725, F=50000 (qty band [1,101)),
        // cif=60055.3725, duty(TILE 30%)=18016.6118, (cif+duty)*1.07=83537.0232,
        // +clearance(qty band [1,101))=8000 => TC=91537.0232, rounding through the per-piece
        // column back to 91537.0200 total. goodsCostThb (per piece, "50.0000" above) is
        // UNCHANGED by the V109 buffers — it never was affected by freight/insurance/duty/
        // clearance, only totalLandedCostThb (which now includes all of them) moved.
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("91537.0200");
    }

    @Test
    void unitConversion_quotePerPieceRequestInBoxes() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_PIECE, new BigDecimal("200.00"), "50.00",
            new BigDecimal("0.5"), new BigDecimal("20"), null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200.000000");
        // V152 (V109 engine wiring): 200 pieces / 100 sqm total at Italy [8,12)mm — hand-verified
        // (see PricingFormulaEngineTest and LandedCostCalculatorFormulaIntegrationTest for the
        // full step-by-step derivation): C=10000, i=55.3725, F=50000 (qty band [1,101)),
        // cif=60055.3725, duty(TILE 30%)=18016.6118, (cif+duty)*1.07=83537.0232,
        // +clearance(qty band [1,101))=8000 => TC=91537.0232, rounding through the per-piece
        // column back to 91537.0200 total. goodsCostThb (per piece, "50.0000" above) is
        // UNCHANGED by the V109 buffers — it never was affected by freight/insurance/duty/
        // clearance, only totalLandedCostThb (which now includes all of them) moved.
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("91537.0200");
    }

    @Test
    void unitConversion_quotePerSqmRequestInPieces() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("200"), UnitBasis.PER_PIECE,
            UnitBasis.PER_SQM, new BigDecimal("100.00"), "100.00",
            new BigDecimal("0.5"), null, null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200");
        // V152 (V109 engine wiring): 200 pieces / 100 sqm total at Italy [8,12)mm — hand-verified
        // (see PricingFormulaEngineTest and LandedCostCalculatorFormulaIntegrationTest for the
        // full step-by-step derivation): C=10000, i=55.3725, F=50000 (qty band [1,101)),
        // cif=60055.3725, duty(TILE 30%)=18016.6118, (cif+duty)*1.07=83537.0232,
        // +clearance(qty band [1,101))=8000 => TC=91537.0232, rounding through the per-piece
        // column back to 91537.0200 total. goodsCostThb (per piece, "50.0000" above) is
        // UNCHANGED by the V109 buffers — it never was affected by freight/insurance/duty/
        // clearance, only totalLandedCostThb (which now includes all of them) moved.
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("91537.0200");
    }

    @Test
    void unitConversion_quotePerPieceRequestInSqm() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("100"), UnitBasis.PER_SQM,
            UnitBasis.PER_PIECE, new BigDecimal("200.00"), "50.00",
            new BigDecimal("0.5"), null, null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200.000000");
        // V152 (V109 engine wiring): 200 pieces / 100 sqm total at Italy [8,12)mm — hand-verified
        // (see PricingFormulaEngineTest and LandedCostCalculatorFormulaIntegrationTest for the
        // full step-by-step derivation): C=10000, i=55.3725, F=50000 (qty band [1,101)),
        // cif=60055.3725, duty(TILE 30%)=18016.6118, (cif+duty)*1.07=83537.0232,
        // +clearance(qty band [1,101))=8000 => TC=91537.0232, rounding through the per-piece
        // column back to 91537.0200 total. goodsCostThb (per piece, "50.0000" above) is
        // UNCHANGED by the V109 buffers — it never was affected by freight/insurance/duty/
        // clearance, only totalLandedCostThb (which now includes all of them) moved.
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("91537.0200");
    }

    // The gap the review specifically called out: FactoryQuoteService.receive()'s own
    // per-basis validation only requires piecesPerBox when the QUOTE's own unitBasis is
    // PER_BOX (see unitConversionRejectsMissingBoxConversionBeforeCosting above) — it has no
    // way to know the REQUEST is PER_BOX, since that lives on a different aggregate. A
    // PER_PIECE quote with no piecesPerBox therefore sails through receive() even when the
    // request itself is in boxes; PricingCostingService.calculate() must be the one place that
    // catches this, since it is the only place both bases are known at once.
    @Test
    void unitConversion_missingPiecesPerBoxForBoxRequestAgainstAPerPieceQuoteIs422() {
        assertThatThrownBy(() -> singleItemCosting(
            new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_PIECE, new BigDecimal("200.00"), "50.00",
            new BigDecimal("0.5"), null, null))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains("piecesPerBox");
            });
    }

    /**
     * Builds a fresh single-item pricing request against "Factory C" / TestLand's all-zero
     * price_calc_config (see the {@code catalogProductIdFactoryC} field javadoc), drives it
     * through submit -> pickup -> generate draft -> factory response -> mark ready -> CEO
     * startReview, and returns the resulting single costing line. V141 ("CEO owns costing"):
     * markReadyForCosting auto-advances this single-factory request straight to
     * READY_FOR_CEO_REVIEW, and the CEO's startReview computes the cost via the SAME
     * LandedCostCalculator.calculate() Import's deleted createDraft/recalculate used to trigger
     * — every arithmetic assertion in the tests driving this helper is unaffected by WHO
     * triggers the calculation, only the calculation itself matters.
     */
    private PricingCostingItemDto singleItemCosting(
        BigDecimal requestedQty, String requestedUnitBasis,
        String quotedUnitBasis, BigDecimal quotedQuantity, String rawPrice,
        BigDecimal sqmPerUnit, BigDecimal piecesPerBox, BigDecimal linearMPerUnit
    ) {
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdFactoryC, null, "TestBrand", "TestModel", "TestBrand TestModel",
            null, null, "1x1", "Factory C", requestedQty, requestedQty, "unit", requestedUnitBasis,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "unit conversion test", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory C");
        ReceiveFactoryQuoteRequest response = responseWithUnit("REF-UNIT", "THB", rawPrice,
            draft.items().get(0).pricingRequestItemId(), quotedUnitBasis, quotedQuantity,
            sqmPerUnit, piecesPerBox, linearMPerUnit);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        PricingDecisionDto decision = pricingDecisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(null, "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingCostingDto costing = costingService.get(decision.pricingCostingId(), ceoActor);
        return costing.items().get(0);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Regression: FactoryQuoteRepository.insertDraftItems used to seed a freshly drafted item's
    // unit_basis by text-matching the free-text requested_unit ('sqm'/'sq.m'/'m2'/'m²'/'ตร.ม.'
    // -> PER_SQM, everything else -> PER_PIECE) — a CASE with no branch for PER_BOX or
    // PER_LINEAR_M, so a request item asked for in boxes or linear metres seeded its draft as
    // PER_PIECE from the moment the draft was created. The fix copies
    // pricing_request_item.requested_unit_basis (V68's own canonical, NOT NULL + CHECK-
    // constrained column) straight across instead of re-deriving it. Both cases below assert
    // the draft state right after generateDrafts() and BEFORE any receive/response call —
    // replaceResponseItems deletes and re-inserts every item from Import's own form, so
    // asserting after a receive would prove nothing about the seed.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void generateDraftsSeedsUnitBasisFromRequestedUnitBasisForABoxRequest() {
        DraftAndRequestItem result = draftItemForRequestedBasis(UnitBasis.PER_BOX);

        assertThat(result.draftItem().unitBasis()).isEqualTo(result.requestItem().requestedUnitBasis());
        assertThat(result.draftItem().unitBasis()).isNotEqualTo(UnitBasis.PER_PIECE);
    }

    @Test
    void generateDraftsSeedsUnitBasisFromRequestedUnitBasisForALinearMetreRequest() {
        DraftAndRequestItem result = draftItemForRequestedBasis(UnitBasis.PER_LINEAR_M);

        assertThat(result.draftItem().unitBasis()).isEqualTo(result.requestItem().requestedUnitBasis());
        assertThat(result.draftItem().unitBasis()).isNotEqualTo(UnitBasis.PER_PIECE);
    }

    /**
     * Builds a fresh single-item pricing request against "Factory C" with the given
     * requestedUnitBasis, drives it through submit -> pickup -> generateDrafts — same pipeline as
     * {@link #singleItemCosting}, truncated right after generateDrafts and deliberately never
     * reaching receive() — and returns the resulting draft item alongside the persisted request
     * item it was seeded from.
     */
    private DraftAndRequestItem draftItemForRequestedBasis(String requestedUnitBasis) {
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdFactoryC, null, "TestBrand", "TestModel", "TestBrand TestModel",
            null, null, "1x1", "Factory C", new BigDecimal("10"), new BigDecimal("10"), "unit",
            requestedUnitBasis, QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "unit basis draft seed test", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory C");
        PricingRequestDtos.PricingRequestItemDto requestItem =
            pricingRequestService.get(pricingRequestId, importActor).items().get(0);
        return new DraftAndRequestItem(draft.items().get(0), requestItem);
    }

    private record DraftAndRequestItem(
        FactoryQuoteItemDto draftItem, PricingRequestDtos.PricingRequestItemDto requestItem) {}

    @Test
    void customerChangeRevisionSupersedesPriorRequestAndCreatesNewDraftRevision() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("88888888-8888-4888-8888-888888888888"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        PricingRequestRequests.CustomerChangeRevisionRequest revision =
            new PricingRequestRequests.CustomerChangeRevisionRequest(
                "Customer changed size", "12121212-1212-4212-8212-121212121212",
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(20),
                new BigDecimal("1200.00"), "THB", "revision request",
                List.of(pricingItem("SCG", "Tile A revised", "Factory A", new BigDecimal("12"))));
        PricingRequestDtos.PricingRequestDetailDto created =
            pricingRequestService.createCustomerChangeRevision(pricingRequestId, revision, salesActor);

        assertThat(created.summary().status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(created.summary().revisionNo()).isEqualTo(2);
        assertThat(created.summary().parentPricingRequestId()).isEqualTo(pricingRequestId);
        assertThat(pricingRequestService.get(pricingRequestId, ceoActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUPERSEDED);
    }

    // Review remediation (COMMIT 5, P2 finding 2): createCustomerChangeRevision computed the next
    // revision_no via a bare SELECT MAX(...)+1 with no lock, so two concurrent callers racing the
    // SAME parent (different clientRequestIds -- two genuinely distinct customer-change requests,
    // not a retry of the same one) could both read the same MAX before either INSERT committed.
    @Test
    void createCustomerChangeRevisionSerializesConcurrentCallersOnTheSameChainAndAvoidsDuplicateRevisionNumbers()
            throws Exception {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("aaaaaaaa-9999-4999-8999-aaaaaaaaaaaa"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);

        // This test harness (AbstractPostgresIntegrationTest) wires PricingRequestService with
        // `new`, so there is no Spring proxy and @Transactional is inert here -- without an
        // explicit transaction, each JDBC call from the repository would auto-commit individually
        // and the advisory lock (scoped to the current transaction via pg_advisory_XACT_lock)
        // would not span the whole createCustomerChangeRevision call the way it does in
        // production. Same pattern as
        // PricingFactoryQuoteCostingIntegrationTest's own
        // factoryQuoteReceiveConcurrentRevisionRetryWithSameClientRequestIdCreatesExactlyOneRevision:
        // wrap each racing call in an explicit transaction bound to the same DataSource `jdbc` uses.
        var txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);

        PricingRequestRequests.CustomerChangeRevisionRequest revisionA =
            new PricingRequestRequests.CustomerChangeRevisionRequest(
                "Customer changed size", "bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb",
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(20),
                new BigDecimal("1200.00"), "THB", "revision A",
                List.of(pricingItem("SCG", "Tile A revised", "Factory A", new BigDecimal("12"))));
        PricingRequestRequests.CustomerChangeRevisionRequest revisionB =
            new PricingRequestRequests.CustomerChangeRevisionRequest(
                "Customer changed quantity", "cccccccc-2222-4222-8222-cccccccccccc",
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(21),
                new BigDecimal("1500.00"), "THB", "revision B",
                List.of(pricingItem("SCG", "Tile A revised again", "Factory A", new BigDecimal("15"))));

        Callable<Long> taskA = () -> txTemplate.execute(status ->
            pricingRequestService.createCustomerChangeRevision(pricingRequestId, revisionA, salesActor)
                .summary().id());
        Callable<Long> taskB = () -> txTemplate.execute(status ->
            pricingRequestService.createCustomerChangeRevision(pricingRequestId, revisionB, salesActor)
                .summary().id());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Long> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<Long> futureA = executor.submit(taskA);
            Future<Long> futureB = executor.submit(taskB);
            for (Future<Long> future : List.of(futureA, futureB)) {
                try {
                    successes.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // Exactly one racer wins (creates the revision); the other is refused cleanly, not left
        // dangling. Its failure is the CLEAN outcome (PricingRequestRepository serialized the two
        // computations so no revision_no ever collided at INSERT time; the loser fails later, at
        // supersedeForCustomerRevision's own compare-and-set, once it sees the parent already
        // SUPERSEDED) -- proving the advisory lock actually serialized this, rather than the two
        // racers colliding on the DB unique index and one surfacing a raw, uncaught
        // DataIntegrityViolationException instead.
        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        List<Map<String, Object>> duplicateRevisions = jdbc.queryForList("""
            SELECT revision_no, COUNT(*) AS cnt
              FROM sales.pricing_request
             WHERE pricing_request_id = :root OR root_pricing_request_id = :root
             GROUP BY revision_no
            HAVING COUNT(*) > 1
            """, Map.of("root", pricingRequestId));
        assertThat(duplicateRevisions).isEmpty();

        Long chainRowCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request
             WHERE pricing_request_id = :root OR root_pricing_request_id = :root
            """, Map.of("root", pricingRequestId), Long.class);
        assertThat(chainRowCount).isEqualTo(2L); // root + exactly the one successful revision

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request
             WHERE root_pricing_request_id = :root AND revision_no = 2
            """, Map.of("root", pricingRequestId), Long.class)).isEqualTo(1L);
    }

    /** Simulates one outbox worker tick draining everything currently claimable. */
    private void drainDispatches() {
        // No-op now: FactoryQuoteService.send is synchronous (manual-RFQ redesign) --
        // there is no dispatch/worker queue left to drain. Kept (rather than removing
        // every call site) so this helper's callers do not all need to be revisited
        // individually.
    }

    private void markAllFactoriesReady(long pricingRequestId) {
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            FactoryQuoteDto response = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(response.id(), importActor);
        }
    }

    private FactoryQuoteDto quoteFor(List<FactoryQuoteDto> quotes, String factoryName) {
        return quotes.stream()
            .filter(quote -> factoryName.equals(quote.factoryName()))
            .findFirst()
            .orElseThrow();
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return response(ref, currency, price, pricingRequestItemId, UUID.randomUUID().toString());
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId,
                                                String clientRequestId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            clientRequestId);
    }

    /**
     * Finding B (financial-integrity review, commit 3) test helper: lets a test specify exactly
     * the QUOTE's unit basis, quoted quantity, and every conversion factor — for the
     * unit-normalization test matrix, where the quote's basis and the request's basis are
     * deliberately varied independently of each other.
     */
    private ReceiveFactoryQuoteRequest responseWithUnit(String ref, String currency, String price,
                                                        long pricingRequestItemId, String quotedUnitBasis,
                                                        BigDecimal quotedQuantity, BigDecimal sqmPerUnit,
                                                        BigDecimal piecesPerBox, BigDecimal linearMPerUnit) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quotedQuantity, quotedUnitBasis, quotedUnitBasis,
                new BigDecimal(price), currency, null, sqmPerUnit, piecesPerBox, linearMPerUnit,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    /**
     * Single-item, single-factory (Factory A only) variant of {@link #pricingRequest()} — used by
     * the attachment-immutability tests below, which need fine-grained control over exactly one
     * quote's revision history without a second factory's quote also needing to reach
     * READY_FOR_COSTING before a costing draft can be created.
     */
    private PricingRequestRequests.CreatePricingRequestRequest singleFactoryPricingRequest(String clientRequestId) {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "step 2 request", clientRequestId,
            List.of(pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10"))));
    }

    private PricingRequestRequests.CreatePricingRequestRequest pricingRequest() {
        return pricingRequest("77777777-7777-7777-7777-777777777777");
    }

    private PricingRequestRequests.CreatePricingRequestRequest pricingRequest(String clientRequestId) {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "step 2 request", clientRequestId,
            List.of(
                pricingItem("SCG", "Tile A", "Factory A", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B", "Factory B", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        // Financial-integrity review Finding A (commit 3): submit() now requires every item's
        // catalog snapshot to be fully resolved, so this must reference a real, ACTIVE catalog
        // product — one dedicated catalog product per factory, created in wireServicesAndCreateDeal.
        Long productId = "Factory A".equals(factory) ? catalogProductIdFactoryA
            : "Factory B".equals(factory) ? catalogProductIdFactoryB : null;
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, null, null, "60x60", factory, qty, qty, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /** The CEO fixture -- needs a real position, see CustomerQuotationIntegrationTest for why:
     *  CeoApproverRule keys the notified set on position กรรมการผู้จัดการ alone, and
     *  {@link #createEmployee} leaves positionTh null. Division stays MD, so the `ceo` role and
     *  every authz assertion here are unchanged. */
    private long createManagingDirector(EmployeeRepository employees, String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
