package th.co.glr.hr.orderconfirmation;

import th.co.glr.hr.ticket.DeliveryRoundFixture;
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
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CustomerChangeRevisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Receiving, Inventory Allocation, and Delivery — real-DB acceptance coverage for the
 * central question: does {@code sales.ticket_item.qty} still match
 * what the pricing-request chain actually settled on by the time the deal reaches delivery? See
 * {@code OrderConfirmationService#reconcileTicketItems}'s own Javadoc for the full answer (no, it
 * drifts, both on a first submission and further after a cost-affecting revision) and the design
 * decision that followed from it.
 *
 * <p>Drives a deal through the REAL upstream services (no shortcuts), exactly like {@code
 * ProcurementServiceIntegrationTest} — this class exists separately because its coverage
 * (quantity reconciliation, reserveStock/completeDelivery composition, the "cannot deliver more
 * than reconciled qty" hard constraint) is a materially different acceptance scenario from the
 * factory-PO-centric one, not because the wiring differs.
 */
class InventoryDeliveryFulfilmentIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private RecordingSalesMailer mailer;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;
    private PricingDecisionService decisionService;
    private CustomerQuotationRepository quotationRepository;
    private CustomerQuotationService quotationService;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;
    private OrderConfirmationService orderConfirmation;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private long accountUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;

    private static final String FACTORY = "Factory Inventory A";

    @BeforeEach
    void wireEveryStepsServiceAndCreateFactory() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
        CustomerRepository customers = new CustomerRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-inventory-delivery-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
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

        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing create/recalculate/submit is
        // gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
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

        salesRepId = createEmployee(employees, "พนักงานขาย แปด", "sales-step8@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า แปด", "import-step8@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร แปด", "ceo-step8@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี แปด", "account-step8@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");

        insertFactory(FACTORY);
    }

    private void insertFactory(String name) {
    }

    /**
     * <b>Asserts a refusal, not a convergence.</b>
     *
     * <p>The sequence "Sales confirms the original order (X=10), the customer then asks for a
     * change, the revision (Y=15) is accepted and confirmed, reconciliation converges forward" is
     * <b>structurally unreachable</b>, and not by accident: {@code confirmOrder}
     * requires {@code QUOTATION_ACCEPTED}, and a customer-change revision is refused FROM
     * {@code QUOTATION_ACCEPTED}. Once the customer has accepted and the order is confirmed, the
     * deal is moving to PO and fulfilment — changing it there is an order amendment, which is a
     * mechanism this codebase does not have yet.
     *
     * <p>So this test asserts the refusal, and
     * that the confirmed quantity is left exactly as it was.
     *
     * <p><b>Coverage genuinely lost, stated rather than hidden:</b> the forward-convergence branch
     * of {@code OrderConfirmationService}'s reconciliation (confirm PR1, then confirm PR2 on the
     * same ticket item) has no reachable trigger <i>via the revision chain</i>. A deal may
     * carry 0..N pricing requests, so a second INDEPENDENT pricing request on the same ticket may
     * still reach it — that is not verified either way here. The code is unaffected; this
     * test can no longer drive it.
     */
    @Test
    void confirmOrder_thenACustomerChangeRevision_isRefused_soAConfirmedOrderCannotBeRevised() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("10"));
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("10");

        assertThatThrownBy(() -> createRevisionAndDriveToQuotationAccepted(deal, new BigDecimal("15")))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                // Pinned to the QUOTATION_ACCEPTED refusal specifically: this walk touches several services that can each 409
                // for their own reasons, and a bare status assertion would be satisfied by any of them.
                assertThat(e.getMessage()).contains("ลูกค้ายอมรับใบเสนอราคาแล้ว");
            });

        // The confirmed order is untouched — no half-applied revision, no drift back to the stub.
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("10");
        assertThat(pricingRequestService.get(deal.pricingRequestId, salesActor).summary().status())
            .isEqualTo("QUOTATION_ACCEPTED");
    }

    // Fixture helpers.

    private record Deal(long ticketId, long ticketItemId, long pricingRequestId, long catalogProductId) {}

    private BigDecimal ticketItemQty(long itemId) {
        return jdbc.queryForObject(
            "SELECT qty FROM sales.ticket_item WHERE item_id = :id", Map.of("id", itemId), BigDecimal.class);
    }

    /** Creates a brand-new deal and drives its FIRST pricing request, requesting quantity, all the way to
     * QUOTATION_ACCEPTED via the real Steps 1-5 services — deliberately stopping there. */
    private Deal createDealAndDriveFirstPricingRequestToQuotationAccepted(BigDecimal quantity) {
        return createDealAndDriveFirstPricingRequest(quantity, true);
    }

    private Deal createDealAndDriveFirstPricingRequest(BigDecimal quantity, boolean recordAcceptance) {
        long catalogProductId = insertCatalogProduct(FACTORY, "IT",
            "TEST-INV-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Inventory " + UUID.randomUUID() + " จำกัด", "0100000000009", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0009");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Inventory");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Inventory", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile Inventory", FACTORY))),
            salesActor);
        long ticketId = created.summary().id();
        long ticketItemId = created.items().get(0).id();

        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile Inventory", "SCG Tile Inventory",
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, quantity.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "step 8 acceptance walk", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveDraftPricingRequestToQuotationIssued(pricingRequestId, FACTORY, quantity);
        if (recordAcceptance) {
            recordAcceptanceOnCurrentQuotation(pricingRequestId);
        }
        return new Deal(ticketId, ticketItemId, pricingRequestId, catalogProductId);
    }

    /** Creates a customer-change revision on deal's ticket item, requesting the NEW quantity, and drives IT to
     * QUOTATION_ACCEPTED. The parent must be at QUOTATION_ISSUED. */
    private long createRevisionAndDriveToQuotationAccepted(Deal deal, BigDecimal newQuantity) {
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item resolveItems persists — requestedQty/ requestedUnit/requestedUnitBasis are derived
        // instead.
        PricingRequestRequests.PricingRequestItemRequest revisedItem = new PricingRequestRequests.PricingRequestItemRequest(
            deal.ticketItemId, deal.catalogProductId, null, "SCG", "Tile Inventory", "SCG Tile Inventory",
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, newQuantity.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        CustomerChangeRevisionRequest revisionRequest = new CustomerChangeRevisionRequest(
            "ลูกค้าขอเปลี่ยนจำนวน", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
            "Designer Co.", LocalDate.now().plusDays(14), new BigDecimal("5000.00"), "THB",
            "step 8 revision walk", List.of(revisedItem));
        PricingRequestDetailDto revision = pricingRequestService.createCustomerChangeRevision(
            deal.pricingRequestId, revisionRequest, salesActor);
        long revisedPricingRequestId = revision.summary().id();

        driveDraftPricingRequestToQuotationAccepted(revisedPricingRequestId, FACTORY, newQuantity);
        return revisedPricingRequestId;
    }

    private void driveDraftPricingRequestToQuotationAccepted(long pricingRequestId, String factory, BigDecimal quantity) {
        driveDraftPricingRequestToQuotationIssued(pricingRequestId, factory, quantity);
        recordAcceptanceOnCurrentQuotation(pricingRequestId);
    }

    /**
     * Records the customer's ACCEPTED outcome on whichever quotation of {@code pricingRequestId}
     * is currently ISSUED. Split out from {@link #driveDraftPricingRequestToQuotationAccepted} so
     * a test can stop at QUOTATION_ISSUED and revise from there — the only point a revision is
     * legal.
     */
    private void recordAcceptanceOnCurrentQuotation(long pricingRequestId) {
        CustomerQuotationDto issued = quotationService.listForPricingRequest(pricingRequestId, salesActor).stream()
            .filter(q -> QuotationStatus.ISSUED.equals(q.docStatus()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ISSUED quotation on pricing request " + pricingRequestId));
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), salesActor);
    }

    private void driveDraftPricingRequestToQuotationIssued(long pricingRequestId, String factory, BigDecimal quantity) {
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        String email = factory.toLowerCase().replace(" ", "-") + "@example.com";
        factoryQuoteService.markContacted(draft.id(),
            new MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
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
        // V141 ("CEO owns costing"): markReadyForCosting auto-advances the request straight to READY_FOR_CEO_REVIEW
        // the moment the (only, here) factory quote is ready — Import no longer creates/recalculates/submits a costing
        // of its own; the CEO's startReview below computes it.

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
        quotationService.issue(
            draftQuotation.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
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
