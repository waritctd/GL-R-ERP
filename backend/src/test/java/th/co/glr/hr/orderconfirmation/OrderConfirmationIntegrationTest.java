package th.co.glr.hr.orderconfirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateRevisionRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.RecordQuotationOutcomeRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
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
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcService;
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
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DepositPolicy;
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
 * Real-DB acceptance + authz + concurrency coverage for Step 6 (Deposit, Payment, and Order
 * Confirmation). Drives a single-item deal through the REAL Steps 1-5 services (no shortcuts) to
 * {@code PricingRequestStatus.QUOTATION_ACCEPTED}, then exercises {@link OrderConfirmationService}
 * against the real, already-tested {@link TicketService}/{@link DepositNoticeService} pipeline it
 * bridges into.
 */
class OrderConfirmationIntegrationTest extends AbstractPostgresIntegrationTest {
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
    private DepositNoticeRepository depositNoticeRepository;
    private DepositNoticeService depositNoticeService;
    private OrderConfirmationService orderConfirmation;

    private long salesRepId;
    private long otherSalesId;
    private long importUserId;
    private long ceoUserId;
    private long accountUserId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;

    private long ticketId;
    private long catalogProductId;

    private static final String FACTORY = "Factory OC";

    @BeforeEach
    void wireEverySixStepsServiceAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-order-confirmation-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

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
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        // V141 ("CEO owns costing"): shared by FactoryQuoteService's markReadyForCosting
        // auto-advance check and PricingDecisionService's startReview/recalculateCost.
        th.co.glr.hr.pricingcosting.LandedCostCalculator landedCostCalculator =
            new th.co.glr.hr.pricingcosting.LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
                new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);

        costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing
        // create/recalculate/submit is gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);

        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);

        PriceCalcService priceCalcMock = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications);

        depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย หก", "sales-step6@glr.co.th", "SALES", "แผนกขาย");
        otherSalesId = createEmployee(employees, "พนักงานขาย อื่นหก", "sales-step6-other@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า หก", "import-step6@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร หก", "ceo-step6@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี หก", "account-step6@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:factory, 'factory-oc@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factory", FACTORY));
        catalogProductId = insertCatalogProduct(FACTORY, "IT", "TEST-OC-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Order Confirm จำกัด", "0100000000006", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0006");
        ProjectDto project = projects.create(customer.id(), "โครงการ Order Confirm");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Order Confirm", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile OC", FACTORY))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Acceptance scenario (end to end, real Postgres)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void fullChain_quotationAcceptedThroughDepositPaid_composesWithoutShortcuts() {
        long pricingRequestId = driveToQuotationAccepted();

        // No pricing chain has ever touched the legacy ticket status machine — confirms the
        // task's own premise before the bridge acts.
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(TicketStatus.DRAFT);

        // ── Step 6.1: the bridge action ─────────────────────────────────────────────────
        String confirmKey = UUID.randomUUID().toString();
        OrderConfirmationDtos.OrderConfirmationResultDto result = orderConfirmation.confirmOrder(
            pricingRequestId, new OrderConfirmationRequests.ConfirmOrderRequest(confirmKey), salesActor);
        assertThat(result.ticket().summary().status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(result.ticket().summary().paymentStatus()).isEqualTo("CUSTOMER_CONFIRMED");
        assertThat(result.ticket().summary().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(result.pricingRequest().orderConfirmedAt()).isNotNull();

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'ORDER_CONFIRMED_FROM_QUOTATION'
            """, Map.of("id", ticketId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event WHERE pricing_request_id = :id AND event_kind = 'ORDER_CONFIRMED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);

        // Idempotent replay with the SAME key: no duplicate CUSTOMER_CONFIRMED event.
        OrderConfirmationDtos.OrderConfirmationResultDto replay = orderConfirmation.confirmOrder(
            pricingRequestId, new OrderConfirmationRequests.ConfirmOrderRequest(confirmKey), salesActor);
        assertThat(replay.ticket().summary().status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'CUSTOMER_CONFIRMED'
            """, Map.of("id", ticketId), Long.class)).isEqualTo(1L);

        // A retry with a DIFFERENT (or no) key against an already-confirmed request is a clean 409.
        assertThatThrownBy(() -> orderConfirmation.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // ── Step 6.2: deposit notice from the accepted quotation (DepositPolicy's own 50%
        //    default — req.depositPercent()=null — so the FULL deposit-notice amount paid below
        //    is a genuine PARTIAL payment against the quotation's total, landing on DEPOSIT_PAID
        //    rather than FULLY_PAID) ──────────────────────────────────────────────────────────
        CustomerQuotationDto acceptedQuotation = quotationRepository.findByPricingRequest(pricingRequestId).stream()
            .filter(q -> QuotationStatus.ACCEPTED.equals(q.docStatus()))
            .findFirst().orElseThrow();
        DepositNoticeDto draftNotice = orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest(null), salesActor);
        assertThat(draftNotice.status()).isEqualTo("DRAFT");
        assertThat(draftNotice.items()).hasSize(1);
        assertThat(draftNotice.items().get(0).qty()).isEqualByComparingTo(acceptedQuotation.items().get(0).requestedQuantity());
        assertThat(draftNotice.items().get(0).netUnitPrice()).isEqualByComparingTo(acceptedQuotation.items().get(0).finalUnitPrice());
        assertThat(draftNotice.subtotal()).isEqualByComparingTo(acceptedQuotation.subtotalAmount());
        assertThat(draftNotice.reference()).isEqualTo(acceptedQuotation.number());

        // Traces to the quotation, NOT to any sales.ticket_item row — Step 4/5 never wrote a
        // ticket_item price column for this deal, so a legacy-fallback build (which reads
        // approved_price from ticket_item) would have produced ZERO items here, not one.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_item WHERE ticket_id = :id AND approved_price IS NOT NULL
            """, Map.of("id", ticketId), Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);

        DepositNoticeDto issuedNotice = depositNoticeService.issue(draftNotice.id(), salesActor);
        assertThat(issuedNotice.status()).isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject(
            "SELECT payment_status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo("DEPOSIT_NOTICE_ISSUED");

        // ── Step 6.3: Account confirms the deposit paid ─────────────────────────────────
        BigDecimal payableBeforePayment = tickets.payableAmount(ticketId);
        assertThat(payableBeforePayment).isEqualByComparingTo(acceptedQuotation.subtotalAmount());

        TicketDto afterDeposit = ticketService.confirmDepositPaid(ticketId, accountActor);
        assertThat(afterDeposit.summary().paymentStatus()).isEqualTo("DEPOSIT_PAID");
        assertThat(afterDeposit.summary().salesStage()).isEqualTo(DealStage.DEPOSIT_RECEIVED);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.payment_receipt WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .isEqualTo(1L);
        // The FULL deposit-notice amount (DepositPolicy's own 50% default of the quotation
        // total) was paid, in one shot, with NO override note (recordPaymentInternal only
        // requires one when the overpayment guard actually trips) — proving item 3's fix: a
        // full-amount DEPOSIT payment on a new-chain deal must not be flagged as an overpayment
        // just because payableAmount() used to be blind to the new chain's own quotation total.
        BigDecimal paid = jdbc.queryForObject(
            "SELECT amount FROM sales.payment_receipt WHERE ticket_id = :id", Map.of("id", ticketId), BigDecimal.class);
        assertThat(paid).isEqualByComparingTo(draftNotice.depositAmount());
        assertThat(paid).isLessThan(payableBeforePayment); // strictly partial — not an overpayment by construction.
        String note = jdbc.queryForObject(
            "SELECT note FROM sales.payment_receipt WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        assertThat(note).isEqualTo("ยืนยันรับมัดจำ"); // the plain confirmDepositPaid note, not an overpayment override.

        // ── Item 4: duplicate-payment prevention — confirmDepositPaid a second time 409s ──
        assertThatThrownBy(() -> ticketService.confirmDepositPaid(ticketId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.payment_receipt WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Deposit-notice autofill (branch fix: DepositNoticeService.createDraft's own header
    // autofill + new-chain item fallback), driven through the TICKET-LEVEL route — i.e. the
    // route DepositNoticePage's "สร้างเอกสารฉบับร่าง" button actually calls, not the
    // pricing-request-scoped createDepositNoticeFromQuotation bridge exercised above.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createDraft_ticketLevelRoute_autofillsHeaderAndSourcesItemsFromAcceptedQuotation() {
        long pricingRequestId = driveToQuotationAccepted();
        orderConfirmation.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor);

        // Precondition matching the branch's own diagnosis: every ticket_item on this deal still
        // carries approved_price = NULL — Steps 1-5 never write that legacy column, so the
        // TicketService.approve-only writer never ran.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_item WHERE ticket_id = :id AND approved_price IS NOT NULL
            """, Map.of("id", ticketId), Long.class)).isZero();

        CustomerQuotationDto accepted = quotationRepository.findByPricingRequest(pricingRequestId).stream()
            .filter(q -> QuotationStatus.ACCEPTED.equals(q.docStatus()))
            .findFirst().orElseThrow();

        TicketSummaryDto ticketSummary = tickets.findById(ticketId).orElseThrow().summary();
        CustomerDto customer = new CustomerRepository(jdbc).findById(ticketSummary.customerId()).orElseThrow();

        // The ticket-level route, called with no explicit items/header — exactly what
        // DepositNoticePage's fallback path (and the pre-fix production behaviour) both call.
        DepositNoticeDto draft = depositNoticeService.createDraft(ticketId,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);

        // Items: sourced from the ACCEPTED quotation, not the (all-NULL) ticket_item rows.
        assertThat(draft.items()).hasSize(1);
        assertThat(draft.items().get(0).qty())
            .isEqualByComparingTo(accepted.items().get(0).requestedQuantity());
        assertThat(draft.items().get(0).netUnitPrice())
            .isEqualByComparingTo(accepted.items().get(0).finalUnitPrice());

        // Header: customerTaxId/customerAddress from the customer master via ticket.customerId,
        // projectName from the ticket summary itself.
        assertThat(draft.customerTaxId()).isEqualTo(customer.taxId());
        assertThat(draft.customerAddress()).isEqualTo(customer.address() + " " + customer.branch());
        assertThat(draft.projectName()).isEqualTo(ticketSummary.projectName());
        assertThat(ticketSummary.projectName()).isNotBlank(); // guards against a vacuously-true assertion above
    }

    /**
     * Review finding (fixed before merge, not deferred):
     * {@code CustomerQuotationRepository.findByTicket} originally copied {@code
     * findByPricingRequest}'s {@code ORDER BY quotation_revision_no, quotation_id} — but that
     * counter is scoped to a single pricing request (V74's own migration comment), so it is not
     * comparable across the multiple pricing requests one ticket can have. This deal has TWO
     * independent pricing requests: PR#1 is revised once (while still ISSUED, before the customer
     * ever accepts anything) and THEN accepted, so its accepted quotation ends at {@code
     * quotation_revision_no = 2} — but PR#2 is created and accepted strictly AFTER that, at its
     * own first-ever {@code quotation_revision_no = 1}. Sorted by the old key, PR#1's revision-2
     * row would sort AFTER PR#2's revision-1 row despite being older, so a forward "latest wins"
     * scan would land on the wrong (superseded-by-time) pricing request's quotation — wrong
     * quantities and wrong money on a customer financial document. Sorted by {@code quotation_id}
     * alone (the fix), the actually-newer PR#2 row wins.
     */
    @Test
    void createDraft_picksTheNewerPricingRequestsAcceptedQuotation_notTheOneWithTheHigherRevisionNo() {
        // PR#1 (created FIRST, raw factory price 100.00): drive to quotation v1 ISSUED — NOT yet
        // accepted. CustomerQuotationService.createRevision is reachable ONLY from ISSUED/
        // REVISION_REQUESTED (its own guard rejects ACCEPTED, among others), so the revision must
        // happen BEFORE the customer's acceptance, not after. Create + issue a commercial-only
        // revision (v2 — this also immediately supersedes v1, per createRevision's own write),
        // THEN accept v2. PR#1's accepted quotation ends at revision_no = 2.
        long pr1Id = driveToQuotationIssuedNotYetAccepted(new BigDecimal("100.00"));
        CustomerQuotationDto pr1V1Issued = quotationRepository.findByPricingRequest(pr1Id).stream()
            .filter(q -> QuotationStatus.ISSUED.equals(q.docStatus())).findFirst().orElseThrow();
        CustomerQuotationDto pr1V2Draft = quotationService.createRevision(pr1V1Issued.id(),
            new CreateRevisionRequest("correction", UUID.randomUUID().toString()), salesActor);
        CustomerQuotationDto pr1V2Issued = quotationService.issue(pr1V2Draft.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        // recordOutcome returns the quotation it just updated — use that directly rather than a
        // second findByPricingRequest().filter(ACCEPTED).findFirst() lookup, which would be
        // ambiguous if more than one row were ever ACCEPTED at once (it can't be here, since v1
        // is already SUPERSEDED, but this sidesteps the assumption entirely).
        CustomerQuotationDto pr1Accepted = quotationService.recordOutcome(pr1V2Issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ยืนยันหลังแก้ไข", UUID.randomUUID().toString()),
            salesActor);
        assertThat(pr1Accepted.docStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(pr1Accepted.quotationRevisionNo()).isEqualTo(2); // the HIGHER revision_no
        assertThat(pricingRequestService.get(pr1Id, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);
        // confirmOrder gates on QUOTATION_ACCEPTED, so it must run AFTER the outcome above.
        orderConfirmation.confirmOrder(pr1Id, new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor);

        // PR#2 (created SECOND, raw factory price 250.00 — deliberately different so its line
        // item is distinguishable from PR#1's): its own first-ever quotation, revision 1 — a
        // LOWER revision_no than PR#1's, but a HIGHER quotation_id (created strictly later).
        long pr2Id = driveToQuotationAccepted(new BigDecimal("250.00"));
        orderConfirmation.confirmOrder(pr2Id, new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor);
        CustomerQuotationDto pr2Accepted = quotationRepository.findByPricingRequest(pr2Id).stream()
            .filter(q -> QuotationStatus.ACCEPTED.equals(q.docStatus())).findFirst().orElseThrow();
        assertThat(pr2Accepted.quotationRevisionNo()).isEqualTo(1); // the LOWER revision_no
        assertThat(pr2Accepted.id()).isGreaterThan(pr1Accepted.id()); // but the newer row

        DepositNoticeDto draft = depositNoticeService.createDraft(ticketId,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);

        // Wrong-way-round: PR#1's (superseded-by-time, higher-revision) price must be ABSENT —
        // not merely "PR#2's price is present", which an off-by-one bug could satisfy by
        // coincidence if the two prices ever collided.
        assertThat(draft.items()).hasSize(1);
        assertThat(draft.items().get(0).netUnitPrice())
            .isEqualByComparingTo(pr2Accepted.items().get(0).finalUnitPrice())
            .isNotEqualByComparingTo(pr1Accepted.items().get(0).finalUnitPrice());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authorization
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmOrder_nonOwningSalesRep_cannotConfirm() {
        long pricingRequestId = driveToQuotationAccepted();
        assertThatThrownBy(() -> orderConfirmation.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(null), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        // Nothing moved: the guard must fail BEFORE any write, not roll one back.
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(TicketStatus.DRAFT);
    }

    @Test
    void confirmOrder_importAndCeo_cannotConfirm() {
        long pricingRequestId = driveToQuotationAccepted();
        assertThatThrownBy(() -> orderConfirmation.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> orderConfirmation.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * Reuses the existing, real backend authz gate on {@code confirmDepositPaid}/{@code
     * recordPayment} (ACCOUNT_ROLES = {account, ceo}) — cited, not re-proven: {@code
     * TicketServiceTest.confirmDepositPaid_rejectsSalesRole}/{@code confirmDepositPaid_rejectsImportRole}
     * already cover this at the unit level. This test only proves it holds for a NEW-CHAIN deal
     * specifically (real DB, real quotation-sourced payable amount).
     */
    @Test
    void confirmDepositPaid_salesActor_cannotReach_onNewChainDeal() {
        long pricingRequestId = driveToQuotationAccepted();
        orderConfirmation.confirmOrder(pricingRequestId, new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor);
        orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest(null), salesActor);
        assertThatThrownBy(() -> ticketService.confirmDepositPaid(ticketId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Guard: reachable ONLY from QUOTATION_ACCEPTED
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmOrder_beforeQuotationAccepted_isRejected() {
        long pricingRequestId = driveToQuotationIssuedNotYetAccepted();
        assertThatThrownBy(() -> orderConfirmation.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(TicketStatus.DRAFT);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The CEO-credit-terms bypass path — reaches order-confirmed without a deposit notice.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Traces {@code TicketService.issueImportRequest}'s {@code depositPolicyBypassesNotice} logic
     * for a real credit-terms deal: bypasses the deposit notice entirely and, because {@code
     * issueImportRequest} advances straight to {@code DealStage.PROCUREMENT} (the whole import
     * journey lives inside that one stage — see {@code DealStage}'s own Javadoc), the deal SKIPS
     * {@code DealStage.DEPOSIT_RECEIVED} rather than landing on it. paymentStatus stays
     * CUSTOMER_CONFIRMED throughout (no payment was ever recorded), and no deposit_notice row is
     * ever created for this ticket — this is the actual bypass shape the DealStage/paymentStatus/
     * DepositPolicy machinery already models, reported here as found rather than assumed.
     */
    @Test
    void ceoCreditTermsBypass_reachesOrderConfirmed_withoutADepositNotice() {
        long pricingRequestId = driveToQuotationAccepted();
        orderConfirmation.confirmOrder(pricingRequestId, new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor);

        TicketDto waived = ticketService.waiveDeposit(ticketId, DepositPolicy.CREDIT_CUSTOMER,
            "ลูกค้าเครดิตชั้นดี อนุมัติเทอมเครดิตแทนมัดจำ", accountActor);
        assertThat(waived.summary().depositPolicy()).isEqualTo(DepositPolicy.CREDIT_CUSTOMER);

        TicketDto afterIr = ticketService.issueImportRequest(ticketId, importActor);
        assertThat(afterIr.summary().fulfillmentStatus()).isEqualTo(FulfilmentStatus.IR_ISSUED);
        assertThat(afterIr.summary().salesStage()).isEqualTo(DealStage.PROCUREMENT);
        // paymentStatus is untouched — no payment was ever recorded on this deal.
        assertThat(afterIr.summary().paymentStatus()).isEqualTo("CUSTOMER_CONFIRMED");

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.deposit_notice WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Transaction-harness coverage (test/transaction-harness): confirmOrder's write sequence is
    // lockPricingRequest -> replay check -> markOrderConfirmed (1) ->
    // markQuotationIssuedForOrderConfirmation (2) -> tickets.addEvent (3) -> reconcileTicketItems
    // (4) -> ticketService.confirmCustomer (5-6) -> pricingRequests.addEvent ORDER_CONFIRMED (7)
    // -> notifications.notifyByRoleForPricingRequest (8, last). Every hand-wired integration test
    // in this suite — including every OTHER test in this very file — drives orderConfirmation
    // with `new OrderConfirmationService(...)`, which has NO Spring AOP proxy, so @Transactional
    // on confirmOrder does nothing for them: every JDBC statement auto-commits independently.
    // These two tests close that gap.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Real proxy, real rollback: fails at the LAST write (the notification) and proves all seven
     * writes before it are undone. Built via {@link AbstractPostgresIntegrationTest#transactional}
     * so {@code confirmOrder}'s own {@code @Transactional} — not a test-supplied transaction — is
     * what has to do the work; see {@link
     * #confirmOrder_withoutTheProxy_strandsTheDealHalfConfirmed_theHarnessDefectItself()} for the
     * vacuity control proving this assertion is not trivially true.
     */
    @Test
    void confirmOrder_failingAfterEveryWrite_rollsBackAllOfThem_whenProxied() {
        long pricingRequestId = driveToQuotationAccepted();
        List<Map<String, Object>> itemsBefore = ticketItemSnapshot();

        OrderConfirmationService failing = wireFailingOrderConfirmationService();

        assertThatThrownBy(() -> transactional(failing).confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
            "SELECT order_confirmed_at IS NOT NULL FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), Boolean.class))
            .as("markOrderConfirmed's write (1 of 8) must not survive a rollback triggered by the "
                + "8th write failing")
            .isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .as("markQuotationIssuedForOrderConfirmation's write (2) must not survive")
            .isEqualTo(TicketStatus.DRAFT);
        assertThat(jdbc.queryForObject(
            "SELECT payment_status IS NULL FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), Boolean.class))
            .as("confirmCustomer's updatePaymentStatus write (5) must not survive")
            .isTrue();
        assertThat(countTicketEventsOfKind("ORDER_CONFIRMED_FROM_QUOTATION"))
            .as("tickets.addEvent's write (3) must not survive")
            .isZero();
        assertThat(countTicketEventsOfKind("CUSTOMER_CONFIRMED"))
            .as("confirmCustomer's own ticket_event write (6) must not survive")
            .isZero();
        assertThat(countPricingRequestEventsOfKind(pricingRequestId, "ORDER_CONFIRMED"))
            .as("pricingRequests.addEvent ORDER_CONFIRMED (7) must not survive")
            .isZero();
        assertThat(countPricingRequestEventsOfKind(pricingRequestId, "TICKET_ITEMS_RECONCILED"))
            .as("reconcileTicketItems's own event (part of write 4) must not survive")
            .isZero();
        assertThat(ticketItemSnapshot())
            .as("reconcileTicketItems's UPDATE/INSERT on sales.ticket_item (write 4) must not survive")
            .isEqualTo(itemsBefore);

        // The deal must not be stuck: a subsequent confirm through the REAL (non-failing) service
        // — the retry production depends on — succeeds.
        OrderConfirmationDtos.OrderConfirmationResultDto retried = orderConfirmation.confirmOrder(
            pricingRequestId, new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor);
        assertThat(retried.ticket().summary().status()).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(retried.pricingRequest().orderConfirmedAt()).isNotNull();
    }

    /**
     * The vacuity control, and the reason the assertions above are not passing for a trivial
     * reason. Same injected failure, but calls the RAW un-proxied service exactly as all other
     * integration tests in this suite do — no {@link AbstractPostgresIntegrationTest#transactional}
     * wrapping. Because {@code @Transactional} does nothing without a real AOP proxy, every write
     * before the injected failure COMMITS independently, stranding the deal half-confirmed:
     * {@code order_confirmed_at} is set, the ticket sits at {@code quotation_issued}, {@code
     * confirmCustomer}'s payment/stage writes landed, and the reconciliation write(s) survive
     * independently of everything after them — and a retry now 409s, because {@code
     * markOrderConfirmed}'s own compare-and-set already fired. The deal is confirmed but the
     * notification the accepted order depends on never fired, and nothing will ever re-drive it;
     * only hand-written SQL frees it in production today.
     *
     * <p>(Reviewer's note: an earlier draft of this Javadoc claimed {@code confirmCustomer} "never
     * ran, paymentStatus stays NULL". That was wrong — {@code confirmCustomer} is writes 5-6, i.e.
     * strictly BEFORE the injected 8th-write failure, so its writes commit like all the others.
     * The claim is now pinned by an assertion below rather than asserted only in prose.)
     *
     * <p>Documents today's broken auto-commit behaviour deliberately — it asserts the DEFECT, not
     * a desired outcome, and should be DELETED the day the base test harness itself runs inside a
     * real Spring context with proxied beans (at which point every plain, hand-wired service in
     * this suite would roll back correctly and this divergent-behaviour test would no longer
     * describe reality).
     */
    @Test
    void confirmOrder_withoutTheProxy_strandsTheDealHalfConfirmed_theHarnessDefectItself() {
        long pricingRequestId = driveToQuotationAccepted();
        List<Map<String, Object>> itemsBefore = ticketItemSnapshot();

        OrderConfirmationService failing = wireFailingOrderConfirmationService();

        assertThatThrownBy(() -> failing.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
            "SELECT order_confirmed_at IS NOT NULL FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), Boolean.class))
            .as("without a proxy, markOrderConfirmed's write commits on its own and survives — the "
                + "harness defect this test documents")
            .isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(ticketItemSnapshot())
            .as("reconcileTicketItems's write also survives independently of everything after it")
            .isNotEqualTo(itemsBefore);
        // Pins the corrected claim in this method's Javadoc: confirmCustomer (writes 5-6) runs
        // strictly BEFORE the injected 8th-write failure, so its writes commit too. The exact
        // mirror image of the proxied test's "payment_status IS NULL / zero CUSTOMER_CONFIRMED
        // events" assertions — which is what makes those two non-vacuous.
        assertThat(jdbc.queryForObject(
            "SELECT payment_status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .as("confirmCustomer's updatePaymentStatus write (5) survives without a proxy")
            .isEqualTo("CUSTOMER_CONFIRMED");
        assertThat(countTicketEventsOfKind("CUSTOMER_CONFIRMED"))
            .as("confirmCustomer's own ticket_event write (6) survives without a proxy")
            .isOne();

        // The consequence that matters: the deal is confirmed but never fully processed, and a
        // retry 409s because markOrderConfirmed's compare-and-set already fired.
        assertThatThrownBy(() -> orderConfirmation.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** Identical to the fixture's {@code orderConfirmation} except its {@code
     * NotificationRepository} is a mock that throws on the LAST write of confirmOrder's sequence
     * — every other dependency (pricingRequests, tickets, ticketService, quotationRepository,
     * depositNoticeService) stays REAL, so everything before that last write is genuinely
     * exercised against real Postgres. */
    private OrderConfirmationService wireFailingOrderConfirmationService() {
        NotificationRepository failingNotifications = mock(NotificationRepository.class);
        doThrow(new IllegalStateException("injected failure after every confirmOrder write"))
            .when(failingNotifications)
            .notifyByRoleForPricingRequest(anyString(), anyLong(), anyString(), anyString());
        return new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, failingNotifications);
    }

    private List<Map<String, Object>> ticketItemSnapshot() {
        return jdbc.queryForList(
            "SELECT item_id, qty, qty_sqm FROM sales.ticket_item WHERE ticket_id = :id ORDER BY item_id",
            Map.of("id", ticketId));
    }

    private long countTicketEventsOfKind(String kind) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind",
            Map.of("id", ticketId, "kind", kind), Long.class);
        return count == null ? 0 : count;
    }

    private long countPricingRequestEventsOfKind(long pricingRequestId, String eventKind) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_request_event WHERE pricing_request_id = :id AND event_kind = :kind",
            Map.of("id", pricingRequestId, "kind", eventKind), Long.class);
        return count == null ? 0 : count;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Mutation-check evidence (verbatim red output recorded in the branch handoff)
    // ─────────────────────────────────────────────────────────────────────────────────────
    // See docs/agent-handoffs/95_feat-sales-deposit-order-confirmation.md "Authz Evidence" for
    // the mutation-check narrative against these three tests:
    //   confirmOrder_beforeQuotationAccepted_isRejected        (the QUOTATION_ACCEPTED-only gate)
    //   fullChain_..._composesWithoutShortcuts's replay assertions (the clientRequestId guard)
    //   confirmOrder_nonOwningSalesRep_cannotConfirm            (the owner-only guard)

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private long driveToQuotationAccepted() {
        return driveToQuotationAccepted(new BigDecimal("100.00"));
    }

    /** Same drive, parameterized on the factory's raw unit price — so two independent pricing
     * requests on the SAME ticket (see the cross-pricing-request ordering test below) can be
     * driven to two DISTINGUISHABLE accepted quotations. */
    private long driveToQuotationAccepted(BigDecimal rawUnitPrice) {
        long pricingRequestId = driveToQuotationIssuedNotYetAccepted(rawUnitPrice);
        CustomerQuotationDto issued = quotationRepository.findByPricingRequest(pricingRequestId).stream()
            .filter(q -> QuotationStatus.ISSUED.equals(q.docStatus()))
            .findFirst().orElseThrow();
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเคกับใบเสนอราคา", UUID.randomUUID().toString()),
            salesActor);
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);
        return pricingRequestId;
    }

    private long driveToQuotationIssuedNotYetAccepted() {
        return driveToQuotationIssuedNotYetAccepted(new BigDecimal("100.00"));
    }

    private long driveToQuotationIssuedNotYetAccepted(BigDecimal rawUnitPrice) {
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductId, null, "SCG", "Tile OC", "SCG Tile OC", null, null, "60x60", FACTORY,
            new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "step 6 acceptance walk", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest("factory-oc@example.com", null, null, UUID.randomUUID().toString()), importActor);
        drainDispatches();

        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-OC", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("10.00"), "piece", UnitBasis.PER_PIECE,
                rawUnitPrice, "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        // V141 ("CEO owns costing"): markReadyForCosting auto-advances the request straight to
        // READY_FOR_CEO_REVIEW the moment the (only, here) factory quote is ready — Import no
        // longer creates/recalculates/submits a costing of its own; the CEO's startReview below
        // computes it.

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto decisionItem = decision.items().get(0);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
            new UpdatePricingDecisionItemRequest(decisionItem.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
        assertThat(approved.status()).isEqualTo("APPROVED");

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
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
