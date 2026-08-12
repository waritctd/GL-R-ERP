package th.co.glr.hr.procurement;

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
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingService;
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
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.procurement.ProcurementDtos.FactoryPurchaseOrderDto;
import th.co.glr.hr.procurement.ProcurementRequests.CancelFactoryPurchaseOrderRequest;
import th.co.glr.hr.procurement.ProcurementRequests.CreateFactoryPurchaseOrdersRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordGoodsReceivedRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordShippingDetailRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CompleteDeliveryRequest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Real-DB coverage for the factory-PO -&gt; ticket import-status rollup ({@code
 * TicketRepository#deriveImportStatus}, {@code TicketService#applyPurchaseOrderRollup}, and the
 * refusal guard the ticket-level {@code markShipping}/{@code markGoodsReceived} setters now carry).
 * This is a permission/behaviour change (a role that previously succeeded now gets a 409 on a
 * PO-tracked deal), so per CLAUDE.md it ships as a real-DB integration test through the real
 * services, never mocks — see {@code AbstractPostgresIntegrationTest}.
 *
 * <p>Fixture wiring is copied from {@link ProcurementServiceIntegrationTest} (read that class
 * first): the same {@code @BeforeEach} hand-wiring of Steps 1-7's real services, and the same
 * {@code driveToProcurement}/{@code driveToQuotationIssuedNotYetAccepted} two-factory fixture,
 * which is exactly what a rollup test needs — TWO factory purchase orders on ONE ticket.
 */
class DerivedFulfilmentRollupIntegrationTest extends AbstractPostgresIntegrationTest {
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

    private static final String FACTORY_A = "Factory Rollup A";
    private static final String FACTORY_B = "Factory Rollup B";

    @BeforeEach
    void wireEverySevenStepsServiceAndCreateFactories() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-derived-fulfilment-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage);

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
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
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        // V141 ("CEO owns costing"): shared by FactoryQuoteService's markReadyForCosting
        // auto-advance check and PricingDecisionService's startReview/recalculateCost.
        th.co.glr.hr.pricingcosting.LandedCostCalculator landedCostCalculator =
            new th.co.glr.hr.pricingcosting.LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
                new PriceCalcConfigRepository(jdbc), new FactoryConfigRepository(jdbc));
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);

        costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing
        // create/recalculate/submit is gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);

        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator);

        PriceCalcService priceCalcMock = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications);

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        purchaseOrders = new ProcurementRepository(jdbc);
        // The one line this branch actually adds to the fixture, vs ProcurementServiceIntegrationTest:
        // ProcurementService now takes ticketService too, so it can call applyPurchaseOrderRollup.
        procurement = new ProcurementService(purchaseOrders, pricingRequests, tickets, notifications, ticketService);

        salesRepId = createEmployee(employees, "พนักงานขาย รอลอัพ", "sales-rollup@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า รอลอัพ", "import-rollup@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร รอลอัพ", "ceo-rollup@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี รอลอัพ", "account-rollup@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");

        insertFactory(FACTORY_A);
        insertFactory(FACTORY_B);
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
    // Refusal guard — wrong-way-round: the caller must NOT be able to do the thing they
    // shouldn't, and the row must NOT have changed, not merely that a throw happened.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void importCannotMarkGoodsReceivedWhileTheOtherFactoryIsStillShipping() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        FactoryPurchaseOrderDto poA = poFor(created, FACTORY_A);

        procurement.recordShippingDetail(poA.id(),
            new RecordShippingDetailRequest("CONT-0001", LocalDate.now().plusDays(3), LocalDate.now().plusDays(30), "AWAITING_CLEARANCE"),
            importActor);
        procurement.recordGoodsReceived(poA.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        // PO B (factory B) is left untouched — still OPEN — so not every live PO is received.

        assertThatThrownBy(() -> ticketService.markGoodsReceived(deal.ticketId, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Not merely "did not become GOODS_RECEIVED" — pin the exact value the PO rollup already
        // wrote (SHIPPING), proving the refused call really left the row untouched.
        TicketSummaryDto after = tickets.findById(deal.ticketId).orElseThrow().summary();
        assertThat(after.fulfillmentStatus()).isEqualTo(FulfilmentStatus.SHIPPING);
    }

    @Test
    void ceoCannotMarkGoodsReceivedWhileTheOtherFactoryIsStillShipping() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        FactoryPurchaseOrderDto poA = poFor(created, FACTORY_A);

        procurement.recordShippingDetail(poA.id(),
            new RecordShippingDetailRequest("CONT-0002", LocalDate.now().plusDays(3), LocalDate.now().plusDays(30), "AWAITING_CLEARANCE"),
            importActor);
        procurement.recordGoodsReceived(poA.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);

        // CEO is the OTHER member of FULFILMENT_ROLES — the refusal must not be import-only.
        assertThatThrownBy(() -> ticketService.markGoodsReceived(deal.ticketId, ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        TicketSummaryDto after = tickets.findById(deal.ticketId).orElseThrow().summary();
        assertThat(after.fulfillmentStatus()).isEqualTo(FulfilmentStatus.SHIPPING);
    }

    @Test
    void importCannotMarkShippingOnAPoTrackedDeal() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        // Neither PO has been progressed — both still OPEN, so the PO rollup itself currently
        // asserts nothing (deriveImportStatus would return null). The refusal must fire anyway:
        // it is unconditional on PO presence, not "only when the PO rollup disagrees" (design
        // decision 4) — a ticket-level write that happens to agree by coincidence still bypasses
        // the PO record as the source of truth.

        assertThatThrownBy(() -> ticketService.markShipping(deal.ticketId, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        TicketSummaryDto after = tickets.findById(deal.ticketId).orElseThrow().summary();
        assertThat(after.fulfillmentStatus()).isEqualTo(FulfilmentStatus.IR_ISSUED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The rollup itself.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void rollupMovesTicketToShippingWhenOnlyOneFactoryHasShipped() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        FactoryPurchaseOrderDto poA = poFor(created, FACTORY_A);

        procurement.recordShippingDetail(poA.id(),
            new RecordShippingDetailRequest("CONT-0003", LocalDate.now().plusDays(3), LocalDate.now().plusDays(30), null),
            importActor);

        TicketSummaryDto after = tickets.findById(deal.ticketId).orElseThrow().summary();
        assertThat(after.fulfillmentStatus()).isEqualTo(FulfilmentStatus.SHIPPING);
        assertThat(after.fulfillmentStatus()).isNotEqualTo(FulfilmentStatus.GOODS_RECEIVED);
    }

    @Test
    void rollupMovesTicketToGoodsReceivedOnlyWhenEveryLivePoIsReceived() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        FactoryPurchaseOrderDto poA = poFor(created, FACTORY_A);
        FactoryPurchaseOrderDto poB = poFor(created, FACTORY_B);

        procurement.recordGoodsReceived(poA.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        procurement.recordGoodsReceived(poB.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);

        TicketSummaryDto after = tickets.findById(deal.ticketId).orElseThrow().summary();
        assertThat(after.fulfillmentStatus()).isEqualTo(FulfilmentStatus.GOODS_RECEIVED);
        assertThat(after.salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        // driveToProcurement always reaches DEPOSIT_PAID before issueImportRequest, so
        // applyGoodsReceived's payment-track branch is exercised too, not just the fulfillment write.
        assertThat(after.paymentStatus()).isEqualTo("AWAITING_FINAL_PAYMENT");
        // A permanent event, not just the current status column — this is what
        // TicketService#warehouseDeliveryAvailable keys on to unlock WAREHOUSE-source delivery.
        assertThat(tickets.hasReceivedGoods(deal.ticketId)).isTrue();
    }

    @Test
    void cancellingTheLastOutstandingPoCompletesTheRollup() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        FactoryPurchaseOrderDto poA = poFor(created, FACTORY_A);
        FactoryPurchaseOrderDto poB = poFor(created, FACTORY_B);

        procurement.recordGoodsReceived(poA.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        // Still SHIPPING here — B is live and OPEN, so not every live PO is received yet.
        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().fulfillmentStatus())
            .isEqualTo(FulfilmentStatus.SHIPPING);

        procurement.cancel(poB.id(), new CancelFactoryPurchaseOrderRequest("เปลี่ยนโรงงานใหม่"), importActor);

        // B is now CANCELLED, i.e. no longer "live" — the only live PO (A) is RECEIVED, so the
        // rollup completes even though the deal now has an unreceived PO on it (it just isn't live).
        TicketSummaryDto after = tickets.findById(deal.ticketId).orElseThrow().summary();
        assertThat(after.fulfillmentStatus()).isEqualTo(FulfilmentStatus.GOODS_RECEIVED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The close-gate protection (delivery-axis firewall).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aLatePoMutationCannotRewriteFullyDelivered() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        FactoryPurchaseOrderDto poA = poFor(created, FACTORY_A);
        FactoryPurchaseOrderDto poB = poFor(created, FACTORY_B);

        procurement.recordGoodsReceived(poA.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        procurement.recordGoodsReceived(poB.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().fulfillmentStatus())
            .isEqualTo(FulfilmentStatus.GOODS_RECEIVED);

        TicketDto afterDelivery = ticketService.completeDelivery(
            deal.ticketId, new CompleteDeliveryRequest(null, null), importActor);
        assertThat(afterDelivery.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);

        // Both POs are now RECEIVED — a terminal (CLOSED) PO status, so neither
        // recordShippingDetail/recordGoodsReceived/cancel can legitimately run again through
        // ProcurementService (its own compare-and-set guards refuse a closed PO with 409, before
        // ever reaching the rollup call). The only way left to exercise the exact scenario the
        // delivery-axis firewall exists for — a rollup call arriving after the ticket has already
        // left the import axis — is to call applyPurchaseOrderRollup directly, as the plan for
        // this branch anticipates.
        ticketService.applyPurchaseOrderRollup(deal.ticketId, importActor);

        TicketSummaryDto afterLateRollup = tickets.findById(deal.ticketId).orElseThrow().summary();
        assertThat(afterLateRollup.fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Non-regression: a deal with NO purchase orders keeps using the ticket-level setters.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aStockOnlyDealWithNoPurchaseOrdersStillUsesTheTicketLevelSetters() {
        DealFixture deal = driveToProcurement(true);
        // Deliberately never call procurement.createPurchaseOrders — zero rows in
        // sales.factory_purchase_order for this ticket, confirmed via the real repository below,
        // not assumed.
        assertThat(tickets.hasLivePurchaseOrders(deal.ticketId)).isFalse();

        ticketService.markIrSent(deal.ticketId, importActor);
        TicketDto afterShipping = ticketService.markShipping(deal.ticketId, importActor);
        assertThat(afterShipping.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.SHIPPING);

        TicketDto afterGoodsReceived = ticketService.markGoodsReceived(deal.ticketId, importActor);
        assertThat(afterGoodsReceived.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.GOODS_RECEIVED);
        assertThat(afterGoodsReceived.summary().salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The close gate itself, on a PO-tracked multi-factory deal.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void multiFactoryDealCanStillClose() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        FactoryPurchaseOrderDto poA = poFor(created, FACTORY_A);
        FactoryPurchaseOrderDto poB = poFor(created, FACTORY_B);

        procurement.recordGoodsReceived(poA.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        procurement.recordGoodsReceived(poB.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        ticketService.completeDelivery(deal.ticketId, new CompleteDeliveryRequest(null, null), importActor);
        ticketService.confirmFinalPayment(deal.ticketId, accountActor);
        insertInvoiceAttachment(deal.ticketId, accountUserId);

        ticketService.confirmCloseReady(deal.ticketId, accountActor);
        TicketDto closed = ticketService.verifyClose(deal.ticketId, ceoActor);

        assertThat(closed.summary().status()).isEqualTo(TicketStatus.CLOSED);
        assertThat(closed.summary().lifecycle()).isEqualTo(DealLifecycle.COMPLETED);
    }

    @Test
    void cannotCloseWhileAFactoryHasNotDelivered() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThat(created).hasSize(2);
        FactoryPurchaseOrderDto poA = poFor(created, FACTORY_A);
        FactoryPurchaseOrderDto poB = poFor(created, FACTORY_B);

        // Goods reached GLR's own warehouse (GOODS_RECEIVED)...
        procurement.recordGoodsReceived(poA.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        procurement.recordGoodsReceived(poB.id(), new RecordGoodsReceivedRequest(BigDecimal.TEN, null), importActor);
        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().fulfillmentStatus())
            .isEqualTo(FulfilmentStatus.GOODS_RECEIVED);
        // ...and everything else about closing is made ready...
        ticketService.confirmFinalPayment(deal.ticketId, accountActor);
        insertInvoiceAttachment(deal.ticketId, accountUserId);
        // ...but the goods were never delivered onward to the customer. deliveryGateComplete
        // requires FULLY_DELIVERED specifically — GOODS_RECEIVED must not be accepted as
        // "delivered" (see TicketService#deliveryGateComplete's own Javadoc: this predicate used
        // to wrongly accept GOODS_RECEIVED too, and that was a real bug, not a style choice).
        assertThatThrownBy(() -> ticketService.confirmCloseReady(deal.ticketId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(tickets.findById(deal.ticketId).orElseThrow().summary().closeConfirmedAt()).isNull();
    }

    private void insertInvoiceAttachment(long ticketId, long uploadedBy) {
        jdbc.update("""
            INSERT INTO sales.attachment (ticket_id, file_name, file_path, attach_type, uploaded_by)
            VALUES (:ticketId, 'invoice.pdf', '/tmp/derived-fulfilment-test-invoice.pdf', 'INVOICE', :uploadedBy)
            """, Map.of("ticketId", ticketId, "uploadedBy", uploadedBy));
    }

    private FactoryPurchaseOrderDto poFor(List<FactoryPurchaseOrderDto> pos, String factoryName) {
        return pos.stream().filter(po -> factoryName.equals(po.factoryName())).findFirst().orElseThrow();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers — copied from ProcurementServiceIntegrationTest (read that class for the
    // full step-by-step rationale); a two-factory deal is what a PO rollup test needs, and
    // driveToQuotationIssuedNotYetAccepted already produces exactly that.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private record DealFixture(long ticketId, long pricingRequestId) {}

    /** Drives a brand-new deal all the way to {@code DealStage.PROCUREMENT} via the REAL
     * Steps 1-6 services: QUOTATION_ACCEPTED -&gt; confirmOrder -&gt; deposit notice issued -&gt;
     * confirmDepositPaid -&gt; (optionally) issueImportRequest. Two items, two distinct
     * factories, so downstream tests can assert "2 factories -&gt; 2 POs" without a second
     * fixture. */
    private DealFixture driveToProcurement(boolean issueImportRequest) {
        DealFixture deal = driveToQuotationIssuedNotYetAccepted(FACTORY_A, FACTORY_B);
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
            TicketDto afterIr = ticketService.issueImportRequest(deal.ticketId, importActor);
            assertThat(afterIr.summary().salesStage()).isEqualTo(DealStage.PROCUREMENT);
            assertThat(afterIr.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.IR_ISSUED);
        }
        return deal;
    }

    private DealFixture driveToQuotationIssuedNotYetAccepted(String factoryA, String factoryB) {
        long catalogProductIdA = insertCatalogProduct(factoryA, "TH", "TEST-ROLLUP-" + factoryA.hashCode(),
            new BigDecimal("100.00"), "THB", "per_piece");
        long catalogProductIdB = insertCatalogProduct(factoryB, "TH", "TEST-ROLLUP-" + factoryB.hashCode(),
            new BigDecimal("200.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Rollup " + UUID.randomUUID() + " จำกัด", "0100000000009", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0009");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Rollup");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Rollup", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(
                    ticketItem("SCG", "Tile Rollup A", factoryA), ticketItem("SCG", "Tile Rollup B", factoryB))),
            salesActor);
        long ticketId = created.summary().id();

        PricingRequestRequests.PricingRequestItemRequest itemA = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdA, null, "SCG", "Tile Rollup A", "SCG Tile Rollup A", null, null, "60x60", factoryA,
            new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.PricingRequestItemRequest itemB = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdB, null, "SCG", "Tile Rollup B", "SCG Tile Rollup B", null, null, "80x80", factoryB,
            new BigDecimal("5"), new BigDecimal("5"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "derived-fulfilment rollup fixture", UUID.randomUUID().toString(), List.of(itemA, itemB));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(drafts).hasSize(2);
        for (FactoryQuoteDto draft : drafts) {
            long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
            String email = draft.factoryName().toLowerCase().replace(" ", "-") + "@example.com";
            factoryQuoteService.send(draft.id(),
                new SendFactoryQuoteRequest(email, null, null, UUID.randomUUID().toString()), importActor);
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
        // V141 ("CEO owns costing"): markReadyForCosting auto-advances the request straight to
        // READY_FOR_CEO_REVIEW the moment the LAST factory quote is ready — Import no longer
        // creates/recalculates/submits a costing of its own; the CEO's startReview below computes it.

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = decision.items().stream()
            .map(item -> new UpdatePricingDecisionItemRequest(item.id(), null, null, new BigDecimal("1.00"), null))
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
