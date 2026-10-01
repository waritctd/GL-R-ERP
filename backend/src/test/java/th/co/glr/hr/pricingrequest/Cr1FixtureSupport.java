package th.co.glr.hr.pricingrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
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
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * CR-1 (GLA-167) shared fixture: real repositories and services (no Mockito), a deal owned by
 * {@code salesActor}, and the personas every CR-1 integration test needs. Copied from the wiring
 * in {@code PricingFactoryQuoteCostingIntegrationTest} so it stays faithful to production.
 */
abstract class Cr1FixtureSupport extends AbstractPostgresIntegrationTest {
    protected static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    protected PricingRequestRepository pricingRequests;
    protected PricingRequestService pricingRequestService;
    protected FactoryQuoteRepository factoryQuoteRepository;
    protected FactoryQuoteService factoryQuoteService;
    protected LeadTimeChangeService leadTimeChangeService;

    protected long salesRepId;
    protected long otherSalesRepId;
    protected long importUserId;
    protected long ceoUserId;
    protected long accountUserId;
    protected long salesManagerUserId;
    protected UserPrincipal salesActor;       // owns the deal
    protected UserPrincipal otherSalesActor;  // a DIFFERENT sales rep
    protected UserPrincipal importActor;
    protected UserPrincipal ceoActor;
    protected UserPrincipal accountActor;
    protected UserPrincipal salesManagerActor;
    protected long ticketId;
    protected long catalogProductIdFactoryA;
    protected long catalogProductIdFactoryB;

    protected void wireServicesAndCreateDeal() {
        TicketRepository tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-cr1-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage,
            factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);
        // Wiring of the NEW service: if the real constructor differs, this is the ONE line to change.
        leadTimeChangeService = new LeadTimeChangeService(new LeadTimeChangeRepository(jdbc), factoryQuotes,
            pricingRequests, tickets, notifications);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc));

        salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales-cr1@glr.co.th", "SALES", "แผนกขาย");
        otherSalesRepId = createEmployee(employees, "พนักงานขาย อีกคน", "sales-other-cr1@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า ทดสอบ", "import-cr1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = employees.create(new UpsertEmployeeRequest(
            null, null, "ผู้บริหาร ทดสอบ", null, null, null, null, null, null, null,
            "ceo-cr1@glr.co.th", null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
        accountUserId = createEmployee(employees, "บัญชี ทดสอบ", "account-cr1@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager-cr1@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        catalogProductIdFactoryA = insertCatalogProduct("Factory A", "IT", "TEST-A-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B", "IT", "TEST-B-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        jdbc.update("UPDATE price_catalog.factories SET email = 'factory-a@example.com', unit = 'piece' WHERE name = 'Factory A'",
            Map.of());
        jdbc.update("UPDATE price_catalog.factories SET email = 'factory-b@example.com', unit = 'piece' WHERE name = 'Factory B'",
            Map.of());

        CustomerDto customer = customers.create(
            "บริษัท CR1 จำกัด", "0100000000002", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0002");
        ProjectDto project = projects.create(customer.id(), "โครงการ CR1");
        ticketId = ticketService.create(
            new CreateTicketRequest("ดีล CR1", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A", "Factory A"), ticketItem("Cotto", "Tile B", "Factory B"))),
            salesActor).summary().id();
    }

    // ── pricing request builders ─────────────────────────────────────────────────────────────

    /**
     * One request line. {@code leadMin/leadMax} may be null (submit REQUIRES a lead time, so null means the default 3-7); {@code currency}/{@code priceUnit} are the
     * CR-1 terms Sales fixes on the line (null = legacy line).
     */
    protected PricingRequestRequests.PricingRequestItemRequest line(
        String model, String factory, int qty, Integer leadMin, Integer leadMax, String currency, String priceUnit
    ) {
        Long productId = "Factory A".equals(factory) ? catalogProductIdFactoryA
            : "Factory B".equals(factory) ? catalogProductIdFactoryB : null;
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, "Brand", model,
            "Brand " + model, "White", "Matte", "60x60", factory, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qty, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", leadMin == null ? 3 : leadMin, leadMax == null ? 7 : leadMax, null, null, null,
            null, null, currency, priceUnit);
    }

    protected PricingRequestRequests.CreatePricingRequestRequest request(
        PricingRequestRequests.PricingRequestItemRequest... items
    ) {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "cr1 request", UUID.randomUUID().toString(), List.of(items));
    }

    /** Sales creates + submits, import picks it up: the request is IMPORT_REVIEWING. */
    protected long requestInImportReview(PricingRequestRequests.PricingRequestItemRequest... items) {
        long id = pricingRequestService.createDraft(ticketId, request(items), salesActor).summary().id();
        pricingRequestService.submit(id, salesActor);
        pricingRequestService.pickup(id, importActor);
        return id;
    }

    protected FactoryQuoteDto quoteFor(List<FactoryQuoteDto> quotes, String factoryName) {
        return quotes.stream().filter(q -> factoryName.equals(q.factoryName())).findFirst().orElseThrow();
    }

    protected LocalDate todayBangkok() {
        return LocalDate.now(BANGKOK);
    }

    protected long eventCount(long pricingRequestId, String kind) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = :kind
            """, Map.of("id", pricingRequestId, "kind", kind), Long.class);
    }

    protected String requestStatus(long pricingRequestId) {
        return pricingRequestService.get(pricingRequestId, importActor).summary().status();
    }

    /** A response covering EVERY line of {@code quote}, all in one currency / basis. */
    protected ReceiveFactoryQuoteRequest receiveAll(FactoryQuoteDto quote, String currency, String unitBasis) {
        boolean sqm = "PER_SQM".equals(unitBasis);
        List<ReceiveFactoryQuoteItemRequest> items = quote.items().stream()
            .map(i -> new ReceiveFactoryQuoteItemRequest(
                i.pricingRequestItemId(), null, null, new BigDecimal("1.00"), unitBasis, unitBasis,
                new BigDecimal("100.00"), currency, null, new BigDecimal("0.36"), null, null,
                "45 days", null, null))
            .toList();
        return new ReceiveFactoryQuoteRequest("REF-" + quote.id(), currency, "30 days", "45 days",
            "revision", "note", items, UUID.randomUUID().toString());
    }

    // ── people ───────────────────────────────────────────────────────────────────────────────

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    protected UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }
}
