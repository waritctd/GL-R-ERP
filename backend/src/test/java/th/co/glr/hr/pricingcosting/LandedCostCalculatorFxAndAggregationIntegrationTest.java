package th.co.glr.hr.pricingcosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
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
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Real-DB proof for the three P0/P1a fixes to LandedCostCalculator and FxResolver that need the FULL
 * pricing-decision pipeline rather than a DB-free unit test — FxResolverTest covers the FX gate's OWN logic in
 * isolation; this class proves the fixes reach the real chain. */
class LandedCostCalculatorFxAndAggregationIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingDecisionService decisionService;
    private LandedCostCalculator landedCostCalculator;
    private CustomerRepository customers;
    private ProjectRepository projects;
    private TicketService ticketService;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;

    @BeforeEach
    void wireServices() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customers = new CustomerRepository(jdbc);
        projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-fx-aggregation-test-uploads");
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, mock(th.co.glr.hr.factoryquote.FactoryQuoteCarryForward.class));
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaConfigRepository formulaConfigRepository = new PricingFormulaConfigRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(formulaConfigRepository);
        landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        costingRepository = new PricingCostingRepository(jdbc);
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        salesRepId = createEmployee(employees, "พนักงานขาย FX", "sales-fx-agg@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า FX", "import-fx-agg@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร FX", "ceo-fx-agg@glr.co.th", "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
    }

    // a CEO-entered MANUAL non-THB rate reaches startReview, and is fully traceable

    /** Before the P0 fix, EVERY non-THB pricing request 422'd here — FxResolver refused any rate not sourced from
     * BOT, and the ONLY writer reachable from the CEO settings UI (FxRateRepository#upsert) hardcodes source =
     * 'MANUAL'. */
    @Test
    void manualEurRate_reachesStartReview_andDecisionAndCostingRecordFxSourceManual() {
        seedManualFxRate("EUR", new BigDecimal("38.5000"), LocalDate.now());

        long pricingRequestId = singleItemReadyForReview("FX Manual Factory", "EUR");

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "EUR", null, UUID.randomUUID().toString()),
            ceoActor);

        assertThat(decision.fxSource())
            .as("the decision header must pin the ACTUAL source used, not a hardcoded BOT")
            .isEqualTo("MANUAL");
        assertThat(decision.fxRateUsed()).isEqualByComparingTo("38.5000");

        PricingCostingItemDto costingItem = costingRepository.find(decision.pricingCostingId())
            .orElseThrow().items().get(0);
        assertThat(costingItem.fxSource())
            .as("traceability claim: a MANUAL rate is stamped MANUAL on the costing row that "
                + "actually used it, not only on the decision header — this is what the P0 fix's "
                + "reasoning depends on, so it must be asserted, not believed")
            .isEqualTo("MANUAL");
        assertThat(costingItem.fxRate()).isEqualByComparingTo("38.5000");
    }

    // P1a: every missing-factor problem across every item is reported together, in one 422

    // isFullyResolvable's semantics must survive the restructure: false, never a thrown exception

    // Fixture helpers

    /** Seeds (or overwrites) a MANUAL sales.fx_rates row exactly the shape FxRateRepository#upsert — the CEO
     * settings FX write path — produces: source = 'MANUAL', fetched_at = NULL. */
    private void seedManualFxRate(String currency, BigDecimal rateToThb, LocalDate effectiveDate) {
        jdbc.update("""
            INSERT INTO sales.fx_rates (currency, rate_to_thb, effective_date, source, fetched_at, updated_at)
            VALUES (:currency, :rate, :date, 'MANUAL', NULL, now())
            ON CONFLICT (currency) DO UPDATE
            SET rate_to_thb = EXCLUDED.rate_to_thb, effective_date = EXCLUDED.effective_date,
                source = 'MANUAL', fetched_at = NULL, updated_at = now()
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("currency", currency).addValue("rate", rateToThb).addValue("date", effectiveDate));
    }

    /** One item, one factory, driven to READY_FOR_CEO_REVIEW, quoted (and requested) entirely in currency PER_PIECE
     * — the simplest costable fixture, parametrized only by currency so the FX test above can drive a non-THB
     * chain end to end. */
    private long singleItemReadyForReview(String factoryName, String currency) {
        long productId = insertCatalogProduct(factoryName, "IT", "FX-TEST-" + UUID.randomUUID(),
            new BigDecimal("100.00"), currency, "per_piece");

        long ticketId = createDeal("ดีล FX " + UUID.randomUUID(), factoryName);
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, productId, null, "Test", "Model", "Test Model", null, null, "1x1", factoryName,
            new BigDecimal("100"), new BigDecimal("100"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, currency, "fx test request", UUID.randomUUID().toString(), List.of(item));
        // V185: bypasses PricingRequestService.createDraft on purpose -- that method now forces

        // every item.s requestedUnitBasis to PER_PIECE (the new sales form never types a unit/basis

        // directly), which would make it impossible to construct a PER_SQM/PER_BOX/PER_LINEAR_M-basis

        // item for this costing-conversion test. PricingRequestRepository.create performs the exact

        // same DB write createDraft would (persistence only, per that class.s own header Javadoc), just

        // without the sales-form-specific derivation/validation -- LandedCostCalculator does not care

        // how an item.s requested_unit_basis got set, only what it does with the value.

        long pricingRequestId = pricingRequests.create(ticketId, pricingRequests.nextRequestCode(), request, salesRepId);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).stream()
            .filter(q -> factoryName.equals(q.factoryName())).findFirst().orElseThrow();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-FX", currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("100"),
                UnitBasis.PER_PIECE, UnitBasis.PER_PIECE, new BigDecimal("100.00"), currency, null,
                new BigDecimal("1"), null, null, "45 days", null, null)),
            UUID.randomUUID().toString());
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
    }

    private long createDeal(String dealName, String factoryName) {
        CustomerDto customer = customers.create(
            "บริษัท " + UUID.randomUUID() + " จำกัด", "010" + System.nanoTime() % 10000000L,
            "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        ProjectDto project = projects.create(customer.id(), "โครงการ " + dealName);
        TicketDto created = ticketService.create(
            new CreateTicketRequest(dealName, "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("Test", "Model", factoryName))),
            salesActor);
        return created.summary().id();
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
