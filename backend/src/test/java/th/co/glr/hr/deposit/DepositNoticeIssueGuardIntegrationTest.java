package th.co.glr.hr.deposit;

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
import java.util.Optional;
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
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.SendFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
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
import th.co.glr.hr.pricingcosting.LandedCostCalculator;

/**
 * Change 1 (guard hardening plan): {@code DepositNoticeRepository.issue} must refuse a
 * concurrent/duplicate issue against a row that is no longer DRAFT, rather than silently re-minting
 * {@code doc_number} in place. Drives a deal through the REAL Steps 1-6 services (no shortcuts) —
 * same wiring and fixture-building approach as {@code
 * InventoryDeliveryFulfilmentIntegrationTest#fullChain_reserveStockAndCompleteDelivery_...} — up to
 * a successfully issued deposit notice, then exercises both the pre-existing service-level guard
 * ({@code DepositNoticeService.requireDraft}) and the new repository-level SQL guard directly.
 */
class DepositNoticeIssueGuardIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;
    private DepositNoticeRepository depositNoticeRepository;
    private OrderConfirmationService orderConfirmation;

    private long salesRepId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;

    private static final String FACTORY = "Factory Guard A";

    @BeforeEach
    void wireStepsServicesAndCreateFactory() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-deposit-issue-guard-test-uploads");
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
        // Costing moved from Import to CEO: the landed-cost calculation is now a shared
        // LandedCostCalculator that FactoryQuoteService and PricingDecisionService both take,
        // and PricingCostingService no longer owns the repositories it used to compute from.
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes,
            pricingRequests, fxRates, new FactoryConfigRepository(jdbc),
            new CatalogRepository(jdbc), formulaEngine);

        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage,
            dispatchProperties, landedCostCalculator);

        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);

        PriceCalcService priceCalcMock = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications);

        depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย การ์ด", "sales-guard1@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า การ์ด", "import-guard1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร การ์ด", "ceo-guard1@glr.co.th", "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        insertFactory(FACTORY);
    }

    private void insertFactory(String name) {
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:factory, :email, 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factory", name, "email", name.toLowerCase().replace(" ", "-") + "@example.com"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Change 1 tests
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issue_secondServiceCallOnAlreadyIssuedNoticeIsRefused_rowUnchanged() {
        // NEGATIVE (the one that matters): this currently passes via requireDraft — pins the
        // pre-existing rule, which is unaffected by the new repository predicate.
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();

        assertThatThrownBy(() -> depositNoticeService.issue(fixture.docId(), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        DepositNoticeDto reread = depositNoticeRepository.findById(fixture.docId()).orElseThrow();
        assertThat(reread.docNumber()).isEqualTo(fixture.docNumber());
        assertThat(reread.status()).isEqualTo("ISSUED");
    }

    @Test
    void issue_repositoryLevelCallOnAlreadyIssuedRowRefusesWithoutRemintingDocNumber() {
        // NEGATIVE, repository-level: this is what proves the NEW guard. Calling the repository
        // directly bypasses requireDraft entirely — without "AND status = 'DRAFT'" in the UPDATE's
        // WHERE, this returns a fresh doc number and rewrites the row.
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();

        Optional<String> result = depositNoticeRepository.issue(fixture.docId(), salesRepId, "Someone Else");

        assertThat(result).isEmpty();
        DepositNoticeDto reread = depositNoticeRepository.findById(fixture.docId()).orElseThrow();
        assertThat(reread.docNumber()).isEqualTo(fixture.docNumber());
        assertThat(reread.status()).isEqualTo("ISSUED");
    }

    @Test
    void issue_secondDraftOnSameTicketSucceedsAndSupersedesTheFirst() {
        // POSITIVE control: the guard must not block a genuine DRAFT -> ISSUED transition, and the
        // pre-existing "supersede older ISSUED versions" behaviour in the same method must still
        // work correctly with the new predicate in place.
        IssuedNoticeFixture fixture = buildTicketWithIssuedDepositNotice();

        long secondDocId = depositNoticeRepository.createDraft(
            fixture.ticketId(), secondDraftHeader(), secondDraftItems());
        Optional<String> secondDocNumber = depositNoticeRepository.issue(secondDocId, salesRepId, "Sales");

        assertThat(secondDocNumber).isPresent();
        assertThat(secondDocNumber.get()).isNotEqualTo(fixture.docNumber());

        DepositNoticeDto firstReread = depositNoticeRepository.findById(fixture.docId()).orElseThrow();
        assertThat(firstReread.status()).isEqualTo("SUPERSEDED");
        assertThat(firstReread.docNumber()).isEqualTo(fixture.docNumber());

        DepositNoticeDto secondReread = depositNoticeRepository.findById(secondDocId).orElseThrow();
        assertThat(secondReread.status()).isEqualTo("ISSUED");
        assertThat(secondReread.docNumber()).isEqualTo(secondDocNumber.get());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers — mirrors InventoryDeliveryFulfilmentIntegrationTest's approach for
    // driving a deal to an issued deposit notice through the real Steps 1-6 services.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private record IssuedNoticeFixture(long ticketId, long pricingRequestId, long docId, String docNumber) {}

    private DepositNoticeDraftRequest secondDraftHeader() {
        return new DepositNoticeDraftRequest(
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-2",
            new BigDecimal("0.50"), List.of(), null);
    }

    private List<DepositNoticeItemRequest> secondDraftItems() {
        return List.of(new DepositNoticeItemRequest(
            1, "Second draft item", new BigDecimal("1"), "แผ่น",
            new BigDecimal("100.00"), null, new BigDecimal("100.00")));
    }

    private IssuedNoticeFixture buildTicketWithIssuedDepositNotice() {
        long catalogProductId = insertCatalogProduct(FACTORY, "IT",
            "TEST-GUARD1-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Guard1 " + UUID.randomUUID() + " จำกัด", "0100000000019", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0019");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Guard1");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Guard1", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile Guard1", FACTORY))),
            salesActor);
        long ticketId = created.summary().id();
        long ticketItemId = created.items().get(0).id();

        BigDecimal quantity = new BigDecimal("10");
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile Guard1", "SCG Tile Guard1", null, null,
            "60x60", FACTORY, quantity, quantity, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "deposit-notice guard walk", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveDraftPricingRequestToQuotationAccepted(pricingRequestId, quantity);

        orderConfirmation.confirmOrder(pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draft = orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto issued = depositNoticeService.issue(draft.id(), salesActor);

        return new IssuedNoticeFixture(ticketId, pricingRequestId, issued.id(), issued.docNumber());
    }

    private void driveDraftPricingRequestToQuotationAccepted(long pricingRequestId, BigDecimal quantity) {
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        String email = FACTORY.toLowerCase().replace(" ", "-") + "@example.com";
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest(email, null, null, UUID.randomUUID().toString()), importActor);
        drainDispatches();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest(
            "REF-" + UUID.randomUUID(), "THB", "30 days", "45 days", "revision", "note",
            List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quantity, "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        // V141 ("CEO owns costing"): Import's last act is markReadyForCosting above. The three
        // costing write calls that used to sit here are severed (@Deprecated, 409) — startReview
        // now computes the landed cost itself when the CEO opens the review.
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = decision.items().stream()
            .map(decisionItem -> new UpdatePricingDecisionItemRequest(decisionItem.id(), null, new BigDecimal("1.00"), null, null, false))
            .toList();
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, updates), ceoActor);
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);

        CustomerQuotationDto draftQuotation = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()), salesActor);
        CustomerQuotationDto issued = quotationService.issue(
            draftQuotation.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), salesActor);
    }

    private void drainDispatches() {
        for (long id : factoryQuoteService.claimableDispatchIds()) {
            factoryQuoteService.processDispatch(id);
        }
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
