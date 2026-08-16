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
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
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
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Phase 1 UI simplification (owner ruling 2026-08-16): ราคาขั้นต่ำ is no longer a CEO input, so
 * {@link PricingDecisionService#approve} now auto-populates {@code
 * minimumSellingPricePerRequestedUnit} with the approved selling price itself whenever the CEO
 * has not explicitly set a lower floor (see that method's own doc comment for the full
 * reasoning).
 *
 * <p><b>Why this file exists — the money hole it closes.</b> {@code CustomerQuotationService}
 * enforces the floor in three places (update/issue/buildItem), and all three checks are
 * NULL-GUARDED: {@code item.minimumSellingPricePerRequestedUnit() != null && ... < minimum}. A UI
 * change that simply stops setting the field would leave it NULL forever, which does not fail
 * one check — it silently disarms all three, so every future discount on every future pricing
 * decision goes completely unguarded. That is exactly the class of defect this repo has been
 * bitten by before (see CLAUDE.md's "mock more permissive than production" section for the same
 * failure shape in a different feature).
 *
 * <p><b>Written wrong-way-round throughout</b>, per CLAUDE.md: every test here asserts a
 * DISCOUNTED quotation line is still REFUSED, not that a permitted one succeeds — "the guard still
 * fires" is the claim under test, not "the happy path still works". {@link
 * #minimumSellingPrice_isNeverNull_evenWhenTheCeoNeverTypedOne_soTheFirstDiscountAttemptIs422ed}
 * is the direct proof: zero CEO input into ราคาขั้นต่ำ anywhere in this test, and the very first
 * ฿0.01 discount on the very first quotation line is still refused with a 422 quoting the
 * CEO-approved minimum. {@link
 * #approve_leavesMinimumNull_isTheMutationThisFileExistsToCatch} documents (does not itself
 * enforce — see this task's own mutation-check step) what a regression here would look like: a
 * decision item approved with a NULL minimum is the exact hole CustomerQuotationService's guards
 * cannot see through.
 *
 * <p>Real service, real repository, real Postgres — no Mockito on the path under test (the outer
 * class fields ARE mocked, matching every sibling fixture file in this package, but nothing here
 * stubs {@code PricingDecisionService} or {@code CustomerQuotationService} themselves).
 */
class PricingDecisionMinimumPriceAutoPopulationIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String BELOW_MINIMUM_ON_UPDATE_PREFIX = "ราคาหลังหักส่วนลดของรายการ";
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
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        th.co.glr.hr.pricing.PriceCalcService priceCalcMock = mock(th.co.glr.hr.pricing.PriceCalcService.class);
        TicketService ticketService = new TicketService(tickets, notifications, priceCalcMock, objectMapper,
            customers, new QuotationRenderer(), pricingRequestService);
        CustomerQuotationRepository quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications);

        long salesRepId = createEmployee(employees, "พนักงานขาย ขั้นต่ำ", "sales-min-price-autopop@glr.co.th",
            "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า ขั้นต่ำ", "import-min-price-autopop@glr.co.th",
            "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร ขั้นต่ำ", "ceo-min-price-autopop@glr.co.th",
            "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Factory MinPriceAutoPop', 'factory-min-price-autopop@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
        catalogProductId = insertCatalogProduct("Factory MinPriceAutoPop", "TH", "TEST-MPA-001",
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Wrong-way-round: the guard still fires with ZERO CEO input into ราคาขั้นต่ำ anywhere.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The direct proof the task brief asks for. The CEO does nothing except set a margin (the
     * one remaining input) and approve — no minimum-price field exists in the Phase 1 UI at all —
     * yet the very first discount attempt on the resulting quotation, of just one satang, is still
     * refused with a 422 quoting the CEO-approved minimum.
     */
    @Test
    void minimumSellingPrice_isNeverNull_evenWhenTheCeoNeverTypedOne_soTheFirstDiscountAttemptIs422ed() {
        PricingDecisionDto approved = approveOneItemDecision(new BigDecimal("0.20"));
        PricingDecisionItemDto approvedItem = approved.items().get(0);

        // The auto-population itself, at the source: never null, always equal to the approved
        // price (floor == price -> zero discount headroom, exactly today's "any discount refused"
        // outcome per the task brief).
        assertThat(approvedItem.minimumSellingPricePerRequestedUnit()).isNotNull();
        assertThat(approvedItem.minimumSellingPricePerRequestedUnit())
            .isEqualByComparingTo(approvedItem.approvedSellingPricePerRequestedUnit());

        CustomerQuotationDto draft = quotationService.create(approved.pricingRequestId(),
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);
        BigDecimal oneSatang = new BigDecimal("0.01");

        assertThatThrownBy(() -> quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(item.id(), null, null, oneSatang))), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).startsWith(BELOW_MINIMUM_ON_UPDATE_PREFIX);
            });

        // Wrong-way-round: nothing was mutated by the refused attempt.
        CustomerQuotationDto unchanged = quotationService.get(draft.id(), salesActor);
        assertThat(unchanged.items().get(0).salesDiscount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Same proof at the ISSUE gate — {@code CustomerQuotationService#issue}'s own independent
     * null-guarded check, not the one {@code update} already refused above. Corrupts the discount
     * directly via SQL so issue() is what has to catch it, not update(). */
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

    /** A margin of exactly 0 still produces a non-null minimum (== the approved price, which is
     * itself equal to cost at 0 margin) — the auto-population does not depend on the price being
     * "large" or the margin being positive, only on the item having been approved at all. */
    @Test
    void minimumSellingPrice_autoPopulated_evenAtZeroMargin_stillRefusesAnyDiscount() {
        PricingDecisionDto approved = approveOneItemDecision(BigDecimal.ZERO);
        PricingDecisionItemDto approvedItem = approved.items().get(0);
        assertThat(approvedItem.minimumSellingPricePerRequestedUnit())
            .isEqualByComparingTo(approvedItem.approvedSellingPricePerRequestedUnit());

        CustomerQuotationDto draft = quotationService.create(approved.pricingRequestId(),
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        CustomerQuotationItemDto item = draft.items().get(0);

        assertThatThrownBy(() -> quotationService.update(draft.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(item.id(), null, null, new BigDecimal("0.01")))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    /**
     * Documents the mutation this file exists to catch — see this task's own mutation-check step
     * (neuter the auto-population fallback in {@code PricingDecisionService#approve}, confirm
     * {@link #minimumSellingPrice_isNeverNull_evenWhenTheCeoNeverTypedOne_soTheFirstDiscountAttemptIs422ed}
     * goes red, restore, prove byte-identity by sha256). This test itself only pins the CURRENT
     * (correct) behaviour at the DTO level as a fast, non-quotation-round-trip signal; it is not
     * the mutation-check itself.
     */
    @Test
    void approve_leavesMinimumNull_isTheMutationThisFileExistsToCatch() {
        PricingDecisionDto approved = approveOneItemDecision(new BigDecimal("0.20"));
        assertThat(approved.items().get(0).minimumSellingPricePerRequestedUnit()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** One-item deal driven all the way to an APPROVED pricing decision, with margin as the ONLY
     * CEO input — no minimum-price field touched anywhere, matching the Phase 1 UI exactly. */
    private PricingDecisionDto approveOneItemDecision(BigDecimal marginPct) {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, oneItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : factoryQuoteService.generateDrafts(pricingRequestId, importActor)) {
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

    private PricingRequestRequests.CreatePricingRequestRequest oneItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "min price auto-population test request", UUID.randomUUID().toString(),
            List.of(new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null,
                "SCG", "Tile MPA", "SCG Tile MPA", null, null, "60x60", "Factory MinPriceAutoPop",
                new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
                QuantityType.CONFIRMED, null, null, null)));
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
