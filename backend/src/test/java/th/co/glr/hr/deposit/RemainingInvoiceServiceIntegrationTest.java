package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-Postgres coverage for the STORED remaining invoice: lifecycle
 * (draft -&gt; issue -&gt; revise -&gt; issue), numbering (shared {@code AR_GLR} document_sequence,
 * base_number carried across a revision, a fresh base per deal), snapshot immutability, and — per
 * CLAUDE.md's "permission changes must ship evidence" requirement — the write/read authz gate
 * written WRONG-WAY-ROUND: a role that could not issue a deposit notice on this ticket must not be
 * able to write its remaining invoice either, and import must not be able to read it at all.
 *
 * <p>Drives a deal through the REAL Steps 1-6 services to an ACCEPTED quotation + ISSUED deposit
 * notice — the same fixture-building approach {@code DepositNoticeIssueGuardIntegrationTest} uses
 * — because {@link RemainingInvoiceService#createDraft} snapshots off exactly that real state via
 * {@link DepositNoticeService#resolveRemainingInvoiceSnapshot}, and a mocked repository could not
 * prove that resolution, or the SQL it produces, actually behaves as claimed (CLAUDE.md's own
 * "Mockito cannot reach this" rule).
 */
class RemainingInvoiceServiceIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private RecordingSalesMailer mailer;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private DepositNoticeService depositNoticeService;
    private OrderConfirmationService orderConfirmation;
    private RemainingInvoiceRepository remainingInvoiceRepository;
    private RemainingInvoiceService remainingInvoiceService;
    private TicketService ticketService;

    private long salesRepId;
    private long otherSalesRepId;
    private long strangerRepId;
    private long salesManagerId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;

    private static final String FACTORY = "Factory RI";

    @BeforeEach
    void wireStepsServices() {
        tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
        CustomerRepository customers = new CustomerRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-remaining-invoice-test-uploads");
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
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications, new DiscountApprovalRepository(jdbc));

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticePdfRenderer(), customers, quotationRepository, th.co.glr.hr.support.DocumentBundles.noDownload(jdbc), new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc));

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        remainingInvoiceRepository = new RemainingInvoiceRepository(jdbc);
        remainingInvoiceService = new RemainingInvoiceService(
            remainingInvoiceRepository, depositNoticeService, tickets, new RemainingInvoicePdfRenderer(), notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย RI", "sales-ri1@glr.co.th", "SALES", "แผนกขาย");
        otherSalesRepId = createEmployee(employees, "พนักงานขาย RI2", "sales-ri2@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า RI", "import-ri1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร RI", "ceo-ri1@glr.co.th", "MD", "ผู้บริหาร");
        long accountUserId = createEmployee(employees, "บัญชี RI", "account-ri1@glr.co.th", "ACCT", "บัญชี");
        long salesManagerUserId = createEmployee(employees, "ผจก.ขาย RI", "sales-mgr-ri1@glr.co.th", "SALESMGR", "แผนกขาย");
        strangerRepId = createEmployee(employees, "พนักงานขาย RI3", "sales-ri3@glr.co.th", "SALES", "แผนกขาย");
        salesManagerId = salesManagerUserId;
        // findSalesManagerApproverEmployeeIds: active, SA* division, position ผู้จัดการ
        jdbc.update("UPDATE hr.employee SET position_id = (SELECT position_id FROM hr.position WHERE name_th = 'ผู้จัดการ' LIMIT 1) WHERE employee_id = :id",
            Map.of("id", salesManagerUserId));
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");
    }

    /**
     * Each tax invoice's VAT is rounded on its own net, so deposit + remaining can
     * differ from the deal's VAT-inclusive payable by a satang -- and outstanding + the final BALANCE receipt then
     * FOLLOW THE ISSUED DOCUMENTS: once the remaining invoice is issued, payable = deposit notice total + remaining
     * invoice grand total, the deposit receipt is the notice total, the balance is exactly the invoice grand total
     * and the deal closes FULLY_PAID with nothing left (no satang overpaid/underpaid).
     */
    @Test
    void issuedRemainingInvoice_outstandingAndFinalPaymentFollowTheIssuedDocuments() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "FollowDocs");
        long ticketId = fixture.ticketId();
        BigDecimal depositNoticeTotal = new DepositNoticeRepository(jdbc).findById(fixture.depositNoticeId()).orElseThrow().totalPayable();
        ticketService.confirmDepositPaid(ticketId, accountActor);

        BigDecimal payableBeforeInvoice = tickets.payableAmount(ticketId);
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(ticketId, null, salesActor).id(), salesActor);
        // fixture precondition: per-document rounding really does drift from the deal payable here
        assertThat(payableBeforeInvoice.subtract(depositNoticeTotal)).isNotEqualByComparingTo(issued.grandTotal());

        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo(depositNoticeTotal.add(issued.grandTotal()));
        assertThat(tickets.findSummaryById(ticketId).orElseThrow().amountOutstanding()).isEqualByComparingTo(issued.grandTotal());

        // Owner ruling 2026-11-07: final payment opens at DELIVERY_SCHEDULING.
        tickets.updateSalesStage(ticketId, DealStage.DELIVERY_SCHEDULING);
        ticketService.confirmFinalPayment(ticketId, accountActor);

        assertThat(jdbc.queryForObject(
            "SELECT amount FROM sales.payment_receipt WHERE ticket_id = :id AND kind = 'BALANCE'",
            Map.of("id", ticketId), BigDecimal.class)).isEqualByComparingTo(issued.grandTotal());
        var after = tickets.findSummaryById(ticketId).orElseThrow();
        assertThat(after.paymentStatus()).isEqualTo("FULLY_PAID");
        assertThat(after.amountOutstanding()).isEqualByComparingTo("0");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Hand-off notification: issuing the remaining invoice tells account to collect it.
    // Pins the bell row AND the email path for the same recipient.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static final String RI_ISSUED_TITLE = "ออกใบแจ้งหนี้ (ยอดคงเหลือ) แล้ว — รอยืนยันรับชำระ";

    private String invoiceIssuedMessage(long ticketId, UserPrincipal issuer, RemainingInvoiceDocumentDto doc,
                                        boolean revision) {
        var s = tickets.findById(ticketId).orElseThrow().summary();
        return issuer.name() + " ออกใบแจ้งหนี้ " + doc.docNumber() + (revision ? " (ฉบับแก้ไข)" : "") + " แล้ว\n"
            + "ดีล " + s.code() + " · ลูกค้า " + s.customerName() + " · ยอดชำระรวม VAT "
            + th.co.glr.hr.common.ThaiText.money(doc.grandTotal()) + " บาท"
            + "\nสิ่งที่ต้องทำต่อ: ตรวจรับเงินโอนจากลูกค้า แล้วบันทึกรับชำระในระบบ";
    }

    @Test
    void issue_firstVersion_notifiesAccountWithTheVatInclusiveTotal() {
        long accountId = mailer.seedRoleEmployee("account");
        DealFixture fixture = buildAcceptedDeal(salesActor, "NotifyIssue");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);
        mailer.assertNoNotice("REMAINING_INVOICE_ISSUED", RI_ISSUED_TITLE); // a draft notifies nobody

        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(draft.id(), salesActor);

        mailer.assertRoleNotice("account", "REMAINING_INVOICE_ISSUED", RI_ISSUED_TITLE,
            invoiceIssuedMessage(fixture.ticketId(), salesActor, issued, false),
            "/finance/deals/" + fixture.ticketId(), accountId);
    }

    @Test
    void createDraft_thenIssue_mintsFirstVersionOfANewBase() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "Lifecycle1");

        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.docNumber()).isNull();
        assertThat(draft.items()).isNotEmpty();
        // vatAmount/grandTotal are stored money fields — pin them against the SAME formula
        // DepositNoticeService#computeRemainingInvoiceMoney (which this class reuses) documents.
        assertThat(draft.vatAmount())
            .isEqualByComparingTo(draft.netAmount().multiply(new BigDecimal("0.07")).setScale(2, RoundingMode.HALF_UP));
        assertThat(draft.grandTotal()).isEqualByComparingTo(draft.netAmount().add(draft.vatAmount()));

        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(draft.id(), salesActor);
        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.baseNumber()).matches("GLR\\d{7}");
        assertThat(issued.version()).isEqualTo(1);
        assertThat(issued.docNumber()).isEqualTo(issued.baseNumber() + "-1");
        assertThat(issued.vatAmount())
            .isEqualByComparingTo(issued.netAmount().multiply(new BigDecimal("0.07")).setScale(2, RoundingMode.HALF_UP));
        assertThat(issued.grandTotal()).isEqualByComparingTo(issued.netAmount().add(issued.vatAmount()));
    }

    @Test
    void revise_thenIssue_keepsTheBaseNumberAndBumpsVersion_andSupersedesThePredecessor() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "Lifecycle2");
        RemainingInvoiceDocumentDto v1 = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        RemainingInvoiceDocumentDto revisionDraft = remainingInvoiceService.revise(v1.id(), salesActor);
        assertThat(revisionDraft.status()).isEqualTo("DRAFT");
        RemainingInvoiceDocumentDto v2 = remainingInvoiceService.issue(revisionDraft.id(), salesActor);

        assertThat(v2.baseNumber()).isEqualTo(v1.baseNumber());
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.docNumber()).isEqualTo(v1.baseNumber() + "-2");

        RemainingInvoiceDocumentDto predecessorReread = remainingInvoiceService.get(v1.id(), salesActor);
        assertThat(predecessorReread.status()).isEqualTo("SUPERSEDED");
        assertThat(predecessorReread.supersededById()).isEqualTo(v2.id());
        // The predecessor's OWN identity never changes on supersede — same discipline
        // DepositNoticeRepository#supersede/ImportRequestRepository#supersede already follow.
        assertThat(predecessorReread.docNumber()).isEqualTo(v1.docNumber());
    }

    @Test
    void issue_snapshotIsFrozen_editingTheSourceDepositNoticeAfterwardsDoesNotChangeTheIssuedInvoice() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "Frozen1");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);
        BigDecimal originalDeduction = issued.depositDeduction();
        BigDecimal originalNet = issued.netAmount();
        byte[] originalFile = remainingInvoiceService.file(issued.id(), salesActor);

        // Mutate the SOURCE deposit notice's already-issued deposit amount directly — the remaining invoice was
        // already ISSUED before this happened, and per this document's own "always rendered from the snapshot" rule
        // must not move.
        jdbc.update("UPDATE sales.deposit_notice SET deposit_amount = :amt WHERE deposit_notice_id = :id",
            Map.of("amt", new BigDecimal("1.00"), "id", fixture.depositNoticeId()));

        RemainingInvoiceDocumentDto rereadAfterUnrelatedEdit = remainingInvoiceService.get(issued.id(), salesActor);
        assertThat(rereadAfterUnrelatedEdit.depositDeduction()).isEqualByComparingTo(originalDeduction);
        assertThat(rereadAfterUnrelatedEdit.netAmount()).isEqualByComparingTo(originalNet);
        assertThat(remainingInvoiceService.file(issued.id(), salesActor)).isEqualTo(originalFile);
    }

    @Test
    void createDraft_negativeNetAfterDeduction_isRefusedWithConflict() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "Negative1");
        // Push the deposit notice's own deposit amount above the item total it will be matched
        // against — items/deduction both come from the matched notice's own snapshot,
        // so inflating ITS depositAmount is what drives the remaining invoice's net negative.
        jdbc.update("UPDATE sales.deposit_notice SET deposit_amount = :amt WHERE deposit_notice_id = :id",
            Map.of("amt", new BigDecimal("999999.00"), "id", fixture.depositNoticeId()));

        assertThatThrownBy(() -> remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /** "One live remaining invoice per deal" is a real DB invariant, not merely an
     * application-level courtesy check — {@code ux_remaining_invoice_ticket_issued} is
     * on ticket_id ALONE, not (ticket_id, customer_quotation_id). Proven by going around
     * {@link RemainingInvoiceService#issue}'s own supersede-before-issue pass entirely: a raw
     * second ISSUED row for the SAME ticket_id, inserted directly via JDBC, must be refused by
     * POSTGRES itself, regardless of which quotation chain it claims. */
    @Test
    void v188_dbInvariant_atMostOneIssuedRemainingInvoicePerTicket_evenBypassingTheService() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "DbInvariantIssued");
        remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.remaining_invoice
                (ticket_id, customer_quotation_id, base_number, version, doc_number, status,
                 items_total, deposit_deduction, net_amount, vat_amount, grand_total,
                 issued_by_id, issued_by_name, issued_at)
            VALUES (:ticketId, NULL, 'GLR69099999', 1, 'GLR69099999-1', 'ISSUED',
                    0, 0, 0, 0, 0, :actorId, 'Actor', now())
            """, Map.of("ticketId", fixture.ticketId(), "actorId", salesActor.id())))
            .as("a second ISSUED row for the same ticket_id must be refused by the DB itself, even"
                + " for a wholly different (here: NULL) quotation chain")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // Authz — WRONG-WAY-ROUND (CLAUDE.md requirement): reused DepositNoticeService predicate

    @Test
    void createDraft_nonOwningSalesRep_isRefused() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzCreate1");
        assertThatThrownBy(() -> remainingInvoiceService.createDraft(fixture.ticketId(), null, otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** v2.3.0: the read-all grant is READ only: a flag-only reader (a sales rep who does not own the deal) writes no invoice. */
    @Test
    void flagOnlyReader_cannotCreateOrIssueARemainingInvoice() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "FlagOnlyWrite");
        long draftId = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id();
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id = :id", Map.of("id", otherSalesActor.id()));

        assertThatThrownBy(() -> remainingInvoiceService.createDraft(fixture.ticketId(), null, otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> remainingInvoiceService.issue(draftId, otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(remainingInvoiceService.get(draftId, salesActor).status()).isEqualTo("DRAFT");
    }

    @Test
    void read_ceoAndAccountInScopeAndSalesManager_canReadAndDownload_viewerRoleUnaffectedByTheWriteGate() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzRead3");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        // Account is ROW-SCOPED to its list scope (live, S10+). Below S10 every read is refused;
        // ceo and sales_manager are unaffected by the scope.
        // (Below S10 AND no pending payment status: a deposit-notice-issued deal is in scope by the payment rule.)
        setStage(fixture.ticketId(), "NEGOTIATION");
        jdbc.update("UPDATE sales.ticket SET payment_status = NULL WHERE ticket_id = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", fixture.ticketId()));
        assertThatThrownBy(() -> remainingInvoiceService.get(issued.id(), accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> remainingInvoiceService.file(issued.id(), accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> remainingInvoiceService.list(fixture.ticketId(), accountActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(remainingInvoiceService.get(issued.id(), ceoActor).id()).isEqualTo(issued.id());
        setStage(fixture.ticketId(), "PROCUREMENT");
        assertThat(remainingInvoiceService.get(issued.id(), accountActor).id()).isEqualTo(issued.id());
        // This test's name promises sales_manager too, so that actor is constructed and asserted here.
        assertThat(remainingInvoiceService.get(issued.id(), salesManagerActor).id()).isEqualTo(issued.id());
        assertThat(remainingInvoiceService.file(issued.id(), ceoActor)).isNotEmpty();
        assertThat(remainingInvoiceService.file(issued.id(), accountActor)).isNotEmpty();
        assertThat(remainingInvoiceService.file(issued.id(), salesManagerActor)).isNotEmpty();
    }

    private List<Map<String, Object>> editedEvents(long ticketId) {
        return jdbc.queryForList(
            "SELECT actor_id, message FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'EDITED'",
            Map.of("t", ticketId));
    }

    @Test
    void updateDraft_onAnIssuedInvoice_editsInPlace_keepsNumberAndStatus_andWritesAnAuditEvent() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "EditIssuedRi");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);
        assertThat(editedEvents(fixture.ticketId())).isEmpty();

        RemainingInvoiceDocumentDto edited = remainingInvoiceService.updateDraft(issued.id(),
            new RemainingInvoiceDraftRequest(null, "PO-ISSUED-EDIT", "DEP-ISSUED-EDIT", LocalDate.of(2026, 11, 20),
                List.of("edited note")), salesActor);

        assertThat(edited.id()).isEqualTo(issued.id());
        assertThat(edited.status()).isEqualTo("ISSUED");
        assertThat(edited.docNumber()).isEqualTo(issued.docNumber());
        assertThat(edited.version()).isEqualTo(issued.version());
        assertThat(edited.reference()).isEqualTo("PO-ISSUED-EDIT");
        assertThat(edited.depositReference()).isEqualTo("DEP-ISSUED-EDIT");
        assertThat(edited.docDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(edited.notes()).containsExactly("edited note");
        RemainingInvoiceDocumentDto reread = remainingInvoiceService.get(issued.id(), salesActor);
        assertThat(reread.status()).isEqualTo("ISSUED");
        assertThat(reread.reference()).isEqualTo("PO-ISSUED-EDIT");

        List<Map<String, Object>> events = editedEvents(fixture.ticketId());
        assertThat(events).hasSize(1);
        assertThat(((Number) events.get(0).get("actor_id")).longValue()).isEqualTo(salesRepId);
        String message = (String) events.get(0).get("message");
        assertThat(message).contains(issued.docNumber()).contains("PO-ISSUED-EDIT");
        assertThat(message).contains(issued.reference() == null ? "" : issued.reference());
    }

    /** Partial deposit: the notice says 107,000 (100,000 pre-VAT) but only 50,000 was received. The invoice deducts the
     * full notice (max of notice and received), and payable = notice total + invoice -- no double billing. */
    @Test
    void partialDepositReceipt_invoiceStillDeductsTheNotice_andPayableFollowsTheFrozenDeduction() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "PartialDeposit");
        long ticketId = fixture.ticketId();
        var dn = new DepositNoticeRepository(jdbc).findById(fixture.depositNoticeId()).orElseThrow();
        ticketService.confirmDepositPaid(ticketId, accountActor);
        jdbc.update("UPDATE sales.payment_receipt SET amount = 50000 WHERE ticket_id = :id AND kind = 'DEPOSIT'",
            Map.of("id", ticketId));

        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(ticketId, null, salesActor).id(), salesActor);

        assertThat(issued.depositDeduction()).isEqualByComparingTo(dn.depositAmount());
        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo(dn.totalPayable().add(issued.grandTotal()));
    }

    @Test
    void updateDraft_onAnIssuedInvoice_nonOwnerRolesAreRefused_rowAndAuditUntouched() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "EditIssuedRiAuthz");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        for (UserPrincipal intruder : List.of(otherSalesActor, ceoActor, importActor, accountActor, salesManagerActor)) {
            assertThatThrownBy(() -> remainingInvoiceService.updateDraft(issued.id(),
                    new RemainingInvoiceDraftRequest(null, "SHOULD-NEVER-STICK", null, null, null), intruder))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        RemainingInvoiceDocumentDto reread = remainingInvoiceService.get(issued.id(), salesActor);
        assertThat(reread.reference()).isEqualTo(issued.reference());
        assertThat(editedEvents(fixture.ticketId())).isEmpty();
    }

    @Test
    void updateDraft_onASupersededInvoice_isStillRefusedWith409() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "EditSupersededRi");
        RemainingInvoiceDocumentDto v1 = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);
        remainingInvoiceService.issue(remainingInvoiceService.revise(v1.id(), salesActor).id(), salesActor);
        assertThat(remainingInvoiceService.get(v1.id(), salesActor).status()).isEqualTo("SUPERSEDED");

        assertThatThrownBy(() -> remainingInvoiceService.updateDraft(v1.id(),
                new RemainingInvoiceDraftRequest(null, "SHOULD-NEVER-STICK", null, null, null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo("เอกสารนี้ถูกแทนที่ด้วยฉบับใหม่แล้ว แก้ไขไม่ได้");
            });
        assertThat(remainingInvoiceService.get(v1.id(), salesActor).reference()).isEqualTo(v1.reference());
        assertThat(editedEvents(fixture.ticketId())).isEmpty();
    }

    /** Once the deposit is paid in full the notice cannot be edited BELOW what was received (account refunds first). */
    @Test
    void editingTheIssuedDepositNotice_belowTheReceivedDeposit_isRefused() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "EditBelowReceived");
        ticketService.confirmDepositPaid(fixture.ticketId(), accountActor);
        DepositNoticeDto notice = depositNoticeService.getById(fixture.depositNoticeId(), salesActor);

        assertThatThrownBy(() -> depositNoticeService.update(
            fixture.depositNoticeId(), noticeWithPercent(notice, "0.01"), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ลดยอดมัดจำต่ำกว่าที่รับชำระแล้วไม่ได้");
            });
    }

    /** Overpaid deposit: the invoice still deducts the NOTICE's deposit and payable = notice total + invoice; after
     * account refunds the excess as an ADJUSTMENT receipt, only the invoice is outstanding (the refund is never left unbilled). */
    @Test
    void overpaidDeposit_invoiceDeductsTheNotice_andRefundLeavesNothingOutstanding() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "OverpaidDeposit");
        long ticketId = fixture.ticketId();
        var dn = new DepositNoticeRepository(jdbc).findById(fixture.depositNoticeId()).orElseThrow();
        ticketService.confirmDepositPaid(ticketId, accountActor);
        BigDecimal excess = new BigDecimal("10700.00");
        jdbc.update("UPDATE sales.payment_receipt SET amount = :a WHERE ticket_id = :id AND kind = 'DEPOSIT'",
            Map.of("a", dn.totalPayable().add(excess), "id", ticketId));

        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(ticketId, null, salesActor).id(), salesActor);
        assertThat(issued.depositDeduction()).isEqualByComparingTo(dn.depositAmount());
        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo(dn.totalPayable().add(issued.grandTotal()));

        jdbc.update("INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref)"
            + " VALUES (:t, 'ADJUSTMENT', :a, :by, 'REFUND-TEST')",
            Map.of("t", ticketId, "a", excess, "by", accountActor.id()));
        assertThat(tickets.netDepositReceived(ticketId)).isEqualByComparingTo(dn.totalPayable());
        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo(dn.totalPayable().add(issued.grandTotal()));
        // net received = notice total, so only the invoice itself is still outstanding (no phantom 10,700)
        assertThat(tickets.findSummaryById(ticketId).orElseThrow().amountOutstanding())
            .isEqualByComparingTo(issued.grandTotal());
    }

    @Test
    void updatingAnIssuedNotice_above100Percent_isRefused_andOnACancelledDeal_isRefused() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "UpdateGuards");
        DepositNoticeDto notice = depositNoticeService.getById(fixture.depositNoticeId(), salesActor);

        assertThatThrownBy(() -> depositNoticeService.update(
            fixture.depositNoticeId(), noticeWithPercent(notice, "1.50"), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));

        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED' WHERE ticket_id = :id",
            Map.of("id", fixture.ticketId()));
        assertThatThrownBy(() -> depositNoticeService.update(
            fixture.depositNoticeId(), noticeWithPercent(notice, "0.30"), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    }

    /** updateDraft's repository writes are told the status the service read, so a DRAFT save cannot land on a row
     * issued in between (and an ISSUED save cannot land on a superseded one). */
    @Test
    void repositoryWrites_refuseARowThatIsNoLongerInTheStatusTheServiceRead() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "RiRepoRace");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        int fields = remainingInvoiceRepository.updateDraftFields(issued.id(), "RACE", null, LocalDate.of(2026, 1, 1),
            List.of(), null, th.co.glr.hr.deposit.IssueNames.NONE, "DRAFT");
        int snapshot = remainingInvoiceRepository.updateSnapshot(issued.id(), null, "RACE", null, null, null, null,
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "DRAFT");

        assertThat(fields).isZero();
        assertThat(snapshot).isZero();
        RemainingInvoiceDocumentDto reread = remainingInvoiceService.get(issued.id(), salesActor);
        assertThat(reread.reference()).isEqualTo(issued.reference());
        assertThat(reread.grandTotal()).isEqualByComparingTo(issued.grandTotal());
    }

    @Test
    void editingTheIssuedDepositNotice_refreshesADraftInvoiceToo() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "CascadeDraftRi");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);
        DepositNoticeDto notice = depositNoticeService.getById(fixture.depositNoticeId(), salesActor);
        String newPercent = notice.depositPercent().compareTo(new BigDecimal("0.40")) == 0 ? "0.20" : "0.40";

        DepositNoticeDto editedNotice = depositNoticeService.update(
            fixture.depositNoticeId(), noticeWithPercent(notice, newPercent), salesActor);

        RemainingInvoiceDocumentDto refreshed = remainingInvoiceService.get(draft.id(), salesActor);
        assertThat(refreshed.status()).isEqualTo("DRAFT");
        assertThat(refreshed.depositDeduction()).isEqualByComparingTo(editedNotice.depositAmount());
        assertThat(refreshed.netAmount()).isEqualByComparingTo(refreshed.itemsTotal().subtract(editedNotice.depositAmount()));
        assertThat(refreshed.grandTotal()).isNotEqualByComparingTo(draft.grandTotal());
    }

    @Test
    void issue_accountAndSalesManager_areRefused_rowStaysDraft() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "IssueAuthz1");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);

        for (UserPrincipal intruder : List.of(accountActor, salesManagerActor)) {
            assertThatThrownBy(() -> remainingInvoiceService.issue(draft.id(), intruder))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(remainingInvoiceService.get(draft.id(), salesActor).status()).isEqualTo("DRAFT");
    }

    private DepositNoticeDraftRequest noticeWithPercent(DepositNoticeDto n, String percent) {
        return new DepositNoticeDraftRequest(n.customerName(), n.customerTaxId(), n.customerAddress(),
            n.projectName(), n.reference(), new BigDecimal(percent), n.notes(), null);
    }

    // Fixture helpers — adapted from DepositNoticeIssueGuardIntegrationTest's own approach for driving a deal
    // through the real Steps 1-6 services to an accepted quotation + issued deposit notice.

    private record DealFixture(long ticketId, long pricingRequestId, long depositNoticeId) {}

    private DealFixture buildAcceptedDeal(UserPrincipal owner, String label) {
        long catalogProductId = insertCatalogProduct(FACTORY, "IT",
            "TEST-RI-" + label + "-" + UUID.randomUUID().toString().substring(0, 8),
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerRepository customersRepo = new CustomerRepository(jdbc);
        ProjectRepository projectsRepo = new ProjectRepository(jdbc);
        CustomerDto customer = customersRepo.create(
            "บริษัท RI " + label + " " + UUID.randomUUID() + " จำกัด", "0100000000019",
            "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0019");
        ProjectDto project = projectsRepo.create(customer.id(), "โครงการ RI " + label);
        TicketService ticketServiceForCreate = new TicketService(tickets,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), new ObjectMapper(), customersRepo,
            new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        TicketDto created = ticketServiceForCreate.create(
            new CreateTicketRequest("ดีล RI " + label, "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile RI " + label, FACTORY))),
            owner);
        long ticketId = created.summary().id();
        long ticketItemId = created.items().get(0).id();

        BigDecimal quantity = new BigDecimal("10");
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            ticketItemId, catalogProductId, null, "SCG", "Tile RI " + label, "SCG Tile RI " + label,
            "White", "Matte", "60x60", FACTORY, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, quantity.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "remaining-invoice test walk", UUID.randomUUID().toString(), List.of(item));
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
        CustomerQuotationDto issued = driveDraftPricingRequestToQuotationIssued(pricingRequestId, quantity, owner);
        quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), owner);
    }

    /** Drives a fresh pricing request to an ISSUED customer quotation. */
    private CustomerQuotationDto driveDraftPricingRequestToQuotationIssued(long pricingRequestId, BigDecimal quantity, UserPrincipal owner) {
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
        return issued;
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private void setStage(long ticketId, String stage) {
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("s", stage).addValue("id", ticketId));
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
