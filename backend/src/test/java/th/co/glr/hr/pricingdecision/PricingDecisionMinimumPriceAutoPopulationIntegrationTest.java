package th.co.glr.hr.pricingdecision;

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
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationItemRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
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
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Phase 1 UI simplification: ราคาขั้นต่ำ is no longer a CEO input, so PricingDecisionService#approve now
 * auto-populates minimumSellingPricePerRequestedUnit with the approved selling price itself whenever the CEO
 * has not explicitly set a lower floor. */
class PricingDecisionMinimumPriceAutoPopulationIntegrationTest extends AbstractPostgresIntegrationTest {
    // CEO discount-approval workflow, Phase 2 (owner ruling 2026-08-16, V155): saving a below-minimum discount no
    // longer throws at update() at all — see th.co.glr.hr.customerquotation.DiscountApprovalIntegrationTest for
    // the full approval workflow.
    private static final String BELOW_MINIMUM_ON_ISSUE = "ไม่สามารถออกใบเสนอราคาได้ — รายการ";

    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;

    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private long ticketId;
    private long catalogProductId;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        TicketRepository tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-min-price-autopop-test-uploads");
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper,
            customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications, new th.co.glr.hr.customerquotation.DiscountApprovalRepository(jdbc));

        long salesRepId = createEmployee(employees, "พนักงานขาย ขั้นต่ำ", "sales-min-price-autopop@glr.co.th",
            "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า ขั้นต่ำ", "import-min-price-autopop@glr.co.th",
            "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร ขั้นต่ำ", "ceo-min-price-autopop@glr.co.th",
            "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        catalogProductId = insertCatalogProduct("Factory MinPriceAutoPop", "IT", "TEST-MPA-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Min Price AutoPop จำกัด", "0100000000021", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0021");
        ProjectDto project = projects.create(customer.id(), "โครงการ Min Price AutoPop");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Min Price AutoPop", "NORMAL", customer.name(), customer.id(),
                project.id(), null, null, null, List.of(ticketItem())),
            salesActor);
        ticketId = created.summary().id();
    }

    // Wrong-way-round: the guard still fires with ZERO CEO input into ราคาขั้นต่ำ anywhere.

    /** The direct proof the task brief asks for. */
    @Test
    void minimumSellingPrice_isNeverNull_evenWhenTheCeoNeverTypedOne_soTheFirstDiscountNeedsCeoApprovalBeforeIssue() {
        PricingDecisionDto approved = approveOneItemDecision(new BigDecimal("0.20"));
        PricingDecisionItemDto approvedItem = approved.items().get(0);

        // The auto-population itself, at the source: never null, always equal to the approved price (floor == price ->
        // zero discount headroom, exactly today's "any discount needs CEO approval" outcome per the task brief).
        assertThat(approvedItem.minimumSellingPricePerRequestedUnit()).isNotNull();
        assertThat(approvedItem.minimumSellingPricePerRequestedUnit())
            .isEqualByComparingTo(approvedItem.approvedSellingPricePerRequestedUnit());

        CustomerQuotationDto draft = quotationService.create(approved.pricingRequestId(),
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);
        BigDecimal oneSatang = new BigDecimal("0.01");

        // Phase 2 (2026-08-16): update() no longer throws — it saves, and opens a CEO approval request
        // (wrong-way-round proof this file still cares about: the request exists at exactly one satang below the
        // auto-populated minimum, the smallest possible breach).
        CustomerQuotationDto discounted = quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(item.id(), null, null, oneSatang))), salesActor);
        assertThat(discounted.items().get(0).salesDiscount()).isEqualByComparingTo(oneSatang);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation_item_discount_approval
             WHERE quotation_item_id = :itemId AND status = 'PENDING'
            """, Map.of("itemId", discounted.items().get(0).id()), Long.class)).isEqualTo(1L);

        // issue() is the site that still refuses this un-approved, below-minimum line.
        assertThatThrownBy(() -> quotationService.issue(discounted.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).startsWith(BELOW_MINIMUM_ON_ISSUE);
            });
    }

    /** Same proof at the ISSUE gate — CustomerQuotationService#issue's own independent null-guarded check, not the
     * one update already refused above. Corrupts the discount directly via SQL so issue() is what has to catch it,
     * not update(). */
    @Test
    void minimumSellingPrice_autoPopulated_alsoBlocksIssueOfAnAlreadyDiscountedLine() {
        PricingDecisionDto approved = approveOneItemDecision(new BigDecimal("0.20"));
        CustomerQuotationDto draft = quotationService.create(approved.pricingRequestId(),
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);

        jdbc.update("""
            UPDATE sales.quotation_item
               SET sales_discount = 0.01,
                   final_unit_price = final_unit_price - 0.01
             WHERE quotation_item_id = :id
            """, Map.of("id", item.id()));

        assertThatThrownBy(() -> quotationService.issue(draft.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).startsWith(BELOW_MINIMUM_ON_ISSUE);
            });
    }

    /** A margin of exactly 0 still produces a non-null minimum (== the approved price, which is itself equal to
     * cost at 0 margin) — the auto-population does not depend on the price being "large" or the margin being
     * positive, only on the item having been approved at all. */
    @Test
    void minimumSellingPrice_autoPopulated_evenAtZeroMargin_stillNeedsApprovalBeforeIssue() {
        PricingDecisionDto approved = approveOneItemDecision(BigDecimal.ZERO);
        PricingDecisionItemDto approvedItem = approved.items().get(0);
        assertThat(approvedItem.minimumSellingPricePerRequestedUnit())
            .isEqualByComparingTo(approvedItem.approvedSellingPricePerRequestedUnit());

        CustomerQuotationDto draft = quotationService.create(approved.pricingRequestId(),
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);

        CustomerQuotationDto discounted = quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(item.id(), null, null, new BigDecimal("0.01")))),
            salesActor);
        assertThat(discounted.items().get(0).salesDiscount()).isEqualByComparingTo(new BigDecimal("0.01"));

        assertThatThrownBy(() -> quotationService.issue(discounted.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    /** Documents the mutation this file exists to catch — see this task's own mutation-check step. This test itself
     * only pins the CURRENT (correct) behaviour at the DTO level as a fast, non-quotation-round-trip signal; it is
     * not the mutation-check itself. */
    @Test
    void approve_leavesMinimumNull_isTheMutationThisFileExistsToCatch() {
        PricingDecisionDto approved = approveOneItemDecision(new BigDecimal("0.20"));
        assertThat(approved.items().get(0).minimumSellingPricePerRequestedUnit()).isNotNull();
    }

    // Helpers

    /** One-item deal driven all the way to an APPROVED pricing decision, with margin as the ONLY CEO input — no
     * minimum-price field touched anywhere, matching the Phase 1 UI exactly. */
    private PricingDecisionDto approveOneItemDecision(BigDecimal marginPct) {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, oneItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : factoryQuoteService.generateDrafts(pricingRequestId, importActor)) {
            factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), draft.items().get(0).pricingRequestItemId()), importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(marginPct, "THB", null, null), ceoActor);
        return decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
    }

    private ReceiveFactoryQuoteRequest response(String ref, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, "THB", "30 days", "45 days", "revision", "note",
            List.of(new ReceiveFactoryQuoteItemRequest(pricingRequestItemId, null, null,
                new BigDecimal("1.00"), "piece", "piece", new BigDecimal("100.00"), "THB", null,
                new BigDecimal("1.00"), null, null, "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
    // required on every item PricingRequestService#createDraft persists —
    // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
    private PricingRequestRequests.CreatePricingRequestRequest oneItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "min price auto-population test request", UUID.randomUUID().toString(),
            List.of(new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null,
                "SCG", "Tile MPA", "SCG Tile MPA", "White", "Matte", "60x60", "Factory MinPriceAutoPop",
                null, null, null, null,
                QuantityType.CONFIRMED, null, null, null,
                null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
                null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
                false, "ไทย-สต็อก", 3, 7, null, null, null)));
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Tile MPA", "White", "Matte", "60x60", "Factory MinPriceAutoPop",
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
