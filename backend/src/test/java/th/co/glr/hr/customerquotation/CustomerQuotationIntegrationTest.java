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
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
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
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-customer-quotation-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage);
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        when(factoryEmail.send(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        AppProperties dispatchProperties = new AppProperties();
        dispatchProperties.getFactoryQuoteDispatch().setReclaimTimeoutSeconds(2);
        dispatchProperties.getFactoryQuoteDispatch().setMaxAttempts(3);
        dispatchProperties.getFactoryQuoteDispatch().setBackoffBaseSeconds(1);
        dispatchProperties.getFactoryQuoteDispatch().setBatchSize(20);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties);
        costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        costingService = new PricingCostingService(costingRepository, pricingRequests, factoryQuotes, tickets,
            fxRates, new PriceCalcConfigRepository(jdbc), new FactoryConfigRepository(jdbc), notifications);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications);
        th.co.glr.hr.pricing.PriceCalcService priceCalcMock = mock(th.co.glr.hr.pricing.PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);
        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย สี่", "sales-step4@glr.co.th", "SALES", "แผนกขาย");
        otherSalesId = createEmployee(employees, "พนักงานขาย อื่นสี่", "sales-step4-other@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า สี่", "import-step4@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร สี่", "ceo-step4@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี สี่", "account-step4@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย สี่", "sales-manager-step4@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES
                ('Factory A4', 'factory-a4@example.com', 'THB', 'piece', 'Thailand'),
                ('Factory B4', 'factory-b4@example.com', 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
        catalogProductIdFactoryA = insertCatalogProduct("Factory A4", "TH", "TEST-A4-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B4", "TH", "TEST-B4-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Factory C4', 'factory-c4@example.com', 'THB', 'piece', 'TestLand4')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
        catalogProductIdFactoryC = insertCatalogProduct("Factory C4", "XX", "TEST-C4-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        jdbc.update("""
            INSERT INTO sales.price_calc_config
                (version, country, freight_per_sqm, insurance_per_sqm, inland_factory_to_port_per_sqm,
                 inland_port_to_warehouse_per_sqm, import_duty_pct, margin_pct, is_current)
            VALUES (1, 'TestLand4', 0, 0, 0, 0, 0, 0, TRUE)
            """, Map.of());

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
        // ...but at the SAME physical quantity and same per-piece economics, the LINE TOTAL
        // (final_unit_price * requested_quantity, both in the SAME requested-unit basis) is
        // identical — the exact rule the task calls out as the highest financial risk.
        assertThat(perBoxItem.lineSubtotal()).isEqualByComparingTo(perPieceItem.lineSubtotal());
        assertThat(perBoxQuotation.subtotalAmount()).isEqualByComparingTo(perPieceQuotation.subtotalAmount());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Discount Policy B: never below the CEO-approved minimum
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void discountBelowMinimum_isRejectedWith422_noAutoEscalation() {
        long pricingRequestId = approvedPricingRequest();
        CustomerQuotationDto draft = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);
        BigDecimal tooMuchDiscount = item.approvedUnitPrice().subtract(item.minimumSellingPricePerRequestedUnit())
            .add(new BigDecimal("1.00"));

        assertThatThrownBy(() -> quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(item.id(), null, null, tooMuchDiscount))), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // Nothing was mutated — the item's discount stays at the pre-attempt value.
        CustomerQuotationDto unchanged = quotationService.get(draft.id(), salesActor);
        assertThat(itemById(unchanged, item.id()).salesDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
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

    @Test
    void recordOutcome_revisionRequested_thenCostAffectingRevision_supersedesOldDecisionAndQuotation() {
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

        // Design correction 1: the OLD pricing request, OLD decision, and OLD quotation are all
        // now SUPERSEDED — none of them silently reads as current any more.
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUPERSEDED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_decision WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class)).isEqualTo("SUPERSEDED");
        CustomerQuotationDto oldQuotation = quotationService.get(revisionRequested.id(), salesActor);
        assertThat(oldQuotation.docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

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
                new UpdatePricingDecisionItemRequest(item.id(), null, null, new BigDecimal("50.00"), null))), ceoActor);
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
            new UpdatePricingDecisionItemRequest(item.id(), null, null, new BigDecimal("1.00"), null))), ceoActor);
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
        PricingCostingDto draftCosting = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("draft", null), importActor);
        costingService.recalculate(draftCosting.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(draftCosting.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);
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
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
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
        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("unit test", UUID.randomUUID().toString()), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);
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

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A4".equals(factory) ? catalogProductIdFactoryA
            : "Factory B4".equals(factory) ? catalogProductIdFactoryB : null;
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, null, null, "60x60", factory, qty, qty, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
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
