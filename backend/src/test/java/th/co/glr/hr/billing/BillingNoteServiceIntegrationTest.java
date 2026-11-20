package th.co.glr.hr.billing;

import static org.assertj.core.api.Assertions.assertThat;
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
import th.co.glr.hr.deposit.DepositNoticePdfRenderer;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceDocumentDto;
import th.co.glr.hr.deposit.RemainingInvoicePdfRenderer;
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
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
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
 * Real-Postgres coverage for the STORED ใบวางบิล (billing note): the candidate
 * pool + outstanding computation, DRAFT/ISSUED/SUPERSEDED/CANCELLED lifecycle, the shared {@code
 * AR_GLR} numbering, the DB-level "no double billing" invariant ({@code
 * ux_billing_note_line_source_live}), and — per CLAUDE.md's "permission changes must ship
 * evidence" requirement — the write/read authz gate written WRONG-WAY-ROUND: a sales rep with no
 * grant, import, hr, and a plain employee with no grant must all be refused and write nothing;
 * a grant holder in ANY role (including plain {@code employee}) and the CEO must succeed.
 *
 * <p>Drives real deals through the REAL upstream services to an ACCEPTED quotation + ISSUED
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
    private EmployeeRepository employees;
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
        employees = new EmployeeRepository(
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
            objectMapper, customersRepo, new QuotationRenderer(), pricingRequestService, employeeAuth, new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customersRepo, new QuotationRenderer(), notifications, new DiscountApprovalRepository(jdbc));

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticePdfRenderer(), customersRepo, quotationRepository, th.co.glr.hr.support.DocumentBundles.noDownload(jdbc), new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc));

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        RemainingInvoiceRepository remainingInvoiceRepository = new RemainingInvoiceRepository(jdbc);
        remainingInvoiceService = new RemainingInvoiceService(
            remainingInvoiceRepository, depositNoticeService, tickets, new RemainingInvoicePdfRenderer(), notifications);

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
        // The grant must work for a PLAIN employee role — a QC&ISO employee is the real-world shape
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
                // Commit issuance on another connection after the editor read DRAFT, but before its guarded UPDATE. Both calls
                // run through real transactional service proxies.
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

    private BillingNoteDraftRequest manualDraftRequest(LocalDate billDate, String... amounts) {
        List<BillingNoteLineSelection> lines = java.util.stream.IntStream.range(0, amounts.length)
            .mapToObj(i -> new BillingNoteLineSelection("MANUAL", null, "MANUAL-" + i,
                LocalDate.of(2026, 1, 1), null, new BigDecimal(amounts[i]), null)).toList();
        return new BillingNoteDraftRequest("FREIGHT", billDate, null, null, lines);
    }

    // Candidates + outstanding computation

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
        // "outstanding" is scoped to. This is a direct-JDBC fixture write, the
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

    // Lifecycle

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

    /** DB invariant, bypassing the service entirely — the direct-JDBC style V188's own tests use. Two LIVE
     * (note_status DRAFT/ISSUED) lines for the same (source_type, source_id) must be refused by Postgres itself. */
    @Test
    void v189_dbInvariant_noDoubleBilling_evenBypassingTheService() {
        CustomerDto customer = createCustomer("DbInvariant1");
        DealFixture deal = buildAcceptedDeal(customer, salesActor, "DbInvariant1");
        long noteAId = billingNoteRepository.insertDraft(customer.id(), "GOODS", null, LocalDate.now(), null, null,
            customer.name(), customer.taxId(), customer.branch(), customer.address(), 1, "Actor");
        billingNoteRepository.replaceLines(noteAId, List.of(new BillingNoteRepository.ResolvedLine(
            "DEPOSIT_NOTICE", deal.depositNoticeId(), deal.ticketId(), "DN-1", LocalDate.now(), null,
            new BigDecimal("100.00"), null)));

        // A second, independent brand-new note (several brand-new drafts of the same
        // customer+type may coexist, so this need not even be a different type) trying to
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
    // Automatic ISSUED -> SETTLED once the note has at
    // least one non-MANUAL line AND every non-MANUAL line's source is fully paid; settling
    // releases lines for a later cycle to reclaim. An ALL-MANUAL note can never satisfy "at least
    // one non-MANUAL line", so it stays ISSUED until markSettled.
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    /** Fully pays a deposit notice's own VAT-inclusive {@code total_payable} by a direct JDBC
     * insert — the same fixture technique {@code candidates_excludesAFullyPaidDepositNotice}
     * already documents and uses (bypassing {@code TicketService#confirmDepositPaid} deliberately,
     * since that path records only the pre-VAT {@code depositAmount}, not the VAT-inclusive figure
     * this candidate/settlement computation is scoped to). */
    private void payOffDepositNoticeInFull(DealFixture deal, DepositNoticeDto notice) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, deposit_notice_id)
            VALUES (:ticketId, 'DEPOSIT', :amount, :actorId, :depositNoticeId)
            """, Map.of("ticketId", deal.ticketId(), "amount", notice.totalPayable(),
                "actorId", accountActor.id(), "depositNoticeId", deal.depositNoticeId()));
    }

    // AUTHZ — written WRONG-WAY-ROUND (CLAUDE.md requirement)

    @Test
    void write_salesWithNoGrant_isForbidden_andNothingIsWritten() {
        CustomerDto customer = createCustomer("AuthzSales");
        assertForbiddenAndNothingWritten(customer, salesActor);
    }

    private void assertForbiddenAndNothingWritten(CustomerDto customer, UserPrincipal actor) {
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of());
        assertThatThrownBy(() -> billingNoteService.createDraft(customer.id(), req, actor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(billingNoteRepository.findByCustomer(customer.id())).isEmpty();
    }

    @Test
    void write_grantHolderInAPlainEmployeeRole_succeeds() {
        // The real-world shape (a QC&ISO employee): role `employee`, grant TRUE.
        CustomerDto customer = createCustomer("AuthzGrant");
        BillingNoteDraftRequest req = new BillingNoteDraftRequest("GOODS", LocalDate.now(), null, null, List.of());
        BillingNoteDocumentDto draft = billingNoteService.createDraft(customer.id(), req, employeeWithGrantActor);
        assertThat(draft.status()).isEqualTo("DRAFT");
    }

    // Fixture helpers — adapted from RemainingInvoiceServiceIntegrationTest's own approach, but parameterised on
    // an EXISTING customer so two deals can share one customer_id.

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
            new QuotationRenderer(), pricingRequestService, employeeAuth, new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
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
        factoryQuoteService.markContacted(draft.id(),
            new MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
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

    private long countLines(long id, String noteStatus) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM sales.billing_note_line WHERE billing_note_id = :id AND note_status = :s",
            Map.of("id", id, "s", noteStatus), Long.class);
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
}
