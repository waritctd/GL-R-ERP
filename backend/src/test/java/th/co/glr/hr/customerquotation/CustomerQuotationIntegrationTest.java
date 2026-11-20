package th.co.glr.hr.customerquotation;

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
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateRevisionRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationItemRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationRequest;
import th.co.glr.hr.dealquotation.WastageCalculator;
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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
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
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB acceptance + authz + concurrency coverage for Customer Quotation Generation
 * and Issuance. Builds directly on the fixtures/style of {@code
 * PricingDecisionIntegrationTest} — an APPROVED pricing_decision is the precondition every
 * operation here starts from.
 */
class CustomerQuotationIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteRepository factoryQuoteRepository;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingCostingService costingService;
    private PricingDecisionRepository decisionRepository;
    private PricingDecisionService decisionService;
    private CustomerQuotationRepository quotationRepository;
    private CustomerQuotationService quotationService;
    private TicketService ticketService;

    private RecordingSalesMailer salesMailer;
    private long salesRepId;
    private long otherSalesId;
    private long importUserId;
    private long ceoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;
    private long catalogProductIdFactoryC;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        salesMailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, salesMailer);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-customer-quotation-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        th.co.glr.hr.pricingcosting.PricingFormulaEngine formulaEngine =
            new th.co.glr.hr.pricingcosting.PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        // V152 (V109 engine wiring): shared by FactoryQuoteService's markReadyForCosting auto-advance check and
        // PricingDecisionService's startReview/recalculateCost.
        th.co.glr.hr.pricingcosting.LandedCostCalculator landedCostCalculator =
            new th.co.glr.hr.pricingcosting.LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
                new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing create/recalculate/submit is
        // gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications, new th.co.glr.hr.customerquotation.DiscountApprovalRepository(jdbc));

        salesRepId = createEmployee(employees, "พนักงานขาย สี่", "sales-step4@glr.co.th", "SALES", "แผนกขาย");
        otherSalesId = createEmployee(employees, "พนักงานขาย อื่นสี่", "sales-step4-other@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า สี่", "import-step4@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createManagingDirector(employees, "ผู้บริหาร สี่", "ceo-step4@glr.co.th");
        accountUserId = createEmployee(employees, "บัญชี สี่", "account-step4@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย สี่", "sales-manager-step4@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        // V152 (V109 engine wiring): factory country must be one of V109's seeded origin countries (Italy/Spain/China)
        // or LandedCostCalculator's freight lookup 422s ("ไม่พบ อัตราค่าขนส่ง") — 'Thailand'/'TestLand4' (pre-V109)
        // are no longer costable.
        catalogProductIdFactoryA = insertCatalogProduct("Factory A4", "IT", "TEST-A4-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B4", "IT", "TEST-B4-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        catalogProductIdFactoryC = insertCatalogProduct("Factory C4", "IT", "TEST-C4-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Step 4 จำกัด", "0100000000004", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0004");
        ProjectDto project = projects.create(customer.id(), "โครงการ Step 4");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Step 4", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A4", "Factory A4"), ticketItem("Cotto", "Tile B4", "Factory B4"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // Acceptance scenario (end to end, real Postgres)

    @Test
    void acceptanceScenario_draftDiscountPreviewIssue() {
        long pricingRequestId = approvedPricingRequest();

        // Sales creates Quotation Draft 1.
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest("30 days", "45 days", "รถขนส่ง", LocalDate.now().plusDays(30),
                "หมายเหตุลูกค้า", UUID.randomUUID().toString()), salesActor);
        assertThat(draft.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(draft.quotationRevisionNo()).isEqualTo(1);
        assertThat(draft.items()).hasSize(2);
        // No cost/margin/FX field anywhere on the DTO — structural, not just tested (see the record definition
        // itself), re-confirmed here defensively.
        for (CustomerQuotationItemDto item : draft.items()) {
            assertThat(item.approvedUnitPrice()).isNotNull();
            assertThat(item.finalUnitPrice()).isEqualByComparingTo(item.approvedUnitPrice());
        }

        // Draft creation must NOT move the deal stage or the pricing request status (rule 6).
        assertThat(jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isNotIn("QUOTE_DESIGN_SIDE", "QUOTE_BUYER");
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);

        // Sales applies a permitted discount to item 1.
        CustomerQuotationItemDto item1 = draft.items().get(0);
        BigDecimal permittedDiscount = item1.approvedUnitPrice()
            .subtract(item1.minimumSellingPricePerRequestedUnit())
            .divide(new BigDecimal("2"), 4, java.math.RoundingMode.HALF_UP);
        CustomerQuotationDto discounted = quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(item1.id(), "คำอธิบายลูกค้าเห็น", "หมายเหตุรายการ", permittedDiscount))),
            salesActor);
        CustomerQuotationItemDto updatedItem1 = itemById(discounted, item1.id());
        assertThat(updatedItem1.salesDiscount()).isEqualByComparingTo(permittedDiscount);
        assertThat(updatedItem1.finalUnitPrice())
            .isEqualByComparingTo(item1.approvedUnitPrice().subtract(permittedDiscount));
        assertThat(updatedItem1.lineSubtotal())
            .isEqualByComparingTo(updatedItem1.finalUnitPrice().multiply(updatedItem1.requestedQuantity())
                .setScale(2, java.math.RoundingMode.HALF_UP));
        // Server-calculated total reflects the discount (never trusts a client total).
        BigDecimal expectedSubtotal = discounted.items().stream()
            .map(CustomerQuotationItemDto::lineSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(discounted.subtotalAmount()).isEqualByComparingTo(expectedSubtotal);

        // Preview PDF/XLSX — must NOT issue or change the deal stage.
        byte[] pdf = quotationService.renderPdf(discounted.id(), salesActor);
        byte[] xlsx = quotationService.renderXlsx(discounted.id(), salesActor);
        assertThat(pdf).isNotEmpty();
        assertThat(xlsx).isNotEmpty();
        assertThat(quotationService.get(discounted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);

        // Issue.
        String issueKey = UUID.randomUUID().toString();
        CustomerQuotationDto issued = quotationService.issue(discounted.id(),
            new IssueCustomerQuotationRequest(issueKey), salesActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
        assertThat(jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isIn("QUOTE_DESIGN_SIDE", "QUOTE_BUYER");

        // Exactly one issue event and one notification.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'QUOTATION_ISSUED'
            """, Map.of("id", ticketId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'CUSTOMER_QUOTATION_ISSUED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE type = 'CUSTOMER_QUOTATION_ISSUED'
            """, Map.of(), Long.class)).isEqualTo(1L);

        // Re-issue with the SAME clientRequestId is idempotent (no second issue event).
        CustomerQuotationDto replay = quotationService.issue(discounted.id(),
            new IssueCustomerQuotationRequest(issueKey), salesActor);
        assertThat(replay.id()).isEqualTo(issued.id());
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'QUOTATION_ISSUED'
            """, Map.of("id", ticketId), Long.class)).isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Discount Policy B: never below the CEO-approved minimum WITHOUT approval — an approval
    // gate at issue(), not a hard 422-on-save. The full CEO approve/reject workflow
    // (price-binding invariant, authz, rejection reasons, notifications) is exercised in
    // DiscountApprovalIntegrationTest, which shares this class's fixtures/style; this test
    // only confirms AT THESE THREE SITES: save does not throw, issue() does.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void discountBelowMinimum_isSavedButIssueRefusedUntilCeoApproves() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);
        // approvedPricingRequest sets an EXPLICIT CEO minimum of ฿50.00 per item — mirrors the original test's own
        // "just past the floor" discount rather than assuming Phase 1 auto-population.
        BigDecimal discount = item.approvedUnitPrice().subtract(item.minimumSellingPricePerRequestedUnit())
            .add(new BigDecimal("1.00"));
        BigDecimal belowMinimumPrice = item.approvedUnitPrice().subtract(discount);
        assertThat(belowMinimumPrice).isLessThan(item.minimumSellingPricePerRequestedUnit());

        // Site ~254 (applyItemUpdates): no longer throws.
        CustomerQuotationDto discounted = quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(item.id(), null, null, discount))), salesActor);
        CustomerQuotationItemDto discountedItem = itemById(discounted, item.id());
        assertThat(discountedItem.salesDiscount()).isEqualByComparingTo(discount);
        assertThat(discountedItem.finalUnitPrice()).isEqualByComparingTo(belowMinimumPrice);

        // A pending discount-approval request now exists for exactly this line, at exactly this price, and the CEO was
        // notified.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation_item_discount_approval
             WHERE quotation_item_id = :itemId AND status = 'PENDING' AND requested_final_unit_price = :price
            """, Map.of("itemId", discountedItem.id(), "price", belowMinimumPrice), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE type = 'DISCOUNT_APPROVAL_REQUESTED'
            """, Map.of(), Long.class)).isEqualTo(1L);

        // Site ~306 (issue()'s own gate): STILL refuses — now because the line is below minimum AND not yet
        // CEO-approved, rather than merely below minimum.
        assertThatThrownBy(() -> quotationService.issue(discounted.id(), new IssueCustomerQuotationRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        // The document itself is untouched by the refused issue attempt.
        assertThat(quotationService.get(discounted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    // Revisions

    @Test
    void correctionAfterIssue_createsRevision2_andLeavesRevision1Readable() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto rev1 = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        assertThat(rev1.docStatus()).isEqualTo(QuotationStatus.ISSUED);

        CustomerQuotationDto rev2 = quotationService.createRevision(rev1.id(),
            new CreateRevisionRequest("แก้ไขคำอธิบาย", UUID.randomUUID().toString()), salesActor);
        assertThat(rev2.quotationRevisionNo()).isEqualTo(2);
        assertThat(rev2.parentQuotationId()).isEqualTo(rev1.id());
        assertThat(rev2.docStatus()).isEqualTo(QuotationStatus.DRAFT);

        // Rev 1 stays readable, now SUPERSEDED.
        CustomerQuotationDto rev1AfterSupersede = quotationService.get(rev1.id(), salesActor);
        assertThat(rev1AfterSupersede.docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        assertThat(rev1AfterSupersede.items()).hasSameSizeAs(rev1.items());

        // Re-issuing rev2 is a no-op for the pricing request (already QUOTATION_ISSUED from rev1).
        CustomerQuotationDto rev2Issued = quotationService.issue(rev2.id(), new IssueCustomerQuotationRequest(null), salesActor);
        assertThat(rev2Issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        List<CustomerQuotationDto> all = quotationService.listForPricingRequest(pricingRequestId, salesActor);
        assertThat(all).hasSize(2);
    }

    // Authorization — wrong-way-round

    @Test
    void nonOwningSalesRep_cannotReadEditOrIssueAnotherRepsQuotation() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);

        assertThatThrownBy(() -> quotationService.get(draft.id(), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null, List.of()), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> quotationService.listForPricingRequest(pricingRequestId, otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // The owner still can (proves the guard is scoped, not a blanket lockout).
        assertThat(quotationService.get(draft.id(), salesActor)).isNotNull();
    }

    /** vatAmount/grandTotal must be round2(subtotal × 7%) / subtotal + vatAmount — a SINGLE document-level rounding
     * — never Σ item.vat/Σ item.lineTotal, which double-rounds through each already-2dp per-line figure and can
     * drift a satang from the document-level rounding. */
    @Test
    void vatAndGrandTotal_areDocumentLevelRounding_notTheSumOfPerLineFigures() {
        long pricingRequestId = threeItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);

        Map<String, BigDecimal> targetPriceByModel = Map.of(
            "Tile A4", new BigDecimal("47111.78"),
            "Tile B4", new BigDecimal("9715.14"),
            "Tile C4", new BigDecimal("35799.63"));
        for (PricingDecisionItemDto item : decision.items()) {
            BigDecimal target = targetPriceByModel.get(item.model());
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, target,
                    "R-H reconciliation test fixture — fixed selling price", target, false))), ceoActor);
        }
        decisionService.approve(decision.id(), new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);

        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        assertThat(draft.items()).hasSize(3);
        for (CustomerQuotationItemDto item : draft.items()) {
            assertThat(item.requestedQuantity()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(item.lineSubtotal()).isEqualByComparingTo(item.approvedUnitPrice());
        }

        BigDecimal expectedSubtotal = new BigDecimal("92626.55");
        BigDecimal expectedVat = new BigDecimal("6483.86");
        BigDecimal expectedGrandTotal = new BigDecimal("99110.41");
        // Guard the fixture's own premise: the naive Σ per-line VAT this test exists to catch really does diverge from
        // the document-level figure on these three lines — otherwise the test could pass on either implementation and
        // prove nothing.
        BigDecimal sumOfPerLineVat = draft.items().stream()
            .map(CustomerQuotationItemDto::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfPerLineVat).isEqualByComparingTo(new BigDecimal("6483.85"))
            .describedAs("fixture premise: Σ per-line VAT must diverge from the document-level VAT")
            .isNotEqualByComparingTo(expectedVat);

        assertThat(draft.subtotalAmount()).isEqualByComparingTo(expectedSubtotal);
        assertThat(draft.vatAmount()).isEqualByComparingTo(expectedVat);
        assertThat(draft.grandTotal()).isEqualByComparingTo(expectedGrandTotal);

        // Re-read from the DB (not just the create() response) — mapQuotation is exercised on every read path, not
        // only the one right after INSERT.
        CustomerQuotationDto reloaded = quotationService.get(draft.id(), salesActor);
        assertThat(reloaded.vatAmount()).isEqualByComparingTo(expectedVat);
        assertThat(reloaded.grandTotal()).isEqualByComparingTo(expectedGrandTotal);
    }

    // Helpers

    private CustomerQuotationItemDto itemById(CustomerQuotationDto quotation, long itemId) {
        return quotation.items().stream().filter(i -> i.id() == itemId).findFirst().orElseThrow();
    }

    /** Drives a two-item pricing request all the way to APPROVED_FOR_QUOTATION with an APPROVED
     * pricing_decision — the precondition every customer-quotation operation starts from. */
    private long approvedPricingRequest() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("50.00"), null, null, false))), ceoActor);
        }
        decisionService.approve(decision.id(), new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);
        return pricingRequestId;
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
        // V141 ("CEO owns costing"): markReadyForCosting auto-advances the request straight to READY_FOR_CEO_REVIEW
        // the moment the LAST factory quote is ready — Import no longer creates/recalculates/submits a costing of its
        // own; the CEO's startReview computes it.
        return pricingRequestId;
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private PricingRequestRequests.CreatePricingRequestRequest twoItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "step 4 request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A4", "Factory A4", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B4", "Factory B4", new BigDecimal("5"))));
    }

    private PricingRequestRequests.CreatePricingRequestRequest threeItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "step 4 VAT reconciliation request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A4", "Factory A4", new BigDecimal("1")),
                pricingItem("Cotto", "Tile B4", "Factory B4", new BigDecimal("1")),
                pricingItem("Marazzi", "Tile C4", "Factory C4", new BigDecimal("1"))));
    }

    /** Like {@link #twoItemSubmittedCosting} but with three items (Factory A4/B4/C4), for the
     * document-level-VAT-vs-Σper-line-VAT reconciliation test, which needs to set each
     * item's price independently. */
    private long threeItemSubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, threeItemPricingRequest(), salesActor)
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
        return pricingRequestId;
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A4".equals(factory) ? catalogProductIdFactoryA
            : "Factory B4".equals(factory) ? catalogProductIdFactoryB
            : "Factory C4".equals(factory) ? catalogProductIdFactoryC : null;
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
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

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /**
     * The CEO fixture. Unlike {@link #createEmployee}, which leaves {@code positionTh} null, this
     * sets a real position -- {@code CeoApproverRule} keys the CEO-notified set on position
     * กรรมการผู้จัดการ ALONE, so an employee with no position can never match it.
     * Division stays MD so the {@code ceo} ROLE ({@code DivisionAccessPolicy}) and
     * every authz assertion in this class are unchanged; only notification routing is affected.
     */
    private long createManagingDirector(EmployeeRepository employees, String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
