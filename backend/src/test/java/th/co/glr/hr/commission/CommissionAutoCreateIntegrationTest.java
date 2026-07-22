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
import org.springframework.web.multipart.MultipartFile;
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
 * Commission redesign Slice A2 — real-DB acceptance coverage for the accountant auto-create
 * trigger (sales does nothing to get commission) and, per CLAUDE.md's "Permission changes must
 * ship evidence", the AUTHZ change that goes with it: sales can no longer create/submit a
 * commission at all.
 *
 * <p>Drives a real chain deal (Steps 1-8, same fixture as {@link CommissionDealLinkageIntegrationTest})
 * to {@link DealStage#CLOSED_PAID}, then through the real {@link CommissionService}, backed by a
 * real {@link CommissionRepository}, {@link TicketRepository}, and {@link AttachmentRepository}
 * against real Postgres. {@link AuditService} and {@link NotificationService} are mocked
 * deliberately — side effects of the decision, not the SQL/authz behavior under test.
 *
 * <p>Every authz case here is written the wrong way round (CLAUDE.md): "sales cannot reach this"
 * is the assertion that matters, not "account can reach their own". See {@code
 * createFromDealAsSales_isForbidden_andCreatesNoCommissionRecord} for the mutation-check record.
 */
class CommissionAutoCreateIntegrationTest extends AbstractPostgresIntegrationTest {
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

    private long importUserId;
    private long ceoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;

    private static final String FACTORY = "Factory Commission AutoCreate";

    @BeforeEach
    void wireEveryStepsServiceAndCreateFactory() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notificationRepository = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-commission-autocreate-test-uploads");
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
            new FileStorageService("/tmp/glr-commission-autocreate-test-invoices"), auditService, notificationService,
            tickets, new AttachmentRepository(jdbc));

        long salesRepId = createEmployee(employees, "พนักงานขาย สิบ", "sales-a2@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า สิบ", "import-a2@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร สิบ", "ceo-a2@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี สิบ", "account-a2@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการขาย สิบ", "salesmgr-a2@glr.co.th", "SA", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

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
    // Happy chain: account creates → SUBMITTED, owner = deal's rep (not the accountant) →
    // sales_manager edits + reason recomputes → sales_manager approve → MANAGER_APPROVED →
    // ceo approve → APPROVED. Also proves the same upload flips the ticket's invoiceOnFile.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createFromDeal_happyChain_thenManagerEditsAndBothApprovalsSucceed() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        long dealOwnerId = ticketService.get(ticketId, ceoActor).summary().createdById();
        assertThat(dealOwnerId).isNotEqualTo(accountUserId); // sanity: distinct people

        CommissionRecord created = commissionService.createFromDeal(
            ticketId, "INV-A2-" + UUID.randomUUID().toString().substring(0, 8), LocalDate.of(2026, 6, 15),
            null, null, null, null, null, null, null, null, invoiceFile(), accountActor);

        assertThat(created.status()).isEqualTo(CommissionStatus.SUBMITTED);
        // The commission belongs to the deal's OWNER, never to the accountant who created it.
        assertThat(created.salesRepId()).isEqualTo(dealOwnerId);
        assertThat(created.salesRepId()).isNotEqualTo(accountUserId);

        // Same upload also satisfies the ticket's three-party close gate.
        TicketDto afterUpload = ticketService.get(ticketId, ceoActor);
        assertThat(afterUpload.summary().invoiceOnFile()).isTrue();
        assertThat(tickets.hasInvoiceAttachment(ticketId)).isTrue();

        UpdateCommissionDeductionsRequest edit = new UpdateCommissionDeductionsRequest(
            null, new BigDecimal("500.00"), null, null, null, null, null, null, null,
            "ธนาคารหักค่าธรรมเนียมเพิ่มตามใบแจ้งยอด");
        CommissionRecord edited = commissionService.updateDeductions(created.id(), edit, salesManagerActor);
        assertThat(edited.invoiceDetails().bankFees()).isEqualByComparingTo(new BigDecimal("500.00"));
        // Final amount is always the recomputed one, never a directly-set number.
        assertThat(edited.commissionableBase()).isNotEqualByComparingTo(created.commissionableBase());

        CommissionRecord managerApproved = commissionService.approve(created.id(), salesManagerActor);
        assertThat(managerApproved.status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);

        CommissionRecord approved = commissionService.approve(created.id(), ceoActor);
        assertThat(approved.status()).isEqualTo(CommissionStatus.APPROVED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Wrong-way-round AUTHZ (the tests that matter): sales cannot create, and cannot approve.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * MUTATION-CHECK RECORD (per CLAUDE.md "Permission changes must ship evidence"):
     * temporarily changed {@code CommissionService.CREATE_FROM_DEAL_ROLES} from
     * {@code Set.of("account")} to {@code Set.of("account", "sales")} (reintroducing the
     * vulnerability this test guards against) — this specific test went red (expected FORBIDDEN,
     * got a created SUBMITTED commission instead) and no other test in the suite flipped. Reverted
     * the change; `git diff` against the pre-mutation tree was empty afterwards.
     */
    @Test
    void createFromDealAsSales_isForbidden_andCreatesNoCommissionRecord() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createFromDeal(
                ticketId, "INV-A2-SALES-" + UUID.randomUUID().toString().substring(0, 8),
                LocalDate.of(2026, 6, 15), null, null, null, null, null, null, null, null,
                invoiceFile(), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(countCommissionRecords()).isEqualTo(before);
        assertThat(tickets.hasInvoiceAttachment(ticketId)).isFalse();
    }

    @Test
    void submitAsSales_isForbidden_andCreatesNoCommissionRecord() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        int before = countCommissionRecords();
        SubmitCommissionRequest request = submitRequestLinkedTo(ticketId, new BigDecimal("1000.00"));

        assertThatThrownBy(() -> commissionService.submit(request, invoiceFile(), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    @Test
    void salesCannotApprove_evenAManagerApprovedCommission() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        CommissionRecord created = commissionService.createFromDeal(
            ticketId, "INV-A2-" + UUID.randomUUID().toString().substring(0, 8), LocalDate.of(2026, 6, 15),
            null, null, null, null, null, null, null, null, invoiceFile(), accountActor);
        commissionService.approve(created.id(), salesManagerActor); // -> MANAGER_APPROVED

        assertThatThrownBy(() -> commissionService.approve(created.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        CommissionRecord stillManagerApproved = commissions.findById(created.id()).orElseThrow();
        assertThat(stillManagerApproved.status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // grossAmount defaulting: omitted → the deal's real payable amount; provided → used as given.
    // Two separate deals — createFromDeal only allows one live commission per deal.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createFromDeal_omittedGrossAmount_defaultsFromDealPayable() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        BigDecimal payable = tickets.payableAmount(ticketId);
        assertThat(payable.signum()).isPositive();

        CommissionRecord created = commissionService.createFromDeal(
            ticketId, "INV-A2-DEFAULT-" + UUID.randomUUID().toString().substring(0, 8),
            LocalDate.of(2026, 6, 15), null, null, null, null, null, null, null, null,
            invoiceFile(), accountActor);

        assertThat(created.invoiceDetails().grossAmount()).isEqualByComparingTo(payable);
        assertThat(created.dealPayableAmountSnapshot()).isEqualByComparingTo(payable);
        assertThat(created.dealAmountMismatch()).isFalse();
    }

    @Test
    void createFromDeal_providedGrossAmount_isUsedAsGiven_insteadOfDealPayable() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        BigDecimal payable = tickets.payableAmount(ticketId);
        BigDecimal override = payable.add(new BigDecimal("50000.00")); // deliberately different + beyond threshold

        CommissionRecord created = commissionService.createFromDeal(
            ticketId, "INV-A2-OVERRIDE-" + UUID.randomUUID().toString().substring(0, 8),
            LocalDate.of(2026, 6, 15), override, null, null, null, null, null, null, null,
            invoiceFile(), accountActor);

        assertThat(created.invoiceDetails().grossAmount()).isEqualByComparingTo(override);
        assertThat(created.invoiceDetails().grossAmount()).isNotEqualByComparingTo(payable);
        assertThat(created.dealAmountMismatch()).isTrue(); // flagged, not blocked
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Duplicate guard: a second commission for the same deal is rejected outright.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createFromDeal_secondCallForSameDeal_isRejected_andCreatesNoSecondRecord() {
        long ticketId = driveDealToClosedPaid(new BigDecimal("10"));
        commissionService.createFromDeal(
            ticketId, "INV-A2-FIRST-" + UUID.randomUUID().toString().substring(0, 8), LocalDate.of(2026, 6, 15),
            null, null, null, null, null, null, null, null, invoiceFile(), accountActor);
        int afterFirst = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createFromDeal(
                ticketId, "INV-A2-SECOND-" + UUID.randomUUID().toString().substring(0, 8), LocalDate.of(2026, 6, 15),
                null, null, null, null, null, null, null, null, invoiceFile(), accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(countCommissionRecords()).isEqualTo(afterFirst);
    }

    @Test
    void createFromDeal_rejectsWhenDealNotYetClosedPaid_andCreatesNoRecord() {
        long ticketId = driveDealThroughDeliveryOnly(new BigDecimal("10")); // DELIVERED, not CLOSED_PAID
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createFromDeal(
                ticketId, "INV-A2-NOTREADY-" + UUID.randomUUID().toString().substring(0, 8), LocalDate.of(2026, 6, 15),
                null, null, null, null, null, null, null, null, invoiceFile(), accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers (same pattern as CommissionDealLinkageIntegrationTest).
    // ─────────────────────────────────────────────────────────────────────────────────────

    private SubmitCommissionRequest submitRequestLinkedTo(long ticketId, BigDecimal grossAmount) {
        long dealOwnerId = ticketService.get(ticketId, ceoActor).summary().createdById();
        return new SubmitCommissionRequest(
            ticketId, dealOwnerId, "INV-A2-MANUAL-" + UUID.randomUUID().toString().substring(0, 8),
            LocalDate.of(2026, 6, 15), grossAmount,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private MultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());
    }

    private int countCommissionRecords() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.commission_record", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    /** Drives a brand-new deal all the way to {@link DealStage#CLOSED_PAID}. */
    private long driveDealToClosedPaid(BigDecimal quantity) {
        long ticketId = driveDealThroughDeliveryOnly(quantity);
        ticketService.confirmFinalPayment(ticketId, accountActor);
        return ticketId;
    }

    /** Same as {@link #driveDealToClosedPaid} but stops right after delivery — DELIVERED, not yet
     * CLOSED_PAID. Used by the "gate rejects a not-yet-closed deal" test. */
    private long driveDealThroughDeliveryOnly(BigDecimal quantity) {
        long catalogProductId = insertCatalogProduct(FACTORY, "TH",
            "TEST-COMM-A2-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท Commission A2 " + UUID.randomUUID() + " จำกัด", "0100000000010", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0010");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ Commission A2");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Commission A2", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile Commission A2", FACTORY))),
            salesActor);
        long ticketId = created.summary().id();
        long ticketItemId = created.items().get(0).id();

        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile Commission A2", "SCG Tile Commission A2", null, null,
            "60x60", FACTORY, quantity, quantity, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "slice a2 closeout walk", UUID.randomUUID().toString(), List.of(item));
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
            new CreateCostingRequest("slice a2 costing", null), importActor);
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
