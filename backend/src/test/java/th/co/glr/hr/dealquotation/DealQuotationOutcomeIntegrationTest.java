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
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RecordOutcomeRequest;
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
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB coverage for customer outcome (R9) on a PRICING_REQUEST-origin
 * quotation, the R8 one-finalized-quotation-per-deal guard across BOTH คำขอราคา origins (this new
 * engine AND the legacy {@code customerquotation/} chain, which share ONE {@code sales.quotation}
 * table), and confirm order (R9, {@code OrderConfirmationService#confirmOrder} reused unchanged).
 *
 * <p>Fixture setup is a trimmed copy of {@code DealQuotationPricingRequestApprovalIntegrationTest}
 * — same helper shapes — PLUS the legacy {@link CustomerQuotationService} and
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
        EmployeeSignatureRepository signatureRepository = new EmployeeSignatureRepository(jdbc);
        quotationService = new DealQuotationService(quotationRepository, tickets, customers,
            new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc),
            signatureRepository, new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        legacyQuotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService.wirePricingRequestDependencies(pricingRequests, decisionRepository, legacyQuotationRepository);
        quotationService.wireTicketService(ticketService);

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

    // outcome authz: mirrors CustomerQuotationService#recordOutcome's gate EXACTLY (SALES_ROLES = Set.of("sales")
    // only — sales_manager is NOT included there, so it is not included here either). Every non-owner case asserts
    // NOTHING was written (still ISSUED).

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

    /** Deliberate divergence check (stated in the brief): this origin's own EDIT_ROLES lets sales_manager
     * edit/submit/cancel ANY deal, but recordOutcome mirrors the LEGACY gate instead, which has no such carve-out
     * — sales_manager must be refused here too. */
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

    // Fixture helpers

    private DealQuotationDto issuedNewOriginQuotation(String recipient, BigDecimal qty) {
        return issuedNewOriginQuotation(recipient, qty, null);
    }

    /** {@code depositPercent} null keeps the percent the quotation was created with. */
    private DealQuotationDto issuedNewOriginQuotation(String recipient, BigDecimal qty, Integer depositPercent) {
        PricingDecisionDto decision = approvedDecision(recipient, qty);
        DealQuotationDto draft = quotationService.createFromPricingRequest(decision.pricingRequestId(), salesActor);
        DealQuotationDto withValidity = quotationService.update(draft.id(), upsertWithValidityDays(draft, 30,
            depositPercent != null ? depositPercent : draft.depositPercent()), salesActor);
        DealQuotationDto submitted = quotationService.submit(withValidity.id(), salesActor);
        return quotationService.approve(submitted.id(), new ApproveRequest(null), salesManagerActor);
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

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(Long sourceTicketItemId, BigDecimal qty) {
        return new PricingRequestRequests.PricingRequestItemRequest(
            sourceTicketItemId, catalogProductId, null, "SCG", "Tile A-S3", "SCG Tile A-S3", "White", "Matte",
            "60x60", FACTORY,
            null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qty.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private long submittedCostingReadyRequest(String recipient, BigDecimal qty) {
        // Links back to the ticket's own ticket_item — the realistic shape
        // OrderConfirmationService#reconcileTicketItems documents as its main case, and the one confirmOrder_...'s own
        // test needs to observe an actual UPDATE rather than a new-line INSERT.
        long sourceTicketItemId = jdbc.queryForObject(
            "SELECT item_id FROM sales.ticket_item WHERE ticket_id = :id ORDER BY item_id LIMIT 1",
            Map.of("id", ticketId), Long.class);
        PricingRequestRequests.PricingRequestItemRequest item = pricingItem(sourceTicketItemId, qty);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            recipient, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "pcr quotation s3 request", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
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

    private DealQuotationRequests.UpsertDealQuotationRequest upsertWithValidityDays(DealQuotationDto q, int days,
                                                                                    Integer depositPercent) {
        List<DealQuotationRequests.ItemInput> items = q.items().stream().map(this::asItemInput).toList();
        return new DealQuotationRequests.UpsertDealQuotationRequest(q.contactId(), q.deptCode(), q.unitCode(),
            q.offerDate(), depositPercent, q.remainderMode(), q.creditDays(), days, q.customerNotes(),
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

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
