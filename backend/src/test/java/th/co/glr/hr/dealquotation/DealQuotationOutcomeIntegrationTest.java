package th.co.glr.hr.dealquotation;

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
import th.co.glr.hr.brand.BrandAssets;
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
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RecordOutcomeRequest;
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
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.orderconfirmation.OrderConfirmationDtos;
import th.co.glr.hr.orderconfirmation.OrderConfirmationRequests;
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
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB coverage for GLA-123 slice S3: customer outcome (R9) on a PRICING_REQUEST-origin
 * quotation, the R8 one-finalized-quotation-per-deal guard across BOTH คำขอราคา origins (this new
 * engine AND the legacy {@code customerquotation/} chain, which share ONE {@code sales.quotation}
 * table), and confirm order (R9, {@code OrderConfirmationService#confirmOrder} reused unchanged).
 *
 * <p>Fixture setup is a trimmed copy of {@code DealQuotationPricingRequestApprovalIntegrationTest}
 * (S2's own suite) — same helper shapes — PLUS the legacy {@link CustomerQuotationService} and
 * {@link OrderConfirmationService} wiring {@code OrderConfirmationIntegrationTest} uses, since R8
 * and confirm-order both genuinely span both engines / the Step 6 bridge.
 */
class DealQuotationOutcomeIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private PricingDecisionRepository decisionRepository;
    private DealQuotationService quotationService;
    private DealQuotationRepository quotationRepository;
    private CustomerQuotationRepository legacyQuotationRepository;
    private CustomerQuotationService legacyQuotationService;
    private OrderConfirmationService orderConfirmation;
    private TicketRepository tickets;
    private TicketService ticketService;

    private long salesRepId;
    private long otherSalesId;
    private long ceoUserId;
    private long salesManagerId;
    private long importUserId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal ceoActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal importActor;
    private long ticketId;
    private long catalogProductId;

    private static final String FACTORY = "Factory S3";

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pcr-quotation-s3-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage,
            factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc));

        Mailer noMail = new Mailer() {
            @Override
            public void send(String to, String subject, String body) {}

            @Override
            public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                 List<Mailer.InlineImage> inlineImages) {}

            @Override
            public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {}

            @Override
            public void sendWithAttachments(String to, String subject, String body, List<Mailer.Attachment> attachments) {}
        };
        quotationRepository = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        EmployeeSignatureRepository signatureRepository = new EmployeeSignatureRepository(jdbc);
        quotationService = new DealQuotationService(quotationRepository, tickets, customers,
            new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc),
            signatureRepository, new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        legacyQuotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService.wirePricingRequestDependencies(pricingRequests, decisionRepository, legacyQuotationRepository);
        quotationService.wireTicketService(ticketService);

        legacyQuotationService = new CustomerQuotationService(legacyQuotationRepository, pricingRequests,
            decisionRepository, tickets, ticketService, customers, new QuotationRenderer(), notifications,
            new DiscountApprovalRepository(jdbc));

        DepositNoticeRepository depositNoticeRepository = new DepositNoticeRepository(jdbc);
        DepositNoticeService depositNoticeService = new DepositNoticeService(depositNoticeRepository, tickets,
            notifications, new DepositNoticeRenderer(), new RemainingInvoiceRenderer(), customers,
            legacyQuotationRepository);
        orderConfirmation = new OrderConfirmationService(
            pricingRequests, tickets, ticketService, legacyQuotationRepository, depositNoticeService, notifications);
        // GLA-123 slice S3 BLOCKER 2 fix — without this wiring, reconcileTicketItems silently
        // falls back to the PR item's own requestedQty for every test in this file, which would
        // make the "quantity edited on the quotation" regression test below pass vacuously.
        orderConfirmation.wireDealQuotationRepository(quotationRepository);

        salesRepId = createEmployee(employees, "พนักงานขาย S3", "sales-s3@glr.co.th", "SALES", "แผนกขาย");
        otherSalesId = createEmployee(employees, "พนักงานขาย S3 อื่น", "sales-s3-other@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า S3", "import-s3@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร S3", "ceo-s3@glr.co.th", "MD", "ผู้บริหาร");
        salesManagerId = createEmployee(employees, "ผจก.ขาย S3", "sm-s3@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        salesManagerActor = actor(salesManagerId, "sales_manager");

        catalogProductId = insertCatalogProduct(FACTORY, "IT", "TEST-S3-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท PCR Quotation S3 จำกัด", "0100000000399", "999 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0399");
        ProjectDto project = projects.create(customer.id(), "โครงการ PCR Quotation S3");
        long contactId = new ContactRepository(jdbc).create(customer.id(), "สมหญิง", "ทดสอบ",
            "ผู้จัดการฝ่ายจัดซื้อ", "contact-s3@example.com", "081-000-0002").id();
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล PCR Quotation S3", "NORMAL", customer.name(), customer.id(), project.id(),
                contactId, null, null, List.of(ticketItem("SCG", "Tile A-S3", FACTORY))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // R9 — outcome authz: mirrors CustomerQuotationService#recordOutcome's gate EXACTLY
    // (SALES_ROLES = Set.of("sales") only — sales_manager is NOT included there, so it is not
    // included here either). Every non-owner case asserts NOTHING was written (still ISSUED).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void recordOutcome_byOwningSales_accepted_setsPrStatusAndDocStatus() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        DealQuotationDto accepted = quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), salesActor);
        assertThat(accepted.docStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        long pricingRequestId = jdbc.queryForObject(
            "SELECT pricing_request_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", issued.id()), Long.class);
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);
    }

    @Test
    void recordOutcome_byNonOwningSales_refused_writesNothing() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    /** Deliberate divergence check (stated in the brief): this origin's own EDIT_ROLES lets
     * sales_manager edit/submit/cancel ANY deal, but recordOutcome mirrors the LEGACY gate
     * instead, which has no such carve-out — sales_manager must be refused here too. */
    @Test
    void recordOutcome_bySalesManager_refused_writesNothing() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    @Test
    void recordOutcome_byCeo_refused_writesNothing() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    @Test
    void recordOutcome_byImport_refused_writesNothing() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    @Test
    void recordOutcome_refusedOnDealDirectOrigin() {
        DealQuotationDto draft = quotationService.create(ticketId, minimalDirectDraft(), salesActor);
        assertThatThrownBy(() -> quotationService.recordOutcome(draft.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void recordOutcome_onExpiredQuotation_refused_writesNothing() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        jdbc.update("UPDATE sales.quotation SET doc_status = 'EXPIRED' WHERE quotation_id = :id",
            Map.of("id", issued.id()));
        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.EXPIRED);
    }

    /** GLA-123 slice S3 BLOCKER-B fix (Opus review against real Postgres, 2026-09-23):
     * REVISION_REQUESTED was missing from {@code hasLivePricingRequestQuotation}'s non-live set,
     * even though S3 (this slice's own recordOutcome) is what makes that outcome reachable —
     * proven end to end against real Postgres before the fix: create refused (409, "a live
     * quotation still exists"), cancel/update refused (not DRAFT), re-recording an outcome
     * refused (not ISSUED), and the expiry sweep never touches it (ISSUED-only). Every exit
     * 409'd — a permanent dead end for any deal the customer asked to change. NOT D12 (revising an
     * ACCEPTED quotation, out of scope) — this is an ISSUED quotation, squarely R9/S3's own
     * concern. Fixed by adding REVISION_REQUESTED to the non-live set, the SAME recovery path
     * REJECTED/EXPIRED already have. */
    @Test
    void revisionRequestedOutcome_isNotAPermanentDeadEnd_createSucceedsFromTheSameDecision() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        long pricingRequestId = jdbc.queryForObject(
            "SELECT pricing_request_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", issued.id()), Long.class);

        DealQuotationDto revisionRequested = quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.REVISION_REQUESTED, "ขอปรับราคา", UUID.randomUUID().toString()),
            salesActor);
        assertThat(revisionRequested.docStatus()).isEqualTo(QuotationStatus.REVISION_REQUESTED);
        // Mirrors REJECTED: this outcome deliberately does NOT change the PR's own status — still
        // QUOTATION_ISSUED, which is exactly what #createFromPricingRequest's own "expiry escape
        // hatch" status gate already tolerates (see that method's own updated comment).
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        // Before the fix, this next call 409'd unconditionally — a permanent dead end.
        DealQuotationDto fresh = quotationService.createFromPricingRequest(pricingRequestId, salesActor);
        assertThat(fresh.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(fresh.id()).isNotEqualTo(issued.id());
        assertThat(fresh.pricingRequestId()).isEqualTo(pricingRequestId);
        // Prefilled from the SAME approved decision — same line count, same CEO-approved price.
        assertThat(fresh.items()).hasSameSizeAs(issued.items());
        assertThat(fresh.items().get(0).unitPrice()).isEqualByComparingTo(issued.items().get(0).ceoListUnitPrice());

        // Normal flow continues unimpeded: submit -> approve -> ISSUED.
        DealQuotationDto withValidity = quotationService.update(fresh.id(), upsertWithValidityDays(fresh, 30), salesActor);
        DealQuotationDto submitted = quotationService.submit(withValidity.id(), salesActor);
        DealQuotationDto reIssued = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(reIssued.docStatus()).isEqualTo(QuotationStatus.ISSUED);

        // The old REVISION_REQUESTED row stays untouched, kept for audit.
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.REVISION_REQUESTED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // R8 — one finalized (ACCEPTED) quotation per DEAL, across BOTH คำขอราคา origins. Both
    // directions are wrong-way-round: the SECOND accept must be refused with NOTHING written,
    // regardless of which origin goes first.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void recordOutcome_newOrigin_refusedWhenLegacyAlreadyAccepted_r8() {
        CustomerQuotationDto legacyAccepted = acceptedLegacyQuotation(PricingRequestRecipient.DESIGNER);
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.OWNER, new BigDecimal("10"));

        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(legacyQuotationService.get(legacyAccepted.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void legacyRecordOutcome_refusedWhenNewOriginAlreadyAccepted_r8() {
        DealQuotationDto newAccepted = acceptedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        CustomerQuotationDto legacyIssued = issuedLegacyQuotation(PricingRequestRecipient.OWNER);

        assertThatThrownBy(() -> legacyQuotationService.recordOutcome(legacyIssued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(legacyQuotationService.get(legacyIssued.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.ISSUED);
        assertThat(quotationService.get(newAccepted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void recordOutcome_newOrigin_notBlockedByLegacyNonAcceptedOutcome_r8() {
        // R8 only fires on ACCEPTED-vs-ACCEPTED — a REJECTED legacy quotation on a sibling
        // pricing request must never block this origin's own accept.
        CustomerQuotationDto legacyIssued = issuedLegacyQuotation(PricingRequestRecipient.DESIGNER);
        legacyQuotationService.recordOutcome(legacyIssued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.REJECTED, "ไม่เอา", UUID.randomUUID().toString()), salesActor);
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.OWNER, new BigDecimal("10"));

        DealQuotationDto accepted = quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), salesActor);
        assertThat(accepted.docStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Confirm order (R9) — OrderConfirmationService#confirmOrder reused UNCHANGED, driven from a
    // PRICING_REQUEST-origin accepted quotation.
    //
    // GLA-123 slice S3 BLOCKER 2 fix (Opus review against real Postgres, 2026-09-23): this
    // section's own header comment used to claim "reconcileTicketItems reads pricing_request_item
    // directly (never the quotation)" — true of the METHOD's plumbing, but WRONG about which
    // number ends up correct: S1 lets sales edit a PRICING_REQUEST-origin quotation line's
    // quantity after the PR was authored (excluded from requireCeoApprovalIfChanged, so no CEO
    // re-approval needed), and reconcileTicketItems used to write the PR's ORIGINAL quantity
    // regardless, silently discarding the customer's actual accepted quantity. The two tests below
    // are the accepted-quotation-quantity regression (edited) and its unedited counterpart
    // (proving the fix did not change the already-correct case).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void confirmOrder_fromNewOriginAcceptedQuotation_unedited_reconcilesTicketItemsAndAdvancesStage() {
        // Original ticket_item.qty is 1 (ticketItem() helper below) — deliberately mismatched
        // from the pricing request's own requestedQty (10 pieces, see pricingItem()) so a
        // successful reconciliation is observable, not a coincidence.
        long originalTicketItemId = jdbc.queryForObject(
            "SELECT item_id FROM sales.ticket_item WHERE ticket_id = :id",
            Map.of("id", ticketId), Long.class);
        assertThat(jdbc.queryForObject(
            "SELECT qty FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", originalTicketItemId), BigDecimal.class))
            .isEqualByComparingTo("1");

        DealQuotationDto accepted = acceptedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        long pricingRequestId = jdbc.queryForObject(
            "SELECT pricing_request_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", accepted.id()), Long.class);
        BigDecimal requestedQty = jdbc.queryForObject(
            "SELECT requested_qty FROM sales.pricing_request_item WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), BigDecimal.class);
        assertThat(requestedQty).isNotEqualByComparingTo("1");
        // The unedited case: the quotation's own accepted line quantity equals the PR's, so the
        // fix's map lookup and the old PR-item fallback must agree.
        assertThat(new BigDecimal(accepted.items().get(0).piecesFinal())).isEqualByComparingTo(requestedQty);

        OrderConfirmationDtos.OrderConfirmationResultDto result = orderConfirmation.confirmOrder(
            pricingRequestId, new OrderConfirmationRequests.ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        assertThat(result.ticket().summary().paymentStatus()).isEqualTo("CUSTOMER_CONFIRMED");
        assertThat(result.ticket().summary().salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);

        BigDecimal reconciledQty = jdbc.queryForObject(
            "SELECT qty FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", originalTicketItemId), BigDecimal.class);
        assertThat(reconciledQty).isEqualByComparingTo(requestedQty);

        // GLA-123 slice S3 MAJOR-B fix — qty_sqm must be reconciled too, not just qty. Unedited
        // case: derived from the accepted quotation's own qty x sqmPerPiece (0.36), which agrees
        // with the PR's own requestedQtySqm here since nothing was edited.
        BigDecimal reconciledQtySqm = jdbc.queryForObject(
            "SELECT qty_sqm FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", originalTicketItemId), BigDecimal.class);
        assertThat(reconciledQtySqm).isEqualByComparingTo(
            WastageCalculator.sqmQuantityFromPieces(requestedQty.intValueExact(), new BigDecimal("0.36")));
    }

    /** GLA-123 slice S3 BLOCKER 2 — real-DB reproduction of the reviewer's exact scenario: PR
     * authored at 10 pieces, quotation line edited to 25 before submit/approve/issue, customer
     * accepts, confirmOrder must reconcile ticket_item.qty to 25 (the ACCEPTED quotation's own
     * quantity), never silently back to 10 (the PR's original, now-stale quantity). */
    @Test
    void confirmOrder_reconcilesToTheAcceptedQuotationsEditedQuantity_notThePricingRequestsOriginal() {
        long originalTicketItemId = jdbc.queryForObject(
            "SELECT item_id FROM sales.ticket_item WHERE ticket_id = :id",
            Map.of("id", ticketId), Long.class);

        DealQuotationDto issued = issuedNewOriginQuotationWithEditedQuantity(
            PricingRequestRecipient.DESIGNER, new BigDecimal("10"), 25);
        DealQuotationDto accepted = quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค 25 แผ่น", UUID.randomUUID().toString()),
            salesActor);
        assertThat(accepted.items().get(0).piecesFinal()).isEqualTo(25);

        long pricingRequestId = jdbc.queryForObject(
            "SELECT pricing_request_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", accepted.id()), Long.class);
        BigDecimal prOriginalQty = jdbc.queryForObject(
            "SELECT requested_qty FROM sales.pricing_request_item WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), BigDecimal.class);
        // The PR itself was never touched by the quotation edit — still 10. This is the exact
        // fact the old code got wrong: it treated THIS number as authoritative for reconciliation.
        assertThat(prOriginalQty).isEqualByComparingTo("10");

        orderConfirmation.confirmOrder(pricingRequestId,
            new OrderConfirmationRequests.ConfirmOrderRequest(UUID.randomUUID().toString()), salesActor);

        BigDecimal reconciledQty = jdbc.queryForObject(
            "SELECT qty FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", originalTicketItemId), BigDecimal.class);
        assertThat(reconciledQty).isEqualByComparingTo("25");
        assertThat(reconciledQty).isNotEqualByComparingTo(prOriginalQty);

        // GLA-123 slice S3 MAJOR-B fix (Opus review against real Postgres, 2026-09-23): qty
        // moving to 25 while qty_sqm silently kept the PR's ORIGINAL 10-piece area (3.6000, from
        // sqmPerPiece 0.36 x 10) was a genuine contradiction between the two columns on the SAME
        // row, proven live. qty_sqm must now move WITH qty: 25 pieces x 0.36 sqm/piece = 9.00,
        // via the shared WastageCalculator#sqmQuantityFromPieces (never reimplemented locally).
        BigDecimal reconciledQtySqm = jdbc.queryForObject(
            "SELECT qty_sqm FROM sales.ticket_item WHERE item_id = :id",
            Map.of("id", originalTicketItemId), BigDecimal.class);
        assertThat(reconciledQtySqm).isEqualByComparingTo("9.00");
        assertThat(reconciledQtySqm).isEqualByComparingTo(
            WastageCalculator.sqmQuantityFromPieces(25, new BigDecimal("0.36")));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-123 item 8 / slice S3 MAJOR 4 fix (Opus review against real Postgres, 2026-09-23):
    // DealQuotationRepository#findByTicket used to be DEAL_DIRECT-only, so a deal whose only
    // quotation is PRICING_REQUEST-origin returned an EMPTY list from GET
    // /tickets/{id}/deal-quotations — the endpoint both DealDocumentRegister.jsx's
    // directQuotationsQuery and DealDirectQuotationPanel.jsx call to render "ใบเสนอราคา" at all.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void listForTicket_includesAPricingRequestOriginQuotation_soTheDocumentRegisterCanRenderIt() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));

        List<DealQuotationDto> forTicket = quotationService.listForTicket(ticketId, salesActor);

        assertThat(forTicket).extracting(DealQuotationDto::id).contains(issued.id());
        assertThat(forTicket).extracting(DealQuotationDto::origin).allMatch("PRICING_REQUEST"::equals);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-123 slice S3 BLOCKER-A fix (Opus review against real Postgres, 2026-09-23):
    // MAJOR 4's own widening of #findByTicket's RESULT SET (above) was not matched by a widening
    // of #listForTicket's own per-row VISIBILITY rule, which stayed the DEAL_DIRECT-shaped
    // VIEW_ROLES {sales,sales_manager,ceo,import,account} + a full hasQuotationGrant bypass — so
    // import/account/any canCreateQuotation-granted employee could now read a PRICING_REQUEST
    // row's unitPrice/discountPct/netUnitPrice through this endpoint, which on THIS origin are the
    // CEO's own approved price and discount (two prior owner rulings restrict them to
    // PRICING_REQUEST_VIEW_ROLES with no grant bypass). Reproduced live: listForTicket as import
    // returned the full item list including discountPct=10.00. Chosen fix: the unauthorized
    // PRICING_REQUEST row is FILTERED OUT of the list entirely (not field-stripped) — see
    // #listForTicket's own comment for why that matches every sibling list/count surface on this
    // class.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void listForTicket_hidesPricingRequestOriginRow_fromImport() {
        issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));

        List<DealQuotationDto> forTicket = quotationService.listForTicket(ticketId, importActor);

        assertThat(forTicket).as("import must not see the PRICING_REQUEST-origin row at all").isEmpty();
    }

    @Test
    void listForTicket_hidesPricingRequestOriginRow_fromAccount() {
        issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));

        List<DealQuotationDto> forTicket = quotationService.listForTicket(ticketId, accountActor("hide"));

        assertThat(forTicket).as("account must not see the PRICING_REQUEST-origin row at all").isEmpty();
    }

    @Test
    void listForTicket_hidesPricingRequestOriginRow_fromACanCreateQuotationGrantHolder() {
        issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));

        List<DealQuotationDto> forTicket = quotationService.listForTicket(ticketId, grantedAccountActor("hide"));

        assertThat(forTicket)
            .as("the can_create_quotation grant does NOT bypass PRICING_REQUEST_VIEW_ROLES — unlike a DEAL_DIRECT row")
            .isEmpty();
    }

    @Test
    void listForTicket_doesNotHidePricingRequestOriginRow_fromSalesOwnerSalesManagerOrCeo() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));

        assertThat(quotationService.listForTicket(ticketId, salesActor))
            .as("the owning sales rep sees the row").extracting(DealQuotationDto::id).contains(issued.id());
        assertThat(quotationService.listForTicket(ticketId, salesManagerActor))
            .as("sales_manager sees the row").extracting(DealQuotationDto::id).contains(issued.id());
        assertThat(quotationService.listForTicket(ticketId, ceoActor))
            .as("ceo sees the row").extracting(DealQuotationDto::id).contains(issued.id());
    }

    /** GLA-123 slice S3 MAJOR-A fix (Opus review, 2026-09-23): the ONLY real-DB regression
     * coverage for BLOCKER 1's fix ({@code findForPricingRequest} switched from the DRAFT-only
     * {@code findOpenDraftForPricingRequest} to {@code findLatestForPricingRequest}) — the 4
     * frontend tests added alongside BLOCKER 1 stub the API response directly and cannot see a
     * backend regression; mutating this method back to the old reader left all 73 of them green.
     * Asserts the method returns the quotation for ISSUED, ACCEPTED and EXPIRED — every status
     * past DRAFT the old reader silently returned {@code Optional.empty()} for. */
    @Test
    void findForPricingRequest_returnsTheQuotationPastDraft_issuedAcceptedAndExpired() {
        DealQuotationDto issued = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        long pricingRequestId = jdbc.queryForObject(
            "SELECT pricing_request_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", issued.id()), Long.class);

        assertThat(quotationService.findForPricingRequest(pricingRequestId, salesActor))
            .as("ISSUED must be visible").isPresent()
            .get().extracting(DealQuotationDto::docStatus).isEqualTo(QuotationStatus.ISSUED);

        DealQuotationDto accepted = quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, UUID.randomUUID().toString()), salesActor);
        assertThat(quotationService.findForPricingRequest(pricingRequestId, salesActor))
            .as("ACCEPTED must be visible").isPresent()
            .get().extracting(DealQuotationDto::docStatus).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(accepted.docStatus()).isEqualTo(QuotationStatus.ACCEPTED); // sanity on the setup itself

        jdbc.update("UPDATE sales.quotation SET doc_status = 'EXPIRED' WHERE quotation_id = :id",
            Map.of("id", issued.id()));
        assertThat(quotationService.findForPricingRequest(pricingRequestId, salesActor))
            .as("EXPIRED must be visible (the escape-hatch UI is keyed on seeing this)").isPresent()
            .get().extracting(DealQuotationDto::docStatus).isEqualTo(QuotationStatus.EXPIRED);
    }

    /** GLA-123 slice S3 MINOR-2 fix (Opus review, 2026-09-23) — mirrors
     * {@code CustomerQuotationService#issue}'s own wrong-quotation replay guard: a replayed
     * clientRequestId resolving to a DIFFERENT quotation than the one this call actually named
     * must 409, not silently hand back the other quotation's DTO with nothing recorded on the one
     * the caller meant to act on. */
    @Test
    void recordOutcome_replayWithClientRequestIdBoundToADifferentQuotation_refused409() {
        DealQuotationDto issuedA = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("10"));
        DealQuotationDto issuedB = issuedNewOriginQuotation(PricingRequestRecipient.OWNER, new BigDecimal("5"));
        String clientRequestId = UUID.randomUUID().toString();
        quotationService.recordOutcome(issuedA.id(),
            new RecordOutcomeRequest(QuotationStatus.REJECTED, null, clientRequestId), salesActor);

        assertThatThrownBy(() -> quotationService.recordOutcome(issuedB.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, clientRequestId), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // Nothing recorded on B — the replay guard fired before any write to it.
        assertThat(quotationService.get(issuedB.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    /** GLA-123 slice S3 MINOR-3 fix (Opus review, 2026-09-23) — S3 round-3 review coverage gap
     * (NEW-4): V75's {@code (issued_by, outcome_client_request_id)} partial unique index is
     * TABLE-WIDE across both คำขอราคา origins, but each engine's own {@code
     * findIdByOutcomeClientRequestId} is origin-scoped ({@code origin IS NULL} for the legacy
     * chain, {@code origin = 'PRICING_REQUEST'} for this one — see each repository method's own
     * Javadoc). So the SAME actor reusing a {@code clientRequestId} across the TWO ENGINES does
     * not replay-match on either side (each side's own scoped lookup finds nothing), and instead
     * falls through to the UPDATE, which then hits the table-wide unique index as a bare {@code
     * DataIntegrityViolationException} — MINOR-3 catches that and turns it into the same clean 409
     * MINOR-2's same-engine guard above already gives, rather than letting it surface as an
     * uncaught 500. This is the cross-engine direction of the collision the reviewer's own probe
     * proved live (legacy-then-new); {@link
     * #recordOutcome_crossEngineClientRequestIdCollision_newThenLegacy_refused409} below proves the
     * reverse order. */
    @Test
    void recordOutcome_crossEngineClientRequestIdCollision_legacyThenNew_refused409() {
        CustomerQuotationDto issuedLegacy = issuedLegacyQuotation(PricingRequestRecipient.DESIGNER);
        DealQuotationDto issuedNew = issuedNewOriginQuotation(PricingRequestRecipient.OWNER, new BigDecimal("5"));
        String clientRequestId = UUID.randomUUID().toString();
        legacyQuotationService.recordOutcome(issuedLegacy.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.REJECTED, null, clientRequestId), salesActor);

        assertThatThrownBy(() -> quotationService.recordOutcome(issuedNew.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, null, clientRequestId), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("clientRequestId ถูกใช้ไปแล้วกับใบเสนอราคาอื่น");
            });
        // Nothing recorded on the new-engine row — the collision surfaced as a clean 409 before
        // any partial write could stick (the UPDATE and the constraint violation are atomic).
        assertThat(quotationService.get(issuedNew.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    /** Reverse order of {@link
     * #recordOutcome_crossEngineClientRequestIdCollision_legacyThenNew_refused409} — same
     * collision, same guard, opposite engine calling second, proving the catch in {@code
     * CustomerQuotationService#recordOutcome} (not just {@code DealQuotationService}'s) actually
     * fires. */
    @Test
    void recordOutcome_crossEngineClientRequestIdCollision_newThenLegacy_refused409() {
        DealQuotationDto issuedNew = issuedNewOriginQuotation(PricingRequestRecipient.DESIGNER, new BigDecimal("5"));
        CustomerQuotationDto issuedLegacy = issuedLegacyQuotation(PricingRequestRecipient.OWNER);
        String clientRequestId = UUID.randomUUID().toString();
        quotationService.recordOutcome(issuedNew.id(),
            new RecordOutcomeRequest(QuotationStatus.REJECTED, null, clientRequestId), salesActor);

        assertThatThrownBy(() -> legacyQuotationService.recordOutcome(issuedLegacy.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, null, clientRequestId), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("clientRequestId ถูกใช้ไปแล้วกับใบเสนอราคาอื่น");
            });
        assertThat(legacyQuotationService.get(issuedLegacy.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private DealQuotationDto acceptedNewOriginQuotation(String recipient, BigDecimal qty) {
        DealQuotationDto issued = issuedNewOriginQuotation(recipient, qty);
        return quotationService.recordOutcome(issued.id(),
            new RecordOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()), salesActor);
    }

    private DealQuotationDto issuedNewOriginQuotation(String recipient, BigDecimal qty) {
        PricingDecisionDto decision = approvedDecision(recipient, qty);
        DealQuotationDto draft = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationDto withValidity = quotationService.update(draft.id(), upsertWithValidityDays(draft, 30), salesActor);
        DealQuotationDto submitted = quotationService.submit(withValidity.id(), salesActor);
        return quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
    }

    /** GLA-123 slice S3 BLOCKER 2 — same as {@link #issuedNewOriginQuotation}, except the
     * quotation's own line quantity is edited AFTER create, BEFORE submit, to {@code
     * editedPiecesInput} — exactly the "sales edits the quantity on the quotation" case S1
     * explicitly allows (excluded from {@code requireCeoApprovalIfChanged}, so no CEO
     * re-approval is needed and a plain sales_manager approval suffices here, matching
     * production). */
    private DealQuotationDto issuedNewOriginQuotationWithEditedQuantity(String recipient, BigDecimal prQty,
                                                                        int editedPiecesInput) {
        PricingDecisionDto decision = approvedDecision(recipient, prQty);
        DealQuotationDto draft = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationRequests.ItemInput editedItem = new DealQuotationRequests.ItemInput(
            draft.items().get(0).locationLabel(), draft.items().get(0).catalogPriceId(), draft.items().get(0).productCode(),
            draft.items().get(0).brand(), draft.items().get(0).model(), draft.items().get(0).color(),
            draft.items().get(0).texture(), draft.items().get(0).sizeText(), draft.items().get(0).thicknessMm(),
            draft.items().get(0).sqmPerPiece(), draft.items().get(0).quantityMode(), draft.items().get(0).areaSqm(),
            editedPiecesInput, draft.items().get(0).wastageMode(), draft.items().get(0).wastageValue(),
            draft.items().get(0).piecesPerBox(), draft.items().get(0).unitPrice(), draft.items().get(0).discountPct(),
            draft.items().get(0).originCountry(), draft.items().get(0).leadTimeMinDays(),
            draft.items().get(0).leadTimeMaxDays(), draft.items().get(0).itemNotes(),
            "TILE", null, null, null, null, null, null, null, null,
            draft.items().get(0).id(), draft.items().get(0).sqmPerBox(), false);
        DealQuotationRequests.UpsertDealQuotationRequest editRequest = new DealQuotationRequests.UpsertDealQuotationRequest(
            draft.contactId(), draft.deptCode(), draft.unitCode(), draft.offerDate(), draft.depositPercent(),
            draft.remainderMode(), draft.creditDays(), 30, draft.customerNotes(), draft.priceMode(),
            draft.documentLanguage(), draft.currency(), List.of(editedItem));
        DealQuotationDto edited = quotationService.update(draft.id(), editRequest, salesActor);
        // Confirms the edit actually landed BEFORE driving the rest of the flow — if this
        // assertion ever fails, the test below would otherwise silently degrade into the
        // unedited case.
        assertThat(edited.items().get(0).piecesFinal()).isEqualTo(editedPiecesInput);
        DealQuotationDto submitted = quotationService.submit(edited.id(), salesActor);
        return quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
    }

    private CustomerQuotationDto acceptedLegacyQuotation(String recipient) {
        CustomerQuotationDto issued = issuedLegacyQuotation(recipient);
        return legacyQuotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest(QuotationStatus.ACCEPTED, "ลูกค้าโอเค", UUID.randomUUID().toString()),
            salesActor);
    }

    private CustomerQuotationDto issuedLegacyQuotation(String recipient) {
        PricingDecisionDto decision = approvedDecision(recipient, new BigDecimal("5"));
        CustomerQuotationDto draft = legacyQuotationService.create(decision.pricingRequestId(),
            new CreateCustomerQuotationRequest(null, null, null, LocalDate.now().plusDays(30), null,
                UUID.randomUUID().toString()),
            salesActor);
        return legacyQuotationService.issue(draft.id(), new IssueCustomerQuotationRequest(UUID.randomUUID().toString()),
            salesActor);
    }

    private PricingDecisionDto approvedDecision(String recipient, BigDecimal qty) {
        long pricingRequestId = submittedCostingReadyRequest(recipient, qty);
        PricingDecisionDto started = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = List.of(
            discountItem(started.items().get(0).id(), new BigDecimal("10")));
        PricingDecisionDto afterMode = decisionService.update(started.id(),
            new UpdatePricingDecisionRequest(null, "NET", updates), ceoActor);
        return decisionService.approve(afterMode.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
    }

    private UpdatePricingDecisionItemRequest discountItem(long itemId, BigDecimal discountPct) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            discountPct, false, null, false, null, false);
    }

    private long submittedCostingReadyRequest(String recipient, BigDecimal qty) {
        // Links back to the ticket's own (pre-existing, qty=1) ticket_item — the realistic shape
        // OrderConfirmationService#reconcileTicketItems documents as its main case ("a line that
        // traces back via source_ticket_item_id"), and the one confirmOrder_...'s own test needs
        // to observe an actual UPDATE rather than a new-line INSERT.
        long sourceTicketItemId = jdbc.queryForObject(
            "SELECT item_id FROM sales.ticket_item WHERE ticket_id = :id ORDER BY item_id LIMIT 1",
            Map.of("id", ticketId), Long.class);
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            sourceTicketItemId, catalogProductId, null, "SCG", "Tile A-S3", "SCG Tile A-S3", "White", "Matte",
            "60x60", FACTORY,
            null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qty.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            recipient, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "pcr quotation s3 request", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    private DealQuotationRequests.UpsertDealQuotationRequest upsertWithValidityDays(DealQuotationDto q, int days) {
        List<DealQuotationRequests.ItemInput> items = q.items().stream().map(this::asItemInput).toList();
        return new DealQuotationRequests.UpsertDealQuotationRequest(q.contactId(), q.deptCode(), q.unitCode(),
            q.offerDate(), q.depositPercent(), q.remainderMode(), q.creditDays(), days, q.customerNotes(),
            q.priceMode(), q.documentLanguage(), q.currency(), items);
    }

    private DealQuotationRequests.ItemInput asItemInput(DealQuotationDtos.DealQuotationItemDto item) {
        return new DealQuotationRequests.ItemInput(item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(),
            item.sqmPerPiece(), item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(),
            item.wastageValue(), item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(),
            item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            "TILE", null, null, null, null, null, null, null, null,
            item.id(), item.sqmPerBox(), false);
    }

    private DealQuotationRequests.UpsertDealQuotationRequest minimalDirectDraft() {
        DealQuotationRequests.ItemInput item = new DealQuotationRequests.ItemInput(
            null, null, null, "SCG", "Direct Tile", "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, "TILE", null, null, null, null, null, null, null, null, null, null, false);
        return new DealQuotationRequests.UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, "NET", "TH", "THB", List.of(item));
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
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

    /** {@code account} role, NO {@code can_create_quotation} grant — part of the BLOCKER-A
     * wrong-way-round matrix (mirrors this class's own {@code importActor}). */
    private UserPrincipal accountActor(String suffix) {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        long id = createEmployee(employees, "บัญชี S3 BLOCKER-A " + suffix,
            "acct-s3-blockera-" + suffix + "@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        return actor(id, "account");
    }

    /** {@code account} role WITH the {@code can_create_quotation} grant — mirrors
     * {@code PricingRequestQuotationIntegrationTest#grantedAccountActor}'s identical pattern; the
     * grant lets this actor view/edit ANY DEAL_DIRECT deal, but must NOT bypass
     * {@link DealQuotationService#PRICING_REQUEST_VIEW_ROLES} for a PRICING_REQUEST-origin row. */
    private UserPrincipal grantedAccountActor(String suffix) {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        long id = createEmployee(employees, "บัญชี S3 BLOCKER-A (สิทธิ์พิเศษ) " + suffix,
            "acct-s3-blockera-grant-" + suffix + "@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", id));
        return actor(id, "account");
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
