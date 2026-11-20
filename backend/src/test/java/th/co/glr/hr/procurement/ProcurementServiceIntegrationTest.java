package th.co.glr.hr.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.orderconfirmation.OrderConfirmationDtos.OrderConfirmationResultDto;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingService;
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
import th.co.glr.hr.procurement.ProcurementDtos.FactoryPurchaseOrderDto;
import th.co.glr.hr.procurement.ProcurementRequests.CreateFactoryPurchaseOrdersRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Real-DB acceptance + authz + cross-tenant coverage for Step 7 (Factory Purchase Order and Import Execution). */
class ProcurementServiceIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteRepository factoryQuoteRepository;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingCostingService costingService;
    private PricingDecisionRepository decisionRepository;
    private PricingDecisionService decisionService;
    private CustomerQuotationRepository quotationRepository;
    private CustomerQuotationService quotationService;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;
    private OrderConfirmationService orderConfirmation;
    private ProcurementRepository purchaseOrders;
    private ProcurementService procurement;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private long accountUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;

    private static final String FACTORY_A = "Factory PO A";
    private static final String FACTORY_B = "Factory PO B";

    @BeforeEach
    void wireEverySevenStepsServiceAndCreateFactories() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-procurement-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        // V141 ("CEO owns costing"): shared by FactoryQuoteService's markReadyForCosting auto-advance check and
        // PricingDecisionService's startReview/recalculateCost.
        th.co.glr.hr.pricingcosting.LandedCostCalculator landedCostCalculator =
            new th.co.glr.hr.pricingcosting.LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
                new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);

        costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing create/recalculate/submit is
        // gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);

        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);

        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications, new th.co.glr.hr.customerquotation.DiscountApprovalRepository(jdbc));

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticePdfRenderer(), customers, quotationRepository, th.co.glr.hr.support.DocumentBundles.noDownload(jdbc), new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc));

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        purchaseOrders = new ProcurementRepository(jdbc);
        procurement = new ProcurementService(purchaseOrders, pricingRequests, tickets, notifications, ticketService);

        salesRepId = createEmployee(employees, "พนักงานขาย เจ็ด", "sales-step7@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า เจ็ด", "import-step7@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร เจ็ด", "ceo-step7@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี เจ็ด", "account-step7@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");

        insertFactory(FACTORY_A);
        insertFactory(FACTORY_B);
    }

    private void insertFactory(String name) {
    }

    // Gate: reachable only from QUOTATION_ACCEPTED + DealStage.PROCUREMENT (as strict as issueImportRequest's own
    // precondition, not weaker).

    @Test
    void createPurchaseOrders_beforeQuotationAccepted_isRejected() {
        DealFixture deal = driveToQuotationIssuedNotYetAccepted(FACTORY_A, FACTORY_B);
        assertThatThrownBy(() -> procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_purchase_order WHERE pricing_request_id = :id
            """, Map.of("id", deal.pricingRequestId), Long.class)).isZero();
    }

    // Authorization — Import/CEO only, Sales/account excluded entirely.

    @Test
    void createPurchaseOrders_salesActor_cannotCreate() {
        DealFixture deal = driveToProcurement(true);
        assertThatThrownBy(() -> procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_purchase_order WHERE pricing_request_id = :id
            """, Map.of("id", deal.pricingRequestId), Long.class)).isZero();
    }

    @Test
    void ceoActor_canCreateAndReadPurchaseOrders() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), ceoActor);
        assertThat(created).hasSize(2);
        assertThat(procurement.list(null, ceoActor)).isNotEmpty();
    }

    // Fixture helpers.

    private record DealFixture(long ticketId, long pricingRequestId) {}

    /** Drives a brand-new deal all the way to DealStage.PROCUREMENT via the REAL Steps 1-6 services:
     * QUOTATION_ACCEPTED -> confirmOrder -> deposit notice issued -> confirmDepositPaid -> (optionally)
     * issueImportRequest. */
    private DealFixture driveToProcurement(boolean issueImportRequest) {
        return driveToProcurement(issueImportRequest, false);
    }

    /** The id of the second request {@code driveToProcurement(.., true)} drove to QUOTATION_ISSUED. */
    private long siblingPricingRequestId;

    /**
     * {@code withSiblingRequest}: a SECOND request on the ticket is driven to QUOTATION_ISSUED BEFORE the
     * first one's order is confirmed (walk 4, W4-04: a confirmed deal starts no new pricing round).
     */
    private DealFixture driveToProcurement(boolean issueImportRequest, boolean withSiblingRequest) {
        DealFixture deal = driveToQuotationIssuedNotYetAccepted(FACTORY_A, FACTORY_B);
        if (withSiblingRequest) {
            siblingPricingRequestId = addSecondPricingRequestDrivenToQuotationIssued(deal.ticketId);
        }
        CustomerQuotationDto issued = quotationRepository.findByPricingRequest(deal.pricingRequestId).stream()
            .filter(q -> QuotationStatus.ISSUED.equals(q.docStatus()))
            .findFirst().orElseThrow();
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), salesActor);
        assertThat(pricingRequestService.get(deal.pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);

        OrderConfirmationResultDto confirmed = orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(confirmed.ticket().summary().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);

        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(deal.pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto issuedNotice = depositNoticeService.issue(draftNotice.id(), salesActor);
        assertThat(issuedNotice.status()).isEqualTo("ISSUED");
        TicketDto afterDeposit = ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        assertThat(afterDeposit.summary().paymentStatus()).isEqualTo("DEPOSIT_PAID");
        assertThat(afterDeposit.summary().salesStage()).isEqualTo(DealStage.DEPOSIT_RECEIVED);

        if (issueImportRequest) {
            ticketService.recordFirstImportRequestIssued(deal.ticketId, 0L, importActor); // pre-V184 shape: IR_ISSUED, no rows
            TicketDto afterIr = tickets.findById(deal.ticketId).orElseThrow();
            assertThat(afterIr.summary().salesStage()).isEqualTo(DealStage.PROCUREMENT);
            assertThat(afterIr.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.IR_ISSUED);
        }
        return deal;
    }

    private DealFixture driveToQuotationIssuedNotYetAccepted(String factoryA, String factoryB) {
        long catalogProductIdA = insertCatalogProduct(factoryA, "IT", "TEST-PO-" + factoryA.hashCode(),
            new BigDecimal("100.00"), "THB", "per_piece");
        long catalogProductIdB = insertCatalogProduct(factoryB, "IT", "TEST-PO-" + factoryB.hashCode(),
            new BigDecimal("200.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Procurement " + UUID.randomUUID() + " จำกัด", "0100000000007", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0007");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Procurement");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Procurement", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(
                    ticketItem("SCG", "Tile PO A", factoryA), ticketItem("SCG", "Tile PO B", factoryB))),
            salesActor);
        long ticketId = created.summary().id();

        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
        PricingRequestRequests.PricingRequestItemRequest itemA = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdA, null, "SCG", "Tile PO A", "SCG Tile PO A",
            "White", "Matte", "60x60", factoryA, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.PricingRequestItemRequest itemB = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdB, null, "SCG", "Tile PO B", "SCG Tile PO B",
            "White", "Matte", "80x80", factoryB, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 5, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "step 7 acceptance walk", UUID.randomUUID().toString(), List.of(itemA, itemB));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(drafts).hasSize(2);
        for (FactoryQuoteDto draft : drafts) {
            long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
            String email = draft.factoryName().toLowerCase().replace(" ", "-") + "@example.com";
            factoryQuoteService.markContacted(draft.id(),
            new MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
            drainDispatches();
            BigDecimal price = FACTORY_A.equals(draft.factoryName()) ? new BigDecimal("100.00") : new BigDecimal("200.00");
            ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-" + draft.factoryName(), "THB", "30 days", "45 days",
                "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                    pricingRequestItemId, null, null, draft.items().get(0).quotedQuantity(), "piece", UnitBasis.PER_PIECE,
                    price, "THB", null, new BigDecimal("1.00"), null, null,
                    "45 days", null, null)),
                UUID.randomUUID().toString());
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        // V141 ("CEO owns costing"): markReadyForCosting auto-advances the request straight to READY_FOR_CEO_REVIEW
        // the moment the LAST factory quote is ready — Import no longer creates/recalculates/submits a costing of its
        // own; the CEO's startReview below computes it.

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = decision.items().stream()
            .map(item -> new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("1.00"), null, null, false))
            .toList();
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, updates), ceoActor);
        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
        assertThat(approved.status()).isEqualTo("APPROVED");

        CustomerQuotationDto draftQuotation = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()), salesActor);
        quotationService.issue(draftQuotation.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        return new DealFixture(ticketId, pricingRequestId);
    }

    /** A second pricing request on an EXISTING ticket, driven all the way through Steps 1-4 to QUOTATION_ISSUED —
     * it has its own real APPROVED pricing_decision and its own pricing_costing_item rows, so it is NOT missing
     * anything createPurchaseOrders' costing lookup needs. */
    private long addSecondPricingRequestDrivenToQuotationIssued(long ticketId) {
        String factory = FACTORY_A;
        long catalogProductId = insertCatalogProduct(factory, "IT",
            "TEST-PO-2ND-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductId, null, "SCG", "Tile 2nd PR", "SCG Tile 2nd PR",
            "White", "Matte", "60x60", factory, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 2, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("500.00"), "THB", "second pricing request on the same ticket, not yet accepted",
            UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        factoryQuoteService.markContacted(draft.id(),
            new MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        drainDispatches();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-2ND", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("2.00"), "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        // V141 ("CEO owns costing"): markReadyForCosting auto-advances the request straight to READY_FOR_CEO_REVIEW —
        // the CEO's startReview below computes+persists the costing itself.

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto decisionItem = decision.items().get(0);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
            new UpdatePricingDecisionItemRequest(decisionItem.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);

        CustomerQuotationDto draftQuotation = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()), salesActor);
        quotationService.issue(draftQuotation.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        return pricingRequestId;
    }

    private void drainDispatches() {
        // No-op now: FactoryQuoteService.send is synchronous (manual-RFQ redesign) -- there is no dispatch/worker
        // queue left to drain. Kept (rather than removing every call site) so this helper's callers do not all need to
        // be revisited individually.
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
