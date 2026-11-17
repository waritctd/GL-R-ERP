package th.co.glr.hr.orderconfirmation;

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
import th.co.glr.hr.common.ThaiText;
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
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoicePdfRenderer;
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
import th.co.glr.hr.orderconfirmation.OrderConfirmationDtos.OrderConfirmationResultDto;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
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
import th.co.glr.hr.ticket.CompleteDeliveryRequest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.RecordDeliveryRequest;
import th.co.glr.hr.ticket.RecordPaymentRequest;
import th.co.glr.hr.ticket.StockReservationRequest;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

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
            new DepositNoticePdfRenderer(), customers, quotationRepository);

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

    // THE CENTRAL QUESTION — does ticket_item.qty drift from the pricing-request chain's own settled quantity?

    @Test
    void confirmOrder_reconcilesTicketItemQty_fromTicketCreationStubToFirstPricingRequestQty() {
        // ticketItem() below creates the ticket_item with qty=1 — the SAME stub value every prior step's own fixture
        // uses (see ProcurementServiceIntegrationTest#ticketItem).
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("10"));

        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("1");

        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("10");
    }

    @Test
    void confirmOrder_costAffectingRevisionAfterIssue_reconcilesToRevisedQty_notOriginal() {
        // PR1 reaches QUOTATION_ISSUED with quantity X=10 — deliberately WITHOUT ever calling
        // confirmOrder on it, mirroring the realistic flow (Sales would not confirm an order that
        // is about to be revised).
        //
        // createCustomerChangeRevision's gate derives from PricingRequestStatus.ALLOWED, so the
        // revision is created from QUOTATION_ISSUED — the last point at which one is still legal.
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationIssued(new BigDecimal("10"));
        assertThat(pricingRequestService.get(deal.pricingRequestId, salesActor).summary().status())
            .isEqualTo("QUOTATION_ISSUED");

        // PR2: a cost-affecting revision changing the SAME source item's quantity to Y=15.
        long revisedPricingRequestId = createRevisionAndDriveToQuotationAccepted(deal, new BigDecimal("15"));

        // Only now does confirmOrder ever run — exactly once, on PR2.
        orderConfirmation.confirmOrder(revisedPricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        // The answer to the central question: ticket_item.qty reflects Y (15), not X (10) and not the original
        // ticket-creation stub (1).
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("15");
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

    // reserveStock / completeDelivery compose correctly against the RECONCILED (not stale) quantity, and the hard
    // constraint — "a Deal must not be marked delivered when open quantities remain" — holds on a real, reconciled
    // deal.

    @Test
    void fullChain_reserveStockAndCompleteDelivery_operateAgainstReconciledQty_notStaleTicketCreationQty() {
        // PR1 stops at QUOTATION_ISSUED — the last point a customer-change revision is legal.
        // It is never confirmed, so nothing is lost here: this test only ever confirms PR2.
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationIssued(new BigDecimal("10"));
        long revisedPricingRequestId = createRevisionAndDriveToQuotationAccepted(deal, new BigDecimal("15"));
        orderConfirmation.confirmOrder(revisedPricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("15");

        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(revisedPricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto issuedNotice = depositNoticeService.issue(draftNotice.id(), salesActor);
        assertThat(issuedNotice.status()).isEqualTo("ISSUED");
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        // Deliberately no issueImportRequest here — a deal fully coverable from stock never enters the import track at
        // all (see reserveStock's own "no import journey" comment); reserveStock advances DEPOSIT_RECEIVED straight to
        // DELIVERY_SCHEDULING itself.

        // Declaring the FULL 15 units from stock must be accepted — proving reserveStock reads qty=15 (the reconciled
        // figure), not the stale ticket-creation stub (1) or the pre-revision quantity (10), both of which would have
        // rejected "15" as over-declaring.
        TicketDto afterReserve = ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemId, new BigDecimal("15"), "จองครบจากสต็อก"))),
            ceoActor);
        assertThat(afterReserve.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(afterReserve.summary().salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);

        // ── Hard constraint, wrong-way-round: deliver only PART of the 15, and confirm the deal
        //    is NOT marked delivered while an open quantity remains ─────────────────────────
        // completeDelivery/recordPartialDelivery's gate (canWriteDelivery) is
        // {ceo, owning-rep} only -- salesActor (the deal owner, see the fixture below), not
        // importActor. See DeliveryAuthzIntegrationTest for the authz pin itself; this file is
        // about quantity reconciliation, not authz.
        TicketDto afterPartial = ticketService.recordPartialDelivery(deal.ticketId,
            new RecordDeliveryRequest("STOCK", "ส่งบางส่วน",
                List.of(new RecordDeliveryRequest.Line(deal.ticketItemId, new BigDecimal("9"))), null),
            salesActor);
        assertThat(afterPartial.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.PARTIALLY_DELIVERED);
        // Must NOT have auto-advanced to DELIVERED — 6 of the reconciled 15 units are still open.
        assertThat(afterPartial.summary().salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);

        // completeDelivery ships exactly the remaining 6 (15 - 9), computed against the RECONCILED qty — and only THEN
        // does the deal reach DELIVERED.
        TicketDto afterComplete = ticketService.completeDelivery(deal.ticketId,
            new CompleteDeliveryRequest("ส่งครบ", "คุณลูกค้า"), salesActor);
        assertThat(afterComplete.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(afterComplete.summary().salesStage()).isEqualTo(DealStage.DELIVERED);
        assertThat(afterComplete.items().get(0).qtyDelivered()).isEqualByComparingTo("15");

        // Delivery-note customer confirmation is recorded on the completing delivery.
        assertThat(tickets.findDeliveriesByTicket(deal.ticketId))
            .filteredOn(d -> "คุณลูกค้า".equals(d.recipientName()))
            .hasSize(1);
    }

    @Test
    void completeDelivery_rejectsWhenNothingOutstanding_ratherThanRedeclaringDelivered() {
        // Wrong-way-round on the OTHER side of the same hard constraint: once every reconciled unit is already
        // delivered, completeDelivery must refuse (409), not silently succeed a second time — "must not be marked
        // delivered" also means "cannot be re-marked."
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(deal.pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(draftNotice.id(), salesActor);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.recordFirstImportRequestIssued(deal.ticketId, 0L, importActor); // pre-V184 shape: IR_ISSUED, no rows
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemId, new BigDecimal("4"), null))),
            ceoActor);

        ticketService.completeDelivery(deal.ticketId, new CompleteDeliveryRequest("ส่งครบ", null), salesActor);

        assertThatThrownBy(() -> ticketService.completeDelivery(
            deal.ticketId, new CompleteDeliveryRequest("ส่งซ้ำ", null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // Reconciliation safety net: a downward reconciliation that would drop qty below an already-delivered quantity
    // is refused, not silently applied.

    /**
     * Same structural reason as {@code confirmOrder_thenACustomerChangeRevision_isRefused_...}:
     * getting a quantity DELIVERED requires {@code issueImportRequest}, which requires
     * {@code TicketStatus.QUOTATION_ISSUED}, which only {@code confirmOrder}'s bridge write sets,
     * which requires {@code QUOTATION_ACCEPTED} — from which a revision is refused.
     *
     * <p>The refusal happens one step EARLIER and more bluntly than a downward reconciliation
     * would: the revision cannot be created at all, so {@code confirmOrder} never gets
     * the chance to reject a downward reconciliation.
     *
     * <p><b>Coverage genuinely lost, stated rather than hidden:</b>
     * {@code reconcileTicketItems}' below-already-delivered guard no longer has a reachable trigger
     * through the public API. It remains as defence in depth. If an order-amendment mechanism is
     * ever added, that guard is the first thing that needs its coverage back.
     */
    @Test
    void deliveredOrder_cannotBeRevisedDownwards_becauseTheRevisionIsRefusedOutright() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("10"));
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(deal.pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto issuedNotice = depositNoticeService.issue(draftNotice.id(), salesActor);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.recordFirstImportRequestIssued(deal.ticketId, 0L, importActor); // pre-V184 shape: IR_ISSUED, no rows
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemId, new BigDecimal("10"), null))),
            ceoActor);
        // 6 of the 10 are already delivered before any revision exists.
        ticketService.recordPartialDelivery(deal.ticketId,
            new RecordDeliveryRequest("STOCK", null,
                List.of(new RecordDeliveryRequest.Line(deal.ticketItemId, new BigDecimal("6"))), null),
            salesActor);
        assertThat(ticketItemQtyDelivered(deal.ticketItemId)).isEqualByComparingTo("6");

        // A revision now asks for only 3 — below the 6 already physically delivered. It is refused at creation, so the
        // dangerous state is never constructed in the first place.
        assertThatThrownBy(() -> createRevisionAndDriveToQuotationAccepted(deal, new BigDecimal("3")))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                // Pinned to the QUOTATION_ACCEPTED refusal specifically: this walk touches several services that can each 409
                // for their own reasons, and a bare status assertion would be satisfied by any of them.
                assertThat(e.getMessage()).contains("ลูกค้ายอมรับใบเสนอราคาแล้ว");
            });

        // ticket_item.qty must be untouched (still 10, not silently dropped to 3 or left corrupt), and the delivery
        // history must be intact.
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("10");
        assertThat(ticketItemQtyDelivered(deal.ticketItemId)).isEqualByComparingTo("6");
    }

    // Bug found on review, fixed in the same step (not deferred): a pricing-request line DROPPED entirely by a
    // customer-change revision left its ticket_item row's stale qty untouched — reconcileTicketItems only ever
    // reconciled items still PRESENT in the current revision.

    @Test
    void confirmOrder_revisionDropsALineEntirely_closesItsTicketItemSoItStopsBlockingDelivery() {
        // PR1 stops at QUOTATION_ISSUED, and the original is NOT confirmed first. (Confirming
        // would show the closeout working on a line that had genuinely been correct at some
        // point, B reconciled to 5, rather than one merely never touched.) Confirming requires
        // QUOTATION_ACCEPTED, from which a revision is refused, so that stronger precondition is
        // not constructible and B enters the revision still holding its ticket-creation stub of 1.
        //
        // The behaviour under test is unaffected: closeOutDroppedChainItems closes a dropped line
        // down to its qtyDelivered whatever its qty happened to be, and the assertion that the deal
        // can then actually reach FULLY_DELIVERED is what makes this a real test either way.
        TwoItemDeal deal = createTwoItemDealAndDriveToQuotationIssued(
            new BigDecimal("10"), new BigDecimal("5"));
        assertThat(ticketItemQty(deal.ticketItemBId)).isEqualByComparingTo("1");

        // The revision keeps item A (qty unchanged) but DROPS item B entirely — only A is listed. roundToFullBox=false
        // + piecesInput=10 keeps the derived requestedQty byte-identical to the original 10.
        PricingRequestRequests.PricingRequestItemRequest revisedItemA = new PricingRequestRequests.PricingRequestItemRequest(
            deal.ticketItemAId, deal.catalogProductIdA, null, "SCG", "Tile A", "SCG Tile A",
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        CustomerChangeRevisionRequest revisionRequest = new CustomerChangeRevisionRequest(
            "ลูกค้าขอตัดรายการ B ออก", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
            "Designer Co.", LocalDate.now().plusDays(14), new BigDecimal("5000.00"), "THB",
            "step 8 dropped-line walk", List.of(revisedItemA));
        PricingRequestDetailDto revision = pricingRequestService.createCustomerChangeRevision(
            deal.pricingRequestId, revisionRequest, salesActor);
        long revisedPricingRequestId = revision.summary().id();
        driveDraftPricingRequestToQuotationAccepted(revisedPricingRequestId, FACTORY, new BigDecimal("10"));

        orderConfirmation.confirmOrder(revisedPricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        // B is closed out — qty reconciled down to its qtyDelivered (0), no longer open.
        assertThat(ticketItemQty(deal.ticketItemBId)).isEqualByComparingTo("0");
        assertThat(ticketItemQtyDelivered(deal.ticketItemBId)).isEqualByComparingTo("0");
        // A is untouched by the closeout (it's still current).
        assertThat(ticketItemQty(deal.ticketItemAId)).isEqualByComparingTo("10");

        // The deal can now actually reach fully-delivered — proving this isn't just a data-shape assertion but the
        // real, previously-blocking behavior.
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(revisedPricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(draftNotice.id(), salesActor);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.recordFirstImportRequestIssued(deal.ticketId, 0L, importActor); // pre-V184 shape: IR_ISSUED, no rows
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemAId, new BigDecimal("10"), null))),
            ceoActor);
        TicketDto delivered = ticketService.completeDelivery(
            deal.ticketId, new CompleteDeliveryRequest("ส่งครบ", null), salesActor);
        // Reaches FULLY_DELIVERED / DealStage.DELIVERED — the exact outcome B's phantom open
        // balance would otherwise make permanently unreachable.
        assertThat(delivered.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(delivered.summary().salesStage()).isEqualTo(DealStage.DELIVERED);
    }

    /**
     * Same setup as above but B was PARTIALLY delivered (2 of 5) before the revision drops it.
     *
     * <p>The closeout would preserve delivery history (B's qty → 2, not 0), never fabricating
     * a false "5 delivered" and never violating {@code chk_ticket_item_qty_delivered}, but that
     * scenario cannot be built: reaching a
     * partial delivery requires {@code confirmOrder} (via {@code issueImportRequest}'s
     * {@code TicketStatus.QUOTATION_ISSUED} gate), which requires {@code QUOTATION_ACCEPTED}, from
     * which a revision is refused.
     *
     * <p><b>Coverage genuinely lost, stated rather than hidden:</b> the history-preserving branch
     * of {@code TicketRepository#closeOutDroppedChainItems} (close to {@code qtyDelivered} rather
     * than to zero) no longer has a reachable trigger. The sibling test above still covers the
     * zero-delivered case. If order amendment is ever built, this is the second guard that needs
     * its coverage back.
     */
    @Test
    void aPartiallyDeliveredLine_cannotBeDroppedByARevision_becauseTheRevisionIsRefusedOutright() {
        TwoItemDeal deal = createTwoItemDealAndDriveToQuotationAccepted(
            new BigDecimal("10"), new BigDecimal("5"));
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(deal.pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(draftNotice.id(), salesActor);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.recordFirstImportRequestIssued(deal.ticketId, 0L, importActor); // pre-V184 shape: IR_ISSUED, no rows
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemBId, new BigDecimal("2"), null))),
            ceoActor);
        ticketService.recordPartialDelivery(deal.ticketId,
            new RecordDeliveryRequest("STOCK", null,
                List.of(new RecordDeliveryRequest.Line(deal.ticketItemBId, new BigDecimal("2"))), null),
            salesActor);
        assertThat(ticketItemQtyDelivered(deal.ticketItemBId)).isEqualByComparingTo("2");

        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService's resolveItems persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
        PricingRequestRequests.PricingRequestItemRequest revisedItemA = new PricingRequestRequests.PricingRequestItemRequest(
            deal.ticketItemAId, deal.catalogProductIdA, null, "SCG", "Tile A", "SCG Tile A",
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        CustomerChangeRevisionRequest revisionRequest = new CustomerChangeRevisionRequest(
            "ลูกค้าขอตัดรายการ B ออก", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
            "Designer Co.", LocalDate.now().plusDays(14), new BigDecimal("5000.00"), "THB",
            "step 8 partial-then-dropped walk", List.of(revisedItemA));

        assertThatThrownBy(() -> pricingRequestService.createCustomerChangeRevision(
            deal.pricingRequestId, revisionRequest, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                // Pinned to the QUOTATION_ACCEPTED refusal specifically: this walk touches several services that can each 409
                // for their own reasons, and a bare status assertion would be satisfied by any of them.
                assertThat(e.getMessage()).contains("ลูกค้ายอมรับใบเสนอราคาแล้ว");
            });

        // B keeps both its reconciled quantity and its real delivery history — the refusal happens before anything is
        // written, so there is nothing half-applied to clean up.
        assertThat(ticketItemQty(deal.ticketItemBId)).isEqualByComparingTo("5");
        assertThat(ticketItemQtyDelivered(deal.ticketItemBId)).isEqualByComparingTo("2");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Hand-off notifications: deposit confirmed -> rep, delivery finished -> account.
    // Each case pins the bell row AND the email path for the same recipient.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static final String DEPOSIT_CONFIRMED_TITLE = "ฝ่ายบัญชียืนยันรับเงินมัดจำแล้ว";
    private static final String DELIVERY_COMPLETED_TITLE = "ส่งสินค้าครบแล้ว — รอฝ่ายบัญชีดำเนินการ";
    private static final String PAYMENT_RECEIVED_TITLE = "ฝ่ายบัญชีรับชำระเงินเพิ่มแล้ว";
    private static final String PAYMENT_FULLY_PAID_TITLE = "ลูกค้าชำระเงินครบแล้ว";

    /** What the rep reads when a payment AFTER the deposit leaves a balance. */
    private String paymentReceivedMessage(long ticketId, BigDecimal received, BigDecimal outstanding) {
        return "ฝ่ายบัญชี (" + accountActor.name() + ") บันทึกรับชำระเงิน " + ThaiText.money(received) + " บาท แล้ว\n"
            + dealLabel(ticketId) + " · ยอดค้างชำระ " + ThaiText.money(outstanding) + " บาท\nแจ้งเพื่อทราบ ไม่ต้องดำเนินการ";
    }

    /** What the rep reads when the deal reaches FULLY_PAID (by a payment, or by the no-outstanding walk). */
    private String fullyPaidMessage(long ticketId) {
        BigDecimal payable = tickets.payableAmount(ticketId);
        return "ฝ่ายบัญชี (" + accountActor.name() + ") ยืนยันว่าลูกค้าชำระเงินครบแล้ว\n"
            + dealLabel(ticketId) + " · ยอดรวม " + ThaiText.money(payable) + " บาท\nแจ้งเพื่อทราบ ไม่ต้องดำเนินการ";
    }

    private String dealLabel(long ticketId) {
        TicketSummaryDto s = tickets.findById(ticketId).orElseThrow().summary();
        return "ดีล " + s.code() + " · ลูกค้า " + s.customerName();
    }

    /** confirmOrder -> issued deposit notice, stopping just before account confirms the deposit. */
    private DepositNoticeDto confirmOrderAndIssueDepositNotice(Deal deal) {
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draft = orderConfirmation.createDepositNoticeFromQuotation(deal.pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        return depositNoticeService.issue(draft.id(), salesActor);
    }

    /** An all-stock deal, deposit confirmed, ready to deliver {@code qty} units from stock. */
    private Deal allStockDealWithDepositPaid(String qty) {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal(qty));
        confirmOrderAndIssueDepositNotice(deal);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.recordFirstImportRequestIssued(deal.ticketId, 0L, importActor); // pre-V184 shape: IR_ISSUED, no rows
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemId, new BigDecimal(qty), null))),
            ceoActor);
        return deal;
    }

    private RecordDeliveryRequest stockDelivery(Deal deal, String qty) {
        return new RecordDeliveryRequest("STOCK", null,
            List.of(new RecordDeliveryRequest.Line(deal.ticketItemId, new BigDecimal(qty))), null);
    }

    private String deliveryDoneMessage(Deal deal, String secondLine) {
        return salesActor.name() + " บันทึกส่งสินค้าครบทุกรายการแล้ว\n" + dealLabel(deal.ticketId) + " · " + secondLine;
    }

    /** Import still needed after the deposit: the rep is told to raise the IR. */
    @Test
    void confirmDepositPaid_importStillNeeded_notifiesRepToCreateTheIr() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        DepositNoticeDto notice = confirmOrderAndIssueDepositNotice(deal);

        ticketService.confirmDepositPaid(deal.ticketId, accountActor);

        // The payment that settles the deposit is DEPOSIT_PAYMENT_CONFIRMED only -- never also a "received" notice.
        mailer.assertNoNotice("PAYMENT_RECEIVED", PAYMENT_RECEIVED_TITLE);
        mailer.assertNoNotice("PAYMENT_FULLY_PAID", PAYMENT_FULLY_PAID_TITLE);
        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().fulfillmentStatus())
            .as("fixture reaches the import branch").isNotEqualTo(FulfilmentStatus.FROM_STOCK);
        mailer.assertEmployeeNotice(salesRepId, "DEPOSIT_PAYMENT_CONFIRMED", DEPOSIT_CONFIRMED_TITLE,
            "ฝ่ายบัญชียืนยันรับเงินมัดจำ " + ThaiText.money(notice.totalPayable()) + " บาท แล้ว\n"
                + dealLabel(deal.ticketId) + "\nสิ่งที่ต้องทำต่อ: สร้างใบขอซื้อ (IR) ส่งให้ฝ่ายนำเข้าสั่งซื้อสินค้า",
            "/tickets/" + deal.ticketId);
    }

    /** All lines already covered from stock: confirmDepositPaid routes the deal to FROM_STOCK, and the rep is told to book delivery. */
    @Test
    void confirmDepositPaid_allStockDeal_notifiesRepToScheduleDeliveryInsteadOfAnIr() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        DepositNoticeDto notice = confirmOrderAndIssueDepositNotice(deal);
        jdbc.update("UPDATE sales.ticket_item SET qty_from_stock = qty WHERE ticket_id = :t", Map.of("t", deal.ticketId));
        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().fulfillmentStatus())
            .as("not routed yet: the deposit is what releases stock").isNull();

        ticketService.confirmDepositPaid(deal.ticketId, accountActor);

        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().fulfillmentStatus())
            .as("fixture reaches the all-stock branch").isEqualTo(FulfilmentStatus.FROM_STOCK);
        mailer.assertEmployeeNotice(salesRepId, "DEPOSIT_PAYMENT_CONFIRMED", DEPOSIT_CONFIRMED_TITLE,
            "ฝ่ายบัญชียืนยันรับเงินมัดจำ " + ThaiText.money(notice.totalPayable()) + " บาท แล้ว\n"
                + dealLabel(deal.ticketId)
                + "\nสิ่งที่ต้องทำต่อ: สินค้ามาจากสต็อกทั้งหมด นัดวันส่งสินค้ากับลูกค้าได้เลย",
            "/tickets/" + deal.ticketId);
    }

    /** The deposit can be settled through the generic record-payment action too: the rep is told exactly as for confirmDepositPaid. */
    @Test
    void recordPayment_depositKind_settlingTheDeposit_notifiesRepOnce() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        DepositNoticeDto notice = confirmOrderAndIssueDepositNotice(deal);
        mailer.assertNoNotice("DEPOSIT_PAYMENT_CONFIRMED", DEPOSIT_CONFIRMED_TITLE);

        ticketService.recordPayment(deal.ticketId, new RecordPaymentRequest(
            "DEPOSIT", notice.totalPayable(), null, "รับมัดจำ", notice.id(), null, false), accountActor);

        mailer.assertEmployeeNotice(salesRepId, "DEPOSIT_PAYMENT_CONFIRMED", DEPOSIT_CONFIRMED_TITLE,
            "ฝ่ายบัญชียืนยันรับเงินมัดจำ " + ThaiText.money(notice.totalPayable()) + " บาท แล้ว\n"
                + dealLabel(deal.ticketId) + "\nสิ่งที่ต้องทำต่อ: สร้างใบขอซื้อ (IR) ส่งให้ฝ่ายนำเข้าสั่งซื้อสินค้า",
            "/tickets/" + deal.ticketId);
    }

    /** A single payment big enough to walk straight to FULLY_PAID settles the deposit on the way: the rep gets the deposit notice (for THAT amount) AND the fully-paid notice, once each. */
    @Test
    void recordPayment_thatJumpsStraightToFullyPaid_notifiesRepOnceWithThatAmount() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        confirmOrderAndIssueDepositNotice(deal);
        BigDecimal payable = tickets.payableAmount(deal.ticketId);

        ticketService.recordPayment(deal.ticketId, new RecordPaymentRequest(
            "BALANCE", payable, null, "จ่ายเต็มจำนวน", null, null, false), accountActor);

        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().paymentStatus()).isEqualTo("FULLY_PAID");
        mailer.assertEmployeeNotice(salesRepId, "DEPOSIT_PAYMENT_CONFIRMED", DEPOSIT_CONFIRMED_TITLE,
            "ฝ่ายบัญชียืนยันรับเงินมัดจำ " + ThaiText.money(payable) + " บาท แล้ว\n"
                + dealLabel(deal.ticketId) + "\nสิ่งที่ต้องทำต่อ: สร้างใบขอซื้อ (IR) ส่งให้ฝ่ายนำเข้าสั่งซื้อสินค้า",
            "/tickets/" + deal.ticketId);
        // Review fix: the deposit notice carries the next step, but the rep must ALSO hear the deal is fully paid
        // (a later confirmFinalPayment is a no-op, so this is the only chance). Never a "received" notice.
        mailer.assertEmployeeNotice(salesRepId, "PAYMENT_FULLY_PAID", PAYMENT_FULLY_PAID_TITLE,
            fullyPaidMessage(deal.ticketId), "/tickets/" + deal.ticketId);
        mailer.assertNoNotice("PAYMENT_RECEIVED", PAYMENT_RECEIVED_TITLE);
    }

    /**
     * The later balance payment adds no second DEPOSIT_PAYMENT_CONFIRMED; it clears the whole balance, so the
     * rep is told the deal is fully paid -- exactly once, and not as a "received" notice.
     */
    @Test
    void confirmFinalPayment_afterTheDeposit_notifiesRepFullyPaid_andNotTheDepositAgain() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        DepositNoticeDto notice = confirmOrderAndIssueDepositNotice(deal);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);

        tickets.updateSalesStage(deal.ticketId, DealStage.DELIVERY_SCHEDULING); // 2026-11-07 final-payment gate
        ticketService.confirmFinalPayment(deal.ticketId, accountActor);

        mailer.assertEmployeeNotice(salesRepId, "DEPOSIT_PAYMENT_CONFIRMED", DEPOSIT_CONFIRMED_TITLE,
            "ฝ่ายบัญชียืนยันรับเงินมัดจำ " + ThaiText.money(notice.totalPayable()) + " บาท แล้ว\n"
                + dealLabel(deal.ticketId) + "\nสิ่งที่ต้องทำต่อ: สร้างใบขอซื้อ (IR) ส่งให้ฝ่ายนำเข้าสั่งซื้อสินค้า",
            "/tickets/" + deal.ticketId);
        mailer.assertEmployeeNotice(salesRepId, "PAYMENT_FULLY_PAID", PAYMENT_FULLY_PAID_TITLE,
            fullyPaidMessage(deal.ticketId), "/tickets/" + deal.ticketId);
        mailer.assertNoNotice("PAYMENT_RECEIVED", PAYMENT_RECEIVED_TITLE);
    }

    /** A payment after the deposit that leaves a balance tells the rep the amount and what is still owed; the one that clears it says fully paid instead (never both). */
    @Test
    void recordPayment_afterTheDeposit_notifiesRepReceivedWithTheBalance_thenFullyPaidOnTheLastOne() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        confirmOrderAndIssueDepositNotice(deal);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        BigDecimal owed = tickets.payableAmount(deal.ticketId).subtract(tickets.sumPaid(deal.ticketId));
        BigDecimal part = owed.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
        assertThat(part.signum()).as("fixture: a real balance to split").isPositive();

        ticketService.recordPayment(deal.ticketId, new RecordPaymentRequest(
            "BALANCE", part, null, "งวดแรก", null, null, false), accountActor);

        String firstMessage = paymentReceivedMessage(deal.ticketId, part, owed.subtract(part));
        mailer.assertEmployeeNotice(salesRepId, "PAYMENT_RECEIVED", PAYMENT_RECEIVED_TITLE,
            firstMessage, "/tickets/" + deal.ticketId);
        mailer.assertNoNotice("PAYMENT_FULLY_PAID", PAYMENT_FULLY_PAID_TITLE);

        ticketService.recordPayment(deal.ticketId, new RecordPaymentRequest(
            "BALANCE", owed.subtract(part), null, "งวดสุดท้าย", null, null, false), accountActor);

        mailer.assertEmployeeNotice(salesRepId, "PAYMENT_FULLY_PAID", PAYMENT_FULLY_PAID_TITLE,
            fullyPaidMessage(deal.ticketId), "/tickets/" + deal.ticketId);
        // Still exactly the FIRST received notice: the last payment added none.
        mailer.assertEmployeeNotice(salesRepId, "PAYMENT_RECEIVED", PAYMENT_RECEIVED_TITLE,
            firstMessage, "/tickets/" + deal.ticketId);
    }

    /** The no-outstanding walk (receipts already cover the payable, status still behind) also ends in FULLY_PAID: the rep is told once. */
    @Test
    void confirmFinalPayment_whenNothingIsOutstandingButStatusLags_walksToFullyPaidAndNotifiesRep() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        confirmOrderAndIssueDepositNotice(deal);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        BigDecimal owed = tickets.payableAmount(deal.ticketId).subtract(tickets.sumPaid(deal.ticketId));
        tickets.insertPaymentReceipt(deal.ticketId, "BALANCE", owed, accountActor.id(), null, null, null, null);
        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().paymentStatus())
            .as("fixture: receipts cover the payable but payment_status never advanced").isNotEqualTo("FULLY_PAID");

        tickets.updateSalesStage(deal.ticketId, DealStage.DELIVERY_SCHEDULING); // 2026-11-07 final-payment gate
        ticketService.confirmFinalPayment(deal.ticketId, accountActor);

        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().paymentStatus()).isEqualTo("FULLY_PAID");
        mailer.assertEmployeeNotice(salesRepId, "PAYMENT_FULLY_PAID", PAYMENT_FULLY_PAID_TITLE,
            fullyPaidMessage(deal.ticketId), "/tickets/" + deal.ticketId);
        mailer.assertNoNotice("PAYMENT_RECEIVED", PAYMENT_RECEIVED_TITLE);
    }

    /** A no-deposit (waived) deal never has a "deposit settled" moment, so its first partial payment is a plain "received". */
    @Test
    void recordPayment_onAWaivedDepositDeal_firstPartialPaymentNotifiesRepReceived() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        ticketService.waiveDeposit(deal.ticketId, th.co.glr.hr.ticket.DepositPolicy.WAIVED, "ลูกค้าไม่วางมัดจำ", salesActor);
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        BigDecimal payable = tickets.payableAmount(deal.ticketId);
        BigDecimal part = payable.divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);

        ticketService.recordPayment(deal.ticketId, new RecordPaymentRequest(
            "BALANCE", part, null, "งวดแรก", null, null, false), accountActor);

        mailer.assertEmployeeNotice(salesRepId, "PAYMENT_RECEIVED", PAYMENT_RECEIVED_TITLE,
            paymentReceivedMessage(deal.ticketId, part, payable.subtract(part)), "/tickets/" + deal.ticketId);
        mailer.assertNoNotice("DEPOSIT_PAYMENT_CONFIRMED", DEPOSIT_CONFIRMED_TITLE);
    }

    /** An ADJUSTMENT takes money off the books, it is not money received: no notice. */
    @Test
    void recordPayment_adjustment_notifiesNobody() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        confirmOrderAndIssueDepositNotice(deal);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);

        ticketService.recordPayment(deal.ticketId, new RecordPaymentRequest(
            "ADJUSTMENT", new BigDecimal("10.00"), null, "แก้ยอด", null, null, false), accountActor);

        mailer.assertNoNotice("PAYMENT_RECEIVED", PAYMENT_RECEIVED_TITLE);
        mailer.assertNoNotice("PAYMENT_FULLY_PAID", PAYMENT_FULLY_PAID_TITLE);
    }

    /** An IR already issued while the notice was out: do not tell the rep to create one, ask them to check every factory has its IR. */
    @Test
    void confirmDepositPaid_whenIrsAreAlreadyIssued_notifiesRepToCheckEveryFactoryHasOne() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        DepositNoticeDto notice = confirmOrderAndIssueDepositNotice(deal);
        ticketService.recordFirstImportRequestIssued(deal.ticketId, 0L, importActor); // pre-V184 shape: IR_ISSUED, no rows
        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().fulfillmentStatus())
            .as("fixture: an IR is already issued before the deposit is confirmed").isEqualTo(FulfilmentStatus.IR_ISSUED);

        ticketService.confirmDepositPaid(deal.ticketId, accountActor);

        mailer.assertEmployeeNotice(salesRepId, "DEPOSIT_PAYMENT_CONFIRMED", DEPOSIT_CONFIRMED_TITLE,
            "ฝ่ายบัญชียืนยันรับเงินมัดจำ " + ThaiText.money(notice.totalPayable()) + " บาท แล้ว\n"
                + dealLabel(deal.ticketId) + "\nสิ่งที่ต้องทำต่อ: ตรวจว่าออกใบขอซื้อ (IR) ครบทุกโรงงานแล้ว",
            "/tickets/" + deal.ticketId);
    }

    /** A delivery that leaves units open notifies nobody; the one that finishes the deal notifies account (balance still owed). */
    @Test
    void completeDelivery_afterAPartialOne_notifiesAccountOnlyWhenTheDealIsFullyDelivered_withTheOutstandingBalance() {
        long accountId = mailer.seedRoleEmployee("account");
        Deal deal = allStockDealWithDepositPaid("4");

        ticketService.recordPartialDelivery(deal.ticketId, stockDelivery(deal, "1"), salesActor);

        mailer.assertNoNotice("DELIVERY_COMPLETED", DELIVERY_COMPLETED_TITLE);

        ticketService.completeDelivery(deal.ticketId, new CompleteDeliveryRequest("ส่งครบ", null), salesActor);

        BigDecimal outstanding = tickets.payableAmount(deal.ticketId).subtract(tickets.sumPaid(deal.ticketId));
        assertThat(outstanding.signum()).as("fixture: only the deposit is paid").isPositive();
        mailer.assertRoleNotice("account", "DELIVERY_COMPLETED", DELIVERY_COMPLETED_TITLE,
            deliveryDoneMessage(deal, "ยอดค้างชำระ " + ThaiText.money(outstanding) + " บาท")
                + "\nสิ่งที่ต้องทำต่อ: ติดตามรับชำระยอดที่เหลือ แล้วยืนยันพร้อมปิดดีล",
            "/finance/deals/" + deal.ticketId, accountId);
    }

    /** A partial delivery that happens to cover everything remaining finishes the deal too, and notifies the same way. */
    @Test
    void recordPartialDelivery_thatCoversEverythingRemaining_notifiesAccountToo() {
        long accountId = mailer.seedRoleEmployee("account");
        Deal deal = allStockDealWithDepositPaid("4");

        ticketService.recordPartialDelivery(deal.ticketId, stockDelivery(deal, "4"), salesActor);

        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().fulfillmentStatus())
            .isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        BigDecimal outstanding = tickets.payableAmount(deal.ticketId).subtract(tickets.sumPaid(deal.ticketId));
        mailer.assertRoleNotice("account", "DELIVERY_COMPLETED", DELIVERY_COMPLETED_TITLE,
            deliveryDoneMessage(deal, "ยอดค้างชำระ " + ThaiText.money(outstanding) + " บาท")
                + "\nสิ่งที่ต้องทำต่อ: ติดตามรับชำระยอดที่เหลือ แล้วยืนยันพร้อมปิดดีล",
            "/finance/deals/" + deal.ticketId, accountId);
    }

    /** Nothing left to collect when the delivery finishes: the other wording, asking account to confirm close-ready. */
    @Test
    void completeDelivery_onAnAlreadyPaidInFullDeal_notifiesAccountToConfirmCloseReady() {
        long accountId = mailer.seedRoleEmployee("account");
        Deal deal = allStockDealWithDepositPaid("4");
        tickets.updateSalesStage(deal.ticketId, DealStage.DELIVERY_SCHEDULING); // 2026-11-07 final-payment gate
        ticketService.confirmFinalPayment(deal.ticketId, accountActor);
        assertThat(tickets.payableAmount(deal.ticketId).subtract(tickets.sumPaid(deal.ticketId)).signum())
            .as("fixture: nothing outstanding").isZero();

        ticketService.completeDelivery(deal.ticketId, new CompleteDeliveryRequest("ส่งครบ", null), salesActor);

        mailer.assertRoleNotice("account", "DELIVERY_COMPLETED", DELIVERY_COMPLETED_TITLE,
            deliveryDoneMessage(deal, "ลูกค้าชำระเงินครบแล้ว")
                + "\nสิ่งที่ต้องทำต่อ: ยืนยันพร้อมปิดดีลเพื่อส่งให้ CEO ตรวจสอบ",
            "/finance/deals/" + deal.ticketId, accountId);
    }

    // Fixture helpers.

    private record Deal(long ticketId, long ticketItemId, long pricingRequestId, long catalogProductId) {}

    private record TwoItemDeal(long ticketId, long ticketItemAId, long ticketItemBId,
                               long pricingRequestId, long catalogProductIdA, long catalogProductIdB) {}

    /** Same shape as #createDealAndDriveFirstPricingRequestToQuotationAccepted, but with TWO items on the same
     * factory, so a subsequent revision can drop one of them entirely (the "dropped line" bug fix tests) while
     * keeping the other. */
    private TwoItemDeal createTwoItemDealAndDriveToQuotationAccepted(BigDecimal qtyA, BigDecimal qtyB) {
        return createTwoItemDeal(qtyA, qtyB, true);
    }

    /** Stops at QUOTATION_ISSUED, so a revision may still be created — see
     * #createDealAndDriveFirstPricingRequestToQuotationIssued. */
    private TwoItemDeal createTwoItemDealAndDriveToQuotationIssued(BigDecimal qtyA, BigDecimal qtyB) {
        return createTwoItemDeal(qtyA, qtyB, false);
    }

    private TwoItemDeal createTwoItemDeal(BigDecimal qtyA, BigDecimal qtyB, boolean recordAcceptance) {
        long catalogProductIdA = insertCatalogProduct(FACTORY, "IT",
            "TEST-INV-A-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");
        long catalogProductIdB = insertCatalogProduct(FACTORY, "IT",
            "TEST-INV-B-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Inventory " + UUID.randomUUID() + " จำกัด", "0100000000009", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0009");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Inventory");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Inventory Two Items", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null, List.of(
                    ticketItem("SCG", "Tile A", FACTORY), ticketItem("SCG", "Tile B", FACTORY))),
            salesActor);
        long ticketId = created.summary().id();
        long ticketItemAId = created.items().get(0).id();
        long ticketItemBId = created.items().get(1).id();

        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
        PricingRequestRequests.PricingRequestItemRequest itemA = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemAId, catalogProductIdA, null, "SCG", "Tile A", "SCG Tile A",
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qtyA.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.PricingRequestItemRequest itemB = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemBId, catalogProductIdB, null, "SCG", "Tile B", "SCG Tile B",
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qtyB.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "step 8 two-item walk", UUID.randomUUID().toString(),
            List.of(itemA, itemB));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveTwoItemDraftToQuotationIssued(pricingRequestId);
        if (recordAcceptance) {
            recordAcceptanceOnCurrentQuotation(pricingRequestId);
        }
        return new TwoItemDeal(ticketId, ticketItemAId, ticketItemBId, pricingRequestId, catalogProductIdA, catalogProductIdB);
    }

    /** Same as #driveDraftPricingRequestToQuotationAccepted but responds to EVERY item on the (single,
     * same-factory) draft rather than assuming exactly one — both items share #FACTORY, so generateDrafts produces
     * exactly one draft covering both. */
    private void driveTwoItemDraftToQuotationIssued(long pricingRequestId) {
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        String email = FACTORY.toLowerCase().replace(" ", "-") + "@example.com";
        factoryQuoteService.markContacted(draft.id(),
            new MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        drainDispatches();
        List<ReceiveFactoryQuoteItemRequest> responseItems = draft.items().stream()
            .map(draftItem -> new ReceiveFactoryQuoteItemRequest(
                draftItem.pricingRequestItemId(), null, null, draftItem.quotedQuantity(), "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null))
            .toList();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest(
            "REF-" + UUID.randomUUID(), "THB", "30 days", "45 days", "revision", "note",
            responseItems, UUID.randomUUID().toString());
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

    private BigDecimal ticketItemQty(long itemId) {
        return jdbc.queryForObject(
            "SELECT qty FROM sales.ticket_item WHERE item_id = :id", Map.of("id", itemId), BigDecimal.class);
    }

    private BigDecimal ticketItemQtyDelivered(long itemId) {
        return jdbc.queryForObject(
            "SELECT qty_delivered FROM sales.ticket_item WHERE item_id = :id", Map.of("id", itemId), BigDecimal.class);
    }

    /** Creates a brand-new deal and drives its FIRST pricing request, requesting quantity, all the way to
     * QUOTATION_ACCEPTED via the real Steps 1-5 services — deliberately stopping there. */
    private Deal createDealAndDriveFirstPricingRequestToQuotationAccepted(BigDecimal quantity) {
        return createDealAndDriveFirstPricingRequest(quantity, true);
    }

    /**
     * Same fixture, stopped one step earlier — at QUOTATION_ISSUED, before the customer's
     * acceptance is recorded.
     *
     * <p>This is the ONLY state a
     * customer-change revision can be created from at the end of the chain. QUOTATION_ACCEPTED is
     * terminal and refused — see {@code PricingRequestStatus.ALLOWED} and the two
     * {@code customerChangeRevision_from...IsRejected} tests below. Every test in this file that
     * revises a deal therefore starts here rather than from acceptance.
     */
    private Deal createDealAndDriveFirstPricingRequestToQuotationIssued(BigDecimal quantity) {
        return createDealAndDriveFirstPricingRequest(quantity, false);
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
     * QUOTATION_ACCEPTED. The parent must be at QUOTATION_ISSUED — see
     * #createDealAndDriveFirstPricingRequestToQuotationIssued. */
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
