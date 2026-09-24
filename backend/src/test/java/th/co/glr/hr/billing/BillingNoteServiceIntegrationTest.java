package th.co.glr.hr.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
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
import th.co.glr.hr.customerquotation.DiscountApprovalRepository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticeRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceDocumentDto;
import th.co.glr.hr.deposit.RemainingInvoiceRenderer;
import th.co.glr.hr.deposit.RemainingInvoiceRepository;
import th.co.glr.hr.deposit.RemainingInvoiceService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
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
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
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
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-Postgres coverage for the STORED ใบวางบิล (billing note, GLA-99 step 3): the candidate
 * pool + outstanding computation, DRAFT/ISSUED/SUPERSEDED/CANCELLED lifecycle, the shared {@code
 * AR_GLR} numbering, the DB-level "no double billing" invariant (V189's own {@code
 * ux_billing_note_line_source_live}), and — per CLAUDE.md's "permission changes must ship
 * evidence" requirement — the write/read authz gate written WRONG-WAY-ROUND: a sales rep with no
 * grant, import, hr, and a plain employee with no grant must all be refused and write nothing;
 * a grant holder in ANY role (including plain {@code employee}) and the CEO must succeed.
 *
 * <p>Drives real deals through the REAL Steps 1-6 services to an ACCEPTED quotation + ISSUED
 * deposit notice (+ optionally an ISSUED remaining invoice), the same fixture-building approach
 * {@code RemainingInvoiceServiceIntegrationTest} uses, because a mocked repository could not prove
 * the candidate query's own SQL — the JOIN across {@code remaining_invoice}/{@code deposit_notice}/
 * {@code payment_receipt}/{@code ticket} — actually behaves as claimed.
 */
class BillingNoteServiceIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private TicketService ticketService;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private DepositNoticeService depositNoticeService;
    private OrderConfirmationService orderConfirmation;
    private RemainingInvoiceService remainingInvoiceService;
    private CustomerRepository customersRepo;
    private EmployeeAuthRepository employeeAuth;

    private BillingNoteRepository billingNoteRepository;
    private BillingNoteService billingNoteService;

    private long salesRepId;
    private UserPrincipal salesActor;          // sales, NO grant
    private UserPrincipal importActor;         // import, NO grant
    private UserPrincipal hrActor;             // hr, NO grant
    private UserPrincipal employeeNoGrantActor;
    private UserPrincipal employeeWithGrantActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;

    private static final String FACTORY = "Factory BN";

    @BeforeEach
    void wireStepsServices() {
        tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customersRepo = new CustomerRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        employeeAuth = new EmployeeAuthRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-billing-note-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes,
            pricingRequests, fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);

        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);

        ticketService = new TicketService(tickets, notifications,
            objectMapper, customersRepo, new QuotationRenderer(), pricingRequestService, employeeAuth);

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customersRepo, new QuotationRenderer(), notifications, new DiscountApprovalRepository(jdbc));

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customersRepo, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        RemainingInvoiceRepository remainingInvoiceRepository = new RemainingInvoiceRepository(jdbc);
        remainingInvoiceService = new RemainingInvoiceService(
            remainingInvoiceRepository, depositNoticeService, tickets, new RemainingInvoiceRenderer());

        billingNoteRepository = new BillingNoteRepository(jdbc);
        billingNoteService = transactional(new BillingNoteService(
            billingNoteRepository, customersRepo, tickets, employeeAuth, new BillingNoteRenderer(), jdbc));

        salesRepId = createEmployee(employees, "พนักงานขาย BN", "sales-bn1@glr.co.th", "SALES", "แผนกขาย", false);
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า BN", "import-bn1@glr.co.th", "PCIM", "ฝ่ายนำเข้า", false);
        long hrUserId = createEmployee(employees, "ฝ่ายบุคคล BN", "hr-bn1@glr.co.th", "HR", "ฝ่ายบุคคล", false);
        long ceoUserId = createEmployee(employees, "ผู้บริหาร BN", "ceo-bn1@glr.co.th", "MD", "ผู้บริหาร", false);
        long accountUserId = createEmployee(employees, "บัญชี BN", "account-bn1@glr.co.th", "ACCT", "บัญชี", false);
        long salesManagerUserId = createEmployee(employees, "ผจก.ขาย BN", "sales-mgr-bn1@glr.co.th", "SALESMGR", "แผนกขาย", false);
        long employeeNoGrantId = createEmployee(employees, "พนักงานทั่วไป BN", "emp-bn1@glr.co.th", "GEN", "ทั่วไป", false);
        // The grant must work for a PLAIN employee role — ภิญญดา (QC&ISO) is the real-world shape
        // this pins (see EmployeeAuthRepository#canIssueBillingNote's own Javadoc).
        long employeeWithGrantId = createEmployee(employees, "ผู้มีสิทธิ์วางบิล BN", "emp-grant-bn1@glr.co.th", "QC", "QC&ISO", true);

        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        hrActor = actor(hrUserId, "hr");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");
        employeeNoGrantActor = actor(employeeNoGrantId, "employee");
        employeeWithGrantActor = actor(employeeWithGrantId, "employee");
    }

    @Test
    void concurrentIssue_preventsDraftUpdateFromReplacingIssuedLines() {
        CustomerDto customer = createCustomer("ConcurrentIssue");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            manualDraftRequest(null, "100.00"), employeeWithGrantActor);
        BillingNoteRepository racingRepository = new BillingNoteRepository(jdbc) {
            @Override
            public int updateDraftFields(long id, LocalDate date, String due, String note) {
                // Commit issuance on another connection after the editor read DRAFT, but before
                // its guarded UPDATE. Both calls run through real transactional service proxies.
                java.util.concurrent.CompletableFuture.runAsync(() ->
                    billingNoteService.issue(id, employeeWithGrantActor))
                    .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).join();
                return super.updateDraftFields(id, date, due, note);
            }
        };
        BillingNoteService editingService = transactional(new BillingNoteService(racingRepository,
            customersRepo, tickets, employeeAuth, new BillingNoteRenderer(), jdbc));

        assertThatThrownBy(() -> editingService.updateDraft(draft.id(),
            manualDraftRequest(null, "999.00"), employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        BillingNoteDocumentDto issued = billingNoteService.get(draft.id(), accountActor);
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(issued.lines()).singleElement().satisfies(line -> {
            assertThat(line.id()).isEqualTo(draft.lines().getFirst().id());
            assertThat(line.amount()).isEqualByComparingTo("100.00");
        });
        assertThat(countLines(draft.id(), "ISSUED")).isEqualTo(1);
        assertThat(countLines(draft.id(), "DRAFT")).isZero();
    }

    @Test
    void fractionalManualAmounts_totalMatchesStoredLines_onCreateAndUpdate() {
        CustomerDto customer = createCustomer("FractionalAmounts");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            manualDraftRequest(null, "0.005", "0.005"), employeeWithGrantActor);
        assertThat(draft.totalAmount()).isEqualByComparingTo("0.02");
        assertThat(draft.lines()).allSatisfy(line ->
            assertThat(line.amount()).isEqualByComparingTo("0.01"));

        BillingNoteDocumentDto updated = billingNoteService.updateDraft(draft.id(),
            manualDraftRequest(null, "1.005", "1.005"), employeeWithGrantActor);
        assertThat(updated.totalAmount()).isEqualByComparingTo("2.02");
        assertThat(updated.totalAmount()).isEqualByComparingTo(updated.lines().stream()
            .map(BillingNoteLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void manualAmountRoundingToZero_isRejectedAndDraftCreationRollsBack() {
        CustomerDto customer = createCustomer("BelowSatang");
        assertThatThrownBy(() -> billingNoteService.createDraft(customer.id(),
            manualDraftRequest(null, "0.001"), employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(billingNoteService.list(customer.id(), accountActor)).isEmpty();
    }

    @Test
    void issue_freezesMissingBillDateInBangkokAndPreservesAnExplicitDate() {
        CustomerDto customer = createCustomer("FrozenBillDate");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            manualDraftRequest(null, "100.00"), employeeWithGrantActor);
        assertThat(draft.billDate()).isNull();
        java.time.ZoneId bangkok = java.time.ZoneId.of("Asia/Bangkok");
        LocalDate before = LocalDate.now(bangkok);
        BillingNoteDocumentDto issued = billingNoteService.issue(draft.id(), employeeWithGrantActor);
        assertThat(issued.billDate()).isIn(before, LocalDate.now(bangkok));
        assertThat(billingNoteService.get(issued.id(), accountActor).billDate()).isEqualTo(issued.billDate());

        LocalDate explicit = LocalDate.of(2026, 1, 2);
        BillingNoteDocumentDto datedDraft = billingNoteService.createDraft(customer.id(),
            manualDraftRequest(explicit, "200.00"), employeeWithGrantActor);
        BillingNoteDocumentDto datedIssued = billingNoteService.issue(datedDraft.id(), employeeWithGrantActor);
        assertThat(datedIssued.billDate()).isEqualTo(explicit);
        BillingNoteDocumentDto correction = billingNoteService.revise(datedIssued.id(), employeeWithGrantActor);
        assertThat(billingNoteService.issue(correction.id(), employeeWithGrantActor).billDate()).isEqualTo(explicit);
    }

    private BillingNoteDraftRequest manualDraftRequest(LocalDate billDate, String... amounts) {
        List<BillingNoteLineSelection> lines = java.util.stream.IntStream.range(0, amounts.length)
            .mapToObj(i -> new BillingNoteLineSelection("MANUAL", null, "MANUAL-" + i,
                LocalDate.of(2026, 1, 1), null, new BigDecimal(amounts[i]), null)).toList();
        return new BillingNoteDraftRequest("FREIGHT", billDate, null, null, lines);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Candidates + outstanding computation
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void candidates_listsIssuedRemainingInvoiceAndDepositNoticeAcrossDeals_withFullOutstandingAmounts() {
        CustomerDto customer = createCustomer("Cand1");
        DealFixture dealA = buildAcceptedDeal(customer, salesActor, "CandA");
        RemainingInvoiceDocumentDto ri = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(dealA.ticketId(), null, salesActor).id(), salesActor);
        DealFixture dealB = buildAcceptedDeal(customer, salesActor, "CandB");

        BillingNoteCandidatesDto result = billingNoteService.candidates(customer.id(), accountActor);

        assertThat(result.customerId()).isEqualTo(customer.id());
        List<BillingNoteCandidateDto> riCandidates = result.candidates().stream()
            .filter(c -> "REMAINING_INVOICE".equals(c.sourceType())).toList();
        List<BillingNoteCandidateDto> dnCandidates = result.candidates().stream()
            .filter(c -> "DEPOSIT_NOTICE".equals(c.sourceType())).toList();
        assertThat(riCandidates).hasSize(1);
        assertThat(riCandidates.get(0).sourceId()).isEqualTo(ri.id());
        assertThat(riCandidates.get(0).outstandingAmount()).isEqualByComparingTo(ri.grandTotal());
        // Both deals' deposit notices show up (dealA's own + dealB's own) — 2 DEPOSIT_NOTICE rows.
        assertThat(dnCandidates).hasSize(2);
        assertThat(result.alreadyBilled()).isEmpty();
    }

    @Test
    void candidates_excludesAFullyPaidDepositNotice() {
        CustomerDto customer = createCustomer("CandPaid");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "CandPaid");
        DepositNoticeDto notice = depositNoticeService.getById(deal.depositNoticeId(), salesActor);

        // A direct payment_receipt row for the notice's own FULL VAT-inclusive total_payable —
        // bypassing TicketService#confirmDepositPaid deliberately: that path records only the
        // pre-VAT depositAmount, not the VAT-inclusive figure this candidate query's own
        // "outstanding" is scoped to (GLA-107 answer 1). This is a direct-JDBC fixture write, the
        // same technique RemainingInvoiceServiceIntegrationTest already uses to set up a precise
        // money scenario, not a claim about what confirmDepositPaid itself records.
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, deposit_notice_id)
            VALUES (:ticketId, 'DEPOSIT', :amount, :actorId, :depositNoticeId)
            """, Map.of("ticketId", deal.ticketId(), "amount", notice.totalPayable(),
                "actorId", accountActor.id(), "depositNoticeId", deal.depositNoticeId()));

        BillingNoteCandidatesDto result = billingNoteService.candidates(customer.id(), accountActor);
        assertThat(result.candidates()).noneMatch(c -> "DEPOSIT_NOTICE".equals(c.sourceType()) && c.sourceId() == deal.depositNoticeId());
        assertThat(result.alreadyBilled()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createDraft_thenIssue_mintsFirstVersionOfANewBase() {
        CustomerDto customer = createCustomer("Life1");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "Life1");

        BillingNoteDraftRequest req = new BillingNoteDraftRequest("GOODS", LocalDate.now(), "ครบกำหนด 30 วัน", "ทดสอบ",
            List.of(new BillingNoteLineSelection("DEPOSIT_NOTICE", deal.depositNoticeId(), null, null, null, null, null)));
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.docNumber()).isNull();
        assertThat(draft.lines()).hasSize(1);
        assertThat(draft.totalAmount()).isEqualByComparingTo(draft.lines().get(0).amount());

        BillingNoteDocumentDto issued = billingNoteService.issue(draft.id(), employeeWithGrantActor);
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.baseNumber()).matches("GLR\\d{7}");
        assertThat(issued.version()).isEqualTo(1);
        assertThat(issued.docNumber()).isEqualTo(issued.baseNumber() + "-1");
    }

    @Test
    void issue_twoTypesSameCustomer_areIndependentAndBothLive() {
        CustomerDto customer = createCustomer("TwoTypes");
        DealFixture dealGoods = buildAcceptedDeal(customer, salesActor, "TwoTypesGoods");
        DealFixture dealFreight = buildAcceptedDeal(customer, salesActor, "TwoTypesFreight");

        BillingNoteDocumentDto goods = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealGoods.depositNoticeId()))), employeeWithGrantActor).id(), employeeWithGrantActor);
        BillingNoteDocumentDto freight = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealFreight.depositNoticeId()))), employeeWithGrantActor).id(), employeeWithGrantActor);

        assertThat(goods.status()).isEqualTo("ISSUED");
        assertThat(freight.status()).isEqualTo("ISSUED");
        assertThat(goods.baseNumber()).isNotEqualTo(freight.baseNumber());
    }

    @Test
    void manualLine_createDraftWithNoSourceDocument() {
        CustomerDto customer = createCustomer("Manual1");
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, "ค่าขนส่ง",
            List.of(new BillingNoteLineSelection("MANUAL", null, "INV-EXT-001", LocalDate.now(),
                LocalDate.now().plusDays(30), new BigDecimal("5000.00"), "ค่าขนส่งภายนอก")));
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor);
        assertThat(draft.lines()).hasSize(1);
        assertThat(draft.lines().get(0).sourceId()).isNull();
        assertThat(draft.lines().get(0).ticketId()).isNull();
        assertThat(draft.lines().get(0).docNumber()).isEqualTo("INV-EXT-001");
        assertThat(draft.totalAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void createDraft_manualLineWithNoAmount_isRefused() {
        CustomerDto customer = createCustomer("ManualBad");
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
            List.of(new BillingNoteLineSelection("MANUAL", null, "INV-1", null, null, null, null)));
        assertThatThrownBy(() -> billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void createDraft_moreLinesThanTheTemplateHolds_isRefusedWithConflict() {
        CustomerDto customer = createCustomer("Capacity1");
        List<BillingNoteLineSelection> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < BillingNoteRenderer.MAX_LINE_ROWS + 1; i++) {
            tooMany.add(new BillingNoteLineSelection("MANUAL", null, "INV-" + i, LocalDate.now(), null,
                new BigDecimal("1.00"), null));
        }
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null, tooMany);
        assertThatThrownBy(() -> billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // No double billing
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createDraft_sameSourceOnTwoDraftsForDifferentTypes_secondIsRefused() {
        CustomerDto customer = createCustomer("Dbl1");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "Dbl1");
        billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void candidates_reportsWhichNoteAlreadyClaimsADocument() {
        CustomerDto customer = createCustomer("Dbl2");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "Dbl2");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(), employeeWithGrantActor);

        BillingNoteCandidatesDto result = billingNoteService.candidates(customer.id(), accountActor);
        assertThat(result.candidates()).noneMatch(c -> c.sourceId() == deal.depositNoticeId());
        assertThat(result.alreadyBilled()).anySatisfy(c -> {
            assertThat(c.sourceId()).isEqualTo(deal.depositNoticeId());
            assertThat(c.blockedByNoteId()).isEqualTo(issued.id());
            assertThat(c.blockedByNoteNumber()).isEqualTo(issued.docNumber());
        });
    }

    /** DB invariant, bypassing the service entirely — the direct-JDBC style V188's own tests use.
     * Two LIVE (note_status DRAFT/ISSUED) lines for the same (source_type, source_id) must be
     * refused by Postgres itself. */
    @Test
    void v189_dbInvariant_noDoubleBilling_evenBypassingTheService() {
        CustomerDto customer = createCustomer("DbInvariant1");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "DbInvariant1");
        long noteAId = billingNoteRepository.insertDraft(customer.id(), "GOODS", null, LocalDate.now(), null, null,
            customer.name(), customer.taxId(), customer.branch(), customer.address(), 1, "Actor");
        billingNoteRepository.replaceLines(noteAId, List.of(new BillingNoteRepository.ResolvedLine(
            "DEPOSIT_NOTICE", deal.depositNoticeId(), deal.ticketId(), "DN-1", LocalDate.now(), null,
            new BigDecimal("100.00"), null)));

        // A second, independent brand-new note (B1: several brand-new drafts of the same
        // customer+type may now coexist, so this need not even be a different type) trying to
        // claim the exact same source document — refused by the DB itself, entirely bypassing
        // BillingNoteService's own application-level candidate-claim check.
        long noteBId = billingNoteRepository.insertDraft(customer.id(), "FREIGHT", null, LocalDate.now(), null, null,
            customer.name(), customer.taxId(), customer.branch(), customer.address(), 1, "Actor");
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.billing_note_line
                (billing_note_id, seq, source_type, source_id, ticket_id, doc_number, amount, note_status)
            VALUES (:noteB, 1, 'DEPOSIT_NOTICE', :sourceId, :ticketId, 'DN-1', 100.00, 'DRAFT')
            """, Map.of("noteB", noteBId, "sourceId", deal.depositNoticeId(), "ticketId", deal.ticketId())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // B1 (owner ruling 2026-09-20) — MANY issued notes per customer+type, one per credit cycle;
    // live-uniqueness is per REVISION CHAIN, not (customer_id, type).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void b1_manyIssuedNotesOfTheSameCustomerAndType_coexistAcrossCreditCycles() {
        CustomerDto customer = createCustomer("B1Coexist");
        DealFixture dealA = buildAcceptedDeal(customer, salesActor, "B1CoexistA");
        DealFixture dealB = buildAcceptedDeal(customer, salesActor, "B1CoexistB");

        // Two brand-new (non-correction) GOODS notes for the SAME customer, one per deal — under
        // the pre-B1 model the second createDraft would have been refused outright ("has an
        // ISSUED note, revise instead"). Both must now issue independently.
        BillingNoteDocumentDto cycle1 = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealA.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);
        BillingNoteDocumentDto cycle2 = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealB.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        assertThat(cycle1.status()).isEqualTo("ISSUED");
        assertThat(cycle2.status()).isEqualTo("ISSUED");
        assertThat(cycle1.baseNumber()).isNotEqualTo(cycle2.baseNumber());
        // Both still read ISSUED on a fresh fetch — neither superseded the other (that would only
        // happen within the SAME chain, via revise -> issue).
        assertThat(billingNoteService.get(cycle1.id(), accountActor).status()).isEqualTo("ISSUED");
        assertThat(billingNoteService.get(cycle2.id(), accountActor).status()).isEqualTo("ISSUED");
    }

    @Test
    void b1_severalBrandNewDraftsOfTheSameCustomerAndType_canBeInProgressAtOnce() {
        CustomerDto customer = createCustomer("B1TwoDrafts");
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of());

        // The pre-B1 model refused a second live DRAFT of the same (customer, type) outright.
        BillingNoteDocumentDto draftA = billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor);
        BillingNoteDocumentDto draftB = billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor);

        assertThat(draftA.id()).isNotEqualTo(draftB.id());
        assertThat(billingNoteRepository.findByCustomer(customer.id())).hasSize(2);
    }

    @Test
    void b1_revise_stillRefusesASecondConcurrentCorrectionOfTheSamePredecessor() {
        // Per-chain uniqueness is NOT unlimited — B1 keeps "at most one live correction DRAFT per
        // predecessor", just re-scoped from (customer, type) to revisionOfId.
        CustomerDto customer = createCustomer("B1OneRevisionAtATime");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "B1OneRevisionAtATime");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        billingNoteService.revise(issued.id(), employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.revise(issued.id(), employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // B2/C1 (owner rulings 2026-09-20; C1 NARROWS B2's original "MANUAL lines never block
    // settlement" rule before it ever shipped) — automatic ISSUED -> SETTLED once the note has at
    // least one non-MANUAL line AND every non-MANUAL line's source is fully paid; settling
    // releases lines for a later cycle to reclaim. An ALL-MANUAL note can never satisfy "at least
    // one non-MANUAL line", so it stays ISSUED until markSettled (C1's own tests are below, after
    // this block).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void b2_issuedNoteSettlesAutomatically_thenALaterCycleCanStillBeIssuedForTheSameCustomerAndType() {
        CustomerDto customer = createCustomer("B2Settle");
        DealFixture dealA = buildAcceptedDeal(customer, salesActor, "B2SettleA");
        DealFixture dealB = buildAcceptedDeal(customer, salesActor, "B2SettleB");
        DepositNoticeDto noticeA = depositNoticeService.getById(dealA.depositNoticeId(), salesActor);

        BillingNoteDocumentDto cycle1 = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealA.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        payOffDepositNoticeInFull(dealA, noticeA);

        // The next READ is what recomputes settlement (B2: recompute-on-read) — no separate
        // "settle" action exists.
        BillingNoteDocumentDto reread = billingNoteService.get(cycle1.id(), accountActor);
        assertThat(reread.status()).isEqualTo("SETTLED");

        // Cycle 2, same customer+type, referencing a DIFFERENT (unpaid) deal — issuable while
        // cycle 1 is now SETTLED, exactly as it already is while cycle 1 is still ISSUED (B1).
        BillingNoteDocumentDto cycle2 = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealB.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);
        assertThat(cycle2.status()).isEqualTo("ISSUED");
        assertThat(cycle2.baseNumber()).isNotEqualTo(cycle1.baseNumber());
    }

    @Test
    void b2_settlingReleasesLines_soALaterCycleCanReclaimTheSameDocumentAfterAPaymentReversal() {
        CustomerDto customer = createCustomer("B2Release");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "B2Release");
        DepositNoticeDto notice = depositNoticeService.getById(deal.depositNoticeId(), salesActor);

        BillingNoteDocumentDto bn1 = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);
        payOffDepositNoticeInFull(deal, notice);
        assertThat(billingNoteService.get(bn1.id(), accountActor).status()).isEqualTo("SETTLED");

        // A payment REVERSAL (an ADJUSTMENT receipt equal to what was paid) makes the same
        // document outstanding again — plausible real-world case (a bounced cheque, a correction).
        // Without B2's "settling releases lines" behaviour, BN1's own (SETTLED, but still
        // note_status='ISSUED') line would keep permanently blocking this document.
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, deposit_notice_id)
            VALUES (:ticketId, 'ADJUSTMENT', :amount, :actorId, :depositNoticeId)
            """, Map.of("ticketId", deal.ticketId(), "amount", notice.totalPayable(),
                "actorId", accountActor.id(), "depositNoticeId", deal.depositNoticeId()));

        BillingNoteCandidatesDto candidates = billingNoteService.candidates(customer.id(), accountActor);
        assertThat(candidates.candidates()).anySatisfy(c -> assertThat(c.sourceId()).isEqualTo(deal.depositNoticeId()));
        assertThat(candidates.alreadyBilled()).noneMatch(c -> c.sourceId() == deal.depositNoticeId());

        // ...and a brand-new note can reclaim it outright.
        BillingNoteDocumentDto bn2 = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor);
        assertThat(bn2.lines()).hasSize(1);
    }

    @Test
    void c1_mixedNote_stillAutoSettlesOnceItsOnlyNonManualLineIsPaid() {
        // C1's OTHER half: a MIXED note (at least one non-MANUAL line, plus any number of MANUAL
        // ones) still auto-settles exactly like before — C1 narrows the ALL-MANUAL case only.
        CustomerDto customer = createCustomer("C1Mixed");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "C1Mixed");
        DepositNoticeDto notice = depositNoticeService.getById(deal.depositNoticeId(), salesActor);

        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()),
                    new BillingNoteLineSelection("MANUAL", null, "INV-EXT-B2", LocalDate.now(),
                        LocalDate.now().plusDays(30), new BigDecimal("500.00"), "ค่าขนส่งภายนอก — ไม่มีทางตรวจสอบว่าชำระแล้ว")))
                , employeeWithGrantActor).id(), employeeWithGrantActor);

        payOffDepositNoticeInFull(deal, notice);

        // Settles even though the MANUAL line's own "payment" can never be verified — the
        // deliberate choice (see BillingNoteRepository#reconcileSettlementForCustomer's own
        // Javadoc): a MANUAL line never blocks settlement AS LONG AS the note has at least one
        // non-MANUAL line (C1) — which this note does.
        BillingNoteDocumentDto reread = billingNoteService.get(issued.id(), accountActor);
        assertThat(reread.status()).isEqualTo("SETTLED");
        // Automatic settlement never records who/when (C1) — that is markSettled's own signature.
        assertThat(reread.settledById()).isNull();
        assertThat(reread.settledByName()).isNull();
        assertThat(reread.settledAt()).isNull();
    }

    @Test
    void b2_settlementWaitsForEveryNonManualLineToBePaid() {
        CustomerDto customer = createCustomer("B2Partial");
        DealFixture dealA = buildAcceptedDeal(customer, salesActor, "B2PartialA");
        DealFixture dealB = buildAcceptedDeal(customer, salesActor, "B2PartialB");
        DepositNoticeDto noticeA = depositNoticeService.getById(dealA.depositNoticeId(), salesActor);

        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealA.depositNoticeId()),
                    lineFor("DEPOSIT_NOTICE", dealB.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        // Only dealA's own document gets paid off — dealB's stays outstanding.
        payOffDepositNoticeInFull(dealA, noticeA);

        assertThat(billingNoteService.get(issued.id(), accountActor).status()).isEqualTo("ISSUED");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // C1 (owner ruling 2026-09-20, FIX FIRST per the Opus round-2 review) — an ALL-MANUAL note
    // can NEVER auto-settle; it stays ISSUED until a human calls markSettled, which records
    // who/when and is gated behind the SAME write grant as every other mutation.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void c1_allManualNote_staysIssuedNoMatterHowManyTimesItIsRead() {
        CustomerDto customer = createCustomer("C1AllManual");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
                List.of(new BillingNoteLineSelection("MANUAL", null, "INV-EXT-C1", LocalDate.now(),
                    LocalDate.now().plusDays(30), new BigDecimal("500.00"), "ค่าขนส่ง"))),
                employeeWithGrantActor).id(), employeeWithGrantActor);

        assertThat(issued.status()).isEqualTo("ISSUED");
        // Before the C1 fix, the ORIGINAL rule settled an all-MANUAL note on its very next read —
        // bool_and over zero non-MANUAL lines is vacuously true. Any number of reads must now
        // leave it exactly ISSUED.
        for (int i = 0; i < 3; i++) {
            assertThat(billingNoteService.get(issued.id(), accountActor).status()).isEqualTo("ISSUED");
        }
        assertThat(billingNoteService.candidates(customer.id(), accountActor)).isNotNull(); // also recomputes; must not throw/settle
        assertThat(billingNoteService.get(issued.id(), accountActor).status()).isEqualTo("ISSUED");
    }

    @Test
    void c1_markSettled_settlesAnAllManualNote_recordsWhoAndWhen_andReleasesLines() {
        CustomerDto customer = createCustomer("C1MarkSettled");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
                List.of(new BillingNoteLineSelection("MANUAL", null, "INV-EXT-C1B", LocalDate.now(),
                    LocalDate.now().plusDays(30), new BigDecimal("750.00"), "ค่าขนส่ง"))),
                employeeWithGrantActor).id(), employeeWithGrantActor);

        BillingNoteDocumentDto settled = billingNoteService.markSettled(issued.id(), employeeWithGrantActor);
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.settledById()).isEqualTo(employeeWithGrantActor.id());
        assertThat(settled.settledByName()).isNotBlank();
        assertThat(settled.settledAt()).isNotNull();

        BillingNoteDocumentDto reread = billingNoteService.get(issued.id(), accountActor);
        assertThat(reread.status()).isEqualTo("SETTLED");
        assertThat(reread.settledById()).isEqualTo(employeeWithGrantActor.id());
    }

    @Test
    void c1_markSettled_onlyAllowedWhileIssued() {
        CustomerDto customer = createCustomer("C1NotIssued");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null, List.of()), employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.markSettled(draft.id(), employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void c1_allManualNote_staysMarkReceivedRevisableAndCancellableWhileIssued() {
        // The OTHER half of C1's own ruling: an all-MANUAL note must stay markReceived/revise/
        // cancel-able WHILE still ISSUED (i.e. C1 must not have accidentally blocked those on it).
        CustomerDto customer = createCustomer("C1StillActionable");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
                List.of(new BillingNoteLineSelection("MANUAL", null, "INV-EXT-C1C", LocalDate.now(),
                    LocalDate.now().plusDays(30), new BigDecimal("250.00"), null))),
                employeeWithGrantActor).id(), employeeWithGrantActor);

        BillingNoteDocumentDto received = billingNoteService.markReceived(issued.id(),
            new BillingNoteMarkReceivedRequest("คุณทดสอบ", LocalDate.now().plusDays(7)), employeeWithGrantActor);
        assertThat(received.receivedByName()).isEqualTo("คุณทดสอบ");
        assertThat(received.status()).isEqualTo("ISSUED");

        BillingNoteDocumentDto revisionDraft = billingNoteService.revise(issued.id(), employeeWithGrantActor);
        assertThat(revisionDraft.status()).isEqualTo("DRAFT");
        billingNoteService.deleteDraft(revisionDraft.id(), employeeWithGrantActor); // abandon it

        BillingNoteDocumentDto cancelled = billingNoteService.cancel(issued.id(),
            new BillingNoteCancelRequest("ทดสอบยกเลิก"), employeeWithGrantActor);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
    }

    @Test
    void f2_markSettled_unauthorizedCallerGets403NotConflict_evenWhenTheNoteIsStillADraft() {
        CustomerDto customer = createCustomer("F2MarkSettled");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of()), employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.markSettled(draft.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(billingNoteService.get(draft.id(), accountActor).status()).isEqualTo("DRAFT");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // S3 (round 2) — a MISSING source row (no FK by design) must NOT count as "paid": it must
    // block settlement of the WHOLE note, protecting a paid SIBLING line from being released into
    // a double-billing window.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void s3_missingSourceRow_doesNotSettle_andDoesNotReleaseItsPaidSibling() {
        CustomerDto customer = createCustomer("S3Missing");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "S3Missing");
        DepositNoticeDto notice = depositNoticeService.getById(deal.depositNoticeId(), salesActor);

        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        // A second line referencing a REMAINING_INVOICE row that does not exist — billing_note_line
        // carries NO FK to remaining_invoice/deposit_notice by design (V189's own header comment),
        // so this is constructible directly; bypasses the service entirely, same direct-JDBC
        // technique v189_dbInvariant_noDoubleBilling_evenBypassingTheService already uses.
        jdbc.update("""
            INSERT INTO sales.billing_note_line
                (billing_note_id, seq, source_type, source_id, ticket_id, doc_number, amount, note_status)
            VALUES (:noteId, 2, 'REMAINING_INVOICE', :bogusId, :ticketId, 'RI-MISSING', 999.00, 'ISSUED')
            """, Map.of("noteId", issued.id(), "bogusId", 999_999_999L, "ticketId", deal.ticketId()));

        payOffDepositNoticeInFull(deal, notice);

        // Without the fix, the missing source's outstanding reads SQL NULL -> COALESCE(...,0) <= 0
        // -> counted as "paid", settling the WHOLE note and releasing its real, still-live sibling
        // claim — a double-billing path.
        BillingNoteDocumentDto reread = billingNoteService.get(issued.id(), accountActor);
        assertThat(reread.status()).isEqualTo("ISSUED");
        assertThat(billingNoteRepository.findLiveClaim("DEPOSIT_NOTICE", deal.depositNoticeId())).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // B2f (BLOCKER, round 2) — settle() must be ONE atomic, status-guarded statement: a concurrent
    // cancel landing between reconcile's own SELECT and the old unconditional two-statement update
    // must never stomp CANCELLED -> SETTLED (which used to turn a later plain GET into an
    // unhandled 500 via chk_billing_note_cancel_fields). Tested deterministically (CAS-style) by
    // forcing the cancel to land BEFORE a direct call to the exact guarded statement, rather than
    // with real threads — see BillingNoteRepository#settle's own Javadoc for why a genuine
    // multi-threaded test here would add flakiness without adding coverage: Postgres serializes
    // the two writers on the same row under read-committed regardless, so the only externally
    // observable outcomes are "guard sees ISSUED, applies" or "guard sees non-ISSUED, no-ops" —
    // exactly the two cases this test and the rest of the B2/C1 suite already exercise.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void b2f_settle_isAtomicAndGuardedOnIssued_soAConcurrentCancelCannotBeStompedToSettled() {
        CustomerDto customer = createCustomer("B2fRace");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "B2fRace");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        // Simulates "a concurrent cancel already committed by the time settle()'s own guarded
        // UPDATE runs" deterministically: force the cancel to land first, then call the exact same
        // guarded statement reconcile would have called with this note's id.
        billingNoteService.cancel(issued.id(), new BillingNoteCancelRequest("ยกเลิกก่อน settle แข่งกัน"), employeeWithGrantActor);

        assertThatNoException().isThrownBy(() -> billingNoteRepository.settle(List.of(issued.id())));

        BillingNoteDocumentDto reread = billingNoteService.get(issued.id(), accountActor);
        assertThat(reread.status()).isEqualTo("CANCELLED");
        assertThat(reread.cancelReason()).isNotNull();
        assertThat(reread.cancelledById()).isNotNull();
        assertThat(reread.cancelledAt()).isNotNull();
    }

    /** Fully pays a deposit notice's own VAT-inclusive {@code total_payable} by a direct JDBC
     * insert — the same fixture technique {@code candidates_excludesAFullyPaidDepositNotice}
     * already documents and uses (bypassing {@code TicketService#confirmDepositPaid} deliberately,
     * since that path records only the pre-VAT {@code depositAmount}, not the VAT-inclusive figure
     * this candidate/settlement computation is scoped to, GLA-107 answer 1). */
    private void payOffDepositNoticeInFull(DealFixture deal, DepositNoticeDto notice) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, deposit_notice_id)
            VALUES (:ticketId, 'DEPOSIT', :amount, :actorId, :depositNoticeId)
            """, Map.of("ticketId", deal.ticketId(), "amount", notice.totalPayable(),
                "actorId", accountActor.id(), "depositNoticeId", deal.depositNoticeId()));
    }

    @Test
    void cancel_releasesLinesForRebilling() {
        CustomerDto customer = createCustomer("Cancel1");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "Cancel1");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(), employeeWithGrantActor);

        billingNoteService.cancel(issued.id(), new BillingNoteCancelRequest("ออกผิดลูกค้า"), employeeWithGrantActor);

        // The source is claimable again — a brand-new note (same type, since GOODS is now free
        // again after cancellation) can pick it up.
        BillingNoteDocumentDto rebill = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor);
        assertThat(rebill.lines()).hasSize(1);

        BillingNoteDocumentDto cancelledReread = billingNoteService.get(issued.id(), accountActor);
        assertThat(cancelledReread.status()).isEqualTo("CANCELLED");
        assertThat(cancelledReread.cancelReason()).isEqualTo("ออกผิดลูกค้า");
    }

    @Test
    void revise_thenIssue_keepsBaseNumberBumpsVersionAndSupersedesThePredecessor() {
        CustomerDto customer = createCustomer("Revise1");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "Revise1");
        BillingNoteDocumentDto v1 = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(), employeeWithGrantActor);

        BillingNoteDocumentDto revisionDraft = billingNoteService.revise(v1.id(), employeeWithGrantActor);
        assertThat(revisionDraft.status()).isEqualTo("DRAFT");
        assertThat(revisionDraft.lines()).hasSize(1);
        // The predecessor's line released — same source is momentarily claimable by a NEW draft
        // too (not asserted here, but the revision draft itself already holds it verbatim).

        BillingNoteDocumentDto v2 = billingNoteService.issue(revisionDraft.id(), employeeWithGrantActor);
        assertThat(v2.baseNumber()).isEqualTo(v1.baseNumber());
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.docNumber()).isEqualTo(v1.baseNumber() + "-2");

        BillingNoteDocumentDto predecessorReread = billingNoteService.get(v1.id(), accountActor);
        assertThat(predecessorReread.status()).isEqualTo("SUPERSEDED");
        assertThat(predecessorReread.supersededById()).isEqualTo(v2.id());
    }

    @Test
    void deleteDraft_ofARevisionInProgress_restoresThePredecessorsLinesToIssued() {
        CustomerDto customer = createCustomer("DeleteRevise1");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "DeleteRevise1");
        BillingNoteDocumentDto v1 = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(), employeeWithGrantActor);

        BillingNoteDocumentDto revisionDraft = billingNoteService.revise(v1.id(), employeeWithGrantActor);
        billingNoteService.deleteDraft(revisionDraft.id(), employeeWithGrantActor);

        // v1 is still ISSUED and its line's claim was restored — a NEW draft for the SAME
        // (customer, GOODS) trying to claim the same source is refused (still live under v1).
        assertThatThrownBy(() -> billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        BillingNoteDocumentDto v1Reread = billingNoteService.get(v1.id(), accountActor);
        assertThat(v1Reread.status()).isEqualTo("ISSUED");
    }

    /** F3 (Opus review, GLA-99 step 3 round 1): restoring the predecessor's lines on
     * {@code deleteDraft} can itself lose a race if some OTHER note grabbed the exact same
     * now-released source in the window between {@link BillingNoteService#revise}'s own release
     * and this delete — {@code restoreLinesToIssued} then trips {@code
     * ux_billing_note_line_source_live} exactly like {@code revise}'s own race above. Must map to
     * 409, not surface as an unmapped 500. */
    @Test
    void f3_deleteDraft_ofARevisionInProgress_mapsARestoreRaceTo409() {
        // The race needs a genuine gap: revise() itself is atomic (release-then-reclaim inside ONE
        // transaction, so a source is never actually free while the correction draft still holds
        // it verbatim). The real opening is updateDraft DROPPING one of the correction draft's own
        // lines — replaceLines DELETEs every old row and reinserts only the new selection, so a
        // source the correction draft stops referencing becomes free EVEN THOUGH the original
        // predecessor's own (RELEASED) line for it still exists and would be restored wholesale.
        CustomerDto customer = createCustomer("F3Restore");
        DealFixture dealA = buildAcceptedDeal(customer, salesActor, "F3RestoreA");
        DealFixture dealB = buildAcceptedDeal(customer, salesActor, "F3RestoreB");
        BillingNoteDocumentDto v1 = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealA.depositNoticeId()),
                    lineFor("DEPOSIT_NOTICE", dealB.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        // Starting a correction releases v1's own two lines and the draft re-claims BOTH verbatim.
        BillingNoteDocumentDto revisionDraft = billingNoteService.revise(v1.id(), employeeWithGrantActor);
        assertThat(revisionDraft.lines()).hasSize(2);

        // The corrector drops dealB's own line from the correction — dealB's document is now
        // claimed by NOTHING (v1's own original line for it sits RELEASED, not restored).
        BillingNoteDocumentDto trimmed = billingNoteService.updateDraft(revisionDraft.id(),
            new BillingNoteDraftRequest(null, null, null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealA.depositNoticeId()))), employeeWithGrantActor);
        assertThat(trimmed.lines()).hasSize(1);

        // A completely independent brand-new note (B1: several brand-new drafts of the same
        // customer+type may coexist) legitimately grabs dealB's now-free document.
        BillingNoteDocumentDto rival = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", dealB.depositNoticeId()))), employeeWithGrantActor);
        assertThat(rival.lines()).hasSize(1);

        // Abandoning the correction now tries to restore v1's ENTIRE original line set (dealA AND
        // dealB) to ISSUED — but `rival` already holds a live (DRAFT) claim on dealB's document, so
        // the restore itself collides with ux_billing_note_line_source_live.
        assertThatThrownBy(() -> billingNoteService.deleteDraft(revisionDraft.id(), employeeWithGrantActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** S4 (Opus review, GLA-99 step 3 round 2): {@link BillingNoteService#cancel} allows cancelling
     * an ISSUED note while a correction draft on it is still open (no guard against that, by
     * design). deleteDraft must NOT blindly restore that now-CANCELLED predecessor's lines to a
     * live ISSUED claim when the abandoned correction is deleted — doing so would permanently lock
     * the referenced source documents from ever being billed again (a CANCELLED note is never
     * revised further, so nothing would ever release them again). */
    @Test
    void s4_deleteDraft_doesNotRestoreClaimIfThePredecessorWasCancelledWhileTheCorrectionWasOpen() {
        CustomerDto customer = createCustomer("S4CancelledPred");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "S4CancelledPred");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        BillingNoteDocumentDto revisionDraft = billingNoteService.revise(issued.id(), employeeWithGrantActor);

        // cancel() already allows cancelling the predecessor while a correction draft is open.
        billingNoteService.cancel(issued.id(), new BillingNoteCancelRequest("ยกเลิกระหว่างแก้ไข"), employeeWithGrantActor);

        billingNoteService.deleteDraft(revisionDraft.id(), employeeWithGrantActor);

        // Without the fix, deleteDraft blindly restores the (now-CANCELLED) predecessor's lines to
        // 'ISSUED', permanently locking the referenced document from ever being billed again.
        BillingNoteDocumentDto predecessorReread = billingNoteService.get(issued.id(), accountActor);
        assertThat(predecessorReread.status()).isEqualTo("CANCELLED");
        assertThat(billingNoteRepository.findLiveClaim("DEPOSIT_NOTICE", deal.depositNoticeId())).isEmpty();

        // The document is claimable again by a brand-new note.
        BillingNoteDocumentDto rebill = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor);
        assertThat(rebill.lines()).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // S6 (round 2) — the DataIntegrityViolationException catches must be NARROWED to the real
    // double-billing constraint, so a DIFFERENT DB error (e.g. a value too long for a column) is
    // never misreported as "another note already claimed this document".
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void s6_dataIntegrityViolationCatchIsNarrowed_aValueTooLongErrorIsNotMisreportedAsDoubleBilling() {
        CustomerDto customer = createCustomer("S6Narrow");
        // Bypasses @Valid (HTTP-layer only — see BillingNoteDraftRequestValidationTest for that
        // half of S6) by calling the service directly, proving the CATCH ITSELF is narrowed, not
        // merely that validation now blocks this case before it reaches the DB.
        String tooLong = "X".repeat(40); // billing_note_line.doc_number is VARCHAR(30)
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
            List.of(new BillingNoteLineSelection("MANUAL", null, tooLong, LocalDate.now(), null,
                new BigDecimal("10.00"), null)));

        // Before the fix, writeLines' own catch (DataIntegrityViolationException e) swallowed EVERY
        // DataIntegrityViolationException into the double-billing 409 — wrong here, since a
        // value-too-long violation has nothing to do with double billing. Narrowed, this must now
        // propagate the RAW exception instead (the generic DataAccessException handler reports it
        // honestly as a 500, rather than a misleading double-billing 409).
        assertThatThrownBy(() -> billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
            .isNotInstanceOf(ApiException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // S7 (round 2) — list()/candidates() must gate role-only authz BEFORE requireCustomer, the
    // SAME existence-oracle fix F2 (round 1) made for every write method: a caller with NO
    // possible access to this endpoint must not be able to tell "customer does not exist" (404)
    // apart from "customer exists but I cannot see it" (403).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void s7_list_unauthorizedCallerGets403NotNotFound_evenForANonExistentCustomer() {
        long noSuchCustomerId = 999_999_999L;
        assertThatThrownBy(() -> billingNoteService.list(noSuchCustomerId, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void s7_candidates_unauthorizedCallerGets403NotNotFound_evenForANonExistentCustomer() {
        long noSuchCustomerId = 999_999_998L;
        assertThatThrownBy(() -> billingNoteService.candidates(noSuchCustomerId, importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void markReceived_recordsTheSignatureFields() {
        CustomerDto customer = createCustomer("MarkReceived1");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "MarkReceived1");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(), employeeWithGrantActor);

        BillingNoteDocumentDto received = billingNoteService.markReceived(issued.id(),
            new BillingNoteMarkReceivedRequest("คุณสมชาย", LocalDate.now().plusDays(30)), employeeWithGrantActor);
        assertThat(received.receivedByName()).isEqualTo("คุณสมชาย");
        assertThat(received.receivedAt()).isNotNull();
        assertThat(received.paymentAppointmentDate()).isEqualTo(LocalDate.now().plusDays(30));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // AUTHZ — written WRONG-WAY-ROUND (CLAUDE.md requirement)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void write_salesWithNoGrant_isForbidden_andNothingIsWritten() {
        CustomerDto customer = createCustomer("AuthzSales");
        assertForbiddenAndNothingWritten(customer, salesActor);
    }

    @Test
    void write_importIsForbidden_andNothingIsWritten() {
        CustomerDto customer = createCustomer("AuthzImport");
        assertForbiddenAndNothingWritten(customer, importActor);
    }

    @Test
    void write_hrIsForbidden_andNothingIsWritten() {
        CustomerDto customer = createCustomer("AuthzHr");
        assertForbiddenAndNothingWritten(customer, hrActor);
    }

    @Test
    void write_plainEmployeeWithNoGrant_isForbidden_andNothingIsWritten() {
        CustomerDto customer = createCustomer("AuthzEmpNoGrant");
        assertForbiddenAndNothingWritten(customer, employeeNoGrantActor);
    }

    private void assertForbiddenAndNothingWritten(CustomerDto customer, UserPrincipal actor) {
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of());
        assertThatThrownBy(() -> billingNoteService.createDraft(customer.id(), req, actor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(billingNoteRepository.findByCustomer(customer.id())).isEmpty();
    }

    @Test
    void write_grantHolderInAPlainEmployeeRole_succeeds() {
        // ภิญญดา's real-world shape: role `employee`, grant TRUE.
        CustomerDto customer = createCustomer("AuthzGrant");
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of());
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor);
        assertThat(draft.status()).isEqualTo("DRAFT");
    }

    @Test
    void write_ceoSucceedsWithNoGrantNeeded() {
        CustomerDto customer = createCustomer("AuthzCeo");
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of());
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(), req, ceoActor);
        assertThat(draft.status()).isEqualTo("DRAFT");
    }

    @Test
    void read_accountAndSalesManagerAndCeoAlwaysSee_evenWithoutTheGrant() {
        CustomerDto customer = createCustomer("AuthzRead1");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of()), employeeWithGrantActor);

        assertThat(billingNoteService.get(draft.id(), accountActor).id()).isEqualTo(draft.id());
        assertThat(billingNoteService.get(draft.id(), salesManagerActor).id()).isEqualTo(draft.id());
        assertThat(billingNoteService.get(draft.id(), ceoActor).id()).isEqualTo(draft.id());
    }

    @Test
    void read_salesWhoDoesNotOwnAnyReferencedDeal_isForbidden() {
        CustomerDto customer = createCustomer("AuthzRead2");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "AuthzRead2");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor);

        // A DIFFERENT sales rep, who owns nothing referenced by this note.
        UserPrincipal otherSales = actor(salesRepId + 1_000_000, "sales"); // no such employee row — id alone drives the ownership check
        assertThatThrownBy(() -> billingNoteService.get(draft.id(), otherSales))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void read_salesWhoOwnsAReferencedDeal_succeeds() {
        CustomerDto customer = createCustomer("AuthzRead3");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "AuthzRead3");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor);

        assertThat(billingNoteService.get(draft.id(), salesActor).id()).isEqualTo(draft.id());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // F2 (Opus review, GLA-99 step 3 round 1): requireWriteAccess must run BEFORE requireDraft/
    // requireIssued in every write method — otherwise an unauthorized caller can tell "no such
    // note" (404) or "wrong state" (409) apart from "not authorized" (403) without ever passing
    // the write gate, an existence/state oracle. Each case below targets a note that IS in the
    // "wrong" state for that op (which would 409 today if the ordering bug were still present)
    // and asserts FORBIDDEN instead, with the note's own state unchanged afterwards.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void f2_updateDraft_unauthorizedCallerGets403NotConflict_evenWhenTheNoteIsAlreadyIssued() {
        CustomerDto customer = createCustomer("F2Update");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "F2Update");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, "เดิม",
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.updateDraft(issued.id(),
            new BillingNoteDraftRequest(null, LocalDate.now(), null, "แก้ไขโดยไม่มีสิทธิ์", null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(billingNoteService.get(issued.id(), accountActor).note()).isEqualTo("เดิม");
    }

    @Test
    void f2_issue_unauthorizedCallerGets403NotConflict_evenWhenTheNoteIsAlreadyIssued() {
        CustomerDto customer = createCustomer("F2Issue");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "F2Issue");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.issue(issued.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        BillingNoteDocumentDto reread = billingNoteService.get(issued.id(), accountActor);
        assertThat(reread.version()).isEqualTo(1);
        assertThat(reread.status()).isEqualTo("ISSUED");
    }

    @Test
    void f2_revise_unauthorizedCallerGets403NotConflict_evenWhenTheNoteIsStillADraft() {
        CustomerDto customer = createCustomer("F2Revise");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of()), employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.revise(draft.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(billingNoteRepository.findByCustomer(customer.id())).hasSize(1); // no new draft created
    }

    @Test
    void f2_cancel_unauthorizedCallerGets403NotConflict_evenWhenTheNoteIsStillADraft() {
        CustomerDto customer = createCustomer("F2Cancel");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of()), employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.cancel(draft.id(),
            new BillingNoteCancelRequest("เหตุผลทดสอบ"), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(billingNoteService.get(draft.id(), accountActor).status()).isEqualTo("DRAFT");
    }

    @Test
    void f2_markReceived_unauthorizedCallerGets403NotConflict_evenWhenTheNoteIsStillADraft() {
        CustomerDto customer = createCustomer("F2MarkReceived");
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of()), employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.markReceived(draft.id(),
            new BillingNoteMarkReceivedRequest("คุณทดสอบ", LocalDate.now()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(billingNoteService.get(draft.id(), accountActor).receivedByName()).isNull();
    }

    @Test
    void f2_deleteDraft_unauthorizedCallerGets403NotConflict_evenWhenTheNoteIsAlreadyIssued() {
        CustomerDto customer = createCustomer("F2Delete");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "F2Delete");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);

        assertThatThrownBy(() -> billingNoteService.deleteDraft(issued.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(billingNoteService.get(issued.id(), accountActor).status()).isEqualTo("ISSUED");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers — adapted from RemainingInvoiceServiceIntegrationTest's own approach, but
    // parameterised on an EXISTING customer so two deals can share one customer_id.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private record DealFixture(long ticketId, long pricingRequestId, long depositNoticeId) {}

    private CustomerDto createCustomer(String label) {
        return customersRepo.create(
            "บริษัท BN " + label + " " + UUID.randomUUID() + " จำกัด", "0100000000019",
            "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0019");
    }

    private BillingNoteLineSelection lineFor(String sourceType, long sourceId) {
        return new BillingNoteLineSelection(sourceType, sourceId, null, null, null, null, null);
    }

    private DealFixture buildAcceptedDeal(CustomerDto customer, UserPrincipal owner, String label) {
        long catalogProductId = insertCatalogProduct(FACTORY, "IT",
            "TEST-BN-" + label + "-" + UUID.randomUUID().toString().substring(0, 8),
            new BigDecimal("100.00"), "THB", "per_piece");

        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ BN " + label);
        TicketService ticketServiceForCreate = new TicketService(tickets,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), new ObjectMapper(), customersRepo,
            new QuotationRenderer(), pricingRequestService, employeeAuth);
        TicketDto created = ticketServiceForCreate.create(
            new CreateTicketRequest("ดีล BN " + label, "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile BN " + label, FACTORY))),
            owner);
        long ticketId = created.summary().id();
        long ticketItemId = created.items().get(0).id();

        BigDecimal quantity = new BigDecimal("10");
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile BN " + label, "SCG Tile BN " + label,
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, quantity.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "billing-note test walk", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, owner).summary().id();

        driveDraftPricingRequestToQuotationAccepted(pricingRequestId, quantity, owner);

        orderConfirmation.confirmOrder(pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), owner);
        DepositNoticeDto depositDraft = orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), owner);
        DepositNoticeDto depositIssued = depositNoticeService.issue(depositDraft.id(), owner);

        return new DealFixture(ticketId, pricingRequestId, depositIssued.id());
    }

    private void driveDraftPricingRequestToQuotationAccepted(long pricingRequestId, BigDecimal quantity, UserPrincipal owner) {
        pricingRequestService.submit(pricingRequestId, owner);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        String email = FACTORY.toLowerCase().replace(" ", "-") + "@example.com";
        factoryQuoteService.send(draft.id(), new SendFactoryQuoteRequest(email, null, null), importActor);
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest(
            "REF-" + UUID.randomUUID(), "THB", "30 days", "45 days", "revision", "note",
            List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quantity, "piece", UnitBasis.PER_PIECE,
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

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
                UUID.randomUUID().toString()), owner);
        CustomerQuotationDto issued = quotationService.issue(
            draftQuotation.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), owner);
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), owner);
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh, boolean canIssueBillingNote) {
        long id = employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        if (canIssueBillingNote) {
            jdbc.update("UPDATE hr.employee SET can_issue_billing_note = TRUE WHERE employee_id = :id",
                Map.of("id", id));
        }
        return id;
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }

    // ══════ TEMPORARY REVIEW PROBES (round 3) — removed by copy-back ══════

    private String rawStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM sales.billing_note WHERE id = :id",
            Map.of("id", id), String.class);
    }

    private long countLines(long id, String noteStatus) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM sales.billing_note_line WHERE billing_note_id = :id AND note_status = :s",
            Map.of("id", id, "s", noteStatus), Long.class);
    }

    @Test
    void probe1_markSettled_whileACorrectionDraftIsOpen() {
        CustomerDto customer = createCustomer("P1");
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
                List.of(new BillingNoteLineSelection("MANUAL", null, "P1-MAN", LocalDate.now(),
                    null, new BigDecimal("100.00"), null))), employeeWithGrantActor).id(), employeeWithGrantActor);
        billingNoteService.revise(issued.id(), employeeWithGrantActor); // releases the predecessor's lines
        System.out.println("PROBE1 before: status=" + rawStatus(issued.id())
            + " issuedLines=" + countLines(issued.id(), "ISSUED")
            + " releasedLines=" + countLines(issued.id(), "RELEASED"));
        String outcome;
        try {
            billingNoteService.markSettled(issued.id(), employeeWithGrantActor);
            outcome = "OK";
        } catch (ApiException e) {
            outcome = e.getStatus() + " / " + e.getMessage();
        }
        System.out.println("PROBE1 markSettled -> " + outcome + " ; rawStatusAfter=" + rawStatus(issued.id()));
    }

    @Test
    void probe2_overpaidSource_settles() {
        CustomerDto customer = createCustomer("P2");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "P2");
        DepositNoticeDto notice = depositNoticeService.getById(deal.depositNoticeId(), salesActor);
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, deposit_notice_id)
            VALUES (:t, 'DEPOSIT', :a, :by, :dn)
            """, Map.of("t", deal.ticketId(), "a", notice.totalPayable().add(new BigDecimal("500.00")),
                "by", accountActor.id(), "dn", deal.depositNoticeId()));
        System.out.println("PROBE2 overpaid -> " + billingNoteService.get(issued.id(), accountActor).status());
    }

    @Test
    void probe3_exactBoundary_oneSatangShort_thenExact() {
        CustomerDto customer = createCustomer("P3");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "P3");
        DepositNoticeDto notice = depositNoticeService.getById(deal.depositNoticeId(), salesActor);
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, deposit_notice_id)
            VALUES (:t, 'DEPOSIT', :a, :by, :dn)
            """, Map.of("t", deal.ticketId(), "a", notice.totalPayable().subtract(new BigDecimal("0.01")),
                "by", accountActor.id(), "dn", deal.depositNoticeId()));
        String partly = billingNoteService.get(issued.id(), accountActor).status();
        BillingNoteCandidatesDto cands = billingNoteService.candidates(customer.id(), accountActor);
        System.out.println("PROBE3 oneSatangShort -> " + partly
            + " eligible=" + cands.candidates().size() + " alreadyBilled=" + cands.alreadyBilled().size()
            + " outstanding=" + (cands.alreadyBilled().isEmpty() ? "-" : cands.alreadyBilled().get(0).outstandingAmount()));
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, deposit_notice_id)
            VALUES (:t, 'DEPOSIT', 0.01, :by, :dn)
            """, Map.of("t", deal.ticketId(), "by", accountActor.id(), "dn", deal.depositNoticeId()));
        System.out.println("PROBE3 exact -> " + billingNoteService.get(issued.id(), accountActor).status());
    }

    @Test
    void probe4_sourceSupersededOrCancelled_doesNotSettle() {
        for (String srcStatus : List.of("SUPERSEDED", "CANCELLED")) {
            CustomerDto customer = createCustomer("P4" + srcStatus);
            DealFixture deal = buildAcceptedDeal(customer, salesActor, "P4" + srcStatus);
            DepositNoticeDto notice = depositNoticeService.getById(deal.depositNoticeId(), salesActor);
            BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
                new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                    List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()))), employeeWithGrantActor).id(),
                employeeWithGrantActor);
            payOffDepositNoticeInFull(deal, notice);
            jdbc.update("UPDATE sales.deposit_notice SET status = :s WHERE deposit_notice_id = :id",
                Map.of("s", srcStatus, "id", deal.depositNoticeId()));
            System.out.println("PROBE4 source=" + srcStatus + " paidInFull -> note="
                + billingNoteService.get(issued.id(), accountActor).status()
                + " issuedLines=" + countLines(issued.id(), "ISSUED"));
        }
    }

    @Test
    void probe5_sourceRowDeleted_doesNotSettle() {
        CustomerDto customer = createCustomer("P5");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "P5");
        DealFixture other = buildAcceptedDeal(customer, salesActor, "P5b");
        DepositNoticeDto otherNotice = depositNoticeService.getById(other.depositNoticeId(), salesActor);
        BillingNoteDocumentDto issued = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", deal.depositNoticeId()),
                    lineFor("DEPOSIT_NOTICE", other.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);
        payOffDepositNoticeInFull(other, otherNotice);
        jdbc.update("DELETE FROM sales.payment_receipt WHERE deposit_notice_id = :id",
            Map.of("id", deal.depositNoticeId()));
        // Remove the source document's own children before deleting the parent. Billing-note
        // snapshots deliberately have no source FK and must survive the missing source.
        jdbc.update("DELETE FROM sales.deposit_notice_item WHERE deposit_notice_id = :id",
            Map.of("id", deal.depositNoticeId()));
        jdbc.update("DELETE FROM sales.deposit_notice WHERE deposit_notice_id = :id",
            Map.of("id", deal.depositNoticeId()));
        assertThat(billingNoteService.get(issued.id(), accountActor).status()).isEqualTo("ISSUED");
        assertThat(countLines(issued.id(), "ISSUED")).isEqualTo(2);
        assertThat(billingNoteRepository.findLiveClaim("DEPOSIT_NOTICE", other.depositNoticeId())).isPresent();
    }

    @Test
    void probe6_settleReleasesOnlyItsOwnLines() {
        CustomerDto customer = createCustomer("P6");
        DealFixture paid = buildAcceptedDeal(customer, salesActor, "P6paid");
        DealFixture unpaid = buildAcceptedDeal(customer, salesActor, "P6unpaid");
        DepositNoticeDto paidNotice = depositNoticeService.getById(paid.depositNoticeId(), salesActor);
        BillingNoteDocumentDto noteA = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", paid.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);
        BillingNoteDocumentDto noteB = billingNoteService.issue(billingNoteService.createDraft(customer.id(),
            new BillingNoteDraftRequest("FREIGHT", LocalDate.now(), null, null,
                List.of(lineFor("DEPOSIT_NOTICE", unpaid.depositNoticeId()))), employeeWithGrantActor).id(),
            employeeWithGrantActor);
        payOffDepositNoticeInFull(paid, paidNotice);
        billingNoteService.list(customer.id(), accountActor); // customer-wide reconcile
        System.out.println("PROBE6 A=" + rawStatus(noteA.id()) + " A.issuedLines=" + countLines(noteA.id(), "ISSUED")
            + " | B=" + rawStatus(noteB.id()) + " B.issuedLines=" + countLines(noteB.id(), "ISSUED"));
    }
}
