package th.co.glr.hr.customerquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import th.co.glr.hr.config.AppProperties;
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
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.RecordQuotationOutcomeRequest;
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
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
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
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.DealLostReason;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB acceptance + authz + concurrency coverage for Step 4 (Customer Quotation Generation
 * and Issuance). Builds directly on Step 3's own fixtures/style ({@code
 * PricingDecisionIntegrationTest}) — an APPROVED pricing_decision is the precondition every
 * Step 4 operation starts from.
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
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
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
        // V152 (V109 engine wiring): shared by FactoryQuoteService's markReadyForCosting
        // auto-advance check and PricingDecisionService's startReview/recalculateCost.
        th.co.glr.hr.pricingcosting.LandedCostCalculator landedCostCalculator =
            new th.co.glr.hr.pricingcosting.LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
                new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing
        // create/recalculate/submit is gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));
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

        // V152 (V109 engine wiring): factory country must be one of V109's seeded origin
        // countries (Italy/Spain/China) or LandedCostCalculator's freight lookup 422s ("ไม่พบ
        // อัตราค่าขนส่ง") — 'Thailand'/'TestLand4' (pre-V109) are no longer costable. Every
        // catalogProductIdFactory* below uses insertCatalogProduct's 6-arg overload, which
        // defaults thickness_mm to 10 (inside Italy's seeded [8,12) band, whose top band is
        // open-ended, so ANY quantity resolves — see that helper's own comment).
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Acceptance scenario (end to end, real Postgres)
    // ─────────────────────────────────────────────────────────────────────────────────────

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
        // No cost/margin/FX field anywhere on the DTO — structural, not just tested (see the
        // record definition itself), re-confirmed here defensively.
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
    // Legacy render columns (model/color/texture/size) — bug fix regression coverage
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Regression test for "สร้างใบเสนอราคาแล้ว รายละเอียดสินค้าไม่ขึ้น ทั้ง excel และ pdf": before
     * the fix, {@code sales.quotation_item.model/color/texture/size} were always NULL for a
     * Step 4-created row ({@code PricingDecisionRepository#findApprovedSalesView} dropped
     * color/texture/size from its SELECT, and {@code CustomerQuotationRepository}'s NewItem/
     * insertItems never carried model/color/texture/size through to the INSERT at all), so
     * {@code QuotationRenderer#buildDesc} — reused as-is, never modified, and never the bug —
     * had nothing to append after the bare "กระเบื้อง". This asserts every link in the chain: the
     * persisted row, what the renderer's own read path
     * ({@link TicketRepository#findQuotationItemsByQuotationId}) returns, and the actual
     * rendered XLSX cell — every assertion below fails on the pre-fix code (NULL columns, blank
     * cell), not merely "a row exists".
     */
    @Test
    void create_populatesLegacyRenderColumnsForModelColorTextureSize() throws Exception {
        long pricingRequestId = approvedSingleItemPricingRequestWithRenderFields(
            "RenderModel4", "แดง", "ผิวมัน", "60x60-Render4");

        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest("30 days", "45 days", "รถขนส่ง", LocalDate.now().plusDays(30),
                null, UUID.randomUUID().toString()), salesActor);
        assertThat(draft.items()).hasSize(1);
        long quotationItemId = draft.items().get(0).id();

        // 1. The persisted sales.quotation_item row itself — not merely that a row exists.
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT model, color, texture, size FROM sales.quotation_item WHERE quotation_item_id = :id
            """, Map.of("id", quotationItemId));
        assertThat(row.get("model")).isEqualTo("RenderModel4");
        assertThat(row.get("color")).isEqualTo("แดง");
        assertThat(row.get("texture")).isEqualTo("ผิวมัน");
        assertThat(row.get("size")).isEqualTo("60x60-Render4");

        // 2. TicketRepository.findQuotationItemsByQuotationId is exactly what
        // QuotationRenderer#buildDesc reads at render time (CustomerQuotationService's own
        // loadRenderContext calls this, unmodified) — confirm the four values survive that read.
        List<TicketItemDto> renderItems = tickets.findQuotationItemsByQuotationId(draft.id(), ticketId);
        assertThat(renderItems).hasSize(1);
        TicketItemDto renderItem = renderItems.get(0);
        assertThat(renderItem.model()).isEqualTo("RenderModel4");
        assertThat(renderItem.color()).isEqualTo("แดง");
        assertThat(renderItem.texture()).isEqualTo("ผิวมัน");
        assertThat(renderItem.size()).isEqualTo("60x60-Render4");

        // 3. End to end: renderXlsx -> QuotationRenderer.toXlsx (reused as-is, never modified —
        // the bug was never in this class) actually prints รุ่น/สี/ขนาด/พื้นผิว, not the bare
        // "กระเบื้อง" the user reported. No LibreOffice needed here — toXlsx (unlike toPdf) is
        // pure POI. Row/column layout per QuotationRendererTest's own
        // xlsxSubtotalCellSumsAllPricedItemsNotJustTheRenderedFifteen comment: items start at
        // 0-based row 9 in the flow layout; column B (description) is index 1.
        byte[] xlsx = quotationService.renderXlsx(draft.id(), salesActor);
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
            String description = sheet.getRow(9).getCell(1).getStringCellValue();
            assertThat(description).contains("รุ่น RenderModel4");
            assertThat(description).contains("สี แดง");
            assertThat(description).contains("ขนาด 60x60-Render4");
            assertThat(description).contains("ผิวมัน");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Unit basis (highest financial risk)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void unitBasis_perBoxAndPerPieceRequests_atSamePhysicalQuantity_produceTheSameLineTotal() {
        long perBoxRequestId = approvedSingleItemPricingRequest(new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00", new BigDecimal("0.5"), new BigDecimal("20"));
        long perPieceRequestId = approvedSingleItemPricingRequest(new BigDecimal("200"), UnitBasis.PER_PIECE,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00", new BigDecimal("0.5"), new BigDecimal("20"));

        CustomerQuotationDto perBoxQuotation = quotationService.create(perBoxRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto perPieceQuotation = quotationService.create(perPieceRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);

        CustomerQuotationItemDto perBoxItem = perBoxQuotation.items().get(0);
        CustomerQuotationItemDto perPieceItem = perPieceQuotation.items().get(0);
        assertThat(perBoxItem.requestedUnitBasis()).isEqualTo(UnitBasis.PER_BOX);
        assertThat(perPieceItem.requestedUnitBasis()).isEqualTo(UnitBasis.PER_PIECE);

        // Per-REQUESTED-UNIT price differs (per box vs per piece)...
        assertThat(perBoxItem.finalUnitPrice()).isNotEqualByComparingTo(perPieceItem.finalUnitPrice());
        // V152 (V109 engine wiring): the LANDED COST itself is still exactly basis-invariant —
        // both scenarios share the same 200 pieces / 100 sqm physical quantity at Italy [8,12)mm,
        // so both land on the identical TC=91537.0232 (hand-verified the same way
        // LandedCostCalculatorFormulaIntegrationTest does; see that class for the full
        // step-by-step derivation) — landed cost per box = 91537.0200/10 = 9153.7020, landed cost
        // per piece = 91537.0200/200 = 457.6851, and 9153.7020*10 == 457.6851*200 exactly.
        //
        // But the LINE TOTAL below is no longer basis-invariant, and that is now CORRECT, not a
        // regression: V109's RoundUp[cost x (1+margin) x selling_buffer, nearest ฿10] rounds at
        // the PER-REQUESTED-UNIT level, BEFORE multiplying by quantity — so the same underlying
        // cost rounds up by a different RELATIVE amount depending on how large the per-unit price
        // is, and that per-unit rounding slop gets multiplied by a different quantity (10 boxes
        // vs 200 pieces) on each side. Hand-verified at margin=0.10 (approvedSingleItemPricingRequest's
        // own default), sellingBuffer=1.07:
        //   per-box:   9153.7020 x 1.10 x 1.07 = 10773.907254 -> RoundUp/10 -> ฿10,780.0000/box
        //              x 10 boxes   = ฿107,800.0000
        //   per-piece:  457.6851 x 1.10 x 1.07 =   538.6953827 -> RoundUp/10 -> ฿540.0000/piece
        //              x 200 pieces = ฿108,000.0000
        // A genuine ฿200 difference on a ฿108k order (≈0.19%) — the price of rounding granularity,
        // not a computation error; asserting the two independently-derived expectations (rather
        // than asserting the two totals equal EACH OTHER, which is no longer true) is what proves
        // that.
        assertThat(perBoxItem.lineSubtotal()).isEqualByComparingTo("107800.0000");
        assertThat(perPieceItem.lineSubtotal()).isEqualByComparingTo("108000.0000");
        assertThat(perBoxQuotation.subtotalAmount()).isEqualByComparingTo(perBoxItem.lineSubtotal());
        assertThat(perPieceQuotation.subtotalAmount()).isEqualByComparingTo(perPieceItem.lineSubtotal());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Discount Policy B: never below the CEO-approved minimum WITHOUT approval — Phase 2
    // (owner ruling 2026-08-16) turned the old hard 422-on-save into an approval gate at
    // issue() instead. The full CEO approve/reject workflow (price-binding invariant, authz,
    // rejection reasons, notifications) is exercised in DiscountApprovalIntegrationTest, which
    // shares this class's fixtures/style; this test only re-confirms what changed AT THESE THREE
    // SITES: save no longer throws, issue() still does.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void discountBelowMinimum_isSavedButIssueRefusedUntilCeoApproves() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);
        // approvedPricingRequest() sets an EXPLICIT CEO minimum of ฿50.00 per item (well below
        // its own approved price) — mirrors the original test's own "just past the floor" discount
        // rather than assuming Phase 1 auto-population (that fixture is
        // PricingDecisionMinimumPriceAutoPopulationIntegrationTest's, not this one).
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

        // A pending discount-approval request now exists for exactly this line, at exactly this
        // price, and the CEO was notified.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation_item_discount_approval
             WHERE quotation_item_id = :itemId AND status = 'PENDING' AND requested_final_unit_price = :price
            """, Map.of("itemId", discountedItem.id(), "price", belowMinimumPrice), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE type = 'DISCOUNT_APPROVAL_REQUESTED'
            """, Map.of(), Long.class)).isEqualTo(1L);

        // Site ~306 (issue()'s own gate): STILL refuses — now because the line is below minimum
        // AND not yet CEO-approved, rather than merely below minimum.
        assertThatThrownBy(() -> quotationService.issue(discounted.id(), new IssueCustomerQuotationRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        // The document itself is untouched by the refused issue attempt.
        assertThat(quotationService.get(discounted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Revisions
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Quotation numbering (owner ruling 2026-09-18): "{base}-{n}" from the very first document,
    // matching the direct quotation flow (DealQuotationRepository#baseNumber/#revisionNumber,
    // shared via QuotationNumbering). See that class's Javadoc for the full rule, including the
    // legacy-bare-number fallback pinned by createRevision_ofLegacyBareNumberedQuotation_* below.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void create_firstQuotation_getsDashOneSuffix() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        // "QT-<year>-<4-digit seq>-1" -- the suffix from the very first document, not a bare
        // number. Regex (not a literal seq value) because the sequence is shared, real-DB state.
        assertThat(draft.number()).matches("QT-\\d{4}-\\d{4}-1");
    }

    @Test
    void createRevision_bumpsSameBaseNumber_dash2ThenDash3() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        String base = issued.number().substring(0, issued.number().length() - "-1".length());
        assertThat(issued.number()).isEqualTo(base + "-1");

        CustomerQuotationDto rev2 = quotationService.createRevision(issued.id(),
            new CreateRevisionRequest(null, UUID.randomUUID().toString()), salesActor);
        assertThat(rev2.number()).isEqualTo(base + "-2");

        CustomerQuotationDto rev2Issued = quotationService.issue(rev2.id(), new IssueCustomerQuotationRequest(null), salesActor);
        CustomerQuotationDto rev3 = quotationService.createRevision(rev2Issued.id(),
            new CreateRevisionRequest(null, UUID.randomUUID().toString()), salesActor);
        assertThat(rev3.number()).isEqualTo(base + "-3");
    }

    @Test
    void createRevision_ofLegacyBareNumberedQuotation_getsDashTwo() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        // Simulate a quotation issued BEFORE this change: a bare number, no "-1" suffix, at
        // revisionNo == 1 -- exactly the shape a pre-2026-09-18 production row carries. Writing
        // directly to sales.quotation (never renamed/migrated by app code per the owner ruling) is
        // the only way to reproduce that legacy shape in a fresh test fixture.
        String bareNumber = issued.number().substring(0, issued.number().length() - "-1".length());
        jdbc.update("UPDATE sales.quotation SET number = :number WHERE quotation_id = :id",
            Map.of("number", bareNumber, "id", issued.id()));

        CustomerQuotationDto rev2 = quotationService.createRevision(issued.id(),
            new CreateRevisionRequest(null, UUID.randomUUID().toString()), salesActor);
        // {bare}-2 -- treating the bare original as version 1. NOT {bare}-1 (would collide
        // conceptually with the original) and NOT a freshly-minted, unrelated code.
        assertThat(rev2.number()).isEqualTo(bareNumber + "-2");
    }

    @Test
    void quotationNumbers_areNeverReusedAcrossRevisions() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto rev1 = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        CustomerQuotationDto rev2 = quotationService.createRevision(rev1.id(),
            new CreateRevisionRequest(null, UUID.randomUUID().toString()), salesActor);
        CustomerQuotationDto rev2Issued = quotationService.issue(rev2.id(), new IssueCustomerQuotationRequest(null), salesActor);
        CustomerQuotationDto rev3 = quotationService.createRevision(rev2Issued.id(),
            new CreateRevisionRequest(null, UUID.randomUUID().toString()), salesActor);

        List<String> numbers = List.of(rev1.number(), rev2.number(), rev3.number());
        assertThat(numbers).doesNotHaveDuplicates();

        // The UNIQUE constraint (V6 sales.quotation.number) is the real enforcement -- confirm the
        // three rows actually persisted three distinct strings, not just three distinct DTOs.
        Long distinctCount = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT number) FROM sales.quotation WHERE quotation_id IN (:ids)",
            Map.of("ids", List.of(rev1.id(), rev2.id(), rev3.id())), Long.class);
        assertThat(distinctCount).isEqualTo(3L);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Immutability
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void issuedQuotation_cannotBeEdited() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        CustomerQuotationItemDto item = issued.items().get(0);

        assertThatThrownBy(() -> quotationService.update(issued.id(), new UpdateCustomerQuotationRequest(
            "changed", null, null, null, null, List.of()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> quotationService.cancel(issued.id(),
            new CustomerQuotationRequests.CancelCustomerQuotationRequest(null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Data itself is unchanged.
        CustomerQuotationDto reread = quotationService.get(issued.id(), salesActor);
        assertThat(itemById(reread, item.id()).finalUnitPrice()).isEqualByComparingTo(item.finalUnitPrice());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authorization — wrong-way-round
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    @Test
    void accountRole_cannotReachCustomerQuotationsAtAll() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);

        assertThatThrownBy(() -> quotationService.get(draft.id(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> quotationService.listForPricingRequest(pricingRequestId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void ceoAndImport_canReadButNeverEditOrIssue() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);

        assertThat(quotationService.get(draft.id(), ceoActor)).isNotNull();
        assertThat(quotationService.get(draft.id(), importActor)).isNotNull();
        assertThat(quotationService.get(draft.id(), salesManagerActor)).isNotNull();

        assertThatThrownBy(() -> quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null, List.of()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Input gate
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void create_rejectedWhenPricingRequestNotYetApprovedForQuotation() {
        long pricingRequestId = twoItemSubmittedCosting();
        assertThatThrownBy(() -> quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Step 5: Customer Decision and Commercial Revisions
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void recordOutcome_accepted_transitionsPricingRequest_exactlyOneEventAndNotification_dealStageUnchanged() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        String salesStageBefore = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);

        String outcomeKey = UUID.randomUUID().toString();
        CustomerQuotationDto accepted = quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", "ลูกค้าโอเคกับใบเสนอราคา", outcomeKey), salesActor);
        assertThat(accepted.docStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(accepted.outcomeNote()).isEqualTo("ลูกค้าโอเคกับใบเสนอราคา");
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ACCEPTED);

        String salesStageAfter = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        assertThat(salesStageAfter).isEqualTo(salesStageBefore);

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'CUSTOMER_QUOTATION_ACCEPTED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE type = 'CUSTOMER_QUOTATION_ACCEPTED'
            """, Map.of(), Long.class)).isEqualTo(1L);

        // Idempotent replay — no second event.
        CustomerQuotationDto replay = quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", "ลูกค้าโอเคกับใบเสนอราคา", outcomeKey), salesActor);
        assertThat(replay.id()).isEqualTo(accepted.id());
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'CUSTOMER_QUOTATION_ACCEPTED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void recordOutcome_rejected_doesNotChangePricingRequestStatus() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        CustomerQuotationDto rejected = quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("REJECTED", "ราคาสูงเกินไป", UUID.randomUUID().toString()), salesActor);
        assertThat(rejected.docStatus()).isEqualTo(QuotationStatus.REJECTED);
        // No QUOTATION_REJECTED status exists — the pricing request stays exactly where it was.
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'CUSTOMER_QUOTATION_REJECTED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
    }

    @Test
    void recordOutcome_revisionRequested_thenCommercialOnlyRevision_supersedesOldQuotation() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        CustomerQuotationDto revisionRequested = quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("REVISION_REQUESTED", "ขอปรับเงื่อนไขชำระเงิน", UUID.randomUUID().toString()), salesActor);
        assertThat(revisionRequested.docStatus()).isEqualTo(QuotationStatus.REVISION_REQUESTED);
        // No pricing-request status change on a revision-requested outcome either.
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        // Commercial-only path: createRevision, now reachable from REVISION_REQUESTED.
        CustomerQuotationDto rev2 = quotationService.createRevision(revisionRequested.id(),
            new CreateRevisionRequest("ปรับเงื่อนไขชำระเงินตามที่ลูกค้าขอ", UUID.randomUUID().toString()), salesActor);
        assertThat(rev2.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(rev2.quotationRevisionNo()).isEqualTo(2);
        assertThat(rev2.parentQuotationId()).isEqualTo(revisionRequested.id());

        // createRevision still supersedes its source after the guard widening (confirmed, not
        // assumed — supersede()'s own WHERE clause needed widening too, see the repository).
        CustomerQuotationDto oldAfter = quotationService.get(revisionRequested.id(), salesActor);
        assertThat(oldAfter.docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    /**
     * Renamed and re-pointed by the reissue-through-CEO-chain ruling (owner, 2026-08-13), which
     * SPLIT this cascade in two rather than weakening it.
     *
     * <p>It used to assert that createCustomerChangeRevision superseded the old pricing request,
     * the old decision AND the old quotation, all at creation time. The quotation half was the
     * problem: it left the customer holding no live offer for the entire time the replacement
     * chain ran, and nothing to fall back to if the CEO refused the new price. The decision half
     * stays eager — it is internal pricing state, not the customer-facing offer, and the issued
     * quotation carries its own frozen price snapshot in sales.quotation_item, so superseding the
     * decision cannot change what the customer was quoted.
     *
     * <p>The quotation's retirement is not untested, it moved: it now happens when the REPLACEMENT
     * quotation is issued, which is proved end to end by
     * {@code ReissueThroughCeoChainIntegrationTest#parentQuotationStaysIssuedWhileTheChainRuns_andRetiresWhenTheReplacementIssues}.
     */
    @Test
    void recordOutcome_revisionRequested_thenCostAffectingRevision_supersedesOldDecisionButLeavesTheQuotationLive() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        CustomerQuotationDto revisionRequested = quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("REVISION_REQUESTED", "ขอเปลี่ยนจำนวนสินค้า", UUID.randomUUID().toString()), salesActor);

        // Preconditions: an APPROVED decision exists for this pricing request.
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_decision WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class)).isEqualTo("APPROVED");

        // Cost-affecting path: PricingRequestService.createCustomerChangeRevision (a
        // product/quantity change — the mechanism createRevision structurally cannot do).
        var parentSummary = pricingRequestService.get(pricingRequestId, salesActor).summary();
        PricingRequestRequests.CustomerChangeRevisionRequest changeRequest = new PricingRequestRequests.CustomerChangeRevisionRequest(
            "ลูกค้าขอเปลี่ยนจำนวนสินค้า", UUID.randomUUID().toString(), parentSummary.recipientType(), null, "Designer Co.",
            LocalDate.now().plusDays(14), null, "THB", "cost-affecting revision (design correction 1 test)",
            List.of(pricingItem("SCG", "Tile A4", "Factory A4", new BigDecimal("99"))));
        var revisionDetail = pricingRequestService.createCustomerChangeRevision(pricingRequestId, changeRequest, salesActor);

        // Design correction 1, still in force for the INTERNAL state: the OLD pricing request and
        // OLD decision are SUPERSEDED — neither silently reads as current any more.
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUPERSEDED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_decision WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class)).isEqualTo("SUPERSEDED");
        // Reissue-through-CEO-chain: the CUSTOMER-FACING document is deliberately NOT retired here.
        // The customer's offer stays exactly as it was until a replacement actually issues.
        CustomerQuotationDto oldQuotation = quotationService.get(revisionRequested.id(), salesActor);
        assertThat(oldQuotation.docStatus()).isEqualTo(QuotationStatus.REVISION_REQUESTED);

        // The NEW pricing request revision is a fresh DRAFT, untouched by the cascade.
        assertThat(revisionDetail.summary().status()).isEqualTo(PricingRequestStatus.DRAFT);
        assertThat(revisionDetail.summary().parentPricingRequestId()).isEqualTo(pricingRequestId);
    }

    /**
     * Review follow-up to design correction 1: {@code cancelOpenStep2Children}'s other caller,
     * {@code cancelOpenForTicket} (the deal-lost/deal-cancelled cascade from {@code
     * TicketService}), had exactly the same gap as {@code createCustomerChangeRevision} did
     * before this fix — it cancels the pricing request but never touched a DRAFT/APPROVED
     * decision or a non-terminal quotation. Unlike {@code PricingRequestService.cancel} (whose
     * own {@code canTransition}-gated statuses can never co-exist with an open decision, so the
     * equivalent fix there is defensive-only — see that method's comment), this path uses {@code
     * cancelForDeadDeal}, which deliberately bypasses {@code canTransition} to cancel from ANY
     * open status once the deal itself goes terminal — so it genuinely CAN reach a pricing
     * request with an APPROVED decision and an ISSUED quotation, exactly this scenario.
     */
    @Test
    void ticketMarkLost_cascadesToSupersedeAnOpenPricingDecisionAndQuotation() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        ticketService.markLost(ticketId, DealLostReason.PRICE, null, salesActor);

        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.CANCELLED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_decision WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class)).isEqualTo("SUPERSEDED");
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    @Test
    void createRevision_widenedGuard_succeedsFromRevisionRequested() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        CustomerQuotationDto revisionRequested = quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("REVISION_REQUESTED", null, UUID.randomUUID().toString()), salesActor);

        CustomerQuotationDto rev2 = quotationService.createRevision(revisionRequested.id(),
            new CreateRevisionRequest("ok", UUID.randomUUID().toString()), salesActor);
        assertThat(rev2.docStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    /**
     * Negative space for design correction 3's widened guard: EVERY status other than ISSUED and
     * REVISION_REQUESTED must still be rejected — proves the widening did not silently become
     * "any status", per the task's explicit instruction to test both directions.
     */
    @Test
    void createRevision_widenedGuard_stillRejectsEveryOtherStatus() {
        // DRAFT: never issued.
        long draftPr = approvedPricingRequest();
        CustomerQuotationDto draftQuotation = quotationService.create(draftPr,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        assertRevisionRejected(draftQuotation.id());

        // CANCELLED: a draft that was cancelled.
        long cancelledPr = approvedPricingRequest();
        CustomerQuotationDto toCancel = quotationService.create(cancelledPr,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        quotationService.cancel(toCancel.id(), new CustomerQuotationRequests.CancelCustomerQuotationRequest(null), salesActor);
        assertRevisionRejected(toCancel.id());

        // SUPERSEDED: rev 1 of an already-revised quotation.
        long supersededPr = approvedPricingRequest();
        CustomerQuotationDto rev1Draft = quotationService.create(supersededPr,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto rev1Issued = quotationService.issue(rev1Draft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        quotationService.createRevision(rev1Issued.id(), new CreateRevisionRequest(null, UUID.randomUUID().toString()), salesActor);
        assertRevisionRejected(rev1Issued.id());

        // EXPIRED: flipped by the sweep (past validity_date).
        long expiredPr = approvedPricingRequest();
        CustomerQuotationDto expiredDraft = quotationService.create(expiredPr, new CreateCustomerQuotationRequest(
            null, null, null, LocalDate.now().minusDays(1), null, null), salesActor);
        CustomerQuotationDto expiredIssued = quotationService.issue(expiredDraft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        quotationService.expireOverdueQuotations();
        assertThat(quotationService.get(expiredIssued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.EXPIRED);
        assertRevisionRejected(expiredIssued.id());

        // ACCEPTED.
        long acceptedPr = approvedPricingRequest();
        CustomerQuotationDto acceptedDraft = quotationService.create(acceptedPr,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto acceptedIssued = quotationService.issue(acceptedDraft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        quotationService.recordOutcome(acceptedIssued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", null, UUID.randomUUID().toString()), salesActor);
        assertRevisionRejected(acceptedIssued.id());

        // REJECTED.
        long rejectedPr = approvedPricingRequest();
        CustomerQuotationDto rejectedDraft = quotationService.create(rejectedPr,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto rejectedIssued = quotationService.issue(rejectedDraft.id(), new IssueCustomerQuotationRequest(null), salesActor);
        quotationService.recordOutcome(rejectedIssued.id(),
            new RecordQuotationOutcomeRequest("REJECTED", null, UUID.randomUUID().toString()), salesActor);
        assertRevisionRejected(rejectedIssued.id());
    }

    private void assertRevisionRejected(long quotationId) {
        assertThatThrownBy(() -> quotationService.createRevision(quotationId,
            new CreateRevisionRequest(null, UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void expireOverdueQuotations_sweep_pastValidityFlips_futureValidityAndAcceptedUntouched() {
        long prPast = approvedPricingRequest();
        CustomerQuotationDto draftPast = quotationService.create(prPast, new CreateCustomerQuotationRequest(
            null, null, null, LocalDate.now().minusDays(1), null, null), salesActor);
        CustomerQuotationDto issuedPast = quotationService.issue(draftPast.id(), new IssueCustomerQuotationRequest(null), salesActor);

        long prFuture = approvedPricingRequest();
        CustomerQuotationDto draftFuture = quotationService.create(prFuture, new CreateCustomerQuotationRequest(
            null, null, null, LocalDate.now().plusDays(30), null, null), salesActor);
        CustomerQuotationDto issuedFuture = quotationService.issue(draftFuture.id(), new IssueCustomerQuotationRequest(null), salesActor);

        long prAccepted = approvedPricingRequest();
        CustomerQuotationDto draftAccepted = quotationService.create(prAccepted, new CreateCustomerQuotationRequest(
            null, null, null, LocalDate.now().minusDays(1), null, null), salesActor);
        CustomerQuotationDto issuedAccepted = quotationService.issue(draftAccepted.id(), new IssueCustomerQuotationRequest(null), salesActor);
        quotationService.recordOutcome(issuedAccepted.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", null, UUID.randomUUID().toString()), salesActor);

        int expiredCount = quotationService.expireOverdueQuotations();
        assertThat(expiredCount).isEqualTo(1);

        assertThat(quotationService.get(issuedPast.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.EXPIRED);
        assertThat(quotationService.get(issuedFuture.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(quotationService.get(issuedAccepted.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ACCEPTED);

        // No pricing-request status change on expiry.
        assertThat(pricingRequestService.get(prPast, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'CUSTOMER_QUOTATION_EXPIRED'
            """, Map.of("id", prPast), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification WHERE type = 'CUSTOMER_QUOTATION_EXPIRED'
            """, Map.of(), Long.class)).isEqualTo(1L);

        // Idempotent: a second sweep with nothing new to expire touches nothing further.
        assertThat(quotationService.expireOverdueQuotations()).isEqualTo(0);
    }

    @Test
    void recordOutcome_expiredOutcome_isRejectedRegardlessOfRole() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("EXPIRED", null, UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        // Still rejected for a role that also has no other access to this aggregate — proves
        // EXPIRED is refused unconditionally, not merely "not an owner".
        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("EXPIRED", null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN));

        // Data itself is unchanged.
        assertThat(quotationService.get(issued.id(), salesActor).docStatus()).isEqualTo(QuotationStatus.ISSUED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Step 5: Authorization — wrong-way-round
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void recordOutcome_nonOwningSalesRep_cannotRecordOutcome() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", null, UUID.randomUUID().toString()), otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // The owner still can — proves the guard is scoped, not a blanket lockout.
        assertThat(quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", null, UUID.randomUUID().toString()), salesActor)).isNotNull();
    }

    @Test
    void recordOutcome_ceoAndImport_areReadOnly_cannotRecordOutcome() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", null, UUID.randomUUID().toString()), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Read access is untouched by this guard — still visible, just never mutable.
        assertThat(quotationService.get(issued.id(), ceoActor)).isNotNull();
        assertThat(quotationService.get(issued.id(), importActor)).isNotNull();
    }

    @Test
    void recordOutcome_accountRole_cannotReachAtAll() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationDto issued = quotationService.issue(draft.id(), new IssueCustomerQuotationRequest(null), salesActor);

        assertThatThrownBy(() -> quotationService.recordOutcome(issued.id(),
            new RecordQuotationOutcomeRequest("ACCEPTED", null, UUID.randomUUID().toString()), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // R-H (quotation arithmetic reconciliation, 2026-09-15): document-level VAT/grand total
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code vatAmount}/{@code grandTotal} must be {@code round2(subtotal × 7%)} /
     * {@code subtotal + vatAmount} — a SINGLE document-level rounding — never
     * {@code Σ item.vat}/{@code Σ item.lineTotal}, which double-rounds through each already-2dp
     * per-line figure and can drift a satang from the document-level rounding. Three real
     * QN6900971-4 line amounts (47,111.78 / 9,715.14 / 35,799.63) are used here specifically
     * because their per-line VAT sums to 6,483.85 while round2(their subtotal × 7%) is 6,483.86 —
     * hand-verified before writing this test, not asserted on faith. Each item is priced via the
     * CEO's "ปรับราคาเอง" selling-price override at quantity 1, so
     * {@code lineSubtotal == finalUnitPrice == the override value} exactly, with no discount or
     * quantity multiplication in the way of hitting these figures to the satang.
     */
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
        // Guard the fixture's own premise: the naive Σ per-line VAT this test exists to catch
        // really does diverge from the document-level figure on these three lines — otherwise the
        // test could pass on either implementation and prove nothing.
        BigDecimal sumOfPerLineVat = draft.items().stream()
            .map(CustomerQuotationItemDto::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfPerLineVat).isEqualByComparingTo(new BigDecimal("6483.85"))
            .describedAs("fixture premise: Σ per-line VAT must diverge from the document-level VAT")
            .isNotEqualByComparingTo(expectedVat);

        assertThat(draft.subtotalAmount()).isEqualByComparingTo(expectedSubtotal);
        assertThat(draft.vatAmount()).isEqualByComparingTo(expectedVat);
        assertThat(draft.grandTotal()).isEqualByComparingTo(expectedGrandTotal);

        // Re-read from the DB (not just the create() response) — mapQuotation is exercised on
        // every read path, not only the one right after INSERT.
        CustomerQuotationDto reloaded = quotationService.get(draft.id(), salesActor);
        assertThat(reloaded.vatAmount()).isEqualByComparingTo(expectedVat);
        assertThat(reloaded.grandTotal()).isEqualByComparingTo(expectedGrandTotal);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private CustomerQuotationItemDto itemById(CustomerQuotationDto quotation, long itemId) {
        return quotation.items().stream().filter(i -> i.id() == itemId).findFirst().orElseThrow();
    }

    /** Drives a two-item pricing request all the way to APPROVED_FOR_QUOTATION with an APPROVED
     * pricing_decision — the precondition every Step 4 operation starts from. */
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

    private long approvedSingleItemPricingRequest(
        BigDecimal requestedQty, String requestedUnitBasis, String quotedUnitBasis, BigDecimal quotedQuantity,
        String rawPrice, BigDecimal sqmPerUnit, BigDecimal piecesPerBox
    ) {
        long pricingRequestId = singleItemSubmittedCosting(requestedQty, requestedUnitBasis, quotedUnitBasis,
            quotedQuantity, rawPrice, sqmPerUnit, piecesPerBox);
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.10"), "THB", null, null), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
            new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        decisionService.approve(decision.id(), new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);
        return pricingRequestId;
    }

    /** Like {@link #approvedSingleItemPricingRequest} but lets the caller set
     * model/color/texture/size explicitly — every other helper in this class always passes
     * color=null/texture=null (see {@link #pricingItem}, {@link #singleItemSubmittedCosting}),
     * which is exactly why the legacy-render-column bug went unnoticed by every other test here.
     * Drives the same real chain (submit → pickup → factory quote → costing → CEO decision →
     * approve) as {@link #singleItemSubmittedCosting}, just inlined so it can carry its own item
     * fields through to the pricing request item. */
    private long approvedSingleItemPricingRequestWithRenderFields(
        String model, String color, String texture, String size
    ) {
        // V185 (direct-deal-form parity): thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists — requestedQty/
        // requestedUnit/requestedUnitBasis are derived instead. color/texture/size are the values
        // this test itself is exercising, so they still flow through untouched.
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdFactoryC, null, "RenderBrand4", model, "RenderBrand4 " + model,
            color, texture, size, "Factory C4", null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 1, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "step 4 render-fields unit test", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory C4");
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            response("REF-RENDER4", "THB", "100.00", draft.items().get(0).pricingRequestItemId()), importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.10"), "THB", null, null), ceoActor);
        PricingDecisionItemDto decisionItem = decision.items().get(0);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
            new UpdatePricingDecisionItemRequest(decisionItem.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
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
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        // V141 ("CEO owns costing"): markReadyForCosting auto-advances the request straight to
        // READY_FOR_CEO_REVIEW the moment the LAST factory quote is ready — Import no longer
        // creates/recalculates/submits a costing of its own; the CEO's startReview computes it.
        return pricingRequestId;
    }

    private long singleItemSubmittedCosting(
        BigDecimal requestedQty, String requestedUnitBasis, String quotedUnitBasis, BigDecimal quotedQuantity,
        String rawPrice, BigDecimal sqmPerUnit, BigDecimal piecesPerBox
    ) {
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdFactoryC, null, "TestBrand4", "TestModel4", "TestBrand4 TestModel4",
            null, null, "1x1", "Factory C4", requestedQty, requestedQty, "unit", requestedUnitBasis,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "step 4 unit test", UUID.randomUUID().toString(), List.of(item));
        // V185: bypasses PricingRequestService.createDraft on purpose -- that method now forces every
        // item's requestedUnitBasis to PER_PIECE, which would make it impossible to construct the
        // non-PER_PIECE requestedUnitBasis this unit-conversion matrix exists to test.
        // PricingRequestRepository.create performs the exact same DB write createDraft would.
        long pricingRequestId = pricingRequests.create(ticketId, pricingRequests.nextRequestCode(), request, salesRepId);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory C4");
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-UNIT4", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, quotedQuantity, quotedUnitBasis, quotedUnitBasis,
                new BigDecimal(rawPrice), "THB", null, sqmPerUnit, piecesPerBox, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        // V141 ("CEO owns costing"): markReadyForCosting auto-advances the request straight to
        // READY_FOR_CEO_REVIEW the moment the (only, here) factory quote is ready — Import no
        // longer creates/recalculates/submits a costing of its own; the CEO's startReview computes it.
        return pricingRequestId;
    }

    private FactoryQuoteDto quoteFor(List<FactoryQuoteDto> quotes, String factoryName) {
        return quotes.stream().filter(q -> factoryName.equals(q.factoryName())).findFirst().orElseThrow();
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
     * document-level-VAT-vs-Σper-line-VAT reconciliation test (R-H), which needs to set each
     * item's price independently. */
    private long threeItemSubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, threeItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
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
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a
        // quantity are now required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead. roundToFullBox=false +
        // piecesInput=qty keeps the derived requestedQty byte-identical to `qty`.
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
     * กรรมการผู้จัดการ ALONE, so an employee with no position can never match it. These fixtures
     * used to land in that set via the superseded division-based rule, which is why they needed no
     * position before. Division stays MD so the {@code ceo} ROLE ({@code DivisionAccessPolicy}) and
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
