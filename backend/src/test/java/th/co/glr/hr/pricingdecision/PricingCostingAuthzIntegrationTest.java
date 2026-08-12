package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
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
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.CostOverrideRequest;
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
 * V141 ("CEO owns costing") required authz evidence — CLAUDE.md's "Permission changes must ship
 * evidence, not a claim": {@code recalculateCost}/{@code overrideItemCost} replace what used to be
 * an {@code import}-only write surface ({@code PricingCostingService.createDraft/recalculate/
 * submit}, all deleted) with a CEO-only one ({@link PricingDecisionService#CEO_ROLES}). That is
 * exactly the class of change CLAUDE.md requires a real-DB integration test through the real Java
 * service for — never inferred from {@code mockApi.js}.
 *
 * <p>Written WRONG-WAY-ROUND throughout: every negative test asserts the caller <b>cannot</b>
 * reach what they should not, not that the CEO can reach their own — and asserts the
 * <b>absence</b> of the write in the database afterward (a query, not merely the thrown status
 * code), per CLAUDE.md's "Assert the caller cannot reach what they shouldn't" + "Mutation-check
 * the guard" requirements.
 *
 * <p>{@code import}'s continuing READ access ({@code list}/{@code get} on {@link
 * PricingCostingService}) is the one deliberately preserved grant (plan section 2.4) — proven as
 * a positive control, not assumed.
 */
class PricingCostingAuthzIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingService costingService;
    private PricingDecisionService decisionService;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;

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

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-costing-authz-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage);
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
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new PriceCalcConfigRepository(jdbc), new FactoryConfigRepository(jdbc));
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator);
        th.co.glr.hr.pricing.PriceCalcService priceCalcMock = mock(th.co.glr.hr.pricing.PriceCalcService.class);
        TicketService ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย ออธ", "sales-costing-authz@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า ออธ", "import-costing-authz@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร ออธ", "ceo-costing-authz@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี ออธ", "account-costing-authz@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย ออธ", "sales-manager-costing-authz@glr.co.th",
            "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES
                ('Factory A-CostingAuthz', 'factory-a-costing-authz@example.com', 'THB', 'piece', 'Thailand'),
                ('Factory B-CostingAuthz', 'factory-b-costing-authz@example.com', 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
        catalogProductIdFactoryA = insertCatalogProduct("Factory A-CostingAuthz", "TH", "TEST-CA-A-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B-CostingAuthz", "TH", "TEST-CA-B-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Costing Authz จำกัด", "0100000000010", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0010");
        ProjectDto project = projects.create(customer.id(), "โครงการ Costing Authz");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Costing Authz", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A", "Factory A-CostingAuthz"),
                    ticketItem("Cotto", "Tile B", "Factory B-CostingAuthz"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // recalculateCost — wrong-way-round: import/sales/sales_manager/account all refused,
    // ceo (positive control) can.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void recalculateCost_refusesImportSalesSalesManagerAndAccount_ceoCanStillWrite() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        PricingDecisionItemDto itemBefore = decision.items().get(0);
        BigDecimal frozenCostBefore = itemBefore.frozenLandedCostPerPieceThb();
        Instant calculatedAtBefore = costingItemFor(decision.pricingCostingId(), itemBefore.pricingRequestItemId())
            .calculatedAt();

        for (UserPrincipal forbidden : List.of(importActor, salesActor, salesManagerActor, accountActor)) {
            assertThatThrownBy(() -> decisionService.recalculateCost(decision.id(), forbidden))
                .as("role '%s' must be refused recalculateCost", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        // Assert the ABSENCE of the write, not merely the thrown status code: recalculateCost's
        // replaceItemsPreservingOverrides deletes+reinserts every pricing_costing_item row (which
        // would have bumped calculated_at to a fresh now()), and updateFrozenCosts would have
        // changed the decision item's frozen cost — neither happened.
        PricingDecisionItemDto itemAfterForbiddenAttempts = decisionService.get(decision.id(), importActor).items()
            .stream().filter(i -> i.id() == itemBefore.id()).findFirst().orElseThrow();
        assertThat(itemAfterForbiddenAttempts.frozenLandedCostPerPieceThb()).isEqualByComparingTo(frozenCostBefore);
        assertThat(costingItemFor(decision.pricingCostingId(), itemBefore.pricingRequestItemId()).calculatedAt())
            .isEqualTo(calculatedAtBefore);

        // Positive control: ceo (CEO_ROLES) is NOT stopped by the role gate — proven by reaching
        // all the way past requireRole into the real repository write, unlike the four roles
        // above which never get past requireRole at all (403, before any DB access). This used to
        // be unable to assert success: recalculateCost was broken by an unrelated, unconditional
        // production bug (found while writing this test — see PricingCostingRepository
        // #replaceItemsPreservingOverrides's own javadoc for the full account of the fix). Now
        // that PricingCostingRepository#replaceItemsPreservingOverrides is an UPSERT instead of a
        // delete+reinsert, ceo's call genuinely SUCCEEDS and the write actually lands — proving
        // ceo reaches the real write path, not merely past the role gate.
        PricingDecisionDto recalculatedByCeo = decisionService.recalculateCost(decision.id(), ceoActor);
        assertThat(recalculatedByCeo.status()).isEqualTo(PricingDecisionStatus.DRAFT);
        assertThat(costingItemFor(decision.pricingCostingId(), itemBefore.pricingRequestItemId()).calculatedAt())
            .isAfter(calculatedAtBefore);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // overrideItemCost — wrong-way-round: import/sales/sales_manager/account all refused,
    // ceo (positive control) can.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void overrideItemCost_refusesImportSalesSalesManagerAndAccount_ceoCanStillWrite() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);
        CostOverrideRequest overrideAttempt = new CostOverrideRequest(new BigDecimal("999.0000"), "attempted override");

        for (UserPrincipal forbidden : List.of(importActor, salesActor, salesManagerActor, accountActor)) {
            assertThatThrownBy(() -> decisionService.overrideItemCost(decision.id(), item.id(), overrideAttempt, forbidden))
                .as("role '%s' must be refused overrideItemCost", forbidden.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        // Assert the ABSENCE of the write with a direct DB query — no override row, no event.
        assertThat(jdbc.queryForObject("""
            SELECT manual_landed_cost_per_unit_thb FROM sales.pricing_costing_item
             WHERE pricing_costing_item_id = :id
            """, Map.of("id", item.pricingCostingItemId()), BigDecimal.class))
            .isNull();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_COSTING_ITEM_COST_OVERRIDDEN'
            """, Map.of("id", pricingRequestId), Long.class))
            .isZero();
        assertThat(decisionService.get(decision.id(), importActor).items().stream()
                .filter(i -> i.id() == item.id()).findFirst().orElseThrow().frozenLandedCostPerPieceThb())
            .isEqualByComparingTo(item.frozenLandedCostPerPieceThb());

        // Positive control: ceo (CEO_ROLES) can.
        PricingDecisionDto overridden = decisionService.overrideItemCost(decision.id(), item.id(), overrideAttempt, ceoActor);
        assertThat(overridden.items().stream().filter(i -> i.id() == item.id()).findFirst().orElseThrow()
            .frozenLandedCostPerPieceThb()).isEqualByComparingTo("999.0000");
        assertThat(jdbc.queryForObject("""
            SELECT manual_landed_cost_per_unit_thb FROM sales.pricing_costing_item
             WHERE pricing_costing_item_id = :id
            """, Map.of("id", item.pricingCostingItemId()), BigDecimal.class))
            .isEqualByComparingTo("999.0000");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Positive control: import keeps its deliberately-preserved READ grant (list/get) even
    // though its WRITE access to costing is gone entirely.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void import_canStillReadListAndGet_theDeliberatelyPreservedGrant() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);

        assertThat(costingService.list(pricingRequestId, importActor)).isNotEmpty();
        assertThat(costingService.get(decision.pricingCostingId(), importActor).id())
            .isEqualTo(decision.pricingCostingId());
    }

    /**
     * The three Import-era costing WRITE paths are severed, not deleted — they 409 for EVERY role,
     * including the CEO, because the action itself no longer exists rather than having moved to a
     * different actor. The routes survive only so the pending frontend pass can drop
     * {@code routes.js}/{@code hrApi.js}/{@code mockApi.js} together without {@code
     * contract.test.js} going red in the meantime (same treatment {@code TicketService#submit} got).
     *
     * <p>Asserted wrong-way-round on purpose: the point is that nobody can reach them, and that no
     * costing row appears as a side effect of trying.
     */
    @Test
    void theThreeImportCostingWritePaths_areSeveredFor409_forEveryRoleIncludingCeo_andWriteNothing() {
        long pricingRequestId = twoItemSubmittedCosting();
        long costingsBefore = countCostingsFor(pricingRequestId);

        for (UserPrincipal actor : List.of(importActor, ceoActor, salesActor, salesManagerActor, accountActor)) {
            assertThatThrownBy(() -> costingService.createDraft(pricingRequestId,
                new th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest(null,
                    UUID.randomUUID().toString()), actor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

            assertThatThrownBy(() -> costingService.recalculate(1L,
                new th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest(null), actor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

            assertThatThrownBy(() -> costingService.submit(1L,
                new th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest(null), actor))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }

        // Wrong-way-round: prove the refusals wrote nothing, rather than trusting the status code.
        assertThat(countCostingsFor(pricingRequestId))
            .as("a severed write path must not create a costing row for anyone")
            .isEqualTo(costingsBefore);
        // And the request is still where markReadyForCosting's auto-advance left it.
        assertThat(pricingRequests.findSummary(pricingRequestId).orElseThrow().status())
            .isEqualTo("READY_FOR_CEO_REVIEW");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private long countCostingsFor(long pricingRequestId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM sales.pricing_costing WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), Long.class);
        return count == null ? 0L : count;
    }

    private PricingCostingItemDto costingItemFor(long costingId, long pricingRequestItemId) {
        return costingService.get(costingId, importActor).items().stream()
            .filter(i -> i.pricingRequestItemId() == pricingRequestItemId)
            .findFirst().orElseThrow();
    }

    /** Two-item, two-factory scenario driven to READY_FOR_CEO_REVIEW — the precondition every
     * startReview call below starts from. V141 ("CEO owns costing"): markReadyForCosting's
     * auto-advance (once the LAST factory quote resolves) is what gets the request here; no
     * standalone costing row exists until a test calls decisionService.startReview itself. */
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
            new BigDecimal("1000.00"), "THB", "costing authz request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A", "Factory A-CostingAuthz", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B", "Factory B-CostingAuthz", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A-CostingAuthz".equals(factory) ? catalogProductIdFactoryA
            : "Factory B-CostingAuthz".equals(factory) ? catalogProductIdFactoryB : null;
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
