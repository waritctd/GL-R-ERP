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
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticeRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceRenderer;
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
import th.co.glr.hr.orderconfirmation.OrderConfirmationDtos.OrderConfirmationResultDto;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingService;
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
import th.co.glr.hr.ticket.CompleteDeliveryRequest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.RecordDeliveryRequest;
import th.co.glr.hr.ticket.StockReservationRequest;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Step 8 (Receiving, Inventory Allocation, and Delivery) — real-DB acceptance coverage for the
 * central question this step had to answer first: does {@code sales.ticket_item.qty} still match
 * what the pricing-request chain actually settled on by the time the deal reaches delivery? See
 * {@code OrderConfirmationService#reconcileTicketItems}'s own Javadoc for the full answer (no, it
 * drifts, both on a first submission and further after a cost-affecting revision) and the design
 * decision that followed from it.
 *
 * <p>Drives a deal through the REAL Steps 1-6 services (no shortcuts), exactly like {@code
 * ProcurementServiceIntegrationTest} — this class exists separately because Step 8's own coverage
 * (quantity reconciliation, reserveStock/completeDelivery composition, the "cannot deliver more
 * than reconciled qty" hard constraint) is a materially different acceptance scenario from Step
 * 7's factory-PO-centric one, not because the wiring differs.
 */
class InventoryDeliveryFulfilmentIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
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
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-inventory-delivery-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage);

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
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties);

        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        costingService = new PricingCostingService(costingRepository, pricingRequests, factoryQuotes, tickets,
            fxRates, new PriceCalcConfigRepository(jdbc), new FactoryConfigRepository(jdbc), notifications);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications);

        PriceCalcService priceCalcMock = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications);

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer());

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
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:factory, :email, 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factory", name, "email", name.toLowerCase().replace(" ", "-") + "@example.com"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // THE CENTRAL QUESTION — does ticket_item.qty drift from the pricing-request chain's own
    // settled quantity?
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmOrder_reconcilesTicketItemQty_fromTicketCreationStubToFirstPricingRequestQty() {
        // ticketItem() below creates the ticket_item with qty=1 — the SAME stub value every
        // prior step's own fixture uses (see ProcurementServiceIntegrationTest#ticketItem).
        // Nothing in Steps 1-6 keeps this in sync with the pricing request's own requestedQty —
        // this is the drift confirmed even WITHOUT any revision.
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("10"));

        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("1");

        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("10");
    }

    @Test
    void confirmOrder_costAffectingRevisionAfterAcceptance_reconcilesToRevisedQty_notOriginal() {
        // PR1 reaches QUOTATION_ACCEPTED with quantity X=10 — deliberately WITHOUT ever calling
        // confirmOrder on it, mirroring the realistic flow (Sales would not confirm an order
        // that is about to be revised). PricingRequestStatus.ALLOWED marks QUOTATION_ACCEPTED as
        // terminal for forward transitions, but createCustomerChangeRevision's own gate does NOT
        // consult that table — it is reachable here, proving Step 5's "terminal" design does not
        // block a post-acceptance customer-change revision.
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("10"));
        assertThat(pricingRequestService.get(deal.pricingRequestId, salesActor).summary().status())
            .isEqualTo("QUOTATION_ACCEPTED");

        // PR2: a cost-affecting revision changing the SAME source item's quantity to Y=15.
        long revisedPricingRequestId = createRevisionAndDriveToQuotationAccepted(deal, new BigDecimal("15"));

        // Only now does confirmOrder ever run — exactly once, on PR2.
        orderConfirmation.confirmOrder(revisedPricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        // The answer to the central question: ticket_item.qty reflects Y (15), not X (10) and
        // not the original ticket-creation stub (1).
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("15");
    }

    @Test
    void confirmOrder_calledOnBothTheOriginalAndTheRevision_convergesForwardToTheRevisedQty() {
        // A second, less likely but still reachable sequence: Sales DOES confirm the original
        // order (X=10) before the customer asks for a change. The revision (Y=15) is then driven
        // to acceptance and confirmOrder is called a SECOND time, on PR2 — reconciliation must
        // still converge to the latest confirmed quantity, not get stuck at the first one.
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("10"));
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("10");

        long revisedPricingRequestId = createRevisionAndDriveToQuotationAccepted(deal, new BigDecimal("15"));
        orderConfirmation.confirmOrder(revisedPricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("15");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // reserveStock / completeDelivery compose correctly against the RECONCILED (not stale)
    // quantity, and the hard constraint — "a Deal must not be marked delivered when open
    // quantities remain" — holds on a real, reconciled deal.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void fullChain_reserveStockAndCompleteDelivery_operateAgainstReconciledQty_notStaleTicketCreationQty() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("10"));
        long revisedPricingRequestId = createRevisionAndDriveToQuotationAccepted(deal, new BigDecimal("15"));
        orderConfirmation.confirmOrder(revisedPricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("15");

        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(revisedPricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto issuedNotice = depositNoticeService.issue(draftNotice.id(), salesActor);
        assertThat(issuedNotice.status()).isEqualTo("ISSUED");
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        // Deliberately no issueImportRequest here — a deal fully coverable from stock never
        // enters the import track at all (see reserveStock's own "no import journey" comment);
        // reserveStock advances DEPOSIT_RECEIVED straight to DELIVERY_SCHEDULING itself.

        // Declaring the FULL 15 units from stock must be accepted — proving reserveStock reads
        // qty=15 (the reconciled figure), not the stale ticket-creation stub (1) or the
        // pre-revision quantity (10), both of which would have rejected "15" as over-declaring.
        TicketDto afterReserve = ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemId, new BigDecimal("15"), "จองครบจากสต็อก"))),
            importActor);
        assertThat(afterReserve.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(afterReserve.summary().salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);

        // ── Hard constraint, wrong-way-round: deliver only PART of the 15, and confirm the deal
        //    is NOT marked delivered while an open quantity remains ─────────────────────────
        TicketDto afterPartial = ticketService.recordPartialDelivery(deal.ticketId,
            new RecordDeliveryRequest("STOCK", "ส่งบางส่วน",
                List.of(new RecordDeliveryRequest.Line(deal.ticketItemId, new BigDecimal("9"))), null),
            importActor);
        assertThat(afterPartial.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.PARTIALLY_DELIVERED);
        // Must NOT have auto-advanced to DELIVERED — 6 of the reconciled 15 units are still open.
        assertThat(afterPartial.summary().salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);

        // completeDelivery ships exactly the remaining 6 (15 - 9), computed against the
        // RECONCILED qty — and only THEN does the deal reach DELIVERED.
        TicketDto afterComplete = ticketService.completeDelivery(deal.ticketId,
            new CompleteDeliveryRequest("ส่งครบ", "คุณลูกค้า"), importActor);
        assertThat(afterComplete.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(afterComplete.summary().salesStage()).isEqualTo(DealStage.DELIVERED);
        assertThat(afterComplete.items().get(0).qtyDelivered()).isEqualByComparingTo("15");

        // Delivery-note customer confirmation (Step 8) is recorded on the completing delivery.
        assertThat(tickets.findDeliveriesByTicket(deal.ticketId))
            .filteredOn(d -> "คุณลูกค้า".equals(d.recipientName()))
            .hasSize(1);
    }

    @Test
    void completeDelivery_rejectsWhenNothingOutstanding_ratherThanRedeclaringDelivered() {
        // Wrong-way-round on the OTHER side of the same hard constraint: once every reconciled
        // unit is already delivered, completeDelivery must refuse (409), not silently succeed a
        // second time — "must not be marked delivered" also means "cannot be re-marked."
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("4"));
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(deal.pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(draftNotice.id(), salesActor);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.issueImportRequest(deal.ticketId, importActor);
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemId, new BigDecimal("4"), null))),
            importActor);

        ticketService.completeDelivery(deal.ticketId, new CompleteDeliveryRequest("ส่งครบ", null), importActor);

        assertThatThrownBy(() -> ticketService.completeDelivery(
            deal.ticketId, new CompleteDeliveryRequest("ส่งซ้ำ", null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Reconciliation safety net: a downward reconciliation that would drop qty below an
    // already-delivered quantity is refused, not silently applied.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmOrder_revisionLowersQtyBelowAlreadyDelivered_isRejectedNotSilentlyApplied() {
        Deal deal = createDealAndDriveFirstPricingRequestToQuotationAccepted(new BigDecimal("10"));
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(deal.pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto issuedNotice = depositNoticeService.issue(draftNotice.id(), salesActor);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.issueImportRequest(deal.ticketId, importActor);
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemId, new BigDecimal("10"), null))),
            importActor);
        // 6 of the 10 are already delivered before any revision exists.
        ticketService.recordPartialDelivery(deal.ticketId,
            new RecordDeliveryRequest("STOCK", null,
                List.of(new RecordDeliveryRequest.Line(deal.ticketItemId, new BigDecimal("6"))), null),
            importActor);
        assertThat(ticketItemQtyDelivered(deal.ticketItemId)).isEqualByComparingTo("6");

        // A revision now asks for only 3 — below the 6 already physically delivered.
        long revisedPricingRequestId = createRevisionAndDriveToQuotationAccepted(deal, new BigDecimal("3"));

        assertThatThrownBy(() -> orderConfirmation.confirmOrder(revisedPricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // ticket_item.qty must be untouched (still 10, not silently dropped to 3 or left corrupt).
        assertThat(ticketItemQty(deal.ticketItemId)).isEqualByComparingTo("10");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Bug found on review, fixed in the same step (not deferred): a pricing-request line DROPPED
    // entirely by a customer-change revision left its ticket_item row's stale qty untouched —
    // reconcileTicketItems only ever reconciled items still PRESENT in the current revision.
    // TicketService.completeDelivery/reserveStock iterate ALL of a ticket's items unconditionally,
    // so a stale row with qty > qtyDelivered created a phantom, permanently-undeliverable open
    // balance: the deal could NEVER reach FulfilmentStatus.FULLY_DELIVERED. See
    // TicketRepository#closeOutDroppedChainItems's own Javadoc for the fix.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmOrder_revisionDropsALineEntirely_closesItsTicketItemSoItStopsBlockingDelivery() {
        TwoItemDeal deal = createTwoItemDealAndDriveToQuotationAccepted(
            new BigDecimal("10"), new BigDecimal("5"));
        // Confirm the ORIGINAL first, so both items are properly reconciled (A=10, B=5) before
        // the revision that drops B — proving the closeout works on a line that had genuinely
        // been correct at some point, not one that was merely never touched.
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(ticketItemQty(deal.ticketItemBId)).isEqualByComparingTo("5");

        // The revision keeps item A (qty unchanged) but DROPS item B entirely — only A is listed.
        PricingRequestRequests.PricingRequestItemRequest revisedItemA = new PricingRequestRequests.PricingRequestItemRequest(
            deal.ticketItemAId, deal.catalogProductIdA, null, "SCG", "Tile A", "SCG Tile A", null, null,
            "60x60", FACTORY, new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
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

        // The deal can now actually reach fully-delivered — proving this isn't just a data-shape
        // assertion but the real, previously-blocking behavior.
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(revisedPricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(draftNotice.id(), salesActor);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.issueImportRequest(deal.ticketId, importActor);
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemAId, new BigDecimal("10"), null))),
            importActor);
        TicketDto delivered = ticketService.completeDelivery(
            deal.ticketId, new CompleteDeliveryRequest("ส่งครบ", null), importActor);
        // Reaches FULLY_DELIVERED / DealStage.DELIVERED — the exact outcome B's phantom open
        // balance used to make permanently unreachable.
        assertThat(delivered.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(delivered.summary().salesStage()).isEqualTo(DealStage.DELIVERED);
    }

    /** Same setup as above but B was PARTIALLY delivered (2 of 5) before the revision drops it —
     * the closeout must preserve that history (qty -> 2, not 0), never fabricating a false "5
     * delivered" and never violating chk_ticket_item_qty_delivered by going below what's real. */
    @Test
    void confirmOrder_revisionDropsAPartiallyDeliveredLine_closesToWhatWasActuallyDelivered() {
        TwoItemDeal deal = createTwoItemDealAndDriveToQuotationAccepted(
            new BigDecimal("10"), new BigDecimal("5"));
        orderConfirmation.confirmOrder(deal.pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(deal.pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(draftNotice.id(), salesActor);
        ticketService.confirmDepositPaid(deal.ticketId, accountActor);
        ticketService.issueImportRequest(deal.ticketId, importActor);
        ticketService.reserveStock(deal.ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(deal.ticketItemBId, new BigDecimal("2"), null))),
            importActor);
        ticketService.recordPartialDelivery(deal.ticketId,
            new RecordDeliveryRequest("STOCK", null,
                List.of(new RecordDeliveryRequest.Line(deal.ticketItemBId, new BigDecimal("2"))), null),
            importActor);
        assertThat(ticketItemQtyDelivered(deal.ticketItemBId)).isEqualByComparingTo("2");

        PricingRequestRequests.PricingRequestItemRequest revisedItemA = new PricingRequestRequests.PricingRequestItemRequest(
            deal.ticketItemAId, deal.catalogProductIdA, null, "SCG", "Tile A", "SCG Tile A", null, null,
            "60x60", FACTORY, new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        CustomerChangeRevisionRequest revisionRequest = new CustomerChangeRevisionRequest(
            "ลูกค้าขอตัดรายการ B ออก", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
            "Designer Co.", LocalDate.now().plusDays(14), new BigDecimal("5000.00"), "THB",
            "step 8 partial-then-dropped walk", List.of(revisedItemA));
        PricingRequestDetailDto revision = pricingRequestService.createCustomerChangeRevision(
            deal.pricingRequestId, revisionRequest, salesActor);
        long revisedPricingRequestId = revision.summary().id();
        driveDraftPricingRequestToQuotationAccepted(revisedPricingRequestId, FACTORY, new BigDecimal("10"));

        orderConfirmation.confirmOrder(revisedPricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        // Closed to exactly what was delivered (2), not to 0 — history preserved, nothing fabricated.
        assertThat(ticketItemQty(deal.ticketItemBId)).isEqualByComparingTo("2");
        assertThat(ticketItemQtyDelivered(deal.ticketItemBId)).isEqualByComparingTo("2");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private record Deal(long ticketId, long ticketItemId, long pricingRequestId, long catalogProductId) {}

    private record TwoItemDeal(long ticketId, long ticketItemAId, long ticketItemBId,
                               long pricingRequestId, long catalogProductIdA, long catalogProductIdB) {}

    /** Same shape as {@link #createDealAndDriveFirstPricingRequestToQuotationAccepted}, but with
     * TWO items on the same factory, so a subsequent revision can drop one of them entirely
     * (the "dropped line" bug fix tests) while keeping the other. */
    private TwoItemDeal createTwoItemDealAndDriveToQuotationAccepted(BigDecimal qtyA, BigDecimal qtyB) {
        long catalogProductIdA = insertCatalogProduct(FACTORY, "TH",
            "TEST-INV-A-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");
        long catalogProductIdB = insertCatalogProduct(FACTORY, "TH",
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

        PricingRequestRequests.PricingRequestItemRequest itemA = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemAId, catalogProductIdA, null, "SCG", "Tile A", "SCG Tile A", null, null,
            "60x60", FACTORY, qtyA, qtyA, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.PricingRequestItemRequest itemB = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemBId, catalogProductIdB, null, "SCG", "Tile B", "SCG Tile B", null, null,
            "60x60", FACTORY, qtyB, qtyB, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "step 8 two-item walk", UUID.randomUUID().toString(),
            List.of(itemA, itemB));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveTwoItemDraftToQuotationAccepted(pricingRequestId);
        return new TwoItemDeal(ticketId, ticketItemAId, ticketItemBId, pricingRequestId, catalogProductIdA, catalogProductIdB);
    }

    /** Same as {@link #driveDraftPricingRequestToQuotationAccepted} but responds to EVERY item on
     * the (single, same-factory) draft rather than assuming exactly one — both items share
     * {@link #FACTORY}, so {@code generateDrafts} produces exactly one draft covering both. */
    private void driveTwoItemDraftToQuotationAccepted(long pricingRequestId) {
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        String email = FACTORY.toLowerCase().replace(" ", "-") + "@example.com";
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest(email, null, null, UUID.randomUUID().toString()), importActor);
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

        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("step 8 costing", null), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = decision.items().stream()
            .map(decisionItem -> new UpdatePricingDecisionItemRequest(decisionItem.id(), null, null, new BigDecimal("1.00"), null))
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

    private BigDecimal ticketItemQty(long itemId) {
        return jdbc.queryForObject(
            "SELECT qty FROM sales.ticket_item WHERE item_id = :id", Map.of("id", itemId), BigDecimal.class);
    }

    private BigDecimal ticketItemQtyDelivered(long itemId) {
        return jdbc.queryForObject(
            "SELECT qty_delivered FROM sales.ticket_item WHERE item_id = :id", Map.of("id", itemId), BigDecimal.class);
    }

    /** Creates a brand-new deal (ticket_item stub qty=1, per every prior step's own fixture
     * convention) and drives its FIRST pricing request, requesting {@code quantity}, all the way
     * to QUOTATION_ACCEPTED via the real Steps 1-5 services — deliberately stopping there
     * (confirmOrder is left to the caller, since some tests need to withhold it). */
    private Deal createDealAndDriveFirstPricingRequestToQuotationAccepted(BigDecimal quantity) {
        long catalogProductId = insertCatalogProduct(FACTORY, "TH",
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

        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile Inventory", "SCG Tile Inventory", null, null,
            "60x60", FACTORY, quantity, quantity, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "step 8 acceptance walk", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveDraftPricingRequestToQuotationAccepted(pricingRequestId, FACTORY, quantity);
        return new Deal(ticketId, ticketItemId, pricingRequestId, catalogProductId);
    }

    /** Creates a customer-change revision on {@code deal}'s ticket item, requesting the NEW
     * {@code quantity}, and drives IT to QUOTATION_ACCEPTED too. Reachable even though the
     * parent pricing request already sits at QUOTATION_ACCEPTED — see
     * OrderConfirmationService#reconcileTicketItems's own Javadoc. */
    private long createRevisionAndDriveToQuotationAccepted(Deal deal, BigDecimal newQuantity) {
        PricingRequestRequests.PricingRequestItemRequest revisedItem = new PricingRequestRequests.PricingRequestItemRequest(
            deal.ticketItemId, deal.catalogProductId, null, "SCG", "Tile Inventory", "SCG Tile Inventory", null, null,
            "60x60", FACTORY, newQuantity, newQuantity, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
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
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        String email = factory.toLowerCase().replace(" ", "-") + "@example.com";
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

        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("step 8 costing", null), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = decision.items().stream()
            .map(decisionItem -> new UpdatePricingDecisionItemRequest(decisionItem.id(), null, null, new BigDecimal("1.00"), null))
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
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
