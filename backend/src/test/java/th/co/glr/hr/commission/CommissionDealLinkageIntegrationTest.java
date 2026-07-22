package th.co.glr.hr.commission;

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
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
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
import th.co.glr.hr.notification.NotificationService;
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
import th.co.glr.hr.ticket.CompleteDeliveryRequest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.StockReservationRequest;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Step 9 (final payment / closeout / commission gate) — real-DB acceptance coverage.
 *
 * <p>Two things had never been proven against real Postgres before this step: (1) that a chain
 * deal (PricingRequest → ... → delivery, Steps 1-8) can actually reach {@link
 * DealStage#CLOSED_PAID} end-to-end — final payment recorded on top of full delivery — and (2)
 * that {@link CommissionService#submit} genuinely gates and cross-checks a commission submission
 * against that deal's real state, not just against a Mockito stub. This class drives the real
 * Steps 1-8 services (same fixture pattern as {@code InventoryDeliveryFulfilmentIntegrationTest})
 * one step further, through real payment, into a real {@link CommissionService} backed by a real
 * {@link CommissionRepository} and {@link TicketRepository}.
 *
 * <p>{@link AuditService} and {@link NotificationService} are mocked deliberately — they are
 * side effects of the gate decision (an already-audited, already-notified business action per
 * {@code NotificationService}'s own class comment), not the SQL behavior under test here. Every
 * other collaborator that participates in either the gate/cross-check decision or its persistence
 * (TicketRepository, CommissionRepository, CommissionCalculator) is real.
 */
class CommissionDealLinkageIntegrationTest extends AbstractPostgresIntegrationTest {
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
    private CommissionRepository commissions;
    private CommissionService commissionService;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private long accountUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;

    private static final String FACTORY = "Factory Commission Linkage";

    @BeforeEach
    void wireEveryStepsServiceAndCreateFactory() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notificationRepository = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-commission-linkage-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notificationRepository, objectMapper, new ContactRepository(jdbc), fileStorage);

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
            new FactoryConfigRepository(jdbc), factoryEmail, notificationRepository, fileStorage, dispatchProperties);

        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        costingService = new PricingCostingService(costingRepository, pricingRequests, factoryQuotes, tickets,
            fxRates, new PriceCalcConfigRepository(jdbc), new FactoryConfigRepository(jdbc), notificationRepository);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notificationRepository);

        PriceCalcService priceCalcMock = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notificationRepository, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notificationRepository);

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notificationRepository,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer());

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notificationRepository);

        commissions = new CommissionRepository(jdbc);
        CommissionAttachmentRepository commissionAttachments = new CommissionAttachmentRepository(jdbc);
        AuditService auditService = mock(AuditService.class);
        NotificationService notificationService = mock(NotificationService.class);
        commissionService = new CommissionService(commissions, commissionAttachments, new CommissionCalculator(),
            new FileStorageService("/tmp/glr-commission-linkage-test-invoices"), auditService, notificationService,
            tickets, new AttachmentRepository(jdbc));

        salesRepId = createEmployee(employees, "พนักงานขาย เก้า", "sales-step9@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า เก้า", "import-step9@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร เก้า", "ceo-step9@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี เก้า", "account-step9@glr.co.th", "ACCT", "ฝ่ายบัญชี");
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
    // (1) The pipeline itself: a chain deal genuinely reaches CLOSED_PAID end-to-end. Never
    // proven by an integration test before Step 9 — see class Javadoc.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void fullChainDeal_reachesClosedPaid_afterFullDeliveryAndFinalPayment() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));

        TicketDto after = ticketService.get(ticketId, ceoActor);
        assertThat(after.summary().salesStage()).isEqualTo(DealStage.CLOSED_PAID);
        assertThat(after.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(after.summary().paymentStatus()).isEqualTo("FULLY_PAID");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // (2) The gate: a commission cannot be submitted against a deal that hasn't reached
    // CLOSED_PAID yet — rejected outright, no commission_record row created.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void submit_rejectsWhenLinkedDealHasNotReachedClosedPaid_andCreatesNoRecord() {
        long ticketId = driveDealThroughDeliveryOnly(new BigDecimal("10")); // DELIVERED, not yet CLOSED_PAID
        int before = countCommissionRecords();

        SubmitCommissionRequest request = submitRequestLinkedTo(ticketId, new BigDecimal("1000.00"));

        assertThatThrownBy(() -> commissionService.submit(request, invoiceFile(), accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // (3) Cross-check within threshold: succeeds, no mismatch flag, snapshot recorded.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void submit_withGrossAmountWithinThreshold_succeedsWithNoMismatchFlag() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        BigDecimal payable = tickets.payableAmount(ticketId);
        assertThat(payable.signum()).isPositive();
        // 2% above payable — within the 5% cross-check threshold.
        BigDecimal grossAmount = payable.multiply(new BigDecimal("1.02")).setScale(2, java.math.RoundingMode.HALF_UP);

        SubmitCommissionRequest request = submitRequestLinkedTo(ticketId, grossAmount);
        CommissionRecord created = commissionService.submit(request, invoiceFile(), accountActor);

        assertThat(created.sourceTicketId()).isEqualTo(ticketId);
        assertThat(created.dealAmountMismatch()).isFalse();
        assertThat(created.dealPayableAmountSnapshot()).isEqualByComparingTo(payable);

        CommissionRecord reloaded = commissions.findById(created.id()).orElseThrow();
        assertThat(reloaded.dealAmountMismatch()).isFalse();
        assertThat(reloaded.dealPayableAmountSnapshot()).isEqualByComparingTo(payable);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // (4) Cross-check beyond threshold: NOT blocked, but flagged for reviewers.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void submit_withGrossAmountBeyondThreshold_succeedsButFlagsMismatch() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        BigDecimal payable = tickets.payableAmount(ticketId);
        assertThat(payable.signum()).isPositive();
        // 25% above payable — well beyond the 5% threshold.
        BigDecimal grossAmount = payable.multiply(new BigDecimal("1.25")).setScale(2, java.math.RoundingMode.HALF_UP);

        SubmitCommissionRequest request = submitRequestLinkedTo(ticketId, grossAmount);
        CommissionRecord created = commissionService.submit(request, invoiceFile(), accountActor);

        assertThat(created.dealAmountMismatch()).isTrue();
        assertThat(created.dealPayableAmountSnapshot()).isEqualByComparingTo(payable);
        assertThat(created.status()).isEqualTo(CommissionStatus.SUBMITTED); // not blocked

        CommissionRecord reloaded = commissions.findById(created.id()).orElseThrow();
        assertThat(reloaded.dealAmountMismatch()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // (5) Regression guard: an unlinked (sourceTicketId = null) commission keeps working exactly
    // as it did before Step 9 — this must never start requiring a linked deal.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void submit_withNoLinkedDeal_stillSucceeds_snapshotNullMismatchFalse() {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-STEP9-UNLINKED-" + UUID.randomUUID().toString().substring(0, 8),
            LocalDate.of(2026, 6, 15), new BigDecimal("1000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);

        CommissionRecord created = commissionService.submit(request, invoiceFile(), accountActor);

        assertThat(created.sourceTicketId()).isNull();
        assertThat(created.dealPayableAmountSnapshot()).isNull();
        assertThat(created.dealAmountMismatch()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private SubmitCommissionRequest submitRequestLinkedTo(long ticketId, BigDecimal grossAmount) {
        return new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-STEP9-" + UUID.randomUUID().toString().substring(0, 8),
            LocalDate.of(2026, 6, 15), grossAmount,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private MockMultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());
    }

    private int countCommissionRecords() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.commission_record", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    /** Drives a brand-new deal all the way to {@link DealStage#CLOSED_PAID}: full chain
     * (Steps 1-6) to QUOTATION_ACCEPTED, confirmOrder, deposit paid, reserve+deliver the full
     * quantity from stock (Step 8), then confirmFinalPayment records the remaining balance and
     * the CLOSED_PAID auto-advance fires (paymentFullyPaid AND FULLY_DELIVERED). */
    private long driveDealToClosedPaid(BigDecimal quantity) {
        long ticketId = driveDealThroughDeliveryOnly(quantity);
        ticketService.confirmFinalPayment(ticketId, accountActor);
        return ticketId;
    }

    /** Same as {@link #driveDealToClosedPaid} but stops right after delivery completes —
     * DealStage.DELIVERED / FulfilmentStatus.FULLY_DELIVERED, deposit paid but final balance not
     * yet collected, so the deal is deliberately NOT YET at CLOSED_PAID. Used by the "gate
     * rejects a not-yet-closed deal" test. */
    private long driveDealThroughDeliveryOnly(BigDecimal quantity) {
        long catalogProductId = insertCatalogProduct(FACTORY, "TH",
            "TEST-COMM-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Commission " + UUID.randomUUID() + " จำกัด", "0100000000009", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0009");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Commission");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Commission", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile Commission", FACTORY))),
            salesActor);
        long ticketId = created.summary().id();
        long ticketItemId = created.items().get(0).id();

        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile Commission", "SCG Tile Commission", null, null,
            "60x60", FACTORY, quantity, quantity, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "step 9 closeout walk", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveDraftPricingRequestToQuotationAccepted(pricingRequestId, FACTORY, quantity);

        OrderConfirmationResultDto confirmed = orderConfirmation.confirmOrder(pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(confirmed).isNotNull();

        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto issuedNotice = depositNoticeService.issue(draftNotice.id(), salesActor);
        assertThat(issuedNotice.status()).isEqualTo("ISSUED");
        ticketService.confirmDepositPaid(ticketId, accountActor);

        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(
                new StockReservationRequest.Line(ticketItemId, quantity, "จองครบจากสต็อก"))),
            importActor);
        TicketDto delivered = ticketService.completeDelivery(
            ticketId, new CompleteDeliveryRequest("ส่งครบ", "คุณลูกค้า"), importActor);
        assertThat(delivered.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(delivered.summary().salesStage()).isEqualTo(DealStage.DELIVERED);
        assertThat(delivered.summary().salesStage()).isNotEqualTo(DealStage.CLOSED_PAID);

        return ticketId;
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
            new CreateCostingRequest("step 9 costing", null), importActor);
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
