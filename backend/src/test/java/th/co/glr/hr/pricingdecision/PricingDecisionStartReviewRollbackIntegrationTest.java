package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.config.AppProperties;
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
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
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
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Closes the same gap for {@link PricingDecisionService#startReview} that
 * {@code CommissionInvoiceSentinelRollbackIntegrationTest} closed for {@code CommissionService}:
 * every existing pricing-decision integration test hand-wires {@code new PricingDecisionService(...)},
 * which has no Spring proxy and therefore no transaction at all — those tests would stay green even
 * if {@code @Transactional} were deleted from {@code startReview}, while production silently started
 * committing a pricing request stuck at {@code CEO_REVIEWING} with no reviewable
 * {@code sales.pricing_decision} row behind it.
 *
 * <p>This goes one step further than the Commission precedent: instead of driving the service
 * through the test's own {@code TransactionTemplate} (which proves rollback but, by the Commission
 * test class's own admission, would NOT go red if {@code @Transactional} were deleted — see that
 * class's Javadoc), {@link AbstractPostgresIntegrationTest#transactional} (PR #695) builds a REAL
 * Spring AOP transactional proxy driven by {@link
 * org.springframework.transaction.annotation.AnnotationTransactionAttributeSource}, so the
 * transaction genuinely comes from the {@code @Transactional} annotation on {@code startReview}
 * itself. Deleting the annotation removes the advice's transaction attribute entirely, so the proxy
 * stops opening a transaction and the rollback test below must go red for the right reason — see the
 * mutation-check note in the PR body for the recorded before/after run.
 *
 * <p>The {@link PricingRequestRepository} Mockito spy used in {@link
 * #startReviewFailingAfterTheStatusTransition_rollsBackEveryWrite()} is a <b>fault injector</b>, not
 * the system under test: it delegates every method to a real {@link PricingRequestRepository} except
 * {@code addEvent}, which is armed to throw. Every write asserted against below is real SQL executed
 * against a real Postgres database — the spy only decides when the last write in the chain fails.
 */
class PricingDecisionStartReviewRollbackIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingCostingService costingService;
    private PricingDecisionRepository decisionRepository;
    private NotificationRepository notifications;
    private FxRateRepository fxRates;
    private LandedCostCalculator landedCostCalculator;
    private PricingFormulaEngine formulaEngine;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-decision-rollback-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        when(factoryEmail.send(anyLong(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        AppProperties dispatchProperties = new AppProperties();
        dispatchProperties.getFactoryQuoteDispatch().setReclaimTimeoutSeconds(2);
        dispatchProperties.getFactoryQuoteDispatch().setMaxAttempts(3);
        dispatchProperties.getFactoryQuoteDispatch().setBackoffBaseSeconds(1);
        dispatchProperties.getFactoryQuoteDispatch().setBatchSize(20);
        fxRates = new FxRateRepository(jdbc);
        formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        // V141 ("CEO owns costing"): shared by FactoryQuoteService's markReadyForCosting
        // auto-advance check and PricingDecisionService's startReview (see buildDecisionService).
        landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);
        costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing
        // create/recalculate/submit is gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);
        decisionRepository = new PricingDecisionRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย โรลแบ็ก", "sales-rollback@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า โรลแบ็ก", "import-rollback@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร โรลแบ็ก", "ceo-rollback@glr.co.th", "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES
                ('Factory A3-rollback', 'factory-a3-rollback@example.com', 'THB', 'piece', 'Italy'),
                ('Factory B3-rollback', 'factory-b3-rollback@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
        catalogProductIdFactoryA = insertCatalogProduct("Factory A3-rollback", "IT", "TEST-A3-RB-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B3-rollback", "IT", "TEST-B3-RB-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Rollback จำกัด", "0100000000004", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0004");
        ProjectDto project = projects.create(customer.id(), "โครงการ Rollback");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Rollback", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A3", "Factory A3-rollback"),
                    ticketItem("Cotto", "Tile B3", "Factory B3-rollback"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void startReviewFailingAfterTheStatusTransition_rollsBackEveryWrite() {
        long pricingRequestId = twoItemSubmittedCosting();

        // Fault injector: delegates everything to a real PricingRequestRepository except addEvent,
        // which is startReview's LAST write (see class Javadoc). By the time this throws,
        // lockPricingRequest, createDraft, insertItems, and transition have all really executed
        // against Postgres, so there is something real for the transaction to roll back.
        PricingRequestRepository faulty = org.mockito.Mockito.spy(new PricingRequestRepository(jdbc));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("injected failure"))
            .when(faulty).addEvent(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        PricingDecisionService faultyService = transactional(buildDecisionService(faulty));

        assertThatThrownBy(() -> faultyService.startReview(pricingRequestId,
                new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
                ceoActor))
            .isInstanceOf(DataIntegrityViolationException.class);

        // V141 ("CEO owns costing"): startReview now computes+persists the costing itself, BEFORE
        // the pricing_decision insert — steps 1-2 of 6, ahead of the pricing_decision/
        // pricing_decision_item/status/event writes the pre-V141 assertions below already covered.
        // twoItemSubmittedCosting() leaves ZERO costing rows behind (no standalone Import draft
        // exists any more), so a non-zero count here can only be an orphan from THIS failed call.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales.pricing_costing WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), Long.class))
            .as("startReview inserts sales.pricing_costing (step 1 of 6) BEFORE the injected "
                + "addEvent failure (step 6) — a non-zero count here means the @Transactional "
                + "boundary let an orphan costing row survive a later failure")
            .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_costing_item pci
                  JOIN sales.pricing_costing pc ON pc.pricing_costing_id = pci.pricing_costing_id
                 WHERE pc.pricing_request_id = :id
                """, Map.of("id", pricingRequestId), Long.class))
            .as("sales.pricing_costing_item rows (step 2) must not survive either — same "
                + "transaction boundary as the pricing_costing row above")
            .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales.pricing_decision WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), Long.class))
            .as("startReview inserts sales.pricing_decision (step 3 of 6) BEFORE the injected "
                + "addEvent failure (step 6) — a non-zero count here means the @Transactional "
                + "boundary let a partial CEO-review survive a later failure")
            .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_decision_item pdi
                  JOIN sales.pricing_decision pd ON pd.pricing_decision_id = pdi.pricing_decision_id
                 WHERE pd.pricing_request_id = :id
                """, Map.of("id", pricingRequestId), Long.class))
            .as("sales.pricing_decision_item rows (step 4) must not survive either — same "
                + "transaction boundary as the pricing_decision row above")
            .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), String.class))
            .as("the status UPDATE (step 5, READY_FOR_CEO_REVIEW -> CEO_REVIEWING) must roll back "
                + "too, or this pricing request would be stuck showing 'under CEO review' with no "
                + "reviewable pricing_decision row behind it")
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_request_event
                 WHERE pricing_request_id = :id AND event_kind = 'PRICING_DECISION_STARTED'
                """, Map.of("id", pricingRequestId), Long.class))
            .as("the event insert is exactly where the fault was injected (step 6) — it must not "
                + "have left a lone committed event row behind either")
            .isZero();
    }

    /** Positive control, through the IDENTICAL transactional proxy and no stub: proves the zero
     * counts above mean "rolled back", not "startReview is broken for every input". */
    @Test
    void successfulStartReviewCommits_throughTheSameProxy() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionService realService = transactional(buildDecisionService(pricingRequests));

        PricingDecisionDto decision = realService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);

        assertThat(decision.status()).isEqualTo(PricingDecisionStatus.DRAFT);
        // V141: a genuinely successful startReview, through the same proxy as the rollback test,
        // must commit its pricing_costing row(s) too — proves the zero counts above mean "rolled
        // back", not "startReview never writes a costing".
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales.pricing_costing WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), Long.class))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_costing_item pci
                  JOIN sales.pricing_costing pc ON pc.pricing_costing_id = pci.pricing_costing_id
                 WHERE pc.pricing_request_id = :id
                """, Map.of("id", pricingRequestId), Long.class))
            .as("both two-item pricing_costing_item rows must commit")
            .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales.pricing_decision WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), Long.class))
            .as("a genuinely successful startReview, through the same proxy as the rollback test, "
                + "must commit its pricing_decision row")
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_decision_item pdi
                  JOIN sales.pricing_decision pd ON pd.pricing_decision_id = pdi.pricing_decision_id
                 WHERE pd.pricing_request_id = :id
                """, Map.of("id", pricingRequestId), Long.class))
            .as("both two-item pricing_decision_item rows must commit")
            .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_request_event
                 WHERE pricing_request_id = :id AND event_kind = 'PRICING_DECISION_STARTED'
                """, Map.of("id", pricingRequestId), Long.class))
            .isEqualTo(1L);
    }

    /** Reflection guard mirroring {@code CommissionInvoiceSentinelRollbackIntegrationTest
     * #bothCreatePathsAreTransactional_soTheSentinelCanNeverCommit()}: pins the annotation the
     * rollback test above depends on. */
    @Test
    void startReviewIsTransactional_soAPartialCeoReviewCanNeverCommit() throws Exception {
        long startReviewMethods = java.util.Arrays.stream(PricingDecisionService.class.getMethods())
            .filter(method -> "startReview".equals(method.getName()))
            .count();
        assertThat(startReviewMethods)
            .as("guard against this test silently passing because startReview was renamed or overloaded")
            .isEqualTo(1L);

        Method startReview = PricingDecisionService.class.getMethod(
            "startReview", long.class, StartPricingDecisionRequest.class, UserPrincipal.class);
        assertThat(startReview.getAnnotation(Transactional.class))
            .as("PricingDecisionService#startReview must stay @Transactional — without it, a "
                + "failure between the pricing_decision/pricing_decision_item inserts and the "
                + "final pricing_request_event insert COMMITS a pricing_request stuck at "
                + "CEO_REVIEWING with no reviewable pricing_decision behind it (see the rollback "
                + "test above)")
            .isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private PricingDecisionService buildDecisionService(PricingRequestRepository pricingRequestsForDecision) {
        return new PricingDecisionService(decisionRepository, pricingRequestsForDecision, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
    }

    /** Two-item, two-factory scenario driven to READY_FOR_CEO_REVIEW, mirroring {@code
     * PricingDecisionIntegrationTest#twoItemSubmittedCosting()} — the precondition {@code
     * startReview} requires. V141 ("CEO owns costing"): unlike the pre-V141 name, this no longer
     * creates any {@code sales.pricing_costing} row itself — markReadyForCosting's auto-advance
     * (once the LAST factory quote resolves) is what gets the request to READY_FOR_CEO_REVIEW;
     * {@code startReview} is what computes and persists the costing, which is exactly the
     * transactional write this test class exists to prove rolls back atomically. */
    private long twoItemSubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, twoItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private PricingRequestRequests.CreatePricingRequestRequest twoItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "rollback test request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A3", "Factory A3-rollback", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B3", "Factory B3-rollback", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A3-rollback".equals(factory) ? catalogProductIdFactoryA
            : "Factory B3-rollback".equals(factory) ? catalogProductIdFactoryB : null;
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

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
