package th.co.glr.hr.ticket;

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
import th.co.glr.hr.common.PageRequest;
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
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticeItemRequest;
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
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
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
import th.co.glr.hr.pricingcosting.LandedCostCalculator;

/**
 * Real-Postgres proof for {@link PaymentTrack} + {@code TicketRepository.advancePaymentStatus} +
 * the 7 migrated write sites. {@code PaymentTrackTest} covers the pure state machine in isolation;
 * this class covers (a) the repository's compare-and-set actually enforces it against a real row,
 * and (b) the SERVICE-layer composition ({@code TicketService.confirmCustomer},
 * {@code DepositNoticeService.issue}, {@code reconcilePaymentStatus}, {@code waiveDeposit},
 * {@code issueImportRequest}) walks/refuses correctly through real production code paths, never a
 * mock.
 *
 * <p>Deliberately does NOT extend {@code DepositNoticeIssueGuardIntegrationTest} — the ceo-costing
 * agent may be touching the pricing services it wires. Wiring and the fixture-building approach
 * (drive a deal through the REAL Steps 1-6 services, no shortcuts) are modeled on that class
 * instead — see its own Javadoc — plus its
 * {@code buildTicketWithIssuedDepositNotice}/{@code driveDraftPricingRequestToQuotationAccepted}
 * helpers.
 *
 * <p>Two fixture tiers are used deliberately: the "bare ticket" helpers ({@link #createBareTicket}
 * + {@link TicketRepository#updatePaymentStatusUnchecked}/{@code updateDepositPolicy}) seed a real
 * Postgres row directly for the wrong-way-round repository-level tests, where the fact under test
 * is the GUARD itself, not how the ticket arrived at that state. The heavier
 * {@link #buildDealToQuotationAccepted} fixture drives a full pricing-request chain through the
 * real services for the positive end-to-end walks and the authz-visible test, where the SERVICE
 * composition is exactly what is being proven.
 */
class PaymentTrackIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;
    private DepositNoticeRepository depositNoticeRepository;
    private OrderConfirmationService orderConfirmation;
    private CustomerRepository customersRepo;
    private ProjectRepository projectsRepo;

    private long salesRepId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;

    private static final String FACTORY = "Factory PaymentTrack A";

    @BeforeEach
    void wireStepsServicesAndCreateFactory() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customersRepo = new CustomerRepository(jdbc);
        projectsRepo = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-payment-track-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

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
        // V141 ("CEO owns costing"): the landed-cost calculation is a shared LandedCostCalculator
        // that FactoryQuoteService and PricingDecisionService both take, and PricingCostingService
        // no longer owns the repositories it used to compute from.
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes,
            pricingRequests, fxRates, new FactoryConfigRepository(jdbc),
            new CatalogRepository(jdbc), formulaEngine);

        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage,
            dispatchProperties, landedCostCalculator);

        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);

        PriceCalcService priceCalcMock = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customersRepo, new QuotationRenderer(), pricingRequestService);

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customersRepo, new QuotationRenderer(), notifications);

        depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customersRepo, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย เพย์เมนต์", "sales-paytrack1@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า เพย์เมนต์", "import-paytrack1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร เพย์เมนต์", "ceo-paytrack1@glr.co.th", "MD", "ผู้บริหาร");
        long accountUserId = createEmployee(employees, "ฝ่ายบัญชี เพย์เมนต์", "account-paytrack1@glr.co.th", "ACC", "ฝ่ายบัญชี");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");

        insertFactory(FACTORY);
    }

    private void insertFactory(String name) {
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:factory, :email, 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factory", name, "email", name.toLowerCase().replace(" ", "-") + "@example.com"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // POSITIVE — full end-to-end walks (plan scenarios 6, 7), revision (8), authz-visible (9)
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void requiredPath_walksEndToEnd_nullThroughDepositNoticeIssuedThroughFullyPaid() {
        Deal deal = buildDealToQuotationAccepted("required");

        // site 1: null -> CUSTOMER_CONFIRMED. confirmOrder delegates to TicketService.confirmCustomer
        // since paymentStatus is still null when it runs.
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        // site 2: CUSTOMER_CONFIRMED -> DEPOSIT_NOTICE_ISSUED.
        DepositNoticeDto draft = orderConfirmation.createDepositNoticeFromQuotation(
            deal.pricingRequestId(), new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(draft.id(), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        // site 6 (REQUIRED branch, via reconcilePaymentStatus): DEPOSIT_NOTICE_ISSUED -> DEPOSIT_PAID,
        // triggered by a real ~50% deposit receipt.
        ticketService.confirmDepositPaid(deal.ticketId(), accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_PAID);

        // Fulfilment track (unrelated to payment_status except for site 3's side effect below) —
        // no live sales.factory_purchase_order rows exist for this deal, so the ticket-level
        // setters are not refused by the PO-tracked guard.
        ticketService.issueImportRequest(deal.ticketId(), importActor);
        ticketService.markIrSent(deal.ticketId(), importActor);
        ticketService.markShipping(deal.ticketId(), importActor);

        // site 3: DEPOSIT_PAID -> AWAITING_FINAL_PAYMENT, fired as applyGoodsReceived's side effect.
        ticketService.markGoodsReceived(deal.ticketId(), importActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.AWAITING_FINAL_PAYMENT);

        // site 5 (via confirmFinalPayment -> recordPaymentInternal -> reconcilePaymentStatus, since
        // the remaining ~50% is still outstanding): AWAITING_FINAL_PAYMENT -> FULLY_PAID.
        ticketService.confirmFinalPayment(deal.ticketId(), accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.FULLY_PAID);
    }

    @Test
    void bypassPath_walksEndToEnd_nullThroughFullyPaid_neverVisitsDepositNoticeIssuedOrDepositPaid() {
        Deal deal = buildDealToQuotationAccepted("bypass");
        // waiveDeposit while paymentStatus is still null — legal (rule 4's guard only fires once
        // paymentStatus is non-null and not CUSTOMER_CONFIRMED).
        ticketService.waiveDeposit(deal.ticketId(), DepositPolicy.WAIVED, "ทดสอบ bypass path", accountActor);
        assertThat(depositPolicyOf(deal.ticketId())).isEqualTo(DepositPolicy.WAIVED);

        // site 1: null -> CUSTOMER_CONFIRMED (the bypass path's entry edge is identical to REQUIRED's).
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        // site 8 (no write, rule 5): eligible with no deposit notice at all — CUSTOMER_CONFIRMED
        // plus a bypass policy is sufficient.
        ticketService.issueImportRequest(deal.ticketId(), importActor);
        ticketService.markIrSent(deal.ticketId(), importActor);
        ticketService.markShipping(deal.ticketId(), importActor);
        ticketService.markGoodsReceived(deal.ticketId(), importActor);
        // applyGoodsReceived's site-3 write is guarded on DEPOSIT_PAID specifically — a bypass
        // deal is never DEPOSIT_PAID, so it must NOT fire here.
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        // site 4/5 MULTI-HOP WALK in one call: CUSTOMER_CONFIRMED -> AWAITING_FINAL_PAYMENT ->
        // FULLY_PAID, since nothing has been paid yet (confirmFinalPayment's outstanding = the
        // full payable, so it delegates to recordPaymentInternal -> reconcilePaymentStatus, which
        // walks both hops in the ONE compare-and-set). This is the "walk, don't skip" central
        // design decision exercised through a real, unmodified production code path.
        ticketService.confirmFinalPayment(deal.ticketId(), accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.FULLY_PAID);
    }

    @Test
    void depositNoticeRevision_secondIssueSucceeds_mintsNewDocSupersedesFirst_paymentStatusStaysDepositNoticeIssued() {
        Deal deal = buildDealToQuotationAccepted("revision");
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto firstDraft = orderConfirmation.createDepositNoticeFromQuotation(
            deal.pricingRequestId(), new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        DepositNoticeDto firstIssued = depositNoticeService.issue(firstDraft.id(), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        // A revision: a NEW draft on the SAME ticket, issued while payment_status is still
        // DEPOSIT_NOTICE_ISSUED — PaymentTrack's one legal self-loop (guard e).
        long secondDraftId = depositNoticeService.createDraft(deal.ticketId(), bareDraftRequest(), salesActor).id();
        DepositNoticeDto secondIssued = depositNoticeService.issue(secondDraftId, salesActor);

        assertThat(secondIssued.docNumber()).isNotEqualTo(firstIssued.docNumber());
        assertThat(secondIssued.status()).isEqualTo("ISSUED");
        // PR #698's "AND status = 'DRAFT'" guard on DepositNoticeRepository.issue composes with
        // this change: the second row really was DRAFT (createDraft never touches payment_status)
        // when it was issued.
        DepositNoticeDto firstReread = depositNoticeRepository.findById(firstIssued.id()).orElseThrow();
        assertThat(firstReread.status()).isEqualTo("SUPERSEDED");
        assertThat(firstReread.docNumber()).isEqualTo(firstIssued.docNumber());
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
    }

    @Test
    void bypassDeal_partiallyPaid_becomesVisibleToAccountRoleListScope() {
        Deal deal = buildDealToQuotationAccepted("authz");
        ticketService.waiveDeposit(deal.ticketId(), DepositPolicy.WAIVED, "ทดสอบ authz visibility", accountActor);
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        // BEFORE: CUSTOMER_CONFIRMED is not in ACCOUNT_PENDING_PAYMENT_STATUSES, and there is no
        // overdue outstanding balance (no due_date set on this deal) — not yet visible to account.
        assertThat(accountVisibleTicketIds()).doesNotContain(deal.ticketId());

        BigDecimal payable = tickets.payableAmount(deal.ticketId());
        assertThat(payable.signum()).as("fixture must have a positive payable amount").isPositive();
        ticketService.recordPayment(deal.ticketId(),
            new RecordPaymentRequest("DEPOSIT", payable.multiply(new BigDecimal("0.30")), null,
                "ชำระบางส่วน", null, null, false),
            accountActor);

        // Site 6, bypass branch: CUSTOMER_CONFIRMED -> AWAITING_FINAL_PAYMENT, never DEPOSIT_PAID
        // (which PaymentTrack refuses entirely for a bypass policy).
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.AWAITING_FINAL_PAYMENT);

        // AFTER: the real repository list query through the real service (TicketService.listPage
        // -> TicketRepository.findSummaries -> appendRoleScope), never a mock — proves
        // ACCOUNT_PENDING_PAYMENT_STATUSES's AWAITING_FINAL_PAYMENT entry now actually reaches
        // this bypass-policy deal, which it could not before this branch (only DEPOSIT_PAID/
        // DEPOSIT_NOTICE_ISSUED were pending-payment statuses, and a bypass deal never visits
        // either one).
        assertThat(accountVisibleTicketIds()).contains(deal.ticketId());
    }

    /**
     * Regression for the blocker adversarial review found: a SECOND partial payment on a
     * bypass-policy deal used to throw. reconcilePaymentStatus gated its idempotency check on the
     * hardcoded DEPOSIT_PAID literal while the write target had become policy-dependent, so on a
     * bypass deal the guard never matched, the block re-entered, and PaymentTrack was asked for an
     * AWAITING_FINAL_PAYMENT -> AWAITING_FINAL_PAYMENT self-loop it correctly refuses. Because
     * recordPayment is @Transactional the receipt insert rolled back too, so the instalment could
     * not be recorded at all — an outright regression, surfacing as HTTP 500.
     *
     * The pre-existing bypassDeal_partiallyPaid test records exactly ONE payment, which is why the
     * whole suite stayed green. This one records TWO. It fails without the fix.
     */
    @Test
    void bypassDeal_secondPartialPayment_isRecorded_andPaymentStatusStaysOnPath() {
        Deal deal = buildDealToQuotationAccepted("twopay");
        ticketService.waiveDeposit(deal.ticketId(), DepositPolicy.WAIVED, "ทดสอบชำระสองงวด", accountActor);
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        BigDecimal payable = tickets.payableAmount(deal.ticketId());
        assertThat(payable.signum()).as("fixture must have a positive payable amount").isPositive();
        BigDecimal instalment = payable.multiply(new BigDecimal("0.30"));

        ticketService.recordPayment(deal.ticketId(),
            new RecordPaymentRequest("DEPOSIT", instalment, null, "งวดที่ 1", null, null, false),
            accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.AWAITING_FINAL_PAYMENT);

        // The second instalment is the one that used to blow up.
        ticketService.recordPayment(deal.ticketId(),
            new RecordPaymentRequest("BALANCE", instalment, null, "งวดที่ 2", null, null, false),
            accountActor);

        // Still on the bypass path, and NOT advanced to FULLY_PAID (60% of payable is not full).
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.AWAITING_FINAL_PAYMENT);

        // And the receipt actually persisted — the rollback was the real damage, not just the 500.
        assertThat(tickets.findReceiptsByTicket(deal.ticketId()))
            .as("both instalments must be recorded")
            .hasSize(2);
    }

    /**
     * Owner ruling: a multi-hop advance must WALK, and the intermediate state must SHOW. A
     * REQUIRED deal paying in full from DEPOSIT_PAID goes DEPOSIT_PAID -> AWAITING_FINAL_PAYMENT
     * -> FULLY_PAID. The column ends at FULLY_PAID either way, so asserting only the end state
     * cannot tell a walk from a jump — this asserts the AWAITING_FINAL_PAYMENT ticket_event,
     * which is the only durable trace that the state was ever reached.
     */
    @Test
    void requiredDeal_payingInFullFromDepositPaid_walksThroughAwaitingFinalPayment_andItShows() {
        Deal deal = buildDealToQuotationAccepted("walkshow");
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto walkDraft = orderConfirmation.createDepositNoticeFromQuotation(
            deal.pricingRequestId(), new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(walkDraft.id(), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        ticketService.confirmDepositPaid(deal.ticketId(), accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_PAID);

        long awaitingEventsBefore = countEvents(deal.ticketId(), TicketEventKind.AWAITING_FINAL_PAYMENT);

        // Pay the whole remaining balance in one go — the jump the walk has to decompose.
        BigDecimal outstanding = tickets.payableAmount(deal.ticketId())
            .subtract(nullToZeroLocal(tickets.sumPaid(deal.ticketId())));
        assertThat(outstanding.signum()).as("fixture must leave a balance").isPositive();
        ticketService.recordPayment(deal.ticketId(),
            new RecordPaymentRequest("BALANCE", outstanding, null, "ชำระเต็มจำนวน", null, null, false),
            accountActor);

        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.FULLY_PAID);
        assertThat(countEvents(deal.ticketId(), TicketEventKind.AWAITING_FINAL_PAYMENT))
            .as("the walk must leave a trace that AWAITING_FINAL_PAYMENT was reached")
            .isGreaterThan(awaitingEventsBefore);
    }

    private long countEvents(long ticketId, String kind) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :k",
            java.util.Map.of("id", ticketId, "k", kind), Long.class);
    }

    private static BigDecimal nullToZeroLocal(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private List<Long> accountVisibleTicketIds() {
        return ticketService.listPage(null, accountActor, PageRequest.resolve(0, 200)).items()
            .stream().map(TicketSummaryDto::id).toList();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // WRONG-WAY-ROUND #1 (skip/off-path) — repository level. The MACHINE-level half (canTransition
    // itself returning false for a skip pair, e.g. CUSTOMER_CONFIRMED -> DEPOSIT_PAID) is
    // exhaustively covered by PaymentTrackTest's everySkip_*_isRefusedAtTheMachineLevel tests —
    // duplicating that pure-function assertion here would add a real Postgres round-trip with no
    // new evidence. This proves the SAME refusal survives into advancePaymentStatus against a
    // real row: an off-path target throws BEFORE any SQL executes, not after a partial write.
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void advancePaymentStatus_targetNotARealStatus_throwsBeforeAnySql_rowUnchanged() {
        long ticketId = createBareTicket();
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);

        assertThatThrownBy(() -> tickets.advancePaymentStatus(
                ticketId, DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, "NOT_A_REAL_STATUS"))
            // Converted from IllegalStateException to a 409 at the source: an illegal edge is a
            // conflict the caller can act on, not an opaque 500. The row must still be untouched,
            // which is what the assertion below proves — the throw happens before any SQL.
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // WRONG-WAY-ROUND #2: reverse
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void advancePaymentStatus_reverse_throwsBeforeAnySql_rowUnchanged() {
        long ticketId = createBareTicket();
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.FULLY_PAID);

        assertThatThrownBy(() -> tickets.advancePaymentStatus(
                ticketId, DepositPolicy.REQUIRED, PaymentTrack.FULLY_PAID, PaymentTrack.DEPOSIT_PAID))
            // Converted from IllegalStateException to a 409 at the source: an illegal edge is a
            // conflict the caller can act on, not an opaque 500. The row must still be untouched,
            // which is what the assertion below proves — the throw happens before any SQL.
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.FULLY_PAID);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // EXTRA (not one of the plan's 5 named wrong-way-round scenarios, but required to precisely
    // mutation-check guard (b), the compare-and-set clause itself): a stale "expected" — a
    // concurrent-writer race — and the null-entry IS NOT DISTINCT FROM correctness. See the
    // branch report.
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void advancePaymentStatus_staleExpected_returnsZeroRows_rowUnchanged() {
        long ticketId = createBareTicket();
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_PAID);

        // A LOGICALLY legal single hop (CUSTOMER_CONFIRMED -> DEPOSIT_NOTICE_ISSUED) — but the row
        // is actually at DEPOSIT_PAID, not CUSTOMER_CONFIRMED. PaymentTrack alone cannot see this
        // (it only validates the edge, not the row); only the SQL compare-and-set can.
        int rows = tickets.advancePaymentStatus(
            ticketId, DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        assertThat(rows).isZero();
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_PAID);
    }

    @Test
    void advancePaymentStatus_nullExpected_matchesNullColumn_viaIsNotDistinctFrom() {
        long ticketId = createBareTicket();
        assertThat(paymentStatusOf(ticketId)).isNull();

        int rows = tickets.advancePaymentStatus(
            ticketId, DepositPolicy.REQUIRED, null, PaymentTrack.CUSTOMER_CONFIRMED);

        assertThat(rows).isEqualTo(1);
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // WRONG-WAY-ROUND #3: late waiveDeposit (guard c)
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void waiveDeposit_afterDepositNoticeIssued_isRefused_depositPolicyUnchanged() {
        long ticketId = createBareTicket();
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        assertThatThrownBy(() -> ticketService.waiveDeposit(
                ticketId, DepositPolicy.WAIVED, "สายเกินไป", accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(depositPolicyOf(ticketId)).isEqualTo(DepositPolicy.REQUIRED);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // WRONG-WAY-ROUND #4: issueImportRequest with NULL payment status on a bypass policy (guard d)
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void issueImportRequest_nullPaymentStatusOnBypassPolicy_isRefused_fulfillmentStatusStaysNull() {
        long ticketId = createBareTicket();
        tickets.updateDepositPolicy(ticketId, DepositPolicy.WAIVED, "ทดสอบ", salesRepId);
        tickets.markQuotationIssuedForOrderConfirmation(ticketId); // status draft -> quotation_issued
        assertThat(paymentStatusOf(ticketId)).isNull();

        assertThatThrownBy(() -> ticketService.issueImportRequest(ticketId, importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(fulfillmentStatusOf(ticketId)).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // WRONG-WAY-ROUND #5: off-path — a WAIVED deal cannot reach DEPOSIT_NOTICE_ISSUED
    // ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    void advancePaymentStatus_waivedPolicyCannotReachDepositNoticeIssued_repositoryLevel() {
        long ticketId = createBareTicket();
        tickets.updateDepositPolicy(ticketId, DepositPolicy.WAIVED, "ทดสอบ", salesRepId);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);

        assertThatThrownBy(() -> tickets.advancePaymentStatus(
                ticketId, DepositPolicy.WAIVED, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
            // Converted from IllegalStateException to a 409 at the source: an illegal edge is a
            // conflict the caller can act on, not an opaque 500. The row must still be untouched,
            // which is what the assertion below proves — the throw happens before any SQL.
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);
    }

    /**
     * Same off-path fact, proven through the real SERVICE call this time — this is what proves a
     * gap found while writing this test class: {@code DepositNoticeService.issue}'s loosened
     * precondition (site 2) originally checked ONLY {@code paymentStatus}, not {@code
     * deposit_policy}. {@code createDraft} never checked {@code deposit_policy} either (it only
     * gates on ticket status), so a WAIVED deal that had reached {@code CUSTOMER_CONFIRMED} could
     * still acquire a real DRAFT deposit notice and sail past the service-level guard into {@code
     * advancePaymentStatus}, which would throw {@link IllegalStateException} UNCAUGHT —
     * {@code ApiExceptionHandler} has no handler for it, so it would have surfaced as an opaque
     * 500 ("เกิดข้อผิดพลาดภายในระบบ"), not a clean conflict. Fixed by adding an explicit {@code
     * !DepositPolicy.bypassesDepositNotice(...)} check to that same precondition — see {@code
     * DepositNoticeService.issue}'s own comment.
     */
    @Test
    void depositNoticeServiceIssue_onWaivedPolicyDeal_isRefusedWithCleanConflict_notAnUncaughtException() {
        long ticketId = createBareTicket();
        tickets.updateDepositPolicy(ticketId, DepositPolicy.WAIVED, "ทดสอบ", salesRepId);
        tickets.markQuotationIssuedForOrderConfirmation(ticketId);
        tickets.updatePaymentStatusUnchecked(ticketId, PaymentTrack.CUSTOMER_CONFIRMED);
        long docId = depositNoticeService.createDraft(ticketId, bareDraftRequest(), salesActor).id();

        assertThatThrownBy(() -> depositNoticeService.issue(docId, salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);
        DepositNoticeDto reread = depositNoticeRepository.findById(docId).orElseThrow();
        assertThat(reread.status()).isEqualTo("DRAFT");
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Bare-ticket fixture helpers — for the repository-level / wrong-way-round tests, where the
    // fact under test is the GUARD, not how the ticket got there.
    // ═══════════════════════════════════════════════════════════════════════════════════════

    private long createBareTicket() {
        return tickets.create(sampleTicketRequest(), tickets.nextTicketCode(), salesRepId, "พนักงานขาย เพย์เมนต์");
    }

    private CreateTicketRequest sampleTicketRequest() {
        return new CreateTicketRequest(
            "ดีลทดสอบ payment track", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of());
    }

    private DepositNoticeDraftRequest bareDraftRequest() {
        return new DepositNoticeDraftRequest(
            "ลูกค้าทดสอบ", "0100000000000", "Bangkok", "Showroom", "REF-BARE-" + UUID.randomUUID(),
            new BigDecimal("0.50"), List.of(),
            List.of(new DepositNoticeItemRequest(
                1, "Bare test item", new BigDecimal("1"), "แผ่น",
                new BigDecimal("100.00"), null, new BigDecimal("100.00"))));
    }

    private String paymentStatusOf(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().paymentStatus();
    }

    private String depositPolicyOf(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().depositPolicy();
    }

    private String fulfillmentStatusOf(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().fulfillmentStatus();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Heavy fixture — mirrors DepositNoticeIssueGuardIntegrationTest's approach for driving a
    // deal through the real Steps 1-6 services up to QUOTATION_ACCEPTED, WITHOUT calling
    // confirmOrder (so each positive test controls exactly when site 1 fires itself).
    // ═══════════════════════════════════════════════════════════════════════════════════════

    private record Deal(long ticketId, long pricingRequestId) {}

    private Deal buildDealToQuotationAccepted(String uniqueTag) {
        long catalogProductId = insertCatalogProduct(FACTORY, "TH",
            "TEST-PAYTRACK-" + uniqueTag + "-" + UUID.randomUUID().toString().substring(0, 8),
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customersRepo.create(
            "บริษัท PaymentTrack " + UUID.randomUUID() + " จำกัด",
            "0100000000029", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0029");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ PaymentTrack " + uniqueTag);
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล PaymentTrack " + uniqueTag, "NORMAL", customer.name(), customer.id(),
                project.id(), null, null, null, List.of(ticketItem("SCG", "Tile PaymentTrack", FACTORY))),
            salesActor);
        long ticketId = created.summary().id();
        long ticketItemId = created.items().get(0).id();

        BigDecimal quantity = new BigDecimal("10");
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile PaymentTrack", "SCG Tile PaymentTrack", null, null,
            "60x60", FACTORY, quantity, quantity, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "payment-track walk " + uniqueTag, UUID.randomUUID().toString(),
            List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveDraftPricingRequestToQuotationAccepted(pricingRequestId, quantity);

        return new Deal(ticketId, pricingRequestId);
    }

    private void driveDraftPricingRequestToQuotationAccepted(long pricingRequestId, BigDecimal quantity) {
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        String email = FACTORY.toLowerCase().replace(" ", "-") + "@example.com";
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

        // V141: Import's last act is markReadyForCosting above. The three costing write calls
        // that used to sit here are severed (@Deprecated, 409) — startReview computes the
        // landed cost itself when the CEO opens the review.

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
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
