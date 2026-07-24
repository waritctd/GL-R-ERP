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
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-order-confirmation-test-uploads");
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

        depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer());

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
            VALUES (:factory, 'factory-oc@example.com', 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factory", FACTORY));
        catalogProductId = insertCatalogProduct(FACTORY, "TH", "TEST-OC-001",
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
        long pricingRequestId = driveToQuotationIssuedNotYetAccepted();
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
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("step 6 costing", null), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto decisionItem = decision.items().get(0);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
            new UpdatePricingDecisionItemRequest(decisionItem.id(), null, null, new BigDecimal("1.00"), null))), ceoActor);
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
