package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import th.co.glr.hr.dealquotation.WastageCalculator;
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
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository;
import th.co.glr.hr.importrequest.ImportRequestRenderer;
import th.co.glr.hr.importrequest.ImportRequestRepository;
import th.co.glr.hr.importrequest.ImportRequestService;
import th.co.glr.hr.importrequest.ImportRequestStatus;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
import th.co.glr.hr.pricing.FxRateRepository;
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
    private ImportRequestService importRequestService;
    private ImportRequestRepository importRequestRepository;
    private CustomerRepository customersRepo;
    private ProjectRepository projectsRepo;

    private long salesRepId;
    private UserPrincipal salesActor;
    private UserPrincipal salesManagerActor;
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
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);

        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);

        ticketService = new TicketService(tickets, notifications,
            objectMapper, customersRepo, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customersRepo, new QuotationRenderer(), notifications, new th.co.glr.hr.customerquotation.DiscountApprovalRepository(jdbc));

        depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customersRepo, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        // Wired exactly as ImportRequestPaymentGateIntegrationTest wires it — the per-factory stored
        // aggregate whose issue() shares TicketService's deposit gate with the legacy issueImportRequest.
        importRequestRepository = new ImportRequestRepository(jdbc);
        importRequestService = new ImportRequestService(new ImportRequestQueryRepository(jdbc),
            new ImportRequestRenderer(), importRequestRepository, new FactoryConfigRepository(jdbc), tickets,
            ticketService);

        salesRepId = createEmployee(employees, "พนักงานขาย เพย์เมนต์", "sales-paytrack1@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า เพย์เมนต์", "import-paytrack1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร เพย์เมนต์", "ceo-paytrack1@glr.co.th", "MD", "ผู้บริหาร");
        long accountUserId = createEmployee(employees, "ฝ่ายบัญชี เพย์เมนต์", "account-paytrack1@glr.co.th", "ACC", "ฝ่ายบัญชี");
        long salesManagerId = createEmployee(employees, "ผจก.ขาย เพย์เมนต์", "salesmgr-paytrack1@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");

        insertFactory(FACTORY);
    }

    private void insertFactory(String name) {
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
    void zeroPercentDeal_walksEndToEnd_nullThroughFullyPaid_neverVisitsDepositNoticeIssuedOrDepositPaid() {
        Deal deal = buildDealToQuotationAccepted("zeropercent");
        // C1: this deal has no deposit step because its accepted quotation asks 0% — stamped on the real
        // accepted quotation row BEFORE the order is confirmed; no waiver, no switch.
        stampDepositPercent(deal, 0);

        // site 1: null -> CUSTOMER_CONFIRMED (a 0% deal's entry edge is identical to a deposit deal's).
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        // site 8 (no write, rule 5): eligible with no deposit notice at all — CUSTOMER_CONFIRMED
        // on a 0% deal is sufficient (C3: the import request issues right after the order is confirmed).
        // assertThatCode so that a refusal reads as a FAILURE with this description, not as an ERROR.
        assertThatCode(() -> ticketService.issueImportRequest(deal.ticketId(), importActor))
            .as("a 0% deal may issue its import request right after the order is confirmed")
            .doesNotThrowAnyException();
        ticketService.markIrSent(deal.ticketId(), importActor);
        ticketService.markShipping(deal.ticketId(), importActor);
        ticketService.markGoodsReceived(deal.ticketId(), importActor);
        // applyGoodsReceived's site-3 write is guarded on DEPOSIT_PAID specifically — a 0% deal
        // has no deposit step and is never DEPOSIT_PAID, so it must NOT fire here.
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
    void zeroPercentDeal_partiallyPaid_isVisibleToAccountRoleListScope_beforeAndAfter() {
        Deal deal = buildDealToQuotationAccepted("authz");
        // C1: no deposit step — a 0% accepted quotation, stamped before the order is confirmed.
        stampDepositPercent(deal, 0);
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        // BEFORE (H1, S10 floor): confirmOrder puts the deal at ORDER_RECEIVED (S10), and every live
        // order from S10 on is in account's list by STAGE alone -- so a 0% deal sitting at
        // CUSTOMER_CONFIRMED is already visible, even though CUSTOMER_CONFIRMED is not in
        // ACCOUNT_PENDING_PAYMENT_STATUSES and no due_date is set. This used to assert the opposite.
        assertThat(accountVisibleTicketIds()).contains(deal.ticketId());

        BigDecimal payable = tickets.payableAmount(deal.ticketId());
        assertThat(payable.signum()).as("fixture must have a positive payable amount").isPositive();
        ticketService.recordPayment(deal.ticketId(),
            new RecordPaymentRequest("DEPOSIT", payable.multiply(new BigDecimal("0.30")), null,
                "ชำระบางส่วน", null, null, false),
            accountActor);

        // Site 6, no-deposit branch: CUSTOMER_CONFIRMED -> AWAITING_FINAL_PAYMENT, never DEPOSIT_PAID
        // (a 0% deal has no deposit step, so there is no deposit to confirm).
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.AWAITING_FINAL_PAYMENT);

        // AFTER: the real repository list query through the real service (TicketService.listPage
        // -> TicketRepository.findSummaries -> appendRoleScope), never a mock — the deal stays
        // visible once the partial payment lands (now via BOTH the S10 stage disjunct and
        // ACCOUNT_PENDING_PAYMENT_STATUSES's AWAITING_FINAL_PAYMENT entry).
        assertThat(accountVisibleTicketIds()).contains(deal.ticketId());
    }

    /**
     * Regression for the blocker adversarial review found: a SECOND partial payment on a
     * no-deposit (0%) deal used to throw. reconcilePaymentStatus gated its idempotency check on the
     * hardcoded DEPOSIT_PAID literal while the write target had become dependent on whether the deal
     * has a deposit, so on a no-deposit deal the guard never matched, the block re-entered, and
     * PaymentTrack was asked for an
     * AWAITING_FINAL_PAYMENT -> AWAITING_FINAL_PAYMENT self-loop it correctly refuses. Because
     * recordPayment is @Transactional the receipt insert rolled back too, so the instalment could
     * not be recorded at all — an outright regression, surfacing as HTTP 500.
     *
     * The pre-existing zeroPercentDeal_partiallyPaid test records exactly ONE payment, which is why the
     * whole suite stayed green. This one records TWO. It fails without the fix.
     */
    @Test
    void zeroPercentDeal_secondPartialPayment_isRecorded_andPaymentStatusStaysOnPath() {
        Deal deal = buildDealToQuotationAccepted("twopay");
        // C1: no deposit step — a 0% accepted quotation, stamped before the order is confirmed.
        stampDepositPercent(deal, 0);
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

        // Still on the no-deposit path (AWAITING_FINAL_PAYMENT), and NOT advanced to FULLY_PAID (60% of payable is not full).
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

        // GLA-118: driven as salesActor (the deal's owner, createBareTicket's salesRepId) rather
        // than accountActor as before -- accountActor is no longer allowed to touch the deposit
        // policy at all, so it would now 403 on the ownership gate before ever reaching this
        // guard. salesActor IS the owner, so this still isolates guard c (FR-A-05) on its own.
        assertThatThrownBy(() -> ticketService.waiveDeposit(
                ticketId, DepositPolicy.WAIVED, "สายเกินไป", salesActor))
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
    // C3 (owner rules, 2026-10-05): on a deposit deal the import request waits for the deposit to be CONFIRMED
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * C3 as the story a real deal lives, with NO SQL shortcut on the payment state: the deposit
     * notice is issued through the real services, and only ฝ่ายบัญชี's confirmation moves the deal to
     * DEPOSIT_PAID. Until then the owning rep may PREPARE the import request, but neither route may
     * ISSUE it — the stored per-factory {@code ImportRequestService#issue} and the legacy one-click
     * {@code TicketService#issueImportRequest} — and a refusal writes nothing. After the
     * confirmation the same draft issues and the deal moves into PROCUREMENT.
     *
     * <p>The deal is a DEPOSIT deal explicitly (C1): the chain's accepted quotation carries a NULL
     * {@code deposit_percent}, so 50 is stamped on that real row before the order is confirmed.
     *
     * <p>The per-state truth table (and the 0% deal) is pinned in {@code
     * ImportRequestPaymentGateIntegrationTest}, which stamps {@code payment_status} directly. This is the
     * same rule seen end to end, so that a fixture which merely wrote the status cannot be the only
     * evidence that the status a real notice produces is one the gate refuses.
     */
    @Test
    void importRequest_staysClosed_untilAccountConfirmsTheDeposit_onBothRoutes() {
        Deal deal = buildDealToQuotationAccepted("irgate");
        stampDepositPercent(deal, 50); // a DEPOSIT deal, explicitly — before the order is confirmed
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto noticeDraft = orderConfirmation.createDepositNoticeFromQuotation(
            deal.pricingRequestId(), new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(noticeDraft.id(), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        String stageAtNotice = salesStageOf(deal.ticketId());

        // The rep may still PREPARE the form while the deposit is outstanding...
        List<ImportRequestDto> drafts = importRequestService.createDrafts(deal.ticketId(), null, salesActor);
        assertThat(drafts).hasSize(1);
        long draftId = drafts.get(0).id();
        assertThat(drafts.get(0).status()).isEqualTo(ImportRequestStatus.DRAFT);

        // ...but neither route may ISSUE it on a notice alone, and the refusal is about the deposit.
        assertRefusedForTheDeposit("ImportRequestService#issue on a deal whose deposit is only noticed",
            () -> importRequestService.issue(draftId, null, salesActor));
        assertRefusedForTheDeposit("TicketService#issueImportRequest on a deal whose deposit is only noticed",
            () -> ticketService.issueImportRequest(deal.ticketId(), importActor));

        // Nothing was written by either refusal.
        ImportRequestDto stillDraft = importRequestRepository.findById(draftId).orElseThrow();
        assertThat(stillDraft.status()).isEqualTo(ImportRequestStatus.DRAFT);
        assertThat(stillDraft.docNumber()).isNull();
        assertThat(fulfillmentStatusOf(deal.ticketId())).isNull();
        assertThat(salesStageOf(deal.ticketId())).isEqualTo(stageAtNotice);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        assertThat(countEvents(deal.ticketId(), TicketEventKind.IR_ISSUED)).isZero();

        // ฝ่ายบัญชี confirms the deposit was received — the only thing that opens the gate.
        ticketService.confirmDepositPaid(deal.ticketId(), accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_PAID);

        ImportRequestDto issued = importRequestService.issue(draftId, null, salesActor);
        assertThat(issued.status()).isEqualTo(ImportRequestStatus.ISSUED);
        assertThat(issued.docNumber()).isNotBlank();
        assertThat(fulfillmentStatusOf(deal.ticketId())).isEqualTo(FulfilmentStatus.IR_ISSUED);
        assertThat(salesStageOf(deal.ticketId())).isEqualTo(DealStage.PROCUREMENT);
    }

    /**
     * A refusal because of the DEPOSIT specifically — a 409 whose message names it. A bare CONFLICT
     * could be any of the other 409s on these routes (already issued, no items, no lead time), so
     * the message is the discriminator; only the substring is pinned, so the sentence may be reworded.
     *
     * <p>{@code catchThrowable} + a described {@code assertThat}, not {@code assertThatThrownBy(..).as(..)}:
     * the latter fails with a bare "Expecting code to raise a throwable" BEFORE the description is applied,
     * so a red run could not say which call was accepted. {@code what} names the call.
     */
    private static void assertRefusedForTheDeposit(String what, ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("%s must be REFUSED for the deposit -- the call was accepted", what)
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("มัดจำ");
            });
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // C5 (owner rules, 2026-10-05): on a deposit deal nothing is DELIVERED until the deposit is CONFIRMED
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * C5 as the story a real deal lives, with NO SQL shortcut on the payment state. The owning rep may
     * DECLARE every line from stock while only the customer's confirmation is in — the declaration stays
     * open — but neither delivery writer works then, and still not once the deposit NOTICE is issued: a
     * notice is a document, not money. Each of those refusals is a 409 about the deposit and writes
     * nothing. Only ฝ่ายบัญชี's confirmation of the deposit opens delivery, and then the whole deal is
     * delivered with ONLY the deposit received — the balance is not required.
     *
     * <p>The deal is a DEPOSIT deal explicitly (C1): 50 is stamped on the chain's accepted quotation,
     * whose {@code deposit_percent} is otherwise NULL, before the order is confirmed.
     *
     * <p>The per-state truth table (both sources, both writers, every actor, the 0% deal and the
     * advertised actions) is pinned in {@code DeliveryDepositGateIntegrationTest}, which stamps the
     * payment status directly. This is the same rule end to end, so that a fixture which merely wrote
     * the status cannot be the only evidence that the statuses a real order and a real notice produce
     * are ones the gate refuses. The stock is declared BEFORE the notice is issued, which is the order
     * in which a rep with goods on the shelf works; the declaration reads no payment state, and the
     * notice needs only a quotation-issued deal at CUSTOMER_CONFIRMED, so the order is possible.
     */
    @Test
    void delivery_staysClosed_untilAccountConfirmsTheDeposit_evenWithStockDeclared() {
        Deal deal = buildDealToQuotationAccepted("delivgate");
        stampDepositPercent(deal, 50); // a DEPOSIT deal, explicitly — before the order is confirmed
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);

        // The owning rep declares every line from stock — allowed before the deposit. Nothing is asserted
        // about the STAGE here: whether a declaration should move it before the deposit is another finding.
        TicketItemDto line = tickets.findById(deal.ticketId()).orElseThrow().items().get(0);
        ticketService.reserveStock(deal.ticketId(), new StockReservationRequest(List.of(
            new StockReservationRequest.Line(line.id(), line.qty(), "ทดสอบ"))), salesActor);
        assertThat(fulfillmentStatusOf(deal.ticketId())).isEqualTo(FulfilmentStatus.FROM_STOCK);
        BigDecimal partialQty = line.qty().multiply(new BigDecimal("0.40")).setScale(2, RoundingMode.DOWN);
        assertThat(partialQty).as("a positive part of the line").isPositive().isLessThan(line.qty());

        // On the customer's confirmation alone: neither writer works, and nothing is written.
        assertDeliveryRefusedForTheDeposit("recordPartialDelivery on the customer's confirmation alone",
            deal.ticketId(), line.id(), () -> ticketService.recordPartialDelivery(
                deal.ticketId(), stockDelivery(line.id(), partialQty), salesActor));
        assertDeliveryRefusedForTheDeposit("completeDelivery on the customer's confirmation alone",
            deal.ticketId(), line.id(), () -> ticketService.completeDelivery(
                deal.ticketId(), new CompleteDeliveryRequest(null, null), salesActor));

        // The rep issues the deposit notice — still not money, so still refused.
        DepositNoticeDto noticeDraft = orderConfirmation.createDepositNoticeFromQuotation(
            deal.pricingRequestId(), new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(noticeDraft.id(), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        assertDeliveryRefusedForTheDeposit("recordPartialDelivery once the deposit notice is issued",
            deal.ticketId(), line.id(), () -> ticketService.recordPartialDelivery(
                deal.ticketId(), stockDelivery(line.id(), partialQty), salesActor));
        assertDeliveryRefusedForTheDeposit("completeDelivery once the deposit notice is issued",
            deal.ticketId(), line.id(), () -> ticketService.completeDelivery(
                deal.ticketId(), new CompleteDeliveryRequest(null, null), salesActor));

        // ฝ่ายบัญชี confirms the deposit was received — the only thing that opens delivery.
        ticketService.confirmDepositPaid(deal.ticketId(), accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_PAID);

        ticketService.completeDelivery(deal.ticketId(), new CompleteDeliveryRequest(null, null), salesActor);
        assertThat(fulfillmentStatusOf(deal.ticketId())).isEqualTo(FulfilmentStatus.FULLY_DELIVERED);
        assertThat(salesStageOf(deal.ticketId())).isEqualTo(DealStage.DELIVERED);
        assertThat(paymentStatusOf(deal.ticketId()))
            .as("only the deposit was received — the balance is not required (C5: goods never wait for the balance)")
            .isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(qtyDeliveredOf(line.id())).isEqualByComparingTo(line.qty());
        assertThat(countEvents(deal.ticketId(), TicketEventKind.DELIVERY_COMPLETED)).isEqualTo(1L);
    }

    /**
     * A refusal because of the DEPOSIT (409 + the deposit word, see {@link #assertRefusedForTheDeposit}) that
     * also left the deal exactly as it found it: the same stage, fulfilment and payment status, the line's
     * {@code qty_delivered} unmoved, no {@code sales.delivery_record} row, no delivery event.
     */
    private void assertDeliveryRefusedForTheDeposit(String what, long ticketId, long itemId,
                                                    ThrowableAssert.ThrowingCallable call) {
        String fulfilmentBefore = fulfillmentStatusOf(ticketId);
        String stageBefore = salesStageOf(ticketId);
        String paymentBefore = paymentStatusOf(ticketId);
        BigDecimal deliveredBefore = qtyDeliveredOf(itemId);

        assertRefusedForTheDeposit(what, call);

        assertThat(fulfillmentStatusOf(ticketId)).as("sales.ticket.fulfillment_status").isEqualTo(fulfilmentBefore);
        assertThat(salesStageOf(ticketId)).as("sales.ticket.sales_stage").isEqualTo(stageBefore);
        assertThat(paymentStatusOf(ticketId)).as("sales.ticket.payment_status").isEqualTo(paymentBefore);
        assertThat(qtyDeliveredOf(itemId)).as("sales.ticket_item.qty_delivered").isEqualByComparingTo(deliveredBefore);
        assertThat(deliveryRecordCount(ticketId)).as("sales.delivery_record rows").isZero();
        assertThat(countEvents(ticketId, TicketEventKind.DELIVERY_RECORDED)).as("DELIVERY_RECORDED events").isZero();
        assertThat(countEvents(ticketId, TicketEventKind.DELIVERY_COMPLETED)).as("DELIVERY_COMPLETED events").isZero();
    }

    private static RecordDeliveryRequest stockDelivery(long itemId, BigDecimal qty) {
        return new RecordDeliveryRequest("STOCK", null, List.of(new RecordDeliveryRequest.Line(itemId, qty)), null);
    }

    private BigDecimal qtyDeliveredOf(long itemId) {
        return jdbc.queryForObject("SELECT qty_delivered FROM sales.ticket_item WHERE item_id = :itemId",
            Map.of("itemId", itemId), BigDecimal.class);
    }

    private long deliveryRecordCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.delivery_record WHERE ticket_id = :ticketId",
            Map.of("ticketId", ticketId), Long.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // C4 (owner rules, 2026-10-05): declaring stock must not move a deposit deal past ขั้น 10 before the
    // deposit is CONFIRMED
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Finding D3 (C4) as the story a real deal lives, with NO SQL shortcut on the payment state. The owning
     * rep declares every line from stock while only the customer's confirmation is in: the declaration is
     * saved and takes the deal off the import axis ({@code FROM_STOCK}), but the deal stays at ขั้น 10
     * ({@code ORDER_RECEIVED}) — and it stays there when the deposit NOTICE is issued, because a notice is
     * a document, not money. Only ฝ่ายบัญชี's confirmation of the deposit moves it on, to {@code
     * DELIVERY_SCHEDULING}, with {@code FROM_STOCK} kept.
     *
     * <p>The deal is a DEPOSIT deal explicitly (C1): 50 is stamped on the chain's accepted quotation,
     * whose {@code deposit_percent} is otherwise NULL, before the order is confirmed.
     *
     * <p>The D2 journey above says it asserts nothing about the STAGE after the declaration ("another
     * finding"); this one is that other finding. The per-state truth table, the 0% deal, the release and
     * the guards are pinned in {@code StockDeclarationDepositHoldIntegrationTest}, which stamps the payment
     * status directly. This is the same rule end to end, so that a fixture which merely wrote the status
     * cannot be the only evidence that the statuses a real order and a real notice produce are ones that
     * hold the stage.
     */
    @Test
    void stockDeclaration_holdsTheStage_untilAccountConfirmsTheDeposit() {
        Deal deal = buildDealToQuotationAccepted("stockhold");
        stampDepositPercent(deal, 50); // a DEPOSIT deal, explicitly — before the order is confirmed
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.CUSTOMER_CONFIRMED);
        assertThat(salesStageOf(deal.ticketId())).isEqualTo(DealStage.ORDER_RECEIVED);

        // The owning rep declares every line from stock: saved, and off the import axis...
        TicketItemDto line = tickets.findById(deal.ticketId()).orElseThrow().items().get(0);
        ticketService.reserveStock(deal.ticketId(), new StockReservationRequest(List.of(
            new StockReservationRequest.Line(line.id(), line.qty(), "ทดสอบ"))), salesActor);
        assertThat(tickets.findById(deal.ticketId()).orElseThrow().items().get(0).qtyFromStock())
            .as("the declaration is saved").isEqualByComparingTo(line.qty());
        assertThat(fulfillmentStatusOf(deal.ticketId())).isEqualTo(FulfilmentStatus.FROM_STOCK);
        // ...but the deal does not pass ขั้น 10 on the customer's confirmation alone.
        assertThat(salesStageOf(deal.ticketId())).as("stage after the declaration, deposit not received")
            .isEqualTo(DealStage.ORDER_RECEIVED);

        // The rep issues the deposit notice — a document, not money — so the stage is still held.
        DepositNoticeDto noticeDraft = orderConfirmation.createDepositNoticeFromQuotation(
            deal.pricingRequestId(), new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(noticeDraft.id(), salesActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
        assertThat(fulfillmentStatusOf(deal.ticketId())).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(salesStageOf(deal.ticketId())).as("stage after the notice, deposit not received")
            .isEqualTo(DealStage.ORDER_RECEIVED);

        // ฝ่ายบัญชี confirms the deposit was received — the held deal goes on to DELIVERY_SCHEDULING.
        ticketService.confirmDepositPaid(deal.ticketId(), accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(fulfillmentStatusOf(deal.ticketId())).isEqualTo(FulfilmentStatus.FROM_STOCK);
        assertThat(salesStageOf(deal.ticketId())).as("stage once account confirms the deposit")
            .isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Cancel / lost after money (owner rules, 2026-10-05): once ฝ่ายบัญชี has confirmed a payment, the
    // owning rep and ผจก.ขาย can no longer cancel the deal or mark it lost by themselves
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * The rule as the story a real deal lives, with NO SQL shortcut on the payment state: the deposit
     * notice is issued through the real services and ฝ่ายบัญชี's {@code confirmDepositPaid} moves the deal
     * to DEPOSIT_PAID. From then on the owning rep cannot mark the deal lost or cancel it BY THEMSELVES,
     * and ผจก.ขาย cannot mark it lost (cancel is the owning rep's alone today, so it is not driven for
     * ผจก.ขาย): it needs the CEO's approval. Each call is refused, or held for the CEO's approval — either
     * way the deal has not ended: the lifecycle stays ACTIVE, the ticket status and the payment status are
     * unchanged and neither a MARKED_LOST nor a CANCELLED event is written. The call is therefore
     * TOLERATED ({@link #tolerateRefusal}: only an {@code ApiException} is swallowed), because the owner's
     * rule does not say which of the two designs is built and a test must not demand an exception to
     * stay valid under both. Status and message are not pinned.
     *
     * <p>Approval by the CEO is a flow that does not exist yet and is NOT tested here, and neither is what
     * the CEO may do directly. The three money states the rule names (DEPOSIT_PAID,
     * AWAITING_FINAL_PAYMENT, FULLY_PAID) are pinned at unit level in {@code TicketServiceTest}; this is
     * the end-to-end half through the real service and repository, on the state a real order produces.
     * The old code accepted every one of these calls (the green mirror below shows the same calls working
     * one step earlier), so each case is red before the rule exists — on "the deal ended": the lifecycle
     * assertion comes first so the red line reads "lifecycle expected ACTIVE but was CLOSED_LOST /
     * CANCELLED".
     */
    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({"sales, MARK_LOST", "sales_manager, MARK_LOST", "sales, CANCEL"})
    void afterAccountConfirmsTheDeposit_theOwningRepAndSalesManager_cannotMarkTheDealLostOrCancelIt(
            String role, String action) {
        Deal deal = dealWithTheDepositConfirmedByAccount("afterdep-" + role + "-" + action);
        long ticketId = deal.ticketId();
        String statusBefore = statusOf(ticketId);
        assertThat(lifecycleOf(ticketId)).isEqualTo(DealLifecycle.ACTIVE);
        assertThat(countEvents(ticketId, TicketEventKind.MARKED_LOST)).isZero();
        assertThat(countEvents(ticketId, TicketEventKind.CANCELLED)).isZero();

        // Refused, or held for the CEO's approval: the call is tolerated, what must hold is that the deal did not end.
        tolerateRefusal(() -> endTheDeal(ticketId, role, action));

        assertThat(lifecycleOf(ticketId))
            .as("lifecycle after %s's %s on a deal whose deposit ฝ่ายบัญชี has confirmed -- the deal must not have ended",
                role, action)
            .isEqualTo(DealLifecycle.ACTIVE);
        assertThat(statusOf(ticketId)).as("ticket status after %s's %s", role, action).isEqualTo(statusBefore);
        assertThat(paymentStatusOf(ticketId)).as("payment status after %s's %s", role, action)
            .isEqualTo(PaymentTrack.DEPOSIT_PAID);
        assertThat(countEvents(ticketId, TicketEventKind.MARKED_LOST)).as("MARKED_LOST events after %s's %s", role, action)
            .isZero();
        assertThat(countEvents(ticketId, TicketEventKind.CANCELLED)).as("CANCELLED events after %s's %s", role, action)
            .isZero();
    }

    /**
     * The green mirror: the SAME calls by the SAME people work one step earlier, on a deal whose deposit
     * NOTICE is issued but whose money ฝ่ายบัญชี has not confirmed — a notice is a document, not money — so
     * the refusal above is about the confirmed payment and nothing else (not the actor, not the status,
     * not the stage). Marking lost leaves the deal CLOSED_LOST with one MARKED_LOST event; cancelling
     * leaves it CANCELLED with one CANCELLED event.
     */
    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({"sales, MARK_LOST", "sales_manager, MARK_LOST", "sales, CANCEL"})
    void beforeAnyPaymentIsConfirmed_theOwningRepAndSalesManager_stillCanMarkTheDealLostOrCancelIt(
            String role, String action) {
        Deal deal = dealWithTheDepositNoticeIssued("beforedep-" + role + "-" + action);
        long ticketId = deal.ticketId();
        assertThat(paymentStatusOf(ticketId)).isEqualTo(PaymentTrack.DEPOSIT_NOTICE_ISSUED);

        endTheDeal(ticketId, role, action);

        if ("CANCEL".equals(action)) {
            assertThat(lifecycleOf(ticketId)).isEqualTo(DealLifecycle.CANCELLED);
            assertThat(countEvents(ticketId, TicketEventKind.CANCELLED)).isEqualTo(1L);
        } else {
            assertThat(lifecycleOf(ticketId)).isEqualTo(DealLifecycle.CLOSED_LOST);
            assertThat(countEvents(ticketId, TicketEventKind.MARKED_LOST)).isEqualTo(1L);
        }
    }

    /** A deposit deal whose notice is ISSUED (payment_status DEPOSIT_NOTICE_ISSUED): no money confirmed yet. */
    private Deal dealWithTheDepositNoticeIssued(String tag) {
        Deal deal = buildDealToQuotationAccepted(tag);
        stampDepositPercent(deal, 50); // a DEPOSIT deal, explicitly — before the order is confirmed
        orderConfirmation.confirmOrder(
            deal.pricingRequestId(), new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto noticeDraft = orderConfirmation.createDepositNoticeFromQuotation(
            deal.pricingRequestId(), new CreateDepositNoticeFromQuotationRequest(null), salesActor);
        depositNoticeService.issue(noticeDraft.id(), salesActor);
        return deal;
    }

    /** The same deal after ฝ่ายบัญชี's {@code confirmDepositPaid} — the first confirmed money (DEPOSIT_PAID). */
    private Deal dealWithTheDepositConfirmedByAccount(String tag) {
        Deal deal = dealWithTheDepositNoticeIssued(tag);
        ticketService.confirmDepositPaid(deal.ticketId(), accountActor);
        assertThat(paymentStatusOf(deal.ticketId())).isEqualTo(PaymentTrack.DEPOSIT_PAID);
        return deal;
    }

    /** What the parameterised tests above drive: the role's own call, with the reason that role would give. */
    private void endTheDeal(long ticketId, String role, String action) {
        UserPrincipal actor = "sales_manager".equals(role) ? salesManagerActor : salesActor;
        if ("CANCEL".equals(action)) {
            ticketService.cancel(ticketId, DealCancelReason.OWNER_CANCELLED, "ลูกค้ายกเลิกโครงการ", actor);
        } else {
            ticketService.markLost(ticketId, DealLostReason.PRICE, "แพ้ราคาคู่แข่ง", actor);
        }
    }

    /**
     * The call may be refused (an {@code ApiException}) or accepted without ending the deal — the CEO's
     * approval flow may be built as a refusal or as the same call queueing a request, and both satisfy the
     * owner's rule. Only an {@code ApiException} is swallowed: any other failure still fails the test.
     */
    private static void tolerateRefusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException refusedOrHeld) {
            // tolerated: the assertions that follow pin what must hold either way
        }
    }

    private String lifecycleOf(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().lifecycle();
    }

    private String statusOf(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().status();
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

    /**
     * C1: whether a deal has a deposit is decided by its ACCEPTED quotation's {@code deposit_percent} —
     * above 0 a deposit deal, 0 no deposit step at all. The pricing-request chain's quotation carries NULL
     * (the legacy engine never writes it), so a test that needs the deposit stated stamps it on that real
     * accepted row BEFORE {@code confirmOrder} — never after the order is confirmed, and never by writing
     * the deal-level switch.
     */
    private void stampDepositPercent(Deal deal, int percent) {
        int rows = jdbc.update(
            "UPDATE sales.quotation SET deposit_percent = :p WHERE ticket_id = :t AND doc_status = 'ACCEPTED'",
            Map.of("p", (short) percent, "t", deal.ticketId()));
        assertThat(rows).as("the deal's accepted quotation row").isEqualTo(1);
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

    private String salesStageOf(long ticketId) {
        return tickets.findById(ticketId).orElseThrow().summary().salesStage();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // Heavy fixture — mirrors DepositNoticeIssueGuardIntegrationTest's approach for driving a
    // deal through the real Steps 1-6 services up to QUOTATION_ACCEPTED, WITHOUT calling
    // confirmOrder (so each positive test controls exactly when site 1 fires itself).
    // ═══════════════════════════════════════════════════════════════════════════════════════

    private record Deal(long ticketId, long pricingRequestId) {}

    private Deal buildDealToQuotationAccepted(String uniqueTag) {
        long catalogProductId = insertCatalogProduct(FACTORY, "IT",
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
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a
        // quantity are now required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead. roundToFullBox=false +
        // piecesInput=quantity keeps the derived requestedQty byte-identical to `quantity`.
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile PaymentTrack", "SCG Tile PaymentTrack",
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, quantity.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
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
        // No-op now: FactoryQuoteService.send is synchronous (manual-RFQ redesign) --
        // there is no dispatch/worker queue left to drain. Kept (rather than removing
        // every call site) so this helper's callers do not all need to be revisited
        // individually.
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
