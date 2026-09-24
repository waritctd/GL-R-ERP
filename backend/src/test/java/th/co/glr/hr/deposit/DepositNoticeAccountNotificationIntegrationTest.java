package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ThaiText;
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
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesMailRecipientRepository;
import th.co.glr.hr.notification.SalesNotificationMailRouter;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.ConfirmOrderRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests.CreateDepositNoticeFromQuotationRequest;
import th.co.glr.hr.orderconfirmation.OrderConfirmationService;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
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
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * GLA-32 (owner decision, 2026-09-19): account@glr.co.th is notified at exactly one sales-pipeline
 * moment — {@link DepositNoticeService#issue} — never at {@code confirmDepositPaid}/
 * {@code confirmFinalPayment}/close. Drives a deal through the REAL Steps 1-6 services (no
 * shortcuts, same approach as {@code DepositNoticeIssueGuardIntegrationTest}) up to a DRAFT deposit
 * notice, then exercises {@code issue()} itself with a REAL {@link SalesNotificationMailRouter} so
 * both the in-app row and the shared-mailbox email are real, not a {@code NO_OP} double.
 */
class DepositNoticeAccountNotificationIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private TicketService ticketService;
    private DepositNoticeService depositNoticeService;
    private OrderConfirmationService orderConfirmation;
    private NotificationRepository notifications;
    private CapturingMailer mailer;

    private long salesRepId;
    private long accountEmployeeId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;

    private static final String FACTORY = "Factory Account Notice A";

    @BeforeEach
    void wireStepsServicesWithARealMailRouter() {
        mailer = new CapturingMailer();
        NotificationEmailService emailService = new NotificationEmailService(
            mailer, new BrandAssets(), "", "", "https://portal.test.glr");
        notifications = new NotificationRepository(jdbc,
            new SalesNotificationMailRouter(emailService, new SalesMailRecipientRepository(jdbc)));

        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-deposit-account-notify-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage,
            factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes,
            pricingRequests, fxRates, new FactoryConfigRepository(jdbc),
            new CatalogRepository(jdbc), formulaEngine);

        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);

        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);

        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications,
            new DiscountApprovalRepository(jdbc));

        depositNoticeService = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย บัญชีทดสอบ", "sales-acc1@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า บัญชีทดสอบ", "import-acc1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร บัญชีทดสอบ", "ceo-acc1@glr.co.th", "MD", "ผู้บริหาร");
        accountEmployeeId = createEmployee(employees, "พนักงานบัญชี ทดสอบ", "accountant.notice@glr.co.th", "AC", "ฝ่ายบัญชี");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountEmployeeId, "account");

        // Setup noise (submit/pickup/approve/etc. below all raise their own real mail) must not
        // leak into a test's own assertions.
        mailer.sent.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Positive: the first issue notifies account, in-app AND by mail
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issue_firstTime_insertsAnAccountInAppRowWithTicketCustomerAndAmount() {
        DraftFixture fixture = buildDraftDepositNotice();
        // Setup (create/submit/pickup/factory-quote/costing/decision/quotation/confirm-order) is
        // driven through the same REAL notifications wiring and raises plenty of its own
        // import/ceo/sales rows and mail — isolate issue()'s OWN effect from that noise.
        long maxNotificationIdBeforeIssue = maxNotificationId();
        mailer.sent.clear();

        DepositNoticeDto issued = depositNoticeService.issue(fixture.docId(), salesActor);

        List<Map<String, Object>> newRows = jdbc.queryForList("""
            SELECT employee_id, type, message, link FROM hr.notification WHERE notification_id > :maxId
            """, Map.of("maxId", maxNotificationIdBeforeIssue));
        // issue() raises exactly one notification, addressed at "account" alone — no other role
        // gets a row from this specific call.
        assertThat(newRows).hasSize(1);
        Map<String, Object> row = newRows.get(0);
        assertThat(row.get("employee_id")).isEqualTo(accountEmployeeId);
        assertThat(row.get("type")).isEqualTo("DEPOSIT_NOTICE_ISSUED");
        assertThat(row.get("link")).isEqualTo("/tickets/" + fixture.ticketId());
        String message = (String) row.get("message");
        assertThat(message).contains(fixture.ticketCode());
        assertThat(message).contains(fixture.customerName());
        assertThat(message).contains(issued.docNumber());
        // Opus review (2026-09-19): account matches this against a bank transfer, which is the
        // deposit PLUS 7% VAT — the message must show BOTH figures, not depositAmount alone.
        assertThat(message).contains(ThaiText.money(issued.depositAmount()));
        assertThat(message).contains(ThaiText.money(issued.totalPayable()));
        assertThat(issued.totalPayable()).isNotEqualByComparingTo(issued.depositAmount());
        assertThat(message).doesNotContain("ฉบับแก้ไข");
        // The title (TICKET_EVENT_TITLES' own entry) already ends "รอยืนยันรับชำระ" — the message
        // must not repeat it.
        assertThat(message).doesNotContain("รอยืนยันรับชำระ");
    }

    @Test
    void issue_firstTime_actuallyMailsTheSharedAccountBoxThroughTheRealRouter() {
        DraftFixture fixture = buildDraftDepositNotice();
        mailer.sent.clear();

        DepositNoticeDto issued = depositNoticeService.issue(fixture.docId(), salesActor);

        assertThat(mailer.recipients()).contains("account@glr.co.th");
        assertThat(mailer.recipients()).doesNotContain("accountant.notice@glr.co.th");
        SentMail toAccount = mailer.sent.stream()
            .filter(m -> m.to().equals("account@glr.co.th")).findFirst().orElseThrow();
        assertThat(toAccount.subject()).contains("ออกใบแจ้งมัดจำแล้ว");
        assertThat(toAccount.textBody()).contains(fixture.ticketCode());
        assertThat(toAccount.textBody()).contains(issued.docNumber());
    }

    /**
     * Opus review (2026-09-19): {@code doc.customerName()} CAN be blank (see
     * {@code DepositNoticeService.resolveCustomerHeader}'s own comments on the new
     * pricing-request chain), so this pins the same "ไม่ระบุลูกค้า" fallback
     * {@code TicketService#notifySalesManagerOfRepDeclaration} already uses for the identical
     * gap. Nulled directly on the row rather than threaded through the whole pipeline (which
     * always supplies a real name via the ticket summary) — this simulates the gap cheaply
     * without inventing a new code path to reach it.
     */
    @Test
    void issue_blankCustomerName_fallsBackToPlaceholderRatherThanPrintingBlank() {
        DraftFixture fixture = buildDraftDepositNotice();
        jdbc.update("UPDATE sales.deposit_notice SET customer_name = '' WHERE deposit_notice_id = :id",
            Map.of("id", fixture.docId()));
        long maxNotificationIdBeforeIssue = maxNotificationId();

        depositNoticeService.issue(fixture.docId(), salesActor);

        String message = (String) jdbc.queryForMap("""
            SELECT message FROM hr.notification WHERE notification_id > :maxId
            """, Map.of("maxId", maxNotificationIdBeforeIssue)).get("message");
        assertThat(message).contains("ไม่ระบุลูกค้า");
        assertThat(message).doesNotContain(fixture.customerName());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Wrong-way-round: a failed issue (precondition 409) notifies nobody
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issue_secondCallOnAnAlreadyIssuedDocIsRefused_andRaisesNoSecondAccountNotification() {
        DraftFixture fixture = buildDraftDepositNotice();
        depositNoticeService.issue(fixture.docId(), salesActor);
        long rowsAfterFirstIssue = countAccountNotificationRows();
        assertThat(rowsAfterFirstIssue).isEqualTo(1);
        mailer.sent.clear();

        assertThatThrownBy(() -> depositNoticeService.issue(fixture.docId(), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(countAccountNotificationRows()).isEqualTo(rowsAfterFirstIssue);
        assertThat(mailer.sent()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Revision re-issue (the DEPOSIT_NOTICE_ISSUED self-loop) notifies account again
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issue_revisionReissue_notifiesAccountAgainMarkedAsARevision() {
        DraftFixture fixture = buildDraftDepositNotice();
        depositNoticeService.issue(fixture.docId(), salesActor);
        assertThat(countAccountNotificationRows()).isEqualTo(1);
        mailer.sent.clear();

        // Repository-direct on purpose: DepositNoticeService.createDraft only ever sources the
        // FIRST draft from the ticket's approved items/quotation, so building a literal SECOND
        // draft (the revision) this way is the shortest path to the state under test here, and
        // it is only issue() itself (called through the real service below) that this test cares
        // about exercising faithfully.
        long secondDocId = new DepositNoticeRepository(jdbc).createDraft(fixture.ticketId(),
            new DepositNoticeDraftRequest("ACME Revision", "0100000000000", "Bangkok", "Showroom", "REF-REV",
                new BigDecimal("0.50"), List.of(), null),
            List.of(new DepositNoticeItemRequest(
                1, "Revision item", new BigDecimal("1"), "แผ่น",
                new BigDecimal("100.00"), null, new BigDecimal("100.00"))));

        DepositNoticeDto revisionIssued = depositNoticeService.issue(secondDocId, salesActor);

        assertThat(countAccountNotificationRows()).isEqualTo(2);
        String secondMessage = (String) jdbc.queryForMap("""
            SELECT message FROM hr.notification
             WHERE employee_id = :accountId AND type = 'DEPOSIT_NOTICE_ISSUED'
             ORDER BY notification_id DESC LIMIT 1
            """, Map.of("accountId", accountEmployeeId)).get("message");
        assertThat(secondMessage).contains("ฉบับแก้ไข");
        assertThat(secondMessage).contains(revisionIssued.docNumber());
        assertThat(mailer.recipients()).contains("account@glr.co.th");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Rollback: the critical AfterCommit trap this branch exists to avoid regressing
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issue_rolledBackTransaction_notifiesNobodyByRowOrByMail() {
        DraftFixture fixture = buildDraftDepositNotice();
        mailer.sent.clear();
        DepositNoticeService transactionalService = transactional(depositNoticeService);

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            transactionalService.issue(fixture.docId(), salesActor);
            throw new IllegalStateException("forced rollback after the account notification was raised");
        })).isInstanceOf(IllegalStateException.class);

        // Load-bearing: zero rows proves the transaction actually rolled back, not that issue()
        // never ran.
        assertThat(countAccountNotificationRows()).isZero();
        assertThat(mailer.sent())
            .as("a rolled-back issue() must not email account about a deposit notice that no "
                + "longer exists")
            .isEmpty();
    }

    /**
     * The other half of the same contract — a committed issue() through the same real
     * transactional proxy must still mail after commit. Without this, {@link
     * #issue_rolledBackTransaction_notifiesNobodyByRowOrByMail} alone would stay green on wiring
     * that can never send at all.
     */
    @Test
    void issue_committedTransaction_actuallyMailsAfterCommitThroughTheRealProxy() {
        DraftFixture fixture = buildDraftDepositNotice();
        mailer.sent.clear();
        DepositNoticeService transactionalService = transactional(depositNoticeService);

        transactionTemplate.execute(status -> transactionalService.issue(fixture.docId(), salesActor));

        assertThat(countAccountNotificationRows()).isEqualTo(1);
        assertThat(mailer.recipients()).contains("account@glr.co.th");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Pin: confirmDepositPaid must NOT notify account (owner decided explicitly against it)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmDepositPaid_doesNotRaiseASecondAccountNotification() {
        DraftFixture fixture = buildDraftDepositNotice();
        depositNoticeService.issue(fixture.docId(), salesActor);
        long rowsAfterIssue = countAccountNotificationRows();
        assertThat(rowsAfterIssue).isEqualTo(1);
        mailer.sent.clear();

        // GLA-118 (owner ruling 2026-09-17): confirmDepositPaid is account-only now — the CEO
        // fallback this used to exercise is gone, so this drives it as account instead.
        ticketService.confirmDepositPaid(fixture.ticketId(), accountActor);

        assertThat(countAccountNotificationRows())
            .as("confirmDepositPaid is explicitly NOT one of the notify moments this branch adds")
            .isEqualTo(rowsAfterIssue);
        assertThat(mailer.recipients()).doesNotContain("account@glr.co.th");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers — mirrors DepositNoticeIssueGuardIntegrationTest's approach for driving a
    // deal to a DRAFT deposit notice through the real Steps 1-6 services, stopping one call short
    // of issue() so each test drives that call itself.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private record DraftFixture(long ticketId, long docId, String ticketCode, String customerName) {}

    private long countAccountNotificationRows() {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE employee_id = :accountId AND type = 'DEPOSIT_NOTICE_ISSUED'
            """, Map.of("accountId", accountEmployeeId), Long.class);
    }

    /** Baseline marker so a test can isolate the rows ITS OWN call adds from the setup pipeline's
     *  own import/ceo/sales notifications (submit/pickup/quotation/confirm-order all raise their
     *  own, through the same real wiring). */
    private long maxNotificationId() {
        return jdbc.queryForObject(
            "SELECT COALESCE(MAX(notification_id), 0) FROM hr.notification", Map.of(), Long.class);
    }


    private DraftFixture buildDraftDepositNotice() {
        long catalogProductId = insertCatalogProduct(FACTORY, "IT",
            "TEST-ACC1-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        String customerName = "บริษัท AccountNotice " + UUID.randomUUID() + " จำกัด";
        CustomerDto customer = customersRepo.create(
            customerName, "0100000000029", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0029");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ AccountNotice");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล AccountNotice", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile AccountNotice", FACTORY))),
            salesActor);
        long ticketId = created.summary().id();
        String ticketCode = created.summary().code();
        long ticketItemId = created.items().get(0).id();

        BigDecimal quantity = new BigDecimal("10");
        // Rebased onto develop @84f4123e (V184): V185/GLA-125 made color/texture/thicknessMm/
        // sqmPerPiece/piecesPerBox/a quantity/originCountry/leadTimeMin+MaxDays unconditionally
        // required (PricingRequestService#requireItemFieldsComplete) -- the old 18-arg compat
        // shape (requestedQty/requestedQtySqm/requestedUnit/requestedUnitBasis sent directly, no
        // tile fields) now 400s with "ขาด สี, ผิว, ความหนา, ... ประเทศต้นทาง, ระยะเวลานำเข้า (วัน)".
        // Rebuilt as a complete new-form item: PIECES mode, wastageMode NONE, roundToFullBox
        // false, so the derived requestedQty comes out EXACTLY equal to `quantity` -- preserving
        // this fixture's original "requestedQty/quotedQuantity both equal `quantity`" arithmetic
        // that driveDraftPricingRequestToQuotationAccepted's factory response relies on.
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile AccountNotice", "SCG Tile AccountNotice", "ขาว", "ด้าน",
            "60x60", FACTORY, null, null, null, null, QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), "PIECES", null, quantity.intValueExact(),
            "NONE", null, 4, null, false, "ไทย-สต็อก", 3, 7, null, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "deposit-notice account-notify walk", UUID.randomUUID().toString(),
            List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();

        driveDraftPricingRequestToQuotationAccepted(pricingRequestId, quantity);

        orderConfirmation.confirmOrder(pricingRequestId,
            new ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);
        DepositNoticeDto draft = orderConfirmation.createDepositNoticeFromQuotation(pricingRequestId,
            new CreateDepositNoticeFromQuotationRequest(null), salesActor);

        return new DraftFixture(ticketId, draft.id(), ticketCode, customerName);
    }

    private void driveDraftPricingRequestToQuotationAccepted(long pricingRequestId, BigDecimal quantity) {
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        long pricingRequestItemId = draft.items().get(0).pricingRequestItemId();
        String email = FACTORY.toLowerCase().replace(" ", "-") + "@example.com";
        factoryQuoteService.send(draft.id(),
            new SendFactoryQuoteRequest(email, null, null), importActor);
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
                UUID.randomUUID().toString()), salesActor);
        CustomerQuotationDto issued = quotationService.issue(
            draftQuotation.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), salesActor);
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

    /**
     * Same double as {@code SalesNotificationMailRoutingIntegrationTest} — captures the full
     * rendered mail so subject/body assertions are possible, not just the recipient.
     */
    record SentMail(String to, String subject, String htmlBody, String textBody) {}

    private static final class CapturingMailer implements Mailer {
        private final List<SentMail> sent = new ArrayList<>();

        List<SentMail> sentList() {
            return sent;
        }

        List<String> sent() {
            return sent.stream().map(SentMail::to).toList();
        }

        List<String> recipients() {
            return sent.stream().map(SentMail::to).toList();
        }

        @Override
        public void send(String to, String subject, String body) {
            sent.add(new SentMail(to, subject, null, body));
        }

        @Override
        public void sendHtml(String to, String subject, String htmlBody, String textBody,
                             List<InlineImage> inlineImages) {
            sent.add(new SentMail(to, subject, htmlBody, textBody));
        }

        @Override
        public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {
            sent.add(new SentMail(to, subject, null, body));
        }

        @Override
        public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {
            sent.add(new SentMail(to, subject, null, body));
        }
    }
}
