package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteAttachmentDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
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
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

class PricingFactoryQuoteCostingIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteRepository factoryQuoteRepository;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;
    // The CEO owns costing: every test that needs a costing drives it through
    // PricingDecisionService#startReview/recalculateCost (Import has no costing create/submit).
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
    // Financial-integrity review Finding A (commit 3): submit() now requires every item's catalog snapshot to be
    // fully resolved — see pricingItem() below.
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;
    // "Factory C" / "TestLand" carries an all-zero price_calc_config (freight/insurance/inland/ duty all 0), so
    // landedCostPerUnitThb == goodsCostThb exactly for every line costed against it — used by the
    // unit-normalization test matrix below so those assertions are not diluted by unrelated cost components.
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
        // V152 (V109 engine wiring): shared by FactoryQuoteService's markReadyForCosting auto-advance check and
        // PricingDecisionService's startReview/recalculateCost.
        landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications,
            new FileStorageService("/tmp/glr-pricing-test-uploads"), landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing create/recalculate/submit is
        // gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);
        pricingDecisionService = new PricingDecisionService(new PricingDecisionRepository(jdbc), pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

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
        // V152 (V109 engine wiring): factory country must be one of V109's seeded origin countries (Italy/Spain/China)
        // or LandedCostCalculator's freight lookup 422s ("ไม่พบ อัตราค่าขนส่ง") — 'Thailand'/'TestLand' (pre-V109) are
        // no longer costable.
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

        factoryQuoteService.markContacted(factoryA.id(),
            new MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), secondImportActor);
        factoryQuoteService.markContacted(factoryB.id(),
            new MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), secondImportActor);
        drainDispatches();
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.pricing_request_event
             WHERE pricing_request_id = :id
               AND event_kind = 'FACTORY_CONTACTED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(2L);

        FactoryQuoteDto factoryARevision1 = factoryQuoteService.receive(factoryA.id(),
            response("REF-A-1", "THB", "120.00", factoryA.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryARevision1.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(factoryQuoteService.list(pricingRequestId, importActor))
            .filteredOn(q -> "Factory A".equals(q.factoryName()))
            .hasSize(1);
        // Factory B has not answered yet: the request must still read as awaiting factory response, not
        // costing-in-progress, even though Factory A already responded.
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
        // V141 ("CEO owns costing"): Factory A alone being ready must not advance the request — Factory B has not
        // answered at all yet, so LandedCostCalculator.isFullyResolvable is false and markReadyForCosting's
        // auto-advance does not fire.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        FactoryQuoteDto factoryBResponse = factoryQuoteService.receive(factoryB.id(),
            response("REF-B-1", "THB", "200.00", factoryB.items().get(0).pricingRequestItemId()), importActor);
        factoryQuoteService.markReadyForCosting(factoryBResponse.id(), importActor);
        // The LAST factory quote becoming ready auto-advances the request straight to READY_FOR_CEO_REVIEW — there is
        // no Import-driven costing draft/recalculate/submit step any more (PricingCostingService is read-only; see its
        // own javadoc).
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // A mid-flight revision arriving while READY_FOR_CEO_REVIEW pulls the REQUEST back to
        // AWAITING_FACTORY_RESPONSE.
        FactoryQuoteDto factoryARevision3 = factoryQuoteService.receive(factoryARevision2.id(),
            response("REF-A-3", "THB", "100.00", factoryARevision2.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryARevision3.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(factoryARevision3.revisionNo()).isEqualTo(3);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        // The CEO cannot open a review whose price is about to change under them until Import re-marks this revision
        // ready.
        assertThatThrownBy(() -> pricingDecisionService.startReview(pricingRequestId,
                new StartPricingDecisionRequest(null, "THB", null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        factoryQuoteService.markReadyForCosting(factoryARevision3.id(), importActor);
        // Factory B's quote was untouched by A's revision and is still READY_FOR_COSTING, so re-marking A ready
        // re-advances the request straight back to READY_FOR_CEO_REVIEW.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // CEO opens review — computes + persists the costing itself, from whichever factory quote is CURRENT right now
        // (Factory A's rev 3 at 100.00, Factory B's rev 1 at 200.00).
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
    void bothFactoriesReadyThenAutoAdvanceToReadyForCeoReview() {
        // V141 ("CEO owns costing"): the LAST factory quote becoming ready auto-advances the request straight to
        // READY_FOR_CEO_REVIEW — there is no Import-driven "create a costing draft" step any more to separately
        // trigger the old COSTING_IN_PROGRESS transition.
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        markAllFactoriesReady(pricingRequestId);

        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // The CEO's bell row is the detailed notice, headline 1 (the importer who marked the last quote).
        PricingRequestSummaryDto summary = pricingRequestService.get(pricingRequestId, importActor).summary();
        String expected = CeoReviewNotice.message(
            "ฝ่ายนำเข้า (" + importActor.name() + ") ได้ราคาโรงงานครบทุกรายการแล้ว — รอ CEO ตั้งราคา",
            summary, pricingRequests.findItems(pricingRequestId), landedCostCalculator.calculate(summary));
        String message = jdbc.queryForObject(
            "SELECT message FROM hr.notification WHERE employee_id = :id AND type = 'PRICING_COSTING_SUBMITTED'"
                + " AND link = :link ORDER BY notification_id DESC LIMIT 1",
            Map.of("id", ceoUserId, "link", "/pricing-requests/" + pricingRequestId), String.class);
        assertThat(message).isEqualTo(expected).contains("ต้นทุนรวม ");
        String title = jdbc.queryForObject(
            "SELECT title FROM hr.notification WHERE employee_id = :id AND type = 'PRICING_COSTING_SUBMITTED'"
                + " AND link = :link ORDER BY notification_id DESC LIMIT 1",
            Map.of("id", ceoUserId, "link", "/pricing-requests/" + pricingRequestId), String.class);
        assertThat(title).isEqualTo("รอ CEO ตั้งราคา");
    }

    /** How long a racer waits at the rendezvous below for the other one to reach the same point. Paid ONCE, and
     * only on the correct (serialized) path — see the test's javadoc. */
    private static final long MARK_READY_RENDEZVOUS_MILLIS = 3_000;

    /** THE GAP THIS TEST CLOSES: FactoryQuoteService#markReadyForCosting takes a pg_advisory_xact_lock on the
     * pricing request and its own comment explains exactly what that lock is for. Two traps this test has to
     * dodge, or it would be theatre 1. */
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
        factoryQuoteService.markContacted(draftA.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto respondedA = factoryQuoteService.receive(draftA.id(),
            response("REF-RACE-A", "THB", "100.00", draftA.items().get(0).pricingRequestItemId()), importActor);
        factoryQuoteService.markContacted(draftB.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto respondedB = factoryQuoteService.receive(draftB.id(),
            response("REF-RACE-B", "THB", "200.00", draftB.items().get(0).pricingRequestItemId()), importActor);
        // Neither quote is ready yet, so from its own transaction's point of view EACH racer is marking "the last
        // outstanding factory" ready — the exact situation the lock exists for.
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
        // landedCostCalculator is deliberately the UNSPIED one: isFullyResolvable must read the database exactly as
        // production does, through this thread's own transaction.
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
        // V141 ("CEO owns costing"): markAllFactoriesReady auto-advances the request all the way to
        // READY_FOR_CEO_REVIEW (both quotes ready) — there is no standalone Import costing to build as scaffolding any
        // more.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        FactoryQuoteDto factoryA = quoteFor(factoryQuoteService.list(pricingRequestId, importActor), "Factory A");

        String replayClientRequestId = "cccccccc-3333-4333-8333-cccccccccccc";
        FactoryQuoteDto firstAttempt = factoryQuoteService.receive(factoryA.id(),
            response("REF-REVISED", "THB", "90.00", factoryA.items().get(0).pricingRequestItemId(), replayClientRequestId),
            importActor);
        assertThat(firstAttempt.status()).isEqualTo(FactoryQuoteStatus.RESPONSE_RECEIVED);
        assertThat(firstAttempt.revisionNo()).isEqualTo(2);
        // V141: a revision arriving while READY_FOR_CEO_REVIEW pulls the REQUEST back to AWAITING_FACTORY_RESPONSE —
        // the replacement for the old markOpenCostingsStale side effect (there is no costing row sitting around any
        // more to mark stale).
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        // Simulate the HTTP response for the call above being lost: Import retries with the exact same request against
        // the same (now-superseded) factoryQuoteId.
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
        // The replay must not have re-triggered the pull-back a second time: the request must still read exactly
        // AWAITING_FACTORY_RESPONSE, not have bounced status again.
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
    }

    // ── Factory quote attachment deletion hardening ──
    //
    // Deletion must not be permitted merely because the parent pricing request is in a broad
    // "mutable" set that includes READY_FOR_CEO_REVIEW (and it must not physically remove the row
    // and file). Each test below isolates exactly ONE of the independent
    // guards deleteAttachment applies, by constructing state where the OTHER two guards do
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
        // FactoryQuoteService#uploadAttachment) -- the old "file survives a tombstone" assertion checked disk
        // existence; the equivalent check is now that the blob row survives.
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

    /** MUTATION-CHECKABLE GUARD 1: FactoryQuoteService.ATTACHMENT_DELETE_STATUSES deliberately excludes
     * READY_FOR_CEO_REVIEW (unlike the broader upload-time MUTABLE_STATUSES). */
    @Test
    void factoryQuoteAttachmentDeletion_isRefusedOncePricingRequestReachesReadyForCeoReview() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            singleFactoryPricingRequest("a1000000-0002-4002-8002-a10000000002"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory A");

        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto revision1 = factoryQuoteService.receive(draft.id(),
            response("REF-A-1", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);
        FactoryQuoteAttachmentDto attachmentOnRevision1 = factoryQuoteService.uploadAttachment(revision1.id(),
            new MockMultipartFile("file", "revision1.pdf", "application/pdf", "quote".getBytes()), importActor);
        // Revise BEFORE marking ready: revision1 becomes SUPERSEDED, never READY_FOR_COSTING, never referenced by any
        // costing (only revision2 is).
        FactoryQuoteDto revision2 = factoryQuoteService.receive(revision1.id(),
            response("REF-A-2", "THB", "95.00", revision1.items().get(0).pricingRequestItemId()), importActor);
        assertThat(factoryQuoteService.get(revision1.id(), importActor).status()).isEqualTo(FactoryQuoteStatus.SUPERSEDED);
        // V141 ("CEO owns costing"): this is the ONLY factory quote on this (single-factory) pricing request, so
        // marking it ready auto-advances the request straight to READY_FOR_CEO_REVIEW — there is no separate Import
        // costing draft/submit step any more.
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

        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        assertThatThrownBy(() -> factoryQuoteService.receive(draft.id(), badBox, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Import fills in a blank factory (PricingRequestService#setItemFactory).
    //
    // The hole these close: a line may be SUBMITTED with no factory, and the factory check is deferred to
    // FactoryQuoteService#groupByFactory. Nothing else could then SUPPLY the value — updateDraft is
    // DRAFT-only and sales-owner-only, the request is past DRAFT by then, and there is no
    // MORE_INFO_REQUIRED send-back — so สร้างร่างอีเมล would 422 forever and the only escape would be
    // cancelling the request. setItemFactory is that step, and it is an AUTHORIZATION
    // write (Import-only), so per CLAUDE.md it is pinned here: through the real
    // service AND the real repository, against real Postgres, with the refusals asserted
    // wrong-way-round and every assertion made against the PERSISTED ROW, never the thrown
    // exception alone.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void setItemFactory_importFillsTheBlankAndTheFactoryEmailStepThenSucceeds() {
        long pricingRequestId = pricingRequestWithOneBlankFactoryLine();
        long blankItemId = blankFactoryItemId(pricingRequestId);

        // Before: partial draft generation — line 1
        // (Factory A) resolves and gets its draft; the still-blank row 2 is skipped rather than
        // blocking the whole batch, so this no longer throws at all.
        List<FactoryQuoteDto> beforeDrafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(beforeDrafts).extracting(FactoryQuoteDto::factoryName).containsExactly("Factory A");

        long factoryBId = factoryIdByName("Factory B");
        pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest(factoryBId), importActor);

        // The persisted row — the canonical master name, resolved server-side from factoryBId, not
        // anything the caller typed (the caller sends only an id).
        assertThat(jdbc.queryForObject(
            "SELECT factory FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), String.class)).isEqualTo("Factory B");
        // resolved_factory_id is written — the column the downstream factory-email
        // + CEO costing lookups actually key on.
        assertThat(jdbc.queryForObject(
            "SELECT resolved_factory_id FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), Long.class)).isEqualTo(factoryBId);
        // resolved_factory_name is the CATALOG snapshot and must stay untouched: a master-resolved manual pick is
        // still not a catalog PRODUCT resolution.
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

        long factoryBId = factoryIdByName("Factory B");
        // Wrong-way-round: the two roles that can otherwise READ this request in full — its own sales rep, and the CEO
        // — must not be able to write this field. Import owns the routing decision because Import is who emails the
        // factory.
        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest(factoryBId), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest(factoryBId), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> pricingRequestService.setItemFactory(pricingRequestId, blankItemId,
            new PricingRequestRequests.SetItemFactoryRequest(factoryBId), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(jdbc.queryForObject(
            "SELECT factory FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", blankItemId), String.class)).isNull();
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

    /** {@code SetItemFactoryRequest} carries a factoryId, resolved against the
     * real {@code price_catalog.factories} master row — "Factory A"/"B"/"C" are seeded by
     * {@code insertCatalogProduct} in {@code wireServicesAndCreateDeal} above. */
    private long factoryIdByName(String name) {
        return jdbc.queryForObject(
            "SELECT factory_id FROM price_catalog.factories WHERE name = :name",
            Map.of("name", name), Long.class);
    }

    /** Unlike #freeTextPricingItem, which names "Free Text Factory", this line has NO factory on EITHER side — no
     * catalog product to snapshot one from, and no free text. That is the shape the whole section above is about,
     * and nothing else in this file produced it. */
    // Model is unconditionally required, independent of whether the item
    // has a catalog link — "free text" here means no catalog PRODUCT, not no model. Both helpers
    // pass `description` itself as the model so they (a) reach resolveItems' happy path
    // and (b) print the SAME text callers assert on via
    // PricingRequestItemDto#displayName() (brand/model, falling back to productDescription only
    // when both are blank) — model is not blank here, so it is the source of that text, and
    // the two are deliberately kept equal. productId stays null on both — the "no catalog
    // product to snapshot" shape both are testing.
    private PricingRequestRequests.PricingRequestItemRequest factorylessPricingItem(String description) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, null, null, null, description,
            description, "White", "Matte", "60x60", null, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 1, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private PricingRequestRequests.PricingRequestItemRequest freeTextPricingItem(String description) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, null, null, null, description,
            description, "White", "Matte", "60x60", "Free Text Factory", null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 1, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    // Finding B (financial-integrity review, commit 3): unit-normalization test matrix. The cases below are built so the SAME physical quantity (200 pieces) at the SAME per-piece price (50 THB/piece)
    // is expressed through different quote-basis / request-basis combinations.

    @Test
    void unitConversion_quotePerBoxRequestInBoxes_worksedExampleGoodsComponentIs10000NotThe500ThePreFixCodeProduced() {
        PricingCostingItemDto line = singleItemCosting(
            new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00",
            new BigDecimal("0.5"), new BigDecimal("20"), null);

        assertThat(line.goodsCostThb()).isEqualByComparingTo("50.0000");
        assertThat(line.normalizedQuantityPieces()).isEqualByComparingTo("200.000000");
        // V152: 200 pieces / 100 sqm total at Italy [8,12)mm — hand-verified: C=10000, i=55.3725, F=50000),
        // cif=60055.3725, duty=18016.6118,*1.07=83537.0232, +clearance)=8000 => TC=91537.0232, rounding through the
        // per-piece column back to 91537.0200 total.
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
        // V152: 200 pieces / 100 sqm total at Italy [8,12)mm — hand-verified: C=10000, i=55.3725, F=50000),
        // cif=60055.3725, duty=18016.6118,*1.07=83537.0232, +clearance)=8000 => TC=91537.0232, rounding through the
        // per-piece column back to 91537.0200 total.
        assertThat(line.totalLandedCostThb()).isEqualByComparingTo("91537.0200");
    }

    /**
     * Builds a fresh single-item pricing request against "Factory C" / TestLand's all-zero
     * price_calc_config (see the {@code catalogProductIdFactoryC} field javadoc), drives it
     * through submit -> pickup -> generate draft -> factory response -> mark ready -> CEO
     * startReview, and returns the resulting single costing line. The CEO owns costing:
     * markReadyForCosting auto-advances this single-factory request straight to
     * READY_FOR_CEO_REVIEW, and the CEO's startReview computes the cost via
     * LandedCostCalculator.calculate() — every arithmetic assertion in the tests driving this
     * helper is unaffected by WHO triggers the calculation, only the calculation itself matters.
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
        // V185: bypasses PricingRequestService.createDraft on purpose -- that method now forces every item's
        // requestedUnitBasis to PER_PIECE, which would make it impossible to construct the
        // PER_SQM/PER_BOX/PER_LINEAR_M requestedUnitBasis this whole unit-conversion matrix exists to test.
        long pricingRequestId = pricingRequests.create(ticketId, pricingRequests.nextRequestCode(), request, salesRepId);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory C");
        ReceiveFactoryQuoteRequest response = responseWithUnit("REF-UNIT", "THB", rawPrice,
            draft.items().get(0).pricingRequestItemId(), quotedUnitBasis, quotedQuantity,
            sqmPerUnit, piecesPerBox, linearMPerUnit);
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        PricingDecisionDto decision = pricingDecisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(null, "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingCostingDto costing = costingService.get(decision.pricingCostingId(), ceoActor);
        return costing.items().get(0);
    }

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

    // createCustomerChangeRevision must lock when computing the next revision_no (SELECT
    // MAX(...)+1): two concurrent callers racing the
    // SAME parent (different clientRequestIds -- two genuinely distinct customer-change requests,
    // not a retry of the same one) could otherwise both read the same MAX before either INSERT committed.
    @Test
    void createCustomerChangeRevisionSerializesConcurrentCallersOnTheSameChainAndAvoidsDuplicateRevisionNumbers()
            throws Exception {
        long pricingRequestId = pricingRequestService.createDraft(ticketId,
            pricingRequest("aaaaaaaa-9999-4999-8999-aaaaaaaaaaaa"), salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);

        // This test harness wires PricingRequestService with `new`, so there is no Spring proxy and @Transactional is
        // inert here.
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

        // Exactly one racer wins (creates the revision); the other is refused cleanly, not left dangling.
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
        // No-op now: FactoryQuoteService.send is synchronous (manual-RFQ redesign) -- there is no dispatch/worker
        // queue left to drain. Kept (rather than removing every call site) so this helper's callers do not all need to
        // be revisited individually.
    }

    private void markAllFactoriesReady(long pricingRequestId) {
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
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

    /** Finding B test helper: lets a test specify exactly the QUOTE's unit basis, quoted quantity, and every
     * conversion factor — for the unit-normalization test matrix, where the quote's basis and the request's basis
     * are deliberately varied independently of each other. */
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

    /** Single-item, single-factory variant of #pricingRequest — used by the attachment-immutability tests below,
     * which need fine-grained control over exactly one quote's revision history without a second factory's quote
     * also needing to reach READY_FOR_COSTING before a costing draft can be created. */
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
        // Financial-integrity review Finding A (commit 3): submit() now requires every item's catalog snapshot to be
        // fully resolved, so this must reference a real, ACTIVE catalog product — one dedicated catalog product per
        // factory, created in wireServicesAndCreateDeal.
        Long productId = "Factory A".equals(factory) ? catalogProductIdFactoryA
            : "Factory B".equals(factory) ? catalogProductIdFactoryB : null;
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists (see PricingRequestService#resolveItems).
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, "White", "Matte", "60x60", factory, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qty.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
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

    /** The CEO fixture -- needs a real position, see CustomerQuotationIntegrationTest for why: CeoApproverRule keys
     * the notified set on position กรรมการผู้จัดการ alone, and #createEmployee leaves positionTh null. Division
     * stays MD, so the `ceo` role and every authz assertion here are unchanged. */
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
