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
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.procurement.ProcurementDtos.FactoryPurchaseOrderDto;
import th.co.glr.hr.procurement.ProcurementRequests.CreateFactoryPurchaseOrdersRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordGoodsReceivedRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordShippingDetailRequest;
import th.co.glr.hr.procurement.ProcurementRequests.RecordSupplierProformaRequest;
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

/**
 * Real-DB acceptance + authz + cross-tenant coverage for Step 7 (Factory Purchase Order and
 * Import Execution). Drives a deal through the REAL Steps 1-6 services (no shortcuts) to a
 * deposit-paid, import-request-issued deal at {@code DealStage.PROCUREMENT}, then exercises
 * {@link ProcurementService} against that real state.
 */
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
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-procurement-test-uploads");
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
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties);

        costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        costingService = new PricingCostingService(costingRepository, pricingRequests, factoryQuotes, tickets,
            fxRates, new PriceCalcConfigRepository(jdbc), new FactoryConfigRepository(jdbc), notifications);

        decisionRepository = new PricingDecisionRepository(jdbc);
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

        purchaseOrders = new ProcurementRepository(jdbc);
        procurement = new ProcurementService(purchaseOrders, pricingRequests, tickets, notifications);

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
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:factory, :email, 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factory", name, "email", name.toLowerCase().replace(" ", "-") + "@example.com"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Acceptance scenario (end to end, real Postgres) — 2 factories -> 2 POs, traced items,
    // detail recording, and composition with the existing markIrSent/markShipping/
    // markGoodsReceived ticket-level flow.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void fullChain_quotationAcceptedThroughGoodsReceived_sourcesFromCostingAndComposesWithTicketFlow() {
        DealFixture deal = driveToProcurement(true);

        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(UUID.randomUUID().toString()), importActor);
        assertThat(created).hasSize(2);
        assertThat(created).extracting(FactoryPurchaseOrderDto::factoryName)
            .containsExactlyInAnyOrder(FACTORY_A, FACTORY_B);
        assertThat(created).allSatisfy(po -> {
            assertThat(po.status()).isEqualTo(FactoryPurchaseOrderStatus.OPEN);
            assertThat(po.pricingRequestId()).isEqualTo(deal.pricingRequestId);
            assertThat(po.ticketId()).isEqualTo(deal.ticketId);
            assertThat(po.items()).hasSize(1);
        });

        // ── Items trace to the EXACT costing figures, not re-derived ────────────────────────
        for (FactoryPurchaseOrderDto po : created) {
            var item = po.items().get(0);
            Map<String, Object> costingRow = jdbc.queryForMap("""
                SELECT requested_quantity, raw_unit_price, raw_currency
                  FROM sales.pricing_costing_item WHERE pricing_costing_item_id = :id
                """, Map.of("id", item.pricingCostingItemId()));
            assertThat(item.quantity()).isEqualByComparingTo((BigDecimal) costingRow.get("requested_quantity"));
            assertThat(item.unitPrice()).isEqualByComparingTo((BigDecimal) costingRow.get("raw_unit_price"));
            assertThat(item.currency()).isEqualTo(costingRow.get("raw_currency"));
            assertThat(item.lineTotal()).isEqualByComparingTo(item.quantity().multiply(item.unitPrice()));
            assertThat(po.totalAmount()).isEqualByComparingTo(item.lineTotal());
        }

        // ── Idempotent re-create: no duplicate POs, no duplicate items ──────────────────────
        List<FactoryPurchaseOrderDto> secondCall = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(UUID.randomUUID().toString()), importActor);
        assertThat(secondCall).hasSize(2);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_purchase_order WHERE pricing_request_id = :id
            """, Map.of("id", deal.pricingRequestId), Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_purchase_order_item poi
              JOIN sales.factory_purchase_order po ON po.factory_purchase_order_id = poi.factory_purchase_order_id
             WHERE po.pricing_request_id = :id
            """, Map.of("id", deal.pricingRequestId), Long.class)).isEqualTo(2L);

        // ── Supplier proforma + shipping detail + goods received on one PO ──────────────────
        FactoryPurchaseOrderDto poA = created.stream().filter(po -> FACTORY_A.equals(po.factoryName())).findFirst().orElseThrow();
        FactoryPurchaseOrderDto afterProforma = procurement.recordSupplierProforma(poA.id(),
            new RecordSupplierProformaRequest("PI-2026-0001", "30% deposit, 70% before shipment"), importActor);
        assertThat(afterProforma.supplierProformaRef()).isEqualTo("PI-2026-0001");
        assertThat(afterProforma.status()).isEqualTo(FactoryPurchaseOrderStatus.OPEN); // proforma alone does not advance status

        FactoryPurchaseOrderDto afterShipping = procurement.recordShippingDetail(poA.id(),
            new RecordShippingDetailRequest("CONT-1234567", LocalDate.now().plusDays(3), LocalDate.now().plusDays(30), "AWAITING_CLEARANCE"),
            importActor);
        assertThat(afterShipping.status()).isEqualTo(FactoryPurchaseOrderStatus.SHIPPING);
        assertThat(afterShipping.containerRef()).isEqualTo("CONT-1234567");
        assertThat(afterShipping.customsStatus()).isEqualTo("AWAITING_CLEARANCE");

        FactoryPurchaseOrderDto afterReceived = procurement.recordGoodsReceived(poA.id(),
            new RecordGoodsReceivedRequest(new BigDecimal("12345.6789")), importActor);
        assertThat(afterReceived.status()).isEqualTo(FactoryPurchaseOrderStatus.RECEIVED);
        assertThat(afterReceived.actualLandedCostThb()).isEqualByComparingTo("12345.6789");
        assertThat(afterReceived.receivedAt()).isNotNull();
        // The ESTIMATE (Step 2) stays visible on the item, distinct from the actual just recorded.
        assertThat(afterReceived.items().get(0).estimatedTotalLandedCostThb()).isNotNull();
        assertThat(afterReceived.items().get(0).estimatedTotalLandedCostThb())
            .isNotEqualByComparingTo(afterReceived.actualLandedCostThb());

        // A closed PO cannot be touched again.
        assertThatThrownBy(() -> procurement.recordGoodsReceived(poA.id(),
            new RecordGoodsReceivedRequest(BigDecimal.TEN), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // ── Composes with the EXISTING ticket-level fulfillment flow — proven independent:
        //    markIrSent/markShipping/markGoodsReceived are untouched by this branch and remain
        //    reachable purely off ticket.fulfillment_status, regardless of PO state above ────
        TicketDto afterIrSent = ticketService.markIrSent(deal.ticketId, importActor);
        assertThat(afterIrSent.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.IR_SENT);
        TicketDto afterTicketShipping = ticketService.markShipping(deal.ticketId, importActor);
        assertThat(afterTicketShipping.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.SHIPPING);
        TicketDto afterTicketGoodsReceived = ticketService.markGoodsReceived(deal.ticketId, importActor);
        assertThat(afterTicketGoodsReceived.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.GOODS_RECEIVED);
        assertThat(afterTicketGoodsReceived.summary().salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
        // The OTHER PO (factory B) is still OPEN — the ticket-level flag sequence above advanced
        // independently of it, proving the two really are separate layers, not coupled.
        FactoryPurchaseOrderDto poB = created.stream().filter(po -> FACTORY_B.equals(po.factoryName())).findFirst().orElseThrow();
        assertThat(procurement.get(poB.id(), importActor).status()).isEqualTo(FactoryPurchaseOrderStatus.OPEN);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Gate: reachable only from QUOTATION_ACCEPTED + DealStage.PROCUREMENT (as strict as
    // issueImportRequest's own precondition, not weaker).
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    /**
     * Isolates the {@code PricingRequestStatus.QUOTATION_ACCEPTED} guard specifically from the
     * {@code DealStage.PROCUREMENT} guard, since a deal driven by a SINGLE pricing request can
     * never exercise them independently (the ticket cannot reach {@code DealStage.PROCUREMENT}
     * without first going through {@code confirmOrder}, which itself requires {@code
     * QUOTATION_ACCEPTED} — so "before QUOTATION_ACCEPTED" and "before PROCUREMENT" are always
     * true together on a single-pricing-request deal; disabling either guard alone left {@code
     * createPurchaseOrders_beforeQuotationAccepted_isRejected} above green during mutation-check,
     * see the branch handoff's own Authz Evidence table). CLAUDE.md's own model — 1 Deal -&gt; 0..N
     * Pricing Requests — makes the real isolating scenario reachable: a SECOND pricing request on
     * the SAME ticket, left at SUBMITTED (never accepted), while the FIRST pricing request drives
     * the ticket all the way to DealStage.PROCUREMENT. Calling createPurchaseOrders with the
     * second (wrong, non-accepted) pricing request id must be rejected by the QUOTATION_ACCEPTED
     * check alone, even though the ticket itself genuinely sits at PROCUREMENT.
     */
    @Test
    void createPurchaseOrders_ticketAtProcurementButThisPricingRequestNotAccepted_isRejected() {
        DealFixture deal = driveToProcurement(true);
        // The second pricing request is driven all the way to QUOTATION_ISSUED — it DOES have its
        // own APPROVED decision and its own real pricing_costing_item rows, so
        // findApprovedPricingCostingId succeeds and cannot be the thing that blocks this call.
        // Only status != QUOTATION_ACCEPTED (the customer has not yet accepted THIS quotation) can.
        long otherPricingRequestId = addSecondPricingRequestDrivenToQuotationIssued(deal.ticketId);
        assertThat(pricingRequestService.get(otherPricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        assertThatThrownBy(() -> procurement.createPurchaseOrders(otherPricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_purchase_order WHERE pricing_request_id = :id
            """, Map.of("id", otherPricingRequestId), Long.class)).isZero();
    }

    @Test
    void createPurchaseOrders_quotationAcceptedButImportRequestNotYetIssued_isRejected() {
        // Reaches QUOTATION_ACCEPTED and even DEPOSIT_PAID, but deliberately stops short of
        // calling issueImportRequest — the deal never reaches DealStage.PROCUREMENT.
        DealFixture deal = driveToProcurement(false);
        assertThatThrownBy(() -> procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_purchase_order WHERE pricing_request_id = :id
            """, Map.of("id", deal.pricingRequestId), Long.class)).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authorization — Import/CEO only, Sales/account excluded entirely.
    // ─────────────────────────────────────────────────────────────────────────────────────

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
    void createPurchaseOrders_accountActor_cannotCreate() {
        DealFixture deal = driveToProcurement(true);
        assertThatThrownBy(() -> procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void ceoActor_canCreateAndReadPurchaseOrders() {
        DealFixture deal = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> created = procurement.createPurchaseOrders(deal.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), ceoActor);
        assertThat(created).hasSize(2);
        assertThat(procurement.list(null, ceoActor)).isNotEmpty();
    }

    @Test
    void salesActor_cannotReadPurchaseOrders() {
        DealFixture deal = driveToProcurement(true);
        procurement.createPurchaseOrders(deal.pricingRequestId, new CreateFactoryPurchaseOrdersRequest(null), importActor);
        assertThatThrownBy(() -> procurement.listForPricingRequest(deal.pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> procurement.list(null, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Guard: a PO cannot reference a factory-quote/costing item from a DIFFERENT pricing
    // request — cross-tenant/cross-request reference guard, tested directly at the repository
    // layer (the only way to reach it: the service never accepts a caller-supplied item id).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void insertItems_rejectsCostingItemFromADifferentPricingRequest() {
        DealFixture dealA = driveToProcurement(true);
        List<FactoryPurchaseOrderDto> poAList = procurement.createPurchaseOrders(dealA.pricingRequestId,
            new CreateFactoryPurchaseOrdersRequest(null), importActor);
        long poAId = poAList.get(0).id();

        // A second, wholly distinct deal/pricing request/costing — only driven far enough
        // (Steps 1-2) to have its own real pricing_costing_item rows.
        long foreignCostingItemId = createSecondPricingRequestCostingItem();

        int inserted = purchaseOrders.insertItems(poAId, dealA.pricingRequestId, List.of(foreignCostingItemId));
        assertThat(inserted).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.factory_purchase_order_item
             WHERE factory_purchase_order_id = :poId AND pricing_costing_item_id = :itemId
            """, Map.of("poId", poAId, "itemId", foreignCostingItemId), Long.class)).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Mutation-check evidence (verbatim red output recorded in the branch handoff) — see
    // docs/agent-handoffs/96_feat-procurement-factory-order.md "Authz Evidence" for the
    // mutation-check narrative against these tests:
    //   insertItems_rejectsCostingItemFromADifferentPricingRequest  (cross-tenant guard)
    //   createPurchaseOrders_salesActor_cannotCreate / salesActor_cannotReadPurchaseOrders
    //   createPurchaseOrders_ticketAtProcurementButThisPricingRequestNotAccepted_isRejected
    //       (the QUOTATION_ACCEPTED guard, isolated from DealStage.PROCUREMENT — see this test's
    //       own Javadoc: createPurchaseOrders_beforeQuotationAccepted_isRejected above stays GREEN
    //       even with the QUOTATION_ACCEPTED check fully disabled, because on a single-pricing-
    //       request deal the DealStage.PROCUREMENT guard alone already catches the same case; the
    //       two guards are redundant on that fixture, exactly the trap CLAUDE.md warns about, so
    //       it is NOT listed as evidence for the QUOTATION_ACCEPTED guard — only the isolated test
    //       above is)
    //   createPurchaseOrders_quotationAcceptedButImportRequestNotYetIssued_isRejected
    //       (the DealStage.PROCUREMENT guard, independently confirmed red when disabled alone)

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers.
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
        long catalogProductIdA = insertCatalogProduct(factoryA, "TH", "TEST-PO-" + factoryA.hashCode(),
            new BigDecimal("100.00"), "THB", "per_piece");
        long catalogProductIdB = insertCatalogProduct(factoryB, "TH", "TEST-PO-" + factoryB.hashCode(),
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

        PricingRequestRequests.PricingRequestItemRequest itemA = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdA, null, "SCG", "Tile PO A", "SCG Tile PO A", null, null, "60x60", factoryA,
            new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.PricingRequestItemRequest itemB = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdB, null, "SCG", "Tile PO B", "SCG Tile PO B", null, null, "80x80", factoryB,
            new BigDecimal("5"), new BigDecimal("5"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
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

        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("step 7 costing", null), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);

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

    /** A wholly separate deal, driven only through Steps 1-2 (far enough to produce real
     * pricing_costing_item rows) — used solely to obtain a costing-item id that legitimately
     * belongs to a DIFFERENT pricing request, for the cross-tenant guard test. */
    private long createSecondPricingRequestCostingItem() {
        String factory = "Factory PO Foreign";
        insertFactory(factory);
        long catalogProductId = insertCatalogProduct(factory, "TH", "TEST-PO-FOREIGN-" + UUID.randomUUID().toString().substring(0, 8),
            new BigDecimal("50.00"), "THB", "per_piece");
        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Foreign " + UUID.randomUUID() + " จำกัด", "0100000000008", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0008");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Foreign");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Foreign", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile Foreign", factory))),
            salesActor);
        long ticketId = created.summary().id();

        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductId, null, "SCG", "Tile Foreign", "SCG Tile Foreign", null, null, "60x60", factory,
            new BigDecimal("3"), new BigDecimal("3"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("500.00"), "THB", "cross-tenant guard fixture", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest(factory.toLowerCase().replace(" ", "-") + "@example.com", null, null,
                UUID.randomUUID().toString()), importActor);
        drainDispatches();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-FOREIGN", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("3.00"), "piece", UnitBasis.PER_PIECE,
                new BigDecimal("50.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("foreign costing", null), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        PricingCostingDto submitted = costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);
        return submitted.items().get(0).id();
    }

    /** A second pricing request on an EXISTING ticket (CLAUDE.md's own "1 Deal -&gt; 0..N Pricing
     * Requests" model), driven all the way through Steps 1-4 to QUOTATION_ISSUED — it has its own
     * real APPROVED pricing_decision and its own pricing_costing_item rows, so it is NOT missing
     * anything createPurchaseOrders' costing lookup needs. Only the customer's ACCEPTED outcome
     * (Step 5) is deliberately withheld, isolating the QUOTATION_ACCEPTED status guard (see the
     * test that calls this for the full reasoning). */
    private long addSecondPricingRequestDrivenToQuotationIssued(long ticketId) {
        String factory = FACTORY_A;
        long catalogProductId = insertCatalogProduct(factory, "TH",
            "TEST-PO-2ND-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductId, null, "SCG", "Tile 2nd PR", "SCG Tile 2nd PR", null, null, "60x60", factory,
            new BigDecimal("2"), new BigDecimal("2"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
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
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest(factory.toLowerCase().replace(" ", "-") + "@example.com", null, null,
                UUID.randomUUID().toString()), importActor);
        drainDispatches();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-2ND", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("2.00"), "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("2nd pr costing", null), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto decisionItem = decision.items().get(0);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
            new UpdatePricingDecisionItemRequest(decisionItem.id(), null, null, new BigDecimal("1.00"), null))), ceoActor);
        decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);

        CustomerQuotationDto draftQuotation = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()), salesActor);
        quotationService.issue(draftQuotation.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        return pricingRequestId;
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
