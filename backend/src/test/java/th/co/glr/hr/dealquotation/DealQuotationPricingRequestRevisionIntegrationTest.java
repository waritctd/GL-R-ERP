package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
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
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB coverage for PR B: a sales rep REVISES a PRICING_REQUEST-origin quotation (ISSUED or
 * REVISION_REQUESTED), repeatedly, through the real DealQuotationService + DealQuotationRepository
 * on real Postgres. The revision is a new DRAFT that keeps origin / pricing_request_id /
 * pricing_decision_id / item decision links, continues the {base}-{n} number family, may change the
 * recipient (owner / buyer), and supersedes its parent when it is itself issued. Only the OWNING
 * sales rep may create one (same gate as recordOutcome, narrower than update/submit/cancel).
 *
 * <p>Fixture is a copy of DealQuotationPricingRequestApprovalIntegrationTest (same reason that file
 * gives for duplicating rather than sharing). The approval step lives in ONE helper
 * ({@link #approveToIssued}) so a change to the approval rule touches one place.
 */
class DealQuotationPricingRequestRevisionIntegrationTest extends AbstractPostgresIntegrationTest {
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
    private UserPrincipal otherSalesActor;
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
        otherSalesActor = actor(
            createEmployee(employees, "พนักงานขายอีกคน", "sales2-s2@glr.co.th", "SALES", "แผนกขาย"), "sales");
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
    // AUTHZ FIRST — wrong-way-round. Only the OWNING sales rep may revise. Every case uses an
    // ISSUED source, so the 403 can only come from the role/owner gate, never the status gate.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createRevision_byCeo_forbidden_nothingWritten() {
        assertRefusedWith403(issuedQuotation(), ceoActor);
    }

    @Test
    void createRevision_bySalesManager_forbidden_nothingWritten() {
        // sales_manager may edit/submit/cancel ANY deal's PR-origin quotation, but revising is the
        // owning rep's act (like recordOutcome) — the manager's job is to approve the result.
        assertRefusedWith403(issuedQuotation(), salesManagerActor);
    }

    @Test
    void createRevision_byNonOwningSales_forbidden_nothingWritten() {
        assertRefusedWith403(issuedQuotation(), otherSalesActor);
    }

    @Test
    void createRevision_byImportAccountQc_forbidden_nothingWritten() {
        DealQuotationDto issued = issuedQuotation();
        assertRefusedWith403(issued, importActor);
        assertRefusedWith403(issued, accountActor);
        assertRefusedWith403(issued, qcActor);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Which statuses may be revised: ISSUED and REVISION_REQUESTED, nothing else.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createRevision_ofIssued_createsDraftChild_keepingOriginPricingLinksItemLinksAndNumberFamily() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);

        assertThat(revision.id()).isNotEqualTo(source.id());
        assertThat(revision.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(revision.origin()).isEqualTo("PRICING_REQUEST");
        assertThat(revision.pricingRequestId()).isEqualTo(source.pricingRequestId());
        assertThat(revision.parentQuotationId()).isEqualTo(source.id());
        assertThat(revision.revisionNo()).isEqualTo(source.revisionNo() + 1);
        String base = DealQuotationRepository.baseNumber(source.number(), source.revisionNo());
        assertThat(revision.number()).isEqualTo(DealQuotationRepository.revisionNumber(base, source.revisionNo() + 1));
        assertThat(revision.number()).isNotEqualTo(source.number());
        // pricing_decision_id is frozen on the row (not on the DTO) — read it straight from the table.
        assertThat(decisionIdOf(revision.id())).isNotNull().isEqualTo(decisionIdOf(source.id()));
        // Items copied verbatim, WITH their pricing-request/decision item links — a revision that
        // dropped the links would silently turn off the CEO-price comparison.
        assertThat(revision.items()).hasSameSizeAs(source.items());
        assertThat(itemLinkPairs(revision.id())).isNotEmpty().isEqualTo(itemLinkPairs(source.id()));
        assertThat(revision.items()).allSatisfy(item -> assertThat(item.priceChangedFromCeo()).isFalse());
        assertThat(revision.priceModeChangedFromCeo()).isFalse();
        assertThat(revision.itemsRemovedFromCeoCount()).isZero();
        // Recipient is carried over by default.
        assertThat(revision.recipientType()).isEqualTo(source.recipientType());
        // The source is untouched until the revision itself is issued.
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    @Test
    void createRevision_ofRevisionRequested_succeeds_andIssuingTheRevisionSupersedesIt() {
        DealQuotationDto source = issuedQuotation();
        quotationService.recordOutcome(source.id(),
            new DealQuotationRequests.RecordOutcomeRequest("REVISION_REQUESTED", "ลูกค้าขอแก้ราคา", null), salesActor);
        assertThat(quotationService.get(source.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.REVISION_REQUESTED);

        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);
        assertThat(revision.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(revision.parentQuotationId()).isEqualTo(source.id());
        // Still REVISION_REQUESTED (not yet replaced) while the child is open.
        assertThat(quotationService.get(source.id(), salesActor).docStatus())
            .isEqualTo(QuotationStatus.REVISION_REQUESTED);

        DealQuotationDto issuedRevision = submitAndIssue(revision);
        assertThat(issuedRevision.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    @Test
    void createRevision_ofDraft_conflict() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(
            approvedDecision().pricingRequestId(), salesActor);
        assertConflictNothingWritten(draft, 1);
    }

    @Test
    void createRevision_ofPendingApproval_conflict() {
        assertConflictNothingWritten(submittedQuotation(), 1);
    }

    /** ACCEPTED is deliberately NOT revisable: it is the deal's finalized quotation (R8, and
     * recordOutcome's ACCEPTED already advanced the pricing request to QUOTATION_ACCEPTED and fed
     * order confirmation) — revising it would fork a deal the customer already said yes to. */
    @Test
    void createRevision_ofAccepted_conflict() {
        DealQuotationDto source = issuedQuotation();
        quotationService.recordOutcome(source.id(),
            new DealQuotationRequests.RecordOutcomeRequest("ACCEPTED", null, null), salesActor);
        assertConflictNothingWritten(source, 1);
    }

    /** REJECTED / EXPIRED already have their own recovery path (createFromPricingRequest — a fresh
     * quotation continuing the number family), so they are not revisable. */
    @Test
    void createRevision_ofRejected_conflict() {
        DealQuotationDto source = issuedQuotation();
        quotationService.recordOutcome(source.id(),
            new DealQuotationRequests.RecordOutcomeRequest("REJECTED", null, null), salesActor);
        assertConflictNothingWritten(source, 1);
    }

    @Test
    void createRevision_ofExpired_conflict() {
        DealQuotationDto source = issuedQuotation();
        jdbc.update("UPDATE sales.quotation SET validity_date = CURRENT_DATE - 1 WHERE quotation_id = :id",
            java.util.Map.of("id", source.id()));
        quotationService.expireOverdueQuotations();
        assertConflictNothingWritten(source, 1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // One open child at a time
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createRevision_whileAChildIsDraftOrPending_conflict_andNoSecondRowMinted() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto child = quotationService.createRevision(source.id(), salesActor);
        assertThat(quotationRepository.hasOpenRevision(source.id())).isTrue();

        assertThatThrownBy(() -> quotationService.createRevision(source.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(prOriginRowCount()).isEqualTo(2);

        quotationService.update(child.id(), upsertWithValidityDays(child, 30), salesActor);
        quotationService.submit(child.id(), salesActor); // PENDING_APPROVAL
        assertThatThrownBy(() -> quotationService.createRevision(source.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(prOriginRowCount()).isEqualTo(2);
    }

    @Test
    void createRevision_afterTheChildWasCancelled_succeeds_andNumberKeepsCounting() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto first = quotationService.createRevision(source.id(), salesActor);
        quotationService.cancel(first.id(), new DealQuotationRequests.CancelRequest("ยกเลิกฉบับแก้ไข"), salesActor);
        assertThat(quotationRepository.hasOpenRevision(source.id())).isFalse();

        DealQuotationDto second = quotationService.createRevision(source.id(), salesActor);
        // A cancelled row never frees its number slot: the next one is n+2, not a duplicate-key 500.
        assertThat(second.revisionNo()).isEqualTo(first.revisionNo() + 1);
        assertThat(second.number()).isNotEqualTo(first.number());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Issuing the revision: parent SUPERSEDED, PR status not regressed, outcome on the right row
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issuingTheRevision_supersedesTheParent_andLeavesThePricingRequestAtQuotationIssued() {
        DealQuotationDto source = issuedQuotation();
        long prId = source.pricingRequestId();
        assertThat(pricingRequests.findSummary(prId).orElseThrow().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);
        DealQuotationDto issuedRevision = submitAndIssue(revision);

        assertThat(issuedRevision.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        // Only the FIRST issue moves the pricing request; a revision's issue must not regress/re-run it.
        assertThat(pricingRequests.findSummary(prId).orElseThrow().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
        assertThat(quotationRepository.hasOpenRevision(source.id())).isFalse();
    }

    @Test
    void parentStaysIssued_whileTheChildIsDraft_pending_orRejectedBackToDraft() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto child = quotationService.createRevision(source.id(), salesActor);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);

        quotationService.update(child.id(), upsertWithValidityDays(child, 30), salesActor);
        quotationService.submit(child.id(), salesActor);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);

        quotationService.reject(child.id(), new RejectRequest("แก้อีกครั้ง"), ceoActor);
        assertThat(quotationService.get(child.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    @Test
    void recordOutcome_onTheNewIssuedRevision_works_andOnTheSupersededParentIsRefused() {
        DealQuotationDto source = issuedQuotation();
        long prId = source.pricingRequestId();
        DealQuotationDto issuedRevision = submitAndIssue(quotationService.createRevision(source.id(), salesActor));

        // The superseded parent can no longer record anything.
        assertThatThrownBy(() -> quotationService.recordOutcome(source.id(),
            new DealQuotationRequests.RecordOutcomeRequest("ACCEPTED", null, null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        DealQuotationDto accepted = quotationService.recordOutcome(issuedRevision.id(),
            new DealQuotationRequests.RecordOutcomeRequest("ACCEPTED", "ตกลง", null), salesActor);
        assertThat(accepted.docStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(pricingRequests.findSummary(prId).orElseThrow().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);
    }

    @Test
    void revisingTheRevision_again_chainsNumbers_andEachIssueSupersedesThePrevious() {
        DealQuotationDto original = issuedQuotation();
        DealQuotationDto rev1 = submitAndIssue(quotationService.createRevision(original.id(), salesActor));
        DealQuotationDto rev2Draft = quotationService.createRevision(rev1.id(), salesActor);

        assertThat(rev2Draft.parentQuotationId()).isEqualTo(rev1.id());
        assertThat(rev2Draft.revisionNo()).isEqualTo(original.revisionNo() + 2);
        String base = DealQuotationRepository.baseNumber(original.number(), original.revisionNo());
        assertThat(rev2Draft.number()).isEqualTo(DealQuotationRepository.revisionNumber(base, original.revisionNo() + 2));
        assertThat(rev2Draft.origin()).isEqualTo("PRICING_REQUEST");
        assertThat(itemLinkPairs(rev2Draft.id())).isNotEmpty().isEqualTo(itemLinkPairs(original.id()));

        DealQuotationDto rev2 = submitAndIssue(rev2Draft);
        assertThat(rev2.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(quotationService.get(rev1.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        assertThat(quotationService.get(original.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        // Exactly one live document remains on the pricing request.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation WHERE pricing_request_id = :id AND origin = 'PRICING_REQUEST'
               AND doc_status = 'ISSUED'
            """, java.util.Map.of("id", original.pricingRequestId()), Integer.class)).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Recipient: a revision may be re-addressed to the owner / buyer. sales.quotation already
    // carries recipient_type/recipient_label (V52) — no schema change. Today update() refuses any
    // recipient on a PRICING_REQUEST row; PR B opens that ONLY for a REVISION draft.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void revisionDraft_mayChangeRecipientToOwner_parentKeepsItsOwn() {
        DealQuotationDto source = issuedQuotation();
        assertThat(source.recipientType()).isEqualTo("DESIGNER");
        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);

        DealQuotationDto updated = quotationService.update(revision.id(),
            baseUpsert(revision, 30, itemInputs(revision)).withRecipientType("OWNER"), salesActor);

        assertThat(updated.recipientType()).isEqualTo("OWNER");
        // Label follows the new party (not the stale designer name) — same Thai label DEAL_DIRECT uses.
        assertThat(updated.recipientLabel()).isEqualTo(th.co.glr.hr.ticket.QuotationRecipient.thaiLabel("OWNER"));
        assertThat(quotationService.get(source.id(), salesActor).recipientType()).isEqualTo("DESIGNER");
        // Still the same origin / chain.
        assertThat(updated.origin()).isEqualTo("PRICING_REQUEST");
        assertThat(updated.parentQuotationId()).isEqualTo(source.id());
    }

    @Test
    void revisionDraft_recipientChange_toUnspecifiedOrGarbage_isBadRequest() {
        DealQuotationDto revision = quotationService.createRevision(issuedQuotation().id(), salesActor);
        for (String bad : List.of("UNSPECIFIED", "NOBODY")) {
            assertThatThrownBy(() -> quotationService.update(revision.id(),
                baseUpsert(revision, 30, itemInputs(revision)).withRecipientType(bad), salesActor))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(quotationService.get(revision.id(), salesActor).recipientType()).isEqualTo("DESIGNER");
    }

    /** The first-issue draft (no parent) keeps today's rule: its recipient belongs to its คำขอราคา. */
    @Test
    void firstIssueDraft_recipientChange_stillRefused() {
        DealQuotationDto draft = quotationService.createFromPricingRequest(
            approvedDecision().pricingRequestId(), salesActor);
        assertThatThrownBy(() -> quotationService.update(draft.id(),
            baseUpsert(draft, 30, itemInputs(draft)).withRecipientType("OWNER"), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void issuingARevisionAddressedToOwner_advancesTheStageToQuoteOwner_usingTheQuotationsRecipient() {
        DealQuotationDto source = issuedQuotation();
        // First issue was to the DESIGNER: stage S4.
        assertThat(tickets.findById(ticketId).orElseThrow().summary().salesStage()).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);

        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);
        quotationService.update(revision.id(),
            baseUpsert(revision, 30, itemInputs(revision)).withRecipientType("OWNER"), salesActor);
        DealQuotationDto issued = submitAndIssue(revision);

        assertThat(issued.recipientType()).isEqualTo("OWNER");
        assertThat(tickets.findById(ticketId).orElseThrow().summary().salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        // The pricing request's own recipient is NOT rewritten (it is the request's, not the quotation's).
        assertThat(pricingRequests.findSummary(source.pricingRequestId()).orElseThrow().recipientType())
            .isEqualTo("DESIGNER");
    }

    @Test
    void issuingARevisionAddressedToBuyer_advancesTheStageToQuoteBuyer() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto revision = quotationService.createRevision(source.id(), salesActor);
        quotationService.update(revision.id(),
            baseUpsert(revision, 30, itemInputs(revision)).withRecipientType("BUYER"), salesActor);
        DealQuotationDto issued = submitAndIssue(revision);

        assertThat(issued.recipientType()).isEqualTo("BUYER");
        assertThat(tickets.findById(ticketId).orElseThrow().summary().salesStage()).isEqualTo(DealStage.QUOTE_BUYER);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // APPROVAL of a revision: ONE approval, by EITHER the sales_manager OR the CEO, issues it via
    // the existing single compare-and-set. A revision NEVER auto-issues on submit.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void revision_submit_leavesItPendingApproval_neverAutoIssues() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto pending = pendingRevision(source);
        assertThat(pending.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(pending.approvedById()).isNull();
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    @Test
    void revision_approvedBySalesManager_issues_andSupersedesParent() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto issued = quotationService.approve(
            pendingRevision(source).id(), new ApproveRequest(null), salesManagerActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(issued.approvedById()).isEqualTo(salesManagerId);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    @Test
    void revision_approvedByCeo_issues_andSupersedesParent() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto issued = quotationService.approve(
            pendingRevision(source).id(), new ApproveRequest(null), ceoActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(issued.approvedById()).isEqualTo(ceoUserId);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    @Test
    void revision_approve_byOwningSales_forbidden_stillPending() {
        assertRevisionApproveForbidden(salesActor);
    }

    @Test
    void revision_approve_byNonOwningSales_forbidden_stillPending() {
        assertRevisionApproveForbidden(otherSalesActor);
    }

    @Test
    void revision_approve_byImportAccountQc_forbidden_stillPending() {
        assertRevisionApproveForbidden(importActor);
        assertRevisionApproveForbidden(accountActor);
        assertRevisionApproveForbidden(qcActor);
    }

    @Test
    void revision_rejectBySalesManager_returnsToDraft_parentStaysIssued() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto pending = pendingRevision(source);
        DealQuotationDto rejected = quotationService.reject(pending.id(), new RejectRequest("แก้ราคา"), salesManagerActor);
        assertThat(rejected.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    @Test
    void revision_rejectByCeo_returnsToDraft_andResubmitReusesTheSameRow() {
        DealQuotationDto pending = pendingRevision(issuedQuotation());
        DealQuotationDto rejected = quotationService.reject(pending.id(), new RejectRequest("ทบทวน"), ceoActor);
        assertThat(rejected.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        DealQuotationDto resubmitted = quotationService.submit(pending.id(), salesActor);
        assertThat(resubmitted.id()).isEqualTo(pending.id());
        assertThat(resubmitted.number()).isEqualTo(pending.number());
        assertThat(resubmitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    /**
     * ⚠️ OPEN USER RULING — pins TODAY's behaviour: requireCeoApprovalIfChanged still applies to a
     * revision. If a revision's prices/lines differ from the CEO's decision, a sales_manager gets 403
     * and ONLY the CEO can issue it. This sits in tension with "either the CEO or the sales manager
     * may approve a revision"; the user has NOT yet ruled whether "either" overrides it. If they
     * rule that it does, this test is the one that flips (manager approval of a changed revision
     * would issue it).
     */
    @Test
    void revision_withPricesChangedFromCeoDecision_salesManagerForbidden_onlyCeoIssues_PENDING_USER_RULING() {
        DealQuotationDto source = issuedQuotation();
        DealQuotationDto draft = quotationService.createRevision(source.id(), salesActor);
        DealQuotationDto changed = quotationService.update(draft.id(), upsertWithChangedDiscount(draft, 30), salesActor);
        assertThat(changed.items().get(0).priceChangedFromCeo()).isTrue();
        DealQuotationDto submitted = quotationService.submit(changed.id(), salesActor);

        assertThatThrownBy(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(quotationService.get(submitted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);

        DealQuotationDto issued = quotationService.approve(submitted.id(), new ApproveRequest(null), ceoActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Test-local helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The ONE place that says how a PENDING_APPROVAL PR-origin quotation becomes ISSUED: a single
     * sales_manager approval (the CEO could equally do it when prices are unchanged). */
    private DealQuotationDto approveToIssued(DealQuotationDto submitted) {
        return quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
    }

    /** A revision of {@code source}, saved with a 30-day validity and submitted — PENDING_APPROVAL. */
    private DealQuotationDto pendingRevision(DealQuotationDto source) {
        DealQuotationDto draft = quotationService.createRevision(source.id(), salesActor);
        DealQuotationDto saved = quotationService.update(draft.id(), baseUpsertKeepingRecipient(draft), salesActor);
        return quotationService.submit(saved.id(), salesActor);
    }

    private void assertRevisionApproveForbidden(UserPrincipal actor) {
        DealQuotationDto pending = pendingRevision(issuedQuotation());
        assertThatThrownBy(() -> quotationService.approve(pending.id(), new ApproveRequest(null), actor))
            .as("approve by %s", actor.role())
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        DealQuotationDto after = quotationService.get(pending.id(), salesActor);
        assertThat(after.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(after.approvedById()).isNull();
    }

    private DealQuotationDto submitAndIssue(DealQuotationDto draft) {
        DealQuotationDto withValidity = quotationService.update(
            draft.id(), baseUpsertKeepingRecipient(draft), salesActor);
        DealQuotationDto submitted = quotationService.submit(withValidity.id(), salesActor);
        return approveToIssued(submitted);
    }

    /** A save that keeps everything (incl. a recipient already chosen on the draft) and sets a 30-day validity. */
    private DealQuotationRequests.UpsertDealQuotationRequest baseUpsertKeepingRecipient(DealQuotationDto draft) {
        return baseUpsert(draft, 30, itemInputs(draft));
    }

    private List<DealQuotationRequests.ItemInput> itemInputs(DealQuotationDto quotation) {
        return quotation.items().stream().map(this::existingTileInput).toList();
    }

    private DealQuotationDto issuedQuotation() {
        DealQuotationDto issued = approveToIssued(submittedQuotation());
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        return issued;
    }

    private int prOriginRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :t AND origin = 'PRICING_REQUEST'",
            java.util.Map.of("t", ticketId), Integer.class);
    }

    private Long decisionIdOf(long quotationId) {
        return jdbc.queryForObject("SELECT pricing_decision_id FROM sales.quotation WHERE quotation_id = :id",
            java.util.Map.of("id", quotationId), Long.class);
    }

    /** Order-independent (pricing_request_item_id, pricing_decision_item_id) pairs of a quotation's linked rows. */
    private java.util.Set<String> itemLinkPairs(long quotationId) {
        return quotationRepository.findItemLinks(quotationId).stream()
            .map(l -> l.pricingRequestItemId() + ":" + l.pricingDecisionItemId())
            .collect(java.util.stream.Collectors.toSet());
    }

    private void assertRefusedWith403(DealQuotationDto source, UserPrincipal actor) {
        int before = prOriginRowCount();
        assertThatThrownBy(() -> quotationService.createRevision(source.id(), actor))
            .as("createRevision by %s", actor.role())
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(prOriginRowCount()).isEqualTo(before);
        assertThat(quotationService.get(source.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    private void assertConflictNothingWritten(DealQuotationDto source, int expectedRowsOnTicket) {
        int before = prOriginRowCount();
        assertThatThrownBy(() -> quotationService.createRevision(source.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(prOriginRowCount()).isEqualTo(before).isEqualTo(expectedRowsOnTicket);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

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
