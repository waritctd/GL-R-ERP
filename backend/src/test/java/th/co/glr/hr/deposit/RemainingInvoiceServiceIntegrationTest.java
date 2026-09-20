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
 * Real-Postgres coverage for the STORED remaining invoice (GLA-99 step 2): lifecycle
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
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private DepositNoticeService depositNoticeService;
    private OrderConfirmationService orderConfirmation;
    private RemainingInvoiceRepository remainingInvoiceRepository;
    private RemainingInvoiceService remainingInvoiceService;

    private long salesRepId;
    private long otherSalesRepId;
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
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
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

        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications, new DiscountApprovalRepository(jdbc));

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets, notifications,
            new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers, quotationRepository);

        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, quotationRepository, depositNoticeService, notifications);

        remainingInvoiceRepository = new RemainingInvoiceRepository(jdbc);
        remainingInvoiceService = new RemainingInvoiceService(
            remainingInvoiceRepository, depositNoticeService, tickets, new RemainingInvoiceRenderer());

        salesRepId = createEmployee(employees, "พนักงานขาย RI", "sales-ri1@glr.co.th", "SALES", "แผนกขาย");
        otherSalesRepId = createEmployee(employees, "พนักงานขาย RI2", "sales-ri2@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า RI", "import-ri1@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร RI", "ceo-ri1@glr.co.th", "MD", "ผู้บริหาร");
        long accountUserId = createEmployee(employees, "บัญชี RI", "account-ri1@glr.co.th", "ACCT", "บัญชี");
        long salesManagerUserId = createEmployee(employees, "ผจก.ขาย RI", "sales-mgr-ri1@glr.co.th", "SALESMGR", "แผนกขาย");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Lifecycle + numbering
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createDraft_thenIssue_mintsFirstVersionOfANewBase() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "Lifecycle1");

        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.docNumber()).isNull();
        assertThat(draft.items()).isNotEmpty();
        // P5 (Opus review, GLA-99 step 2 review-round-2): vatAmount/grandTotal are new money
        // fields (V188) that were going stored but unasserted — pin them against the SAME formula
        // DepositNoticeService#computeRemainingInvoiceMoney (which this class now reuses) documents.
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
    void issue_twoDifferentDeals_getDifferentBaseNumbers_fromTheSharedArGlrSequence() {
        DealFixture a = buildAcceptedDeal(salesActor, "Base1");
        DealFixture b = buildAcceptedDeal(salesActor, "Base2");

        RemainingInvoiceDocumentDto issuedA = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(a.ticketId(), null, salesActor).id(), salesActor);
        RemainingInvoiceDocumentDto issuedB = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(b.ticketId(), null, salesActor).id(), salesActor);

        assertThat(issuedA.baseNumber()).isNotEqualTo(issuedB.baseNumber());

        // Both numbers were minted from the SAME sales.document_sequence row (doc_type 'AR_GLR') —
        // the shared sequence the future ใบวางบิล (V189) will also draw from, per the plan.
        Integer lastSeq = jdbc.queryForObject(
            "SELECT last_seq FROM sales.document_sequence WHERE doc_type = 'AR_GLR' AND year_th = :y",
            Map.of("y", LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).getYear() + 543), Integer.class);
        assertThat(lastSeq).isGreaterThanOrEqualTo(2);
    }

    @Test
    void issue_snapshotIsFrozen_editingTheSourceDepositNoticeAfterwardsDoesNotChangeTheIssuedInvoice() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "Frozen1");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);
        BigDecimal originalDeduction = issued.depositDeduction();
        BigDecimal originalNet = issued.netAmount();
        byte[] originalFile = remainingInvoiceService.file(issued.id(), salesActor);

        // Mutate the SOURCE deposit notice's already-issued deposit amount directly (an issued
        // deposit notice cannot be edited through DepositNoticeService.update — DRAFT only — so
        // this reaches straight for the row, the same way this test's own negative-net sibling
        // does) — the remaining invoice was already ISSUED before this happened, and per this
        // document's own "always rendered from the snapshot" rule must not move.
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
        // against — ruling D11: items/deduction both come from the matched notice's own snapshot,
        // so inflating ITS depositAmount is what drives the remaining invoice's net negative.
        jdbc.update("UPDATE sales.deposit_notice SET deposit_amount = :amt WHERE deposit_notice_id = :id",
            Map.of("amt", new BigDecimal("999999.00"), "id", fixture.depositNoticeId()));

        assertThatThrownBy(() -> remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Owner rulings O2 (one live remaining invoice per DEAL) + O3 (issue refreshes the snapshot)
    // — GLA-99 step 2 review-round-1, 2026-09-20.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issue_supersedesAnyOtherIssuedRemainingInvoiceOnTheSameDeal_evenADifferentChain() {
        DealFixture fixtureA = buildAcceptedDeal(salesActor, "MultiChainA");
        RemainingInvoiceDocumentDto issuedA = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixtureA.ticketId(), null, salesActor).id(), salesActor);
        assertThat(issuedA.status()).isEqualTo("ISSUED");

        // A wholly SEPARATE pricing-request chain on the SAME ticket, driven to its own accepted
        // quotation + issued deposit notice — a real shape (a later revision/re-quote on the same
        // deal), not merely a synthetic id swap.
        buildSecondAcceptedQuotationChain(fixtureA.ticketId(), salesActor, "MultiChainB");
        RemainingInvoiceDocumentDto draftB = remainingInvoiceService.createDraft(fixtureA.ticketId(), null, salesActor);
        // The newest qualifying quotation is chain B's — never chain A's (which already has its
        // own ISSUED remaining invoice), proving this draft is genuinely a different chain.
        assertThat(draftB.customerQuotationId()).isNotEqualTo(issuedA.customerQuotationId());

        RemainingInvoiceDocumentDto issuedB = remainingInvoiceService.issue(draftB.id(), salesActor);
        assertThat(issuedB.status()).isEqualTo("ISSUED");
        // A brand-new base_number — this is a NEW chain's first issue, not a revision of A's.
        assertThat(issuedB.baseNumber()).isNotEqualTo(issuedA.baseNumber());

        RemainingInvoiceDocumentDto aReread = remainingInvoiceService.get(issuedA.id(), salesActor);
        assertThat(aReread.status()).isEqualTo("SUPERSEDED");
        assertThat(aReread.supersededById()).isEqualTo(issuedB.id());
        // Superseded stays downloadable (O2's own text).
        assertThat(remainingInvoiceService.file(aReread.id(), salesActor)).isNotEmpty();

        List<RemainingInvoiceDocumentDto> all = remainingInvoiceService.list(fixtureA.ticketId(), salesActor);
        List<RemainingInvoiceDocumentDto> stillIssued = all.stream()
            .filter(d -> "ISSUED".equals(d.status())).toList();
        assertThat(stillIssued).hasSize(1); // exactly one live, per O2
        assertThat(stillIssued.get(0).id()).isEqualTo(issuedB.id());
    }

    @Test
    void createDraft_refusesASecondDraftOnTheDealWhileOneAlreadyExists_evenADifferentChain() {
        DealFixture fixtureA = buildAcceptedDeal(salesActor, "MultiDraftA");
        RemainingInvoiceDocumentDto draftA = remainingInvoiceService.createDraft(fixtureA.ticketId(), null, salesActor);
        assertThat(draftA.status()).isEqualTo("DRAFT");

        buildSecondAcceptedQuotationChain(fixtureA.ticketId(), salesActor, "MultiDraftB");

        // A second DRAFT on the SAME deal is refused even though it would be for a DIFFERENT
        // quotation chain than draftA's — O2: at most one live (DRAFT or ISSUED) remaining
        // invoice per deal, not per chain.
        assertThatThrownBy(() -> remainingInvoiceService.createDraft(fixtureA.ticketId(), null, salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void issue_refreshesSnapshotFromTheLatestDepositNoticeBeforeFreezing() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "Refresh1");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);
        BigDecimal originalDeduction = draft.depositDeduction();
        BigDecimal newDeduction = originalDeduction.add(new BigDecimal("1.00"));

        // The deposit notice is edited/re-issued at a DIFFERENT amount AFTER the draft was
        // created but BEFORE it is issued — O3: issue() must re-snapshot from the LATEST issued
        // notice (+ current accepted quotation), never freeze the draft's own now-stale snapshot.
        jdbc.update("UPDATE sales.deposit_notice SET deposit_amount = :amt WHERE deposit_notice_id = :id",
            Map.of("amt", newDeduction, "id", fixture.depositNoticeId()));

        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(draft.id(), salesActor);
        assertThat(issued.depositDeduction()).isEqualByComparingTo(newDeduction);
        assertThat(issued.netAmount()).isEqualByComparingTo(issued.itemsTotal().subtract(newDeduction));
        // P5: vatAmount/grandTotal must refresh in step with netAmount, not stay pinned to the
        // draft's own now-stale figures.
        assertThat(issued.vatAmount())
            .isEqualByComparingTo(issued.netAmount().multiply(new BigDecimal("0.07")).setScale(2, RoundingMode.HALF_UP));
        assertThat(issued.grandTotal()).isEqualByComparingTo(issued.netAmount().add(issued.vatAmount()));
        assertThat(issued.vatAmount()).isNotEqualByComparingTo(draft.vatAmount());
        // The dialog fields the user already typed on the draft (reference/depositReference/
        // docDate/notes) are NOT touched by the refresh — only the computed content is. Nit
        // (Opus review, GLA-99 step 2 review-round-2): notes was the one dialog field this test
        // never actually asserted survives the refresh.
        assertThat(issued.reference()).isEqualTo(draft.reference());
        assertThat(issued.depositReference()).isEqualTo(draft.depositReference());
        assertThat(issued.docDate()).isEqualTo(draft.docDate());
        assertThat(issued.notes()).isEqualTo(draft.notes());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // REVIEW ROUND 2 POLISH (Opus review, GLA-99 step 2 review-round-2, 2026-09-20) — P1-P3, P5.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** P1: O2 ("one live remaining invoice per deal") is now a real DB invariant, not merely an
     * application-level courtesy check — V188's {@code ux_remaining_invoice_ticket_issued} was
     * widened from (ticket_id, customer_quotation_id) to ticket_id ALONE. Proven by going around
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

    /** P1's DRAFT-side twin: at most one DRAFT per ticket_id, not per (ticket, quotation). */
    @Test
    void v188_dbInvariant_atMostOneDraftRemainingInvoicePerTicket_evenBypassingTheService() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "DbInvariantDraft");
        remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);

        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO sales.remaining_invoice (ticket_id, customer_quotation_id, status) "
            + "VALUES (:ticketId, NULL, 'DRAFT')", Map.of("ticketId", fixture.ticketId())))
            .as("a second DRAFT row for the same ticket_id must be refused by the DB itself, even"
                + " for a wholly different (here: NULL) quotation chain")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** P2: createDraft's own SELECT-then-INSERT (sameChainLive/anotherDraftLive) races the exact
     * same way {@link #revise_repositoryRaceIsMappedToConflict_neverAnUnmapped500} already pins for
     * revise() — a repository-level unique-violation must surface as 409, never an unmapped 500.
     * Same deterministic spied-repository technique that test's own Javadoc explains choosing over
     * a genuine two-thread race (tried and discarded there for being unreliably narrow). */
    @Test
    void createDraft_repositoryRaceIsMappedToConflict_neverAnUnmapped500() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "CreateDraftRaceMock");

        RemainingInvoiceRepository racingRepo = org.mockito.Mockito.spy(remainingInvoiceRepository);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException(
                "simulated ux_remaining_invoice_ticket_draft hit from a concurrent createDraft()"))
            .when(racingRepo).insertDraft(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        RemainingInvoiceService racingService =
            new RemainingInvoiceService(racingRepo, depositNoticeService, tickets, new RemainingInvoiceRenderer());

        assertThatThrownBy(() -> racingService.createDraft(fixture.ticketId(), null, salesActor))
            .as("a DB-level unique-violation race must surface as 409, never an unmapped 500")
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // The failed race left nothing behind.
        assertThat(remainingInvoiceService.list(fixture.ticketId(), salesActor)).isEmpty();
    }

    /** P3: revise() refuses a live DRAFT for a DIFFERENT quotation chain too, not only a same-chain
     * one — the identical cross-chain refusal {@link #createDraft_refusesASecondDraftOnTheDealWhileOneAlreadyExists_evenADifferentChain}
     * already pins for createDraft, now applied to revise() as well (O2: one live DRAFT per DEAL,
     * not per chain — enforceable as a real DB invariant since P1). */
    @Test
    void revise_refusesWhenADraftForADifferentChainAlreadyExistsOnTheDeal() {
        DealFixture fixtureA = buildAcceptedDeal(salesActor, "ReviseCrossChainA");
        RemainingInvoiceDocumentDto issuedA = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixtureA.ticketId(), null, salesActor).id(), salesActor);

        buildSecondAcceptedQuotationChain(fixtureA.ticketId(), salesActor, "ReviseCrossChainB");
        RemainingInvoiceDocumentDto draftB = remainingInvoiceService.createDraft(fixtureA.ticketId(), null, salesActor);
        assertThat(draftB.customerQuotationId()).isNotEqualTo(issuedA.customerQuotationId());

        assertThatThrownBy(() -> remainingInvoiceService.revise(issuedA.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Nothing changed: A stays ISSUED (no orphaned revision draft), B's own draft is untouched.
        assertThat(remainingInvoiceService.get(issuedA.id(), salesActor).status()).isEqualTo("ISSUED");
        assertThat(remainingInvoiceService.get(draftB.id(), salesActor).status()).isEqualTo("DRAFT");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — WRONG-WAY-ROUND (CLAUDE.md requirement): reused DepositNoticeService predicate
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createDraft_nonOwningSalesRep_isRefused() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzCreate1");
        assertThatThrownBy(() -> remainingInvoiceService.createDraft(fixture.ticketId(), null, otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createDraft_ceo_isRefused_theGateHasNoCeoException() {
        // Deliberate: EXACTLY the deposit-notice issue gate (sales-role + ticket-ownership), which
        // has no CEO carve-out — unlike ImportRequestService's own FULL_WRITE_ROLES (sales+ceo).
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzCreate2");
        assertThatThrownBy(() -> remainingInvoiceService.createDraft(fixture.ticketId(), null, ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void createDraft_import_isRefused() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzCreate3");
        assertThatThrownBy(() -> remainingInvoiceService.createDraft(fixture.ticketId(), null, importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void issue_nonOwningSalesRep_isRefused_rowStaysDraft() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzIssue1");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);

        assertThatThrownBy(() -> remainingInvoiceService.issue(draft.id(), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(remainingInvoiceService.get(draft.id(), salesActor).status()).isEqualTo("DRAFT");
    }

    @Test
    void revise_nonOwningSalesRep_isRefused() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzRevise1");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        assertThatThrownBy(() -> remainingInvoiceService.revise(issued.id(), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void deleteDraft_nonOwningSalesRep_isRefused_rowSurvives() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzDelete1");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);

        assertThatThrownBy(() -> remainingInvoiceService.deleteDraft(draft.id(), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(remainingInvoiceService.get(draft.id(), salesActor)).isNotNull();
    }

    @Test
    void read_import_isRefused_onIssuedDocument() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzRead1");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        assertThatThrownBy(() -> remainingInvoiceService.get(issued.id(), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> remainingInvoiceService.list(fixture.ticketId(), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> remainingInvoiceService.file(issued.id(), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void read_nonOwningSalesRep_isRefused() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzRead2");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        assertThatThrownBy(() -> remainingInvoiceService.get(issued.id(), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void read_ceoAndAccountAndSalesManager_canReadAndDownload_viewerRoleUnaffectedByTheWriteGate() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "AuthzRead3");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        assertThat(remainingInvoiceService.get(issued.id(), ceoActor).id()).isEqualTo(issued.id());
        assertThat(remainingInvoiceService.get(issued.id(), accountActor).id()).isEqualTo(issued.id());
        // R1 fix (GLA-99 step 2 review-round-1): this test's own name has always promised
        // sales_manager too, but nothing here ever constructed or asserted that actor — the
        // method name asserted a claim its body could not back up. Added for real now.
        assertThat(remainingInvoiceService.get(issued.id(), salesManagerActor).id()).isEqualTo(issued.id());
        assertThat(remainingInvoiceService.file(issued.id(), ceoActor)).isNotEmpty();
        assertThat(remainingInvoiceService.file(issued.id(), accountActor)).isNotEmpty();
        assertThat(remainingInvoiceService.file(issued.id(), salesManagerActor)).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // R1 (Opus review, GLA-99 step 2 review-round-1, 2026-09-20): updateDraft ITs — lifecycle
    // (edits persist, re-snapshot) + refusals for CEO, account, sales_manager, non-owner sales,
    // and import on EACH write op (update/issue/revise/delete), each asserting NOTHING was
    // written (wrong-way-round, per CLAUDE.md). The pre-existing per-actor tests above already
    // cover otherSalesActor/ceoActor/importActor for issue/revise/delete/createDraft; this block
    // adds the two roles those omitted (account, sales_manager) across all four write ops, plus
    // updateDraft's own coverage from scratch (it had none).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void updateDraft_ownerCanEditDialogFieldsAndTheContentReSnapshots() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "UpdateLifecycle1");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);
        BigDecimal originalDeduction = draft.depositDeduction();
        BigDecimal newDeduction = originalDeduction.add(new BigDecimal("2.00"));

        // The source deposit notice changes AFTER the draft was created — updateDraft's own
        // re-snapshot (plan step 2: "re-snapshot items from latest issued deposit notice") must
        // pick it up.
        jdbc.update("UPDATE sales.deposit_notice SET deposit_amount = :amt WHERE deposit_notice_id = :id",
            Map.of("amt", newDeduction, "id", fixture.depositNoticeId()));

        RemainingInvoiceDocumentDto updated = remainingInvoiceService.updateDraft(draft.id(),
            new RemainingInvoiceDraftRequest(null, "PO-EDITED-1", "", LocalDate.of(2026, 9, 15), List.of()),
            salesActor);

        assertThat(updated.status()).isEqualTo("DRAFT");
        assertThat(updated.reference()).isEqualTo("PO-EDITED-1");
        assertThat(updated.depositReference()).isEqualTo("");
        assertThat(updated.docDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(updated.notes()).isEmpty();
        assertThat(updated.depositDeduction()).isEqualByComparingTo(newDeduction);
        assertThat(updated.netAmount()).isEqualByComparingTo(updated.itemsTotal().subtract(newDeduction));

        // Persisted, not merely returned — reread from storage.
        RemainingInvoiceDocumentDto reread = remainingInvoiceService.get(draft.id(), salesActor);
        assertThat(reread.reference()).isEqualTo("PO-EDITED-1");
        assertThat(reread.depositDeduction()).isEqualByComparingTo(newDeduction);
    }

    @Test
    void updateDraft_omittedFieldsCarryTheExistingValueForward() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "UpdateLifecycle2");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);

        RemainingInvoiceDocumentDto firstEdit = remainingInvoiceService.updateDraft(draft.id(),
            new RemainingInvoiceDraftRequest(null, "PO-KEEP-ME", null, null, null), salesActor);
        assertThat(firstEdit.reference()).isEqualTo("PO-KEEP-ME");

        // A second update touching only docDate — reference must survive untouched (PATCH-shaped,
        // not a full overwrite), per RemainingInvoiceDraftRequest's own Javadoc.
        RemainingInvoiceDocumentDto secondEdit = remainingInvoiceService.updateDraft(draft.id(),
            new RemainingInvoiceDraftRequest(null, null, null, LocalDate.of(2026, 10, 1), null), salesActor);
        assertThat(secondEdit.reference()).isEqualTo("PO-KEEP-ME");
        assertThat(secondEdit.docDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void updateDraft_nonOwnerRoles_areAllRefused_rowUnchanged() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "UpdateAuthz1");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);
        RemainingInvoiceDraftRequest attemptedEdit =
            new RemainingInvoiceDraftRequest(null, "SHOULD-NEVER-STICK", null, null, null);

        for (UserPrincipal intruder : List.of(otherSalesActor, ceoActor, importActor, accountActor, salesManagerActor)) {
            assertThatThrownBy(() -> remainingInvoiceService.updateDraft(draft.id(), attemptedEdit, intruder))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        // Wrong-way-round: nothing any of them attempted actually reached the row.
        RemainingInvoiceDocumentDto reread = remainingInvoiceService.get(draft.id(), salesActor);
        assertThat(reread.reference()).isNotEqualTo("SHOULD-NEVER-STICK");
        assertThat(reread.reference()).isEqualTo(draft.reference());
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

    @Test
    void revise_accountAndSalesManager_areRefused_originalStaysIssued() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "ReviseAuthz1");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        for (UserPrincipal intruder : List.of(accountActor, salesManagerActor)) {
            assertThatThrownBy(() -> remainingInvoiceService.revise(issued.id(), intruder))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        RemainingInvoiceDocumentDto reread = remainingInvoiceService.get(issued.id(), salesActor);
        assertThat(reread.status()).isEqualTo("ISSUED");
        assertThat(reread.supersededById()).isNull();
    }

    @Test
    void deleteDraft_accountAndSalesManager_areRefused_rowSurvives() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "DeleteAuthz1");
        RemainingInvoiceDocumentDto draft = remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor);

        for (UserPrincipal intruder : List.of(accountActor, salesManagerActor)) {
            assertThatThrownBy(() -> remainingInvoiceService.deleteDraft(draft.id(), intruder))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(remainingInvoiceService.get(draft.id(), salesActor)).isNotNull();
    }

    /**
     * Nit fix (Opus review, GLA-99 step 2 review-round-1): two concurrent {@code revise()} calls
     * on the same ISSUED document race past the plain SELECT-then-INSERT {@code hasLiveDraft}
     * check (see {@link RemainingInvoiceService#revise}) — the loser's {@code insertDraft} trips
     * {@code ux_remaining_invoice_ticket_quotation_draft} (V188) as a raw unique violation, which
     * must surface as 409, never an unmapped 500.
     *
     * <p>A genuine two-thread race (the shape {@code PayrollDraftOptimisticConcurrencyIntegrationTest}
     * uses) was tried first and DISCARDED: run against this suite's own test datasource it passed
     * even with the fix's {@code catch} block removed, five times over — the race window this bug
     * needs is narrower than this harness's connection/thread scheduling reliably hits, which would
     * have made that test "green either way", exactly the false-evidence shape CLAUDE.md's own
     * "vacuous test" rule warns about. This version instead forces the EXACT failure mode
     * deterministically — a spied repository whose {@code insertDraft} throws the same exception
     * type Postgres raises for the real unique-index hit — so it is guaranteed red without the
     * catch and green with it, on every run, not "usually".
     */
    @Test
    void revise_repositoryRaceIsMappedToConflict_neverAnUnmapped500() {
        DealFixture fixture = buildAcceptedDeal(salesActor, "ReviseRaceMock");
        RemainingInvoiceDocumentDto issued = remainingInvoiceService.issue(
            remainingInvoiceService.createDraft(fixture.ticketId(), null, salesActor).id(), salesActor);

        RemainingInvoiceRepository racingRepo = org.mockito.Mockito.spy(remainingInvoiceRepository);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException(
                "simulated ux_remaining_invoice_ticket_quotation_draft hit from a concurrent revise()"))
            .when(racingRepo).insertDraft(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        RemainingInvoiceService racingService =
            new RemainingInvoiceService(racingRepo, depositNoticeService, tickets, new RemainingInvoiceRenderer());

        assertThatThrownBy(() -> racingService.revise(issued.id(), salesActor))
            .as("a DB-level unique-violation race must surface as 409, never an unmapped 500")
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // The predecessor is untouched by the failed race — still ISSUED, no orphaned DRAFT.
        assertThat(remainingInvoiceService.get(issued.id(), salesActor).status()).isEqualTo("ISSUED");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers — adapted from DepositNoticeIssueGuardIntegrationTest's own approach for
    // driving a deal through the real Steps 1-6 services to an accepted quotation + issued
    // deposit notice.
    // ─────────────────────────────────────────────────────────────────────────────────────

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
            new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));
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

    /** A SECOND, wholly separate pricing-request chain on an ALREADY-EXISTING ticket — its own
     * catalog product, pricing request, factory quote, pricing decision and CustomerQuotation,
     * driven to ACCEPTED + its own issued deposit notice, exactly like {@link #buildAcceptedDeal}
     * except it reuses the given ticket (and its first ticket item) instead of creating a new one.
     * Used by the O2 (one live remaining invoice per deal) tests to prove superseding/blocking
     * reaches a genuinely different chain, not merely a different id on the same fixture. */
    private DealFixture buildSecondAcceptedQuotationChain(long ticketId, UserPrincipal owner, String label) {
        long catalogProductId = insertCatalogProduct(FACTORY, "IT",
            "TEST-RI-" + label + "-" + UUID.randomUUID().toString().substring(0, 8),
            new BigDecimal("100.00"), "THB", "per_piece");
        long ticketItemId = tickets.findById(ticketId).orElseThrow().items().get(0).id();

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
            new BigDecimal("5000.00"), "THB", "remaining-invoice test walk 2 " + label,
            UUID.randomUUID().toString(), List.of(item));
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
