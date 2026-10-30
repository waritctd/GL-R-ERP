package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
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
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RejectRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
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
import th.co.glr.hr.ticket.DealLostReason;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB coverage for GLA-123 slice S2 (Phase 3 of the sales pricing redesign), REWORKED
 * (owner ruling reversed the dual-approval design, 2026-09-20): ONE approval — by ผู้จัดการฝ่ายขาย
 * OR the CEO — issues a PRICING_REQUEST-origin quotation, reusing the SAME single-shot
 * compare-and-set (and V175 approver snapshot) DEAL_DIRECT already uses. The CEO's price
 * authority survives sales's freedom to edit price after create: a quotation that no longer
 * matches the CEO's decision may ONLY be approved by the CEO.
 *
 * <p>Setup is a trimmed copy of {@code PricingRequestQuotationIntegrationTest}'s own fixture
 * (same fixture shapes, same helper method bodies) plus this slice's own {@link
 * DealQuotationService#wireTicketService} wiring, which S1's suite never needed (issuing is this
 * slice's job). Duplicated rather than shared, matching that file's own stated reason: avoid
 * coupling this new-feature suite's lifecycle to an already-large existing file.
 */
class DealQuotationPricingRequestApprovalIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private DealQuotationService quotationService;
    private DealQuotationRepository quotationRepository;
    private TicketRepository tickets;
    private TicketService ticketService;

    private long salesRepId;
    private long ceoUserId;
    private long salesManagerId;
    private long importUserId;
    private long accountUserId;
    private long qcUserId;
    private UserPrincipal salesActor;
    private UserPrincipal ceoActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal importActor;
    private UserPrincipal accountActor;
    private UserPrincipal qcActor;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;
    // Real signature upload/read, mirrors DealQuotationApproverSnapshotIntegrationTest's identical
    // wiring — used by the "approval freezes a signature" parity test below.
    private EmployeeSignatureRepository signatureRepository;
    private EmployeeSignatureService signatureService;

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

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pcr-quotation-s2-test-uploads");
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
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

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
        signatureRepository = new EmployeeSignatureRepository(jdbc);
        signatureService = new EmployeeSignatureService(signatureRepository, new th.co.glr.hr.activity.ActivityLogRepository(jdbc));
        quotationService = new DealQuotationService(quotationRepository, tickets, customers,
            new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc),
            signatureRepository, new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        th.co.glr.hr.customerquotation.CustomerQuotationRepository customerQuotationRepository =
            new th.co.glr.hr.customerquotation.CustomerQuotationRepository(jdbc);
        quotationService.wirePricingRequestDependencies(pricingRequests, decisionRepository, customerQuotationRepository);
        // GLA-123 slice S2's own wiring — S1's suite never needed this (issuing is this slice's
        // job). See DealQuotationService#ticketService's own Javadoc for why this is a setter.
        quotationService.wireTicketService(ticketService);

        salesRepId = createEmployee(employees, "พนักงานขาย S2", "sales-s2@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า S2", "import-s2@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร S2", "ceo-s2@glr.co.th", "MD", "ผู้บริหาร");
        salesManagerId = createEmployee(employees, "ผจก.ขาย S2", "sm-s2@glr.co.th", "SALES", "ฝ่ายขาย");
        accountUserId = createEmployee(employees, "บัญชี S2", "acct-s2@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        qcUserId = createEmployee(employees, "QC S2", "qc-s2@glr.co.th", "QC", "ฝ่าย QC");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        accountActor = actor(accountUserId, "account");
        qcActor = actor(qcUserId, "qc");

        catalogProductIdFactoryA = insertCatalogProduct("Factory A-S2", "IT", "TEST-A-S2-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B-S2", "IT", "TEST-B-S2-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท PCR Quotation S2 จำกัด", "0100000000299", "999 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0299");
        ProjectDto project = projects.create(customer.id(), "โครงการ PCR Quotation S2");
        long contactId = new ContactRepository(jdbc).create(customer.id(), "สมหญิง", "ทดสอบ",
            "ผู้จัดการฝ่ายจัดซื้อ", "contact-s2@example.com", "081-000-0001").id();
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล PCR Quotation S2", "NORMAL", customer.name(), customer.id(), project.id(),
                contactId, null, null, List.of(ticketItem("SCG", "Tile A-S2", "Factory A-S2"),
                    ticketItem("Cotto", "Tile B-S2", "Factory B-S2"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Submit
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void submit_movesDraftToPendingApproval() {
        DealQuotationDto submitted = submittedQuotation();
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    @Test
    void submit_refusedWithoutValidity_pricingRequestOrigin() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(approvedDecision().pricingRequestId(), salesActor);
        assertThat(draft.validityDays()).isNull();
        assertThatThrownBy(() -> quotationService.submit(draft.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(quotationService.get(draft.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    @Test
    void submit_succeedsWithValidityDays_pricingRequestOrigin() {
        DealQuotationDto submitted = submittedQuotation();
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(submitted.validityDays()).isEqualTo(30);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // ONE approval issues the document — either role, no changes from the CEO's decision.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approve_bySalesManager_whenUnchanged_issues() {
        DealQuotationDto submitted = submittedQuotation();
        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(issued.approvedById()).isEqualTo(salesManagerId);
        assertThat(issued.approvedByName()).isNotBlank();
    }

    @Test
    void approve_byCeo_whenUnchanged_issues() {
        DealQuotationDto submitted = submittedQuotation();
        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), ceoActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(issued.approvedById()).isEqualTo(ceoUserId);
    }

    @Test
    void approve_twiceOnTheSameRow_conflict_secondCallRefused() {
        DealQuotationDto submitted = submittedQuotation();
        quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The CEO-required-when-changed gate: a sales_manager may approve an UNCHANGED quotation,
    // but a quotation that no longer matches the CEO's decision (price/discount, price mode, or a
    // removed CEO-linked line) may ONLY be approved by the CEO.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approve_bySalesManager_whenLinePriceChanged_forbidden_nothingWritten() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(approvedDecision().pricingRequestId(), salesActor);
        DealQuotationDto changed = quotationService.update(draft.id(), upsertWithChangedDiscount(draft, 30), salesActor);
        assertThat(changed.items().get(0).priceChangedFromCeo()).isTrue();
        DealQuotationDto submitted = quotationService.submit(changed.id(), salesActor);

        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(submitted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    @Test
    void approve_byCeo_whenLinePriceChanged_succeeds() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(approvedDecision().pricingRequestId(), salesActor);
        DealQuotationDto changed = quotationService.update(draft.id(), upsertWithChangedDiscount(draft, 30), salesActor);
        DealQuotationDto submitted = quotationService.submit(changed.id(), salesActor);

        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), ceoActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(issued.approvedById()).isEqualTo(ceoUserId);
    }

    @Test
    void approve_bySalesManager_whenPriceModeChanged_forbidden_nothingWritten() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(approvedDecision().pricingRequestId(), salesActor);
        DealQuotationDto changed = quotationService.update(draft.id(), upsertWithDirectNetMode(draft), salesActor);
        assertThat(changed.priceModeChangedFromCeo()).isTrue();
        DealQuotationDto submitted = quotationService.submit(changed.id(), salesActor);

        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(submitted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    @Test
    void approve_byCeo_whenPriceModeChanged_succeeds() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(approvedDecision().pricingRequestId(), salesActor);
        DealQuotationDto changed = quotationService.update(draft.id(), upsertWithDirectNetMode(draft), salesActor);
        DealQuotationDto submitted = quotationService.submit(changed.id(), salesActor);

        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), ceoActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    @Test
    void approve_bySalesManager_whenCeoLineRemoved_forbidden_nothingWritten() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(approvedDecision().pricingRequestId(), salesActor);
        DealQuotationDto oneItem = quotationService.update(draft.id(), upsertWithOnlyFirstItem(draft, 30), salesActor);
        assertThat(oneItem.itemsRemovedFromCeoCount()).isEqualTo(1);
        DealQuotationDto submitted = quotationService.submit(oneItem.id(), salesActor);

        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(submitted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    @Test
    void approve_byCeo_whenCeoLineRemoved_succeeds() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(approvedDecision().pricingRequestId(), salesActor);
        DealQuotationDto oneItem = quotationService.update(draft.id(), upsertWithOnlyFirstItem(draft, 30), salesActor);
        DealQuotationDto submitted = quotationService.submit(oneItem.id(), salesActor);

        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), ceoActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Wrong-way-round role refusals — unrelated to the CEO gate, plain requireApproveAccess.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approve_bySalesRep_forbidden_nothingWritten() {
        DealQuotationDto submitted = submittedQuotation();
        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(submitted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    @Test
    void approve_byImport_forbidden_nothingWritten() {
        DealQuotationDto submitted = submittedQuotation();
        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void approve_byAccount_forbidden_nothingWritten() {
        DealQuotationDto submitted = submittedQuotation();
        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void approve_byQc_forbidden_nothingWritten() {
        DealQuotationDto submitted = submittedQuotation();
        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), qcActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Reject — a single approver (either role) clears the document back to DRAFT with a reason.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void reject_bySalesManager_returnsToDraftWithReason() {
        DealQuotationDto submitted = submittedQuotation();
        DealQuotationDto rejected = quotationService.reject(submitted.id(), new RejectRequest("ราคาไม่เหมาะสม"), salesManagerActor);
        assertThat(rejected.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(rejected.approvalNote()).isEqualTo("ราคาไม่เหมาะสม");
    }

    @Test
    void reject_byCeo_returnsToDraftWithReason() {
        DealQuotationDto submitted = submittedQuotation();
        DealQuotationDto rejected = quotationService.reject(submitted.id(), new RejectRequest("ขอทบทวนราคาใหม่"), ceoActor);
        assertThat(rejected.docStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    @Test
    void reject_thenResubmit_reusesTheSameRow_noRevisionMinted() {
        DealQuotationDto submitted = submittedQuotation();
        quotationService.reject(submitted.id(), new RejectRequest("แก้ราคา"), ceoActor);
        DealQuotationDto resubmitted = quotationService.submit(submitted.id(), salesActor);
        assertThat(resubmitted.id()).isEqualTo(submitted.id());
        assertThat(resubmitted.number()).isEqualTo(submitted.number());
        assertThat(resubmitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Issue hooks: PR status, stage advance, ticket event, validity date
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * D-2 (owner ruling 2026-10-16, superseding their own earlier answer): the stage moves at
     * CREATION, not approval.
     *
     * <p>The approve-time advance at {@code DealQuotationService}'s issue hook is kept: it is
     * idempotent under forward-only, and it still covers a recipient changed between draft and
     * approval. So S4 must be true both before and after, and the issue hook must
     * not write a second STAGE_CHANGED — which is what "idempotent" has to mean here.
     */
    @Test
    void issue_transitionsPricingRequestToQuotationIssued_andTheStageArrivedAtCreation() {
        PricingDecisionDto decision = approvedDecision();
        DealQuotationDto draft = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);

        // The source คำขอราคา is addressed to the DESIGNER, so creating the quotation IS what put
        // the deal on S4 — before any submit or approval exists.
        assertThat(tickets.findById(ticketId).orElseThrow().summary().salesStage())
            .isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
        Integer stageEventsAtCreation = stageChangedEvents();
        assertThat(stageEventsAtCreation).isEqualTo(1);

        DealQuotationDto withValidity = quotationService.update(draft.id(), upsertWithValidityDays(draft, 30), salesActor);
        DealQuotationDto submitted = quotationService.submit(withValidity.id(), salesActor);
        quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);

        PricingRequestSummaryDto afterIssue = pricingRequests.findSummary(decision.pricingRequestId()).orElseThrow();
        assertThat(afterIssue.status()).isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        TicketDto afterIssueTicket = tickets.findById(ticketId).orElseThrow();
        assertThat(afterIssueTicket.summary().salesStage()).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
        assertThat(stageChangedEvents()).isEqualTo(stageEventsAtCreation);
    }

    private Integer stageChangedEvents() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind",
            java.util.Map.of("id", ticketId, "kind", th.co.glr.hr.ticket.TicketEventKind.STAGE_CHANGED),
            Integer.class);
    }

    @Test
    void approve_setsValidityDateFromValidityDays() {
        DealQuotationDto submitted = submittedQuotation();
        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        // Bare LocalDate.now() uses the JVM default zone. DealQuotationService computes the
        // approval date (and so validityDate) with an explicit LocalDate.now(Asia/Bangkok) — see
        // its own BANGKOK constant — so a CI runner whose default zone is UTC disagrees with this
        // assertion for roughly the first 7 hours of each Bangkok day (already tomorrow in
        // Bangkok, still today in UTC). Match the same zone the code under test actually uses.
        assertThat(issued.validityDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Bangkok")).plusDays(30));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // V175 approver snapshot — reused verbatim from DEAL_DIRECT's own #approve, one row, whoever
    // approved. Full signature-survives-a-later-change coverage already lives in
    // DealQuotationApproverSnapshotIntegrationTest for the SAME shared mechanism; this is a
    // parity confirmation that a PRICING_REQUEST-origin row gets one too.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approve_freezesTheApproverSnapshot() throws Exception {
        signatureService.upload(salesManagerId, png(HtmlXlsFidelityTest.signaturePng()), salesManagerActor);
        byte[] originalSignature = signatureRepository.find(salesManagerId).orElseThrow().image();

        DealQuotationDto submitted = submittedQuotation();
        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation_approver_snapshot WHERE quotation_id = :id",
            java.util.Map.of("id", issued.id()), Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(signatureBytes(render(issued))).isEqualTo(originalSignature);

        // The approver replaces their signature AFTER the document already issued -- the ISSUED
        // document must still print the OLD (frozen) signature. Same V175 discipline DEAL_DIRECT's
        // own suite already covers at length; this is a one-shot parity check for this origin.
        signatureService.upload(salesManagerId, png(zigZagSignaturePng()), salesManagerActor);
        DealQuotationDto reread = quotationService.get(issued.id(), salesActor);
        assertThat(signatureBytes(render(reread))).isEqualTo(originalSignature);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Expiry (D5) — ISSUED -> EXPIRED past validity_date; DEAL_DIRECT never expires
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void expireOverdueQuotations_flipsIssuedPastValidityDateToExpired() {
        DealQuotationDto submitted = submittedQuotation();
        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);

        jdbc.update("UPDATE sales.quotation SET validity_date = CURRENT_DATE - 1 WHERE quotation_id = :id",
            java.util.Map.of("id", issued.id()));

        int expiredCount = quotationService.expireOverdueQuotations();
        assertThat(expiredCount).isEqualTo(1);
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.EXPIRED);
    }

    @Test
    void expireOverdueQuotations_neverTouchesADealDirectQuotation() {
        DealQuotationDto created = quotationService.create(ticketId, directDraft(), salesActor);
        DealQuotationDto submittedDirect = quotationService.submit(created.id(), salesActor);
        DealQuotationDto approvedDirect = quotationService.approve(
            submittedDirect.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(approvedDirect.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(approvedDirect.validityDate()).isNotNull();

        jdbc.update("UPDATE sales.quotation SET validity_date = CURRENT_DATE - 1 WHERE quotation_id = :id",
            java.util.Map.of("id", approvedDirect.id()));

        quotationService.expireOverdueQuotations();
        assertThat(quotationService.get(approvedDirect.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.APPROVED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // MAJOR-3 (owner ruling via coordinator, 2026-09-20) — "the expiry escape hatch": once an
    // ISSUED PRICING_REQUEST-origin quotation EXPIRES, sales may create a FRESH one from the SAME
    // approved decision, continuing the SAME number family; the expired document stays as an
    // audit record, never revived/revised. The "one live quotation per request" rule stays.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createFromPricingRequest_afterExpiry_prefillsFromTheSameDecision_continuesTheSameNumberFamily_andCanBeIssued() {
        PricingDecisionDto decision = approvedDecision();
        long pricingRequestId = decision.pricingRequestId();
        DealQuotationDto firstDraft = quotationService.createFromPricingRequest(pricingRequestId, salesActor);
        DealQuotationDto withValidity = quotationService.update(firstDraft.id(),
            upsertWithValidityDays(firstDraft, 30), salesActor);
        DealQuotationDto submitted = quotationService.submit(withValidity.id(), salesActor);
        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        String firstNumber = issued.number();
        assertThat(firstNumber).endsWith("-1");

        jdbc.update("UPDATE sales.quotation SET validity_date = CURRENT_DATE - 1 WHERE quotation_id = :id",
            java.util.Map.of("id", issued.id()));
        assertThat(quotationService.expireOverdueQuotations()).isEqualTo(1);
        DealQuotationDto expired = quotationService.get(issued.id(), salesActor);
        assertThat(expired.docStatus()).isEqualTo(QuotationStatus.EXPIRED);

        // The PR status bookkeeping decision this fix made: NOT rolled back to
        // APPROVED_FOR_QUOTATION -- create() TOLERATES QUOTATION_ISSUED instead (see that
        // method's own comment for why: PricingRequestStatus's forward-only transition graph is
        // SHARED with the legacy customerquotation engine, and adding a backward edge there would
        // risk that flow's own semantics for a one-line gain here).
        assertThat(pricingRequests.findSummary(pricingRequestId).orElseThrow().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        DealQuotationDto secondDraft = quotationService.createFromPricingRequest(pricingRequestId, salesActor);
        assertThat(secondDraft.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(secondDraft.id()).isNotEqualTo(issued.id());
        // Continues the SAME base number family, one suffix further -- "a later version of the
        // same offer" (owner's own framing), not an unrelated fresh base number.
        assertThat(secondDraft.number()).isEqualTo(firstNumber.substring(0, firstNumber.length() - 2) + "-2");
        // Prefilled from the SAME approved decision -- same item count, same price mode.
        assertThat(secondDraft.items()).hasSize(issued.items().size());
        assertThat(secondDraft.priceMode()).isEqualTo(issued.priceMode());
        assertThat(secondDraft.pricingRequestId()).isEqualTo(pricingRequestId);

        // The new document goes through the WHOLE flow again, independently.
        DealQuotationDto secondWithValidity = quotationService.update(secondDraft.id(),
            upsertWithValidityDays(secondDraft, 30), salesActor);
        DealQuotationDto secondSubmitted = quotationService.submit(secondWithValidity.id(), salesActor);
        DealQuotationDto secondIssued = quotationService.approve(secondSubmitted.id(), new ApproveRequest(null), ceoActor);
        assertThat(secondIssued.docStatus()).isEqualTo(QuotationStatus.ISSUED);

        // The FIRST (expired) document is untouched -- an audit record, never revived/revised.
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.EXPIRED);
    }

    @Test
    void createFromPricingRequest_refusedWhileALiveQuotationExists_evenPastDraft() {
        PricingDecisionDto decision = approvedDecision();
        long pricingRequestId = decision.pricingRequestId();
        DealQuotationDto draft = quotationService.createFromPricingRequest(pricingRequestId, salesActor);
        DealQuotationDto withValidity = quotationService.update(draft.id(), upsertWithValidityDays(draft, 30), salesActor);
        // Submitted -- PENDING_APPROVAL, past the idempotent-DRAFT replay check, and the PR's own
        // status is STILL APPROVED_FOR_QUOTATION (it only moves at actual issue) -- exactly the
        // gap this fix closes.
        DealQuotationDto submitted = quotationService.submit(withValidity.id(), salesActor);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> quotationService.createFromPricingRequest(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Also refused once ISSUED (live, not expired yet).
        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThatThrownBy(() -> quotationService.createFromPricingRequest(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // B-4 (VERSIONING.md, owner ruling 2026-10-17): one LIVE offer per DEAL, origin-agnostic.
    // Issuing/approving any quotation retires every OTHER live one on the deal.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issue_aSecondRoundsQuotation_supersedesTheFirstIssuedOne_andWritesTheEvent() {
        DealQuotationDto first = issuedRound();
        DealQuotationDto second = issuedRound();

        assertThat(statusOf(first.id())).isEqualTo("SUPERSEDED");
        assertThat(statusOf(second.id())).isEqualTo("ISSUED");
        assertThat(supersededEventCount(ticketId, first.number(), second.number())).isEqualTo(1);
        assertThat(countWithStatus("ISSUED")).isEqualTo(1);
    }

    @Test
    void issue_supersedesARevisionRequestedSiblingToo() {
        DealQuotationDto first = issuedRound();
        jdbc.update("UPDATE sales.quotation SET doc_status = 'REVISION_REQUESTED' WHERE quotation_id = :id",
            java.util.Map.of("id", first.id()));
        DealQuotationDto second = issuedRound();

        assertThat(statusOf(first.id())).isEqualTo("SUPERSEDED");
        assertThat(supersededEventCount(ticketId, first.number(), second.number())).isEqualTo(1);
    }

    @Test
    void issue_leavesAnAcceptedOrExpiredSiblingAlone() {
        long accepted = insertRawQuotation(ticketId, "QN-B4-ACC", "PRICING_REQUEST", "ACCEPTED");
        long expired = insertRawQuotation(ticketId, "QN-B4-EXP", "PRICING_REQUEST", "EXPIRED");
        DealQuotationDto issued = issuedRound();

        assertThat(statusOf(accepted)).isEqualTo("ACCEPTED");
        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
        assertThat(supersededEventCount(ticketId, "QN-B4-ACC", issued.number())).isZero();
        assertThat(supersededEventCount(ticketId, "QN-B4-EXP", issued.number())).isZero();
    }

    @Test
    void issue_supersedesAnApprovedDirectQuotationOnTheSameDeal() {
        // The pricing route refuses to START while a live direct quotation exists, so the pricing
        // quotation is created+submitted first and the direct row lands afterwards, before approval.
        DealQuotationDto submitted = submittedQuotation();
        long direct = insertRawQuotation(ticketId, "QN-B4-DIRECT", "DEAL_DIRECT", "APPROVED");
        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);

        assertThat(statusOf(direct)).isEqualTo("SUPERSEDED");
        assertThat(supersededEventCount(ticketId, "QN-B4-DIRECT", issued.number())).isEqualTo(1);
    }

    @Test
    void approve_ofADirectQuotation_supersedesAnIssuedPricingRouteSibling() {
        long pr = insertRawQuotation(ticketId, "QN-B4-PR", "PRICING_REQUEST", "ISSUED");
        DealQuotationDto direct = quotationService.create(ticketId, directDraft(), salesActor);
        quotationService.submit(direct.id(), salesActor);
        DealQuotationDto approved = quotationService.approve(direct.id(), new ApproveRequest(null), salesManagerActor);

        assertThat(approved.docStatus()).isEqualTo(QuotationStatus.APPROVED);
        assertThat(statusOf(pr)).isEqualTo("SUPERSEDED");
        assertThat(supersededEventCount(ticketId, "QN-B4-PR", approved.number())).isEqualTo(1);
    }

    @Test
    void issue_neverTouchesAQuotationOnAnotherDeal() {
        java.util.Map<String, Object> t = jdbc.queryForMap(
            "SELECT customer_id, project_id FROM sales.ticket WHERE ticket_id = :t", java.util.Map.of("t", ticketId));
        long otherTicket = ticketService.create(
            new CreateTicketRequest("ดีลอื่น B4", "NORMAL", "ลูกค้า B4", ((Number) t.get("customer_id")).longValue(),
                ((Number) t.get("project_id")).longValue(), null, null, null,
                List.of(ticketItem("SCG", "Tile A-S2", "Factory A-S2"))),
            salesActor).summary().id();
        long other = insertRawQuotation(otherTicket, "QN-B4-OTHER", "PRICING_REQUEST", "ISSUED");
        issuedRound();

        assertThat(statusOf(other)).isEqualTo("ISSUED");
    }

    @Test
    void issue_neverTouchesALegacyOriginNullRow() {
        long legacy = insertRawQuotation(ticketId, "QN-B4-LEGACY", null, "ISSUED");
        issuedRound();

        assertThat(statusOf(legacy)).isEqualTo("ISSUED");
    }

    @Test
    void issue_ofARevision_writesExactlyOneSupersededEventForItsParent() {
        DealQuotationDto parent = issuedRound();
        DealQuotationDto revision = quotationService.createRevision(parent.id(), salesActor);
        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto issued = quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);

        assertThat(statusOf(parent.id())).isEqualTo("SUPERSEDED");
        assertThat(supersededEventCount(ticketId, parent.number(), issued.number())).isEqualTo(1);
    }

    /**
     * Owner ruling 2026-10-17: P1 stays QUOTATION_ISSUED while its only quotation is SUPERSEDED by
     * sibling request P2's issue. Re-quoting P1 would price off the OLDER CEO decision and its
     * issue would retire P2's newer offer, so the API refuses (the UI already hides the button).
     */
    @Test
    void createFromPricingRequest_onARequestWhoseQuotationWasSupersededByASibling_refused409() {
        long p1 = issuedRound().pricingRequestId();
        issuedRound();
        assertThat(statusOf(quotationIdsOnTicket().get(0))).isEqualTo("SUPERSEDED");
        int before = quotationIdsOnTicket().size();

        assertThatThrownBy(() -> quotationService.createFromPricingRequest(p1, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo("คำขอราคานี้ถูกแทนที่ด้วยใบเสนอราคาฉบับใหม่ของดีลแล้ว — ออกใบเสนอราคาจากคำขอราคาล่าสุด หรือสร้างคำขอราคาใหม่");
            });
        assertThat(quotationIdsOnTicket()).hasSize(before);
    }

    /** Pins "only the LATEST row decides": parent SUPERSEDED by its own revision, child EXPIRED
     * -> the expiry escape hatch still works. */
    @Test
    void createFromPricingRequest_whenTheLatestIsAnExpiredRevisionOfASupersededParent_stillAllowed() {
        DealQuotationDto parent = issuedRound();
        DealQuotationDto revision = quotationService.createRevision(parent.id(), salesActor);
        quotationService.submit(revision.id(), salesActor);
        DealQuotationDto child = quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor);
        assertThat(statusOf(parent.id())).isEqualTo("SUPERSEDED");
        jdbc.update("UPDATE sales.quotation SET validity_date = CURRENT_DATE - 1 WHERE quotation_id = :id",
            java.util.Map.of("id", child.id()));
        assertThat(quotationService.expireOverdueQuotations()).isEqualTo(1);
        assertThat(statusOf(child.id())).isEqualTo("EXPIRED");

        DealQuotationDto next = quotationService.createFromPricingRequest(parent.pricingRequestId(), salesActor);

        assertThat(next.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(next.number()).isEqualTo(
            DealQuotationRepository.baseNumber(parent.number(), parent.revisionNo()) + "-3");
    }

    private List<Long> quotationIdsOnTicket() {
        return jdbc.queryForList("SELECT quotation_id FROM sales.quotation WHERE ticket_id = :t ORDER BY quotation_id",
            java.util.Map.of("t", ticketId), Long.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Cancelling the pricing request takes its PENDING_APPROVAL quotation with it. The cancel
    // cascade (PricingRequestRepository#supersedeOpenPricingDecisionAndQuotation) retired DRAFT and
    // every issued status but skipped PENDING_APPROVAL, so the quotation stayed in the approvals
    // queue, and approving it issued an offer on a cancelled request and superseded the deal's live
    // quotation through the B-4 sweep above.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The cascade, through PricingRequestService#cancel. The sibling request's quotation is the
     * control: the supersede is scoped to the cancelled request, never to the deal. */
    @Test
    void cancel_supersedesTheRequestsPendingApprovalQuotation_andTakesItOutOfTheApprovalsQueue() {
        DealQuotationDto sibling = submittedQuotation();
        DealQuotationDto pending = submittedQuotation();
        long pricingRequestId = pending.pricingRequestId();
        assertThat(approvalQueueIds()).contains(sibling.id(), pending.id());

        pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor);

        assertThat(statusOf(pending.id())).isEqualTo("SUPERSEDED");
        assertThat(approvalQueueIds()).doesNotContain(pending.id()).contains(sibling.id());
        assertThat(statusOf(sibling.id())).isEqualTo("PENDING_APPROVAL");
        // The same record the cascade's other statuses get: the request's one cancel event, and no
        // quotation event of its own.
        assertThat(cancelEvents(pricingRequestId))
            .containsExactly("APPROVED_FOR_QUOTATION>CANCELLED:ลูกค้ายกเลิกโครงการ");
        assertThat(ticketEventCount(th.co.glr.hr.ticket.TicketEventKind.DEAL_QUOTATION_SUPERSEDED)).isZero();
    }

    /** The defect the 10-27 review found: approving the cancelled request's quotation from the
     * approvals queue issued it and retired the deal's live offer. Either half of the fix refuses
     * it on its own (the cascade leaves nothing PENDING_APPROVAL to approve; the guard refuses a
     * CANCELLED request), so the tests either side of this one pin each half separately. */
    @Test
    void approve_afterTheRequestWasCancelled_isRefused_andTheDealsLiveQuotationStaysLive() {
        DealQuotationDto live = issuedRound();
        DealQuotationDto pending = submittedQuotation();
        pricingRequestService.cancel(pending.pricingRequestId(), cancelRequest(), salesActor);

        assertThatThrownBy(() -> quotationService.approve(pending.id(), new ApproveRequest(null), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(statusOf(live.id())).isEqualTo("ISSUED");
        assertThat(statusOf(pending.id())).isNotEqualTo("ISSUED");
        assertThat(supersededEventCount(ticketId, live.number(), pending.number())).isZero();
        assertThat(pricingRequestStatusOf(pending.pricingRequestId())).isEqualTo(PricingRequestStatus.CANCELLED);
    }

    /** The approve guard on its own. A request cancelled before this fix kept its PENDING_APPROVAL
     * quotation and nothing retires that row, so approve refuses any quotation whose request is
     * CANCELLED, whatever the row's own status. Built through the real cancel; only the quotation
     * is put back to the PENDING_APPROVAL the old cascade left. The CEO is refused too: the CEO's
     * early return from the price-change gate must not double as a way past this one. */
    @Test
    void approve_ofAPendingQuotationLeftOnACancelledRequest_isRefusedForEitherApprover_andWritesNothing() {
        DealQuotationDto live = issuedRound();
        DealQuotationDto pending = submittedQuotation();
        pricingRequestService.cancel(pending.pricingRequestId(), cancelRequest(), salesActor);
        jdbc.update("UPDATE sales.quotation SET doc_status = 'PENDING_APPROVAL' WHERE quotation_id = :id",
            java.util.Map.of("id", pending.id()));

        for (UserPrincipal approver : List.of(salesManagerActor, ceoActor)) {
            assertThatThrownBy(() -> quotationService.approve(pending.id(), new ApproveRequest(null), approver))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("คำขอราคาของใบเสนอราคานี้ถูกยกเลิกแล้ว จึงอนุมัติไม่ได้");
                });
        }

        // Refused before the first write: this suite has no transaction to roll a late refusal back.
        assertThat(statusOf(pending.id())).isEqualTo("PENDING_APPROVAL");
        assertThat(approverSnapshotCount(pending.id())).isZero();
        assertThat(statusOf(live.id())).isEqualTo("ISSUED");
        assertThat(supersededEventCount(ticketId, live.number(), pending.number())).isZero();
    }

    /** The deal-level cascade (TicketService#markLost -> PricingRequestService#cancelOpenForTicket)
     * runs the same supersede, so a lost deal's pending quotation leaves the queue too. */
    @Test
    void markLost_cancelsTheRequest_andSupersedesItsPendingApprovalQuotation() {
        DealQuotationDto pending = submittedQuotation();

        ticketService.markLost(ticketId, DealLostReason.PRICE, null, salesActor);

        assertThat(pricingRequestStatusOf(pending.pricingRequestId())).isEqualTo(PricingRequestStatus.CANCELLED);
        assertThat(statusOf(pending.id())).isEqualTo("SUPERSEDED");
        assertThat(approvalQueueIds()).doesNotContain(pending.id());
    }

    /** One lock (owner ruling 2026-10-27): cancel takes the request's advisory lock, the one
     * approve takes first, so the two serialise instead of updating the quotation and request rows
     * in opposite orders. Thread A stands in for an approve holding that lock. The cancel must queue
     * for it while holding no row lock (the NOWAIT probe), then finish once the lock frees. */
    @Test
    void cancel_waitsForTheRequestLockAnApproveHolds_beforeItsFirstWrite() throws Exception {
        DealQuotationDto pending = submittedQuotation();
        long pricingRequestId = pending.pricingRequestId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aHolds = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        try {
            Future<?> approveInFlight = pool.submit(() -> transactionTemplate.execute(s -> {
                new th.co.glr.hr.customerquotation.CustomerQuotationRepository(jdbc).lockPricingRequest(pricingRequestId);
                aHolds.countDown();
                try {
                    releaseA.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(aHolds.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> cancel = pool.submit(() -> transactionTemplate.execute(s -> {
                jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '30s'");
                return pricingRequestService.cancel(pricingRequestId, cancelRequest(), salesActor);
            }));

            assertThat(awaitWaiterOnRequestLock(pricingRequestId, cancel))
                .as("cancel queues for the request's advisory lock").isTrue();
            assertThatCode(() -> transactionTemplate.execute(s -> jdbc.queryForList("""
                    SELECT pricing_request_id FROM sales.pricing_request
                     WHERE pricing_request_id = :id FOR UPDATE NOWAIT
                    """, java.util.Map.of("id", pricingRequestId), Long.class)))
                .as("cancel queued before writing the request row").doesNotThrowAnyException();

            releaseA.countDown();
            cancel.get(30, TimeUnit.SECONDS);
            approveInFlight.get(10, TimeUnit.SECONDS);
        } finally {
            releaseA.countDown();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
        assertThat(pricingRequestStatusOf(pricingRequestId)).isEqualTo(PricingRequestStatus.CANCELLED);
        assertThat(statusOf(pending.id())).isEqualTo("SUPERSEDED");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // A customer-change revision retires the old request's quotation that is still waiting for
    // approval (owner ruling 2026-10-27), as it already retired a DRAFT. The new round's quotation
    // then needs ผจก.ขาย/CEO approval like any other. An ISSUED offer stays live until its
    // replacement is issued (reissue-through-CEO-chain ruling, 2026-08-13).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void customerChangeRevision_supersedesTheOldRequestsPendingApprovalQuotation_andApproveIsRefused() {
        DealQuotationDto pending = submittedQuotation();

        pricingRequestService.createCustomerChangeRevision(pending.pricingRequestId(), customerChangeRevision(), salesActor);

        assertThat(statusOf(pending.id())).isEqualTo("SUPERSEDED");
        assertThat(approvalQueueIds()).doesNotContain(pending.id());
        assertThatThrownBy(() -> quotationService.approve(pending.id(), new ApproveRequest(null), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(statusOf(pending.id())).isEqualTo("SUPERSEDED");
    }

    /** From QUOTATION_ISSUED: the pending quotation revision goes, the issued offer it would have
     * replaced stays. */
    @Test
    void customerChangeRevision_retiresAPendingQuotationRevision_andTheIssuedOfferStaysLive() {
        DealQuotationDto issued = issuedRound();
        DealQuotationDto revision = quotationService.createRevision(issued.id(), salesActor);
        quotationService.submit(revision.id(), salesActor);
        assertThat(statusOf(revision.id())).isEqualTo("PENDING_APPROVAL");

        pricingRequestService.createCustomerChangeRevision(issued.pricingRequestId(), customerChangeRevision(), salesActor);

        assertThat(statusOf(revision.id())).isEqualTo("SUPERSEDED");
        assertThat(statusOf(issued.id())).isEqualTo("ISSUED");
        assertThatThrownBy(() -> quotationService.approve(revision.id(), new ApproveRequest(null), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(statusOf(issued.id())).isEqualTo("ISSUED");
    }

    /** The approve guard on its own for a SUPERSEDED request, the same way as the CANCELLED test
     * above: built through the real revision, then the quotation put back to PENDING_APPROVAL. */
    @Test
    void approve_ofAPendingQuotationLeftOnASupersededRequest_isRefusedForEitherApprover_andWritesNothing() {
        DealQuotationDto live = issuedRound();
        DealQuotationDto pending = submittedQuotation();
        pricingRequestService.createCustomerChangeRevision(pending.pricingRequestId(), customerChangeRevision(), salesActor);
        jdbc.update("UPDATE sales.quotation SET doc_status = 'PENDING_APPROVAL' WHERE quotation_id = :id",
            java.util.Map.of("id", pending.id()));

        for (UserPrincipal approver : List.of(salesManagerActor, ceoActor)) {
            assertThatThrownBy(() -> quotationService.approve(pending.id(), new ApproveRequest(null), approver))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage())
                        .isEqualTo("คำขอราคาของใบเสนอราคานี้ถูกแทนที่ด้วยคำขอราคาฉบับแก้ไขแล้ว จึงอนุมัติไม่ได้");
                });
        }

        assertThat(statusOf(pending.id())).isEqualTo("PENDING_APPROVAL");
        assertThat(approverSnapshotCount(pending.id())).isZero();
        assertThat(statusOf(live.id())).isEqualTo("ISSUED");
        assertThat(supersededEventCount(ticketId, live.number(), pending.number())).isZero();
    }

    private PricingRequestRequests.CancelPricingRequestRequest cancelRequest() {
        return new PricingRequestRequests.CancelPricingRequestRequest("ลูกค้ายกเลิกโครงการ");
    }

    /** A customer-change revision of a request built by {@link #twoItemPricingRequest}: the same
     * recipient and the same two lines. */
    private PricingRequestRequests.CustomerChangeRevisionRequest customerChangeRevision() {
        return new PricingRequestRequests.CustomerChangeRevisionRequest(
            "ลูกค้าขอเปลี่ยนแปลงรายละเอียด", UUID.randomUUID().toString(), PricingRequestRecipient.DESIGNER, null,
            "Designer Co.", LocalDate.now().plusDays(14), new BigDecimal("1000.00"), "THB", "revision note",
            null, null, null, null, null, null, null, null,
            List.of(pricingItem("SCG", "Tile A-S2", "Factory A-S2", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B-S2", "Factory B-S2", new BigDecimal("5"))));
    }

    /** Waits until a backend is queued for {@code pricingRequestId}'s advisory lock (the
     * single-bigint form every request locker uses: classid 0, objid the id, objsubid 1), or until
     * {@code caller} finishes without ever queueing. */
    private boolean awaitWaiterOnRequestLock(long pricingRequestId, Future<?> caller) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && !caller.isDone()) {
            Long waiting = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_locks
                 WHERE locktype = 'advisory' AND NOT granted
                   AND classid::bigint = 0 AND objid::bigint = :id AND objsubid = 1
                """, java.util.Map.of("id", pricingRequestId), Long.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** The approvals queue: every PENDING_APPROVAL quotation, as the sales manager lists them. */
    private List<Long> approvalQueueIds() {
        return quotationService.search(List.of(QuotationStatus.PENDING_APPROVAL), false, salesManagerActor)
            .stream().map(DealQuotationDto::id).toList();
    }

    private String pricingRequestStatusOf(long pricingRequestId) {
        return jdbc.queryForObject("SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            java.util.Map.of("id", pricingRequestId), String.class);
    }

    private List<String> cancelEvents(long pricingRequestId) {
        return jdbc.queryForList("""
            SELECT from_status || '>' || to_status || ':' || message FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_REQUEST_CANCELLED'
            """, java.util.Map.of("id", pricingRequestId), String.class);
    }

    private long ticketEventCount(String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = :k",
            java.util.Map.of("t", ticketId, "k", kind), Long.class);
    }

    private long approverSnapshotCount(long quotationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation_approver_snapshot WHERE quotation_id = :id",
            java.util.Map.of("id", quotationId), Long.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private DealQuotationDto issuedRound() {
        return quotationService.approve(submittedQuotation().id(), new ApproveRequest(null), salesManagerActor);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT doc_status FROM sales.quotation WHERE quotation_id = :id",
            java.util.Map.of("id", id), String.class);
    }

    private int rawSeq = 0;

    private long insertRawQuotation(long onTicket, String number, String origin, String status) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, origin, total_amount,
                                         quotation_revision_no, quotation_version)
            VALUES (:t, :n, :by, :s, :o, 1000.00, 1, :v) RETURNING quotation_id
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("t", onTicket).addValue("n", number).addValue("by", salesRepId)
                .addValue("s", status).addValue("o", origin).addValue("v", 900 + rawSeq++),
            Long.class);
    }

    private long supersededEventCount(long onTicket, String oldNumber, String newNumber) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = :k AND message = :m
            """,
            java.util.Map.of("t", onTicket, "k", th.co.glr.hr.ticket.TicketEventKind.DEAL_QUOTATION_SUPERSEDED,
                "m", "ใบ " + oldNumber + " ถูกแทนที่ด้วย " + newNumber),
            Long.class);
    }

    private long countWithStatus(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :t AND doc_status = :s",
            java.util.Map.of("t", ticketId, "s", status), Long.class);
    }

    private DealQuotationRequests.UpsertDealQuotationRequest upsertWithValidityDays(DealQuotationDto quotation, int days) {
        List<DealQuotationRequests.ItemInput> items = quotation.items().stream()
            .map(this::existingTileInput).toList();
        return baseUpsert(quotation, days, items);
    }

    /** Changes the FIRST item's discount away from the CEO's own decision — triggers
     * {@code priceChangedFromCeo} on that line (and, at the header, nothing — priceMode itself is
     * unchanged; see {@link #upsertWithDirectNetMode} for that trigger instead). */
    private DealQuotationRequests.UpsertDealQuotationRequest upsertWithChangedDiscount(DealQuotationDto quotation, int days) {
        DealQuotationItemDto first = quotation.items().get(0);
        BigDecimal changedDiscount = (first.discountPct() == null ? BigDecimal.ZERO : first.discountPct())
            .add(new BigDecimal("5"));
        List<DealQuotationRequests.ItemInput> items = new java.util.ArrayList<>();
        items.add(tileItemWithDiscount(first, changedDiscount));
        for (int i = 1; i < quotation.items().size(); i++) {
            items.add(existingTileInput(quotation.items().get(i)));
        }
        return baseUpsert(quotation, days, items);
    }

    /** Switches the header price mode from NET (this fixture's decision) to DIRECT_NET —
     * triggers {@code priceModeChangedFromCeo}. Every item needs a {@code directNetPrice} once in
     * this mode. */
    private DealQuotationRequests.UpsertDealQuotationRequest upsertWithDirectNetMode(DealQuotationDto quotation) {
        List<DealQuotationRequests.ItemInput> items = quotation.items().stream()
            .map(item -> directNetTileItem(item, item.netUnitPrice())).toList();
        return new DealQuotationRequests.UpsertDealQuotationRequest(quotation.contactId(), quotation.deptCode(),
            quotation.unitCode(), quotation.offerDate(), quotation.depositPercent(), quotation.remainderMode(),
            quotation.creditDays(), 30, quotation.validityMode(), quotation.validityUntil(),
            quotation.customerNotes(), "DIRECT_NET", quotation.documentLanguage(), quotation.currency(),
            quotation.printedByDisplayId(), quotation.salesRepDisplayId(), quotation.projectName(),
            quotation.omitContactHonorific(), quotation.fullPaymentTerm(), items);
    }

    /** Drops every item after the first — the fixture's decision links TWO items, so this leaves
     * exactly one CEO-linked line dropped, triggering {@code itemsRemovedFromCeoCount > 0}. */
    private DealQuotationRequests.UpsertDealQuotationRequest upsertWithOnlyFirstItem(DealQuotationDto quotation, int days) {
        List<DealQuotationRequests.ItemInput> items = List.of(existingTileInput(quotation.items().get(0)));
        return baseUpsert(quotation, days, items);
    }

    private DealQuotationRequests.UpsertDealQuotationRequest baseUpsert(
        DealQuotationDto quotation, int days, List<DealQuotationRequests.ItemInput> items) {
        return new DealQuotationRequests.UpsertDealQuotationRequest(quotation.contactId(), quotation.deptCode(),
            quotation.unitCode(), quotation.offerDate(), quotation.depositPercent(), quotation.remainderMode(),
            quotation.creditDays(), days, quotation.validityMode(), quotation.validityUntil(),
            quotation.customerNotes(), quotation.priceMode(), quotation.documentLanguage(), quotation.currency(),
            quotation.printedByDisplayId(), quotation.salesRepDisplayId(), quotation.projectName(),
            quotation.omitContactHonorific(), quotation.fullPaymentTerm(), items);
    }

    private DealQuotationRequests.ItemInput existingTileInput(DealQuotationItemDto item) {
        return new DealQuotationRequests.ItemInput(item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(),
            item.sqmPerPiece(), item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(),
            item.wastageValue(), item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(),
            item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            "TILE", null, null, null, item.specialPriceSqm(), null, null, null, null,
            item.id(), item.sqmPerBox(), true);
    }

    private DealQuotationRequests.ItemInput tileItemWithDiscount(DealQuotationItemDto item, BigDecimal discountPct) {
        return new DealQuotationRequests.ItemInput(item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(),
            item.sqmPerPiece(), item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(),
            item.wastageValue(), item.piecesPerBox(), item.unitPrice(), discountPct, item.originCountry(),
            item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            "TILE", null, null, null, item.specialPriceSqm(), null, null, null, null,
            item.id(), item.sqmPerBox(), true);
    }

    private DealQuotationRequests.ItemInput directNetTileItem(DealQuotationItemDto item, BigDecimal directNetPrice) {
        return new DealQuotationRequests.ItemInput(item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(),
            item.sqmPerPiece(), item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(),
            item.wastageValue(), item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(),
            item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            "TILE", null, null, null, null, directNetPrice, null, null, null,
            item.id(), item.sqmPerBox(), true);
    }

    private DealQuotationDto submittedQuotation() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(approvedDecision().pricingRequestId(), salesActor);
        // MAJOR-4 fix (Opus re-review, 2026-09-20): submit now REQUIRES a validity (D5's own
        // expiry sweep needs validity_date to ever fire) — this fixture's synthetic pricing
        // request carries no GLA-125 validityDays header term, so every test built on this shared
        // helper must set one explicitly.
        DealQuotationDto withValidity = quotationService.update(draft.id(), upsertWithValidityDays(draft, 30), salesActor);
        return quotationService.submit(withValidity.id(), salesActor);
    }

    private PricingDecisionDto approvedDecision() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto started = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        List<UpdatePricingDecisionItemRequest> updates = new java.util.ArrayList<>(List.of(
            discountItem(started.items().get(0).id(), new BigDecimal("10")),
            discountItem(started.items().get(1).id(), new BigDecimal("5"))));
        PricingDecisionDto afterMode = decisionService.update(started.id(),
            new UpdatePricingDecisionRequest(null, "NET", updates), ceoActor);
        return decisionService.approve(afterMode.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
    }

    /**
     * <b>THE DEFECT the versioning design exists to fix</b> — owner ruling 2026-10-17,
     * {@code .design/quote-stage-gates/VERSIONING.md}: the base number belongs to the DEAL, so a
     * second pricing round on the same deal continues the family instead of starting a new one.
     *
     * <p>A lookup scoped to ONE pricing request (the old {@code findLatestForPricingRequest}) would
     * see none of the first request's quotations on a second request and fall through to a fresh
     * base, costing the rep their version number on every รอบราคาใหม่. The family is read from the
     * deal instead.
     *
     * <p>The first quotation is cancelled because a live one blocks a second create. That is the
     * real flow, not a workaround: the rep abandons the document precisely BECAUSE they are going
     * back for a new price, which is the moment the number is currently lost.
     *
     * <p>Prod note (audit 2026-10-17): zero PRICING_REQUEST-origin quotations exist in production,
     * so the defect never reached a customer; this test keeps it from shipping the day the route is
     * first used.
     */
    @Test
    void createFromPricingRequest_aSecondRoundOnTheSameDeal_continuesTheFamily() {
        DealQuotationDto first = quotationService.createFromPricingRequest(
            approvedDecision().pricingRequestId(), salesActor);
        String base = DealQuotationRepository.baseNumber(first.number(), first.revisionNo());
        assertThat(first.revisionNo()).isEqualTo(1);
        quotationService.cancel(first.id(), new DealQuotationRequests.CancelRequest(null), salesActor);

        DealQuotationDto second = quotationService.createFromPricingRequest(
            approvedDecision().pricingRequestId(), salesActor);

        assertThat(DealQuotationRepository.baseNumber(second.number(), second.revisionNo()))
            .as("the deal's family, not a fresh base").isEqualTo(base);
        assertThat(second.revisionNo()).isEqualTo(2);
        assertThat(second.number()).isEqualTo(base + "-2");
    }

    /**
     * The cross-route case, scoped honestly. One-pricing-route-per-deal makes a deal holding a LIVE
     * document of each origin unreachable — {@code DirectQuotationLocks}' own Javadoc calls that
     * "the mixed-origin deal nobody has ever produced" — so this covers the reachable shape: the rep
     * switches route after abandoning the first document, and the family survives the switch.
     */
    @Test
    void createFromPricingRequest_afterADirectQuotationWasCancelled_continuesTheSameFamily() {
        DealQuotationDto direct = quotationService.create(ticketId, directDraft(), salesActor);
        String base = DealQuotationRepository.baseNumber(direct.number(), direct.revisionNo());
        quotationService.cancel(direct.id(), new DealQuotationRequests.CancelRequest(null), salesActor);

        DealQuotationDto fromRequest = quotationService.createFromPricingRequest(
            approvedDecision().pricingRequestId(), salesActor);

        assertThat(DealQuotationRepository.baseNumber(fromRequest.number(), fromRequest.revisionNo()))
            .as("one family per deal, across both origins").isEqualTo(base);
        assertThat(fromRequest.revisionNo()).isEqualTo(2);
    }

    /** A DEAL_DIRECT draft on this class's own deal. */
    private DealQuotationRequests.UpsertDealQuotationRequest directDraft() {
        DealQuotationRequests.ItemInput item = new DealQuotationRequests.ItemInput(
            null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal("0.36"),
            WastageCalculator.QUANTITY_MODE_PIECES, null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 1,
            new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก", 30, 45, null);
        return new DealQuotationRequests.UpsertDealQuotationRequest(
            null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30, "ครอบครัวเลขที่", List.of(item))
            .withRecipientType("OWNER");
    }

    private UpdatePricingDecisionItemRequest discountItem(long itemId, BigDecimal discountPct) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            discountPct, false, null, false, null, false);
    }

    private long twoItemSubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, twoItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    private PricingRequestRequests.CreatePricingRequestRequest twoItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "pcr quotation s2 request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A-S2", "Factory A-S2", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B-S2", "Factory B-S2", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A-S2".equals(factory) ? catalogProductIdFactoryA
            : "Factory B-S2".equals(factory) ? catalogProductIdFactoryB : null;
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, "White", "Matte", "60x60", factory, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qty.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
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

    // ── Signature rendering helpers — mirrors DealQuotationApproverSnapshotIntegrationTest's own
    // identically-named private helpers (that class's own Javadoc explains each step). ─────────

    private byte[] render(DealQuotationDto q) {
        return quotationService.renderXlsx(q.id(), salesActor);
    }

    private static MockMultipartFile png(byte[] bytes) {
        return new MockMultipartFile("file", "sig.png", "image/png", bytes);
    }

    private static byte[] zigZagSignaturePng() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            280, 110, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(120, 20, 30));
        g.setStroke(new java.awt.BasicStroke(4f));
        for (int x = 20; x < 260; x += 20) {
            g.drawLine(x, (x / 20) % 2 == 0 ? 30 : 80, x + 20, (x / 20) % 2 == 0 ? 80 : 30);
        }
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int labelsRow(Sheet sheet) {
        for (var row : sheet) {
            var cell = row.getCell(0);
            if (cell != null && cell.getCellType() == CellType.STRING
                && cell.getStringCellValue().contains("ผู้พิมพ์") && cell.getStringCellValue().contains("ผู้สั่งซื้อ")) {
                return row.getRowNum();
            }
        }
        throw new AssertionError("signature labels row not found in the rendered XLS");
    }

    private static Sheet sheet(org.apache.poi.ss.usermodel.Workbook wb) {
        return wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
    }

    private static byte[] signatureBytes(byte[] xls) {
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            Sheet sheet = sheet(wb);
            int labels = labelsRow(sheet);
            byte[] found = null;
            for (var shape : ((HSSFSheet) sheet).getDrawingPatriarch().getChildren()) {
                if (shape instanceof HSSFPicture pic
                    && pic.getClientAnchor().getRow1() <= labels
                    && pic.getClientAnchor().getRow2() >= labels
                    && pic.getClientAnchor().getRow2() <= labels + 1) {
                    assertThat(found).as("at most one signature picture").isNull();
                    found = pic.getPictureData().getData();
                }
            }
            return found;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
