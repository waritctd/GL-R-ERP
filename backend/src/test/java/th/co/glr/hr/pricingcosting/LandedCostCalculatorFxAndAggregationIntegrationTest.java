package th.co.glr.hr.pricingcosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
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
 * Real-DB proof for the three P0/P1a fixes to {@link LandedCostCalculator} and {@code FxResolver}
 * that need the FULL pricing-decision pipeline (ticket &rarr; pricing request &rarr; factory quote
 * &rarr; {@code startReview}) rather than a DB-free unit test — {@code FxResolverTest} covers the
 * FX gate's OWN logic in isolation; this class proves the fixes reach the real chain.
 *
 * <p>Mirrors {@link LandedCostCalculatorFormulaIntegrationTest}'s wiring exactly (same fixture
 * conventions, same hand-wired services) — that class owns V109 formula-arithmetic proof; this one
 * owns the P0 FX-gate and P1a aggregation proof, kept separate so neither file's purpose blurs.
 */
class LandedCostCalculatorFxAndAggregationIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingDecisionService decisionService;
    private LandedCostCalculator landedCostCalculator;
    private CustomerRepository customers;
    private ProjectRepository projects;
    private TicketService ticketService;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;

    @BeforeEach
    void wireServices() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customers = new CustomerRepository(jdbc);
        projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-fx-aggregation-test-uploads");
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, mock(th.co.glr.hr.factoryquote.FactoryQuoteCarryForward.class));
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        AppProperties dispatchProperties = new AppProperties();
        dispatchProperties.getFactoryQuoteDispatch().setReclaimTimeoutSeconds(2);
        dispatchProperties.getFactoryQuoteDispatch().setMaxAttempts(3);
        dispatchProperties.getFactoryQuoteDispatch().setBackoffBaseSeconds(1);
        dispatchProperties.getFactoryQuoteDispatch().setBatchSize(20);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaConfigRepository formulaConfigRepository = new PricingFormulaConfigRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(formulaConfigRepository);
        landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);
        costingRepository = new PricingCostingRepository(jdbc);
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย FX", "sales-fx-agg@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า FX", "import-fx-agg@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร FX", "ceo-fx-agg@glr.co.th", "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // P0: a CEO-entered MANUAL non-THB rate reaches startReview, and is fully traceable
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Before the P0 fix, EVERY non-THB pricing request 422'd here — {@code FxResolver} refused any
     * rate not sourced from BOT, and the ONLY writer reachable from the CEO settings UI ({@code
     * FxRateRepository#upsert}) hardcodes {@code source = 'MANUAL'}. This proves the fix through
     * the REAL chain: a MANUAL EUR rate, seeded exactly the way the CEO settings screen would write
     * it, now lets {@code startReview} succeed — and asserts the traceability claim the fix's own
     * reasoning depends on: the decision AND the costing row both record {@code fxSource =
     * "MANUAL"}, not just a believed side effect.
     */
    @Test
    void manualEurRate_reachesStartReview_andDecisionAndCostingRecordFxSourceManual() {
        seedManualFxRate("EUR", new BigDecimal("38.5000"), LocalDate.now());

        long pricingRequestId = singleItemReadyForReview("FX Manual Factory", "EUR");

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "EUR", null, UUID.randomUUID().toString()),
            ceoActor);

        assertThat(decision.fxSource())
            .as("the decision header must pin the ACTUAL source used, not a hardcoded BOT")
            .isEqualTo("MANUAL");
        assertThat(decision.fxRateUsed()).isEqualByComparingTo("38.5000");

        PricingCostingItemDto costingItem = costingRepository.find(decision.pricingCostingId())
            .orElseThrow().items().get(0);
        assertThat(costingItem.fxSource())
            .as("traceability claim: a MANUAL rate is stamped MANUAL on the costing row that "
                + "actually used it, not only on the decision header — this is what the P0 fix's "
                + "reasoning depends on, so it must be asserted, not believed")
            .isEqualTo("MANUAL");
        assertThat(costingItem.fxRate()).isEqualByComparingTo("38.5000");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // P1a: every missing-factor problem across every item is reported together, in one 422
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Two items, from TWO DIFFERENT factories (so two shipments) — this is what actually
     * discriminates the fix from a narrower one that only aggregates WITHIN a shipment. Item A is
     * missing {@code sqmPerUnit} (its factory quote gives none AND its request has no {@code
     * requestedQtySqm} to fall back to); item B is quoted PER_BOX with no {@code piecesPerBox}.
     * Both are cluster-2 ({@code missingFactor}) problems, on items from different shipments — the
     * old, per-shipment {@code resolveItemPhysicals} call would have thrown on item A's shipment
     * alone, and the CEO would never learn about item B until fixing A and re-running. This is one
     * of the two tests this task's mutation-check targets: reverting the aggregation must turn
     * THIS test red (specifically: only ONE of the two assertions below still holds) and nothing
     * else.
     */
    @Test
    void twoItemsAcrossTwoFactories_eachMissingADifferentFactor_bothReportedInOneMessage() {
        long pricingRequestId = twoItemTwoFactoriesEachMissingADifferentFactor();

        assertThatThrownBy(() -> decisionService.startReview(pricingRequestId,
                new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
                ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage())
                    .as("BOTH items' problems, and BOTH factor names in Thai, must appear in the "
                        + "SAME message — proves aggregation across shipments, not first-problem-only")
                    .contains("Model NoSqm")
                    .contains("ตร.ม. ต่อหน่วย (sqmPerUnit)")
                    .contains("Model NoBox")
                    .contains("จำนวนแผ่นต่อกล่อง (piecesPerBox)")
                    // Both bullets present means neither problem shadowed the other.
                    .contains("\n- ");
            });
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // isFullyResolvable's semantics must survive the restructure: false, never a thrown exception
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code FactoryQuoteCarryForward} and {@code FactoryQuoteService#markReadyForCosting} both
     * depend on {@code isFullyResolvable} returning a plain {@code false} (never letting the
     * {@code ApiException} escape) for a request that is not yet costable — a request with no
     * factory quote at all is the simplest such case. Proves this still holds after resolveSources
     * was rewritten to accumulate rather than throw-first.
     */
    @Test
    void isFullyResolvable_returnsFalse_neverThrows_whenNoFactoryQuoteExistsYet() {
        long pricingRequestId = singleItemSubmittedNoQuoteYet("FX Unresolvable Factory");
        var summary = pricingRequestService.get(pricingRequestId, importActor).summary();

        boolean resolvable = landedCostCalculator.isFullyResolvable(summary);

        assertThat(resolvable)
            .as("no factory quote exists yet, so this must be false — and must NOT throw, or "
                + "every caller of isFullyResolvable (markReadyForCosting, FactoryQuoteCarryForward) "
                + "would need its own try/catch instead of a plain boolean check")
            .isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Seeds (or overwrites) a MANUAL {@code sales.fx_rates} row exactly the shape {@code
     * FxRateRepository#upsert} — the CEO settings FX write path — produces: {@code source =
     * 'MANUAL'}, {@code fetched_at = NULL}. Deliberately raw SQL, not the repository, so this test
     * does not depend on the very write path {@code FxResolverTest} already covers in isolation. */
    private void seedManualFxRate(String currency, BigDecimal rateToThb, LocalDate effectiveDate) {
        jdbc.update("""
            INSERT INTO sales.fx_rates (currency, rate_to_thb, effective_date, source, fetched_at, updated_at)
            VALUES (:currency, :rate, :date, 'MANUAL', NULL, now())
            ON CONFLICT (currency) DO UPDATE
            SET rate_to_thb = EXCLUDED.rate_to_thb, effective_date = EXCLUDED.effective_date,
                source = 'MANUAL', fetched_at = NULL, updated_at = now()
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("currency", currency).addValue("rate", rateToThb).addValue("date", effectiveDate));
    }

    /** One item, one factory, driven to READY_FOR_CEO_REVIEW, quoted (and requested) entirely in
     * {@code currency} PER_PIECE — the simplest costable fixture, parametrized only by currency so
     * the FX test above can drive a non-THB chain end to end. */
    private long singleItemReadyForReview(String factoryName, String currency) {
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:name, :email, :currency, 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, country = EXCLUDED.country
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("name", factoryName).addValue("email", "fx-" + UUID.randomUUID() + "@example.com")
                .addValue("currency", currency));
        long productId = insertCatalogProduct(factoryName, "IT", "FX-TEST-" + UUID.randomUUID(),
            new BigDecimal("100.00"), currency, "per_piece");

        long ticketId = createDeal("ดีล FX " + UUID.randomUUID(), factoryName);
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, productId, null, "Test", "Model", "Test Model", null, null, "1x1", factoryName,
            new BigDecimal("100"), new BigDecimal("100"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, currency, "fx test request", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).stream()
            .filter(q -> factoryName.equals(q.factoryName())).findFirst().orElseThrow();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-FX", currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("100"),
                UnitBasis.PER_PIECE, UnitBasis.PER_PIECE, new BigDecimal("100.00"), currency, null,
                new BigDecimal("1"), null, null, "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
    }

    /**
     * Two factories, one item each, both items with a fully valid factory/quote/price/currency/unit
     * (so {@code resolveSources} — cluster 1 — succeeds for both and the request auto-advances to
     * READY_FOR_CEO_REVIEW), but each missing a DIFFERENT physical conversion factor (cluster 2):
     * <ul>
     *   <li>"Model NoSqm" (factory A): quoted with {@code sqmPerUnit = null}, and its request item's
     *       {@code requestedQtySqm} is ALSO null — defeats both of {@code resolveSqmPerPiece}'s
     *       sources, so it throws {@code missingFactor("sqmPerUnit")}.
     *   <li>"Model NoBox" (factory B): the REQUEST is expressed PER_BOX with no {@code
     *       piecesPerBox} on the quote — deliberately NOT the quote's own {@code unitBasis} (quoted
     *       PER_PIECE instead, with {@code sqmPerUnit} supplied so {@code resolveSqmPerPiece} itself
     *       succeeds): {@code FactoryQuoteService#receive} already refuses a PER_BOX/PER_SQM/
     *       PER_LINEAR_M *quote* response with no matching factor (422 at receive time, before this
     *       item could ever reach the calculator), so a quote-side gap is not reachable through the
     *       normal flow — but that check never cross-references the REQUEST's own {@code
     *       requestedUnitBasis}, so {@code quantityToPieces}'s PER_BOX branch (driven by the
     *       request, independent of the quote) is where a real gap survives, and is what this
     *       isolates.
     * </ul>
     */
    private long twoItemTwoFactoriesEachMissingADifferentFactor() {
        String factoryA = "Missing Sqm Factory " + UUID.randomUUID();
        String factoryB = "Missing Box Factory " + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:a, 'agg-a@example.com', 'THB', 'piece', 'Italy'),
                   (:b, 'agg-b@example.com', 'THB', 'piece', 'Italy')
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("a", factoryA).addValue("b", factoryB));
        long productA = insertCatalogProduct(factoryA, "IT", "AGG-A-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        long productB = insertCatalogProduct(factoryB, "IT", "AGG-B-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");

        long ticketId = createTwoFactoryDeal("ดีล Aggregation " + UUID.randomUUID(), factoryA, factoryB);

        PricingRequestItemRequest itemA = new PricingRequestItemRequest(
            null, productA, null, "Brand", "Model NoSqm", "Brand Model NoSqm", null, null, "1x1", factoryA,
            new BigDecimal("100"), null, "piece", UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
        PricingRequestItemRequest itemB = new PricingRequestItemRequest(
            null, productB, null, "Brand", "Model NoBox", "Brand Model NoBox", null, null, "1x1", factoryB,
            new BigDecimal("10"), new BigDecimal("100"), "box", UnitBasis.PER_BOX,
            QuantityType.CONFIRMED, null, null, null);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "aggregation test request", UUID.randomUUID().toString(), List.of(itemA, itemB));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draftA = drafts.stream().filter(q -> factoryA.equals(q.factoryName())).findFirst().orElseThrow();
        FactoryQuoteDto draftB = drafts.stream().filter(q -> factoryB.equals(q.factoryName())).findFirst().orElseThrow();

        // Factory A: PER_PIECE, sqmPerUnit intentionally null.
        ReceiveFactoryQuoteRequest responseA = new ReceiveFactoryQuoteRequest("REF-AGG-A", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draftA.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("100"),
                UnitBasis.PER_PIECE, UnitBasis.PER_PIECE, new BigDecimal("100.00"), "THB", null,
                null, null, null, "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto respondedA = factoryQuoteService.receive(draftA.id(), responseA, importActor);
        factoryQuoteService.markReadyForCosting(respondedA.id(), importActor);

        // Factory B: quoted PER_PIECE (sqmPerUnit supplied, so resolveSqmPerPiece succeeds and
        // FactoryQuoteService#receive's own PER_BOX/PER_SQM/PER_LINEAR_M validation never triggers
        // — that check only looks at the QUOTE's basis). piecesPerBox is null; the REQUEST's own
        // PER_BOX basis (see itemB above) is what drives quantityToPieces to need it anyway.
        ReceiveFactoryQuoteRequest responseB = new ReceiveFactoryQuoteRequest("REF-AGG-B", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draftB.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("100"),
                UnitBasis.PER_PIECE, UnitBasis.PER_PIECE, new BigDecimal("100.00"), "THB", null,
                new BigDecimal("1"), null, null, "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto respondedB = factoryQuoteService.receive(draftB.id(), responseB, importActor);
        factoryQuoteService.markReadyForCosting(respondedB.id(), importActor);

        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .as("both cluster-1 concerns (factory/quote/price/currency/unit) resolve cleanly for "
                + "both items, so the request DOES auto-advance — the missing factors are a "
                + "cluster-2 problem that only surfaces when calculate() actually runs")
            .isEqualTo(th.co.glr.hr.pricingrequest.PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    /** A single item, single factory, submitted and picked up but with NO factory quote drafted or
     * received yet — {@code resolveSources} fails at the "quote not found" step, the simplest
     * unresolvable state. */
    private long singleItemSubmittedNoQuoteYet(String factoryName) {
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:name, 'unresolvable@example.com', 'THB', 'piece', 'Italy')
            """, Map.of("name", factoryName));
        long productId = insertCatalogProduct(factoryName, "IT", "UNRESOLVED-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        long ticketId = createDeal("ดีล Unresolvable " + UUID.randomUUID(), factoryName);
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, productId, null, "Test", "Model", "Test Model", null, null, "1x1", factoryName,
            new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "unresolvable test request", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        // Deliberately stop here — no generateDrafts/receive/markReadyForCosting at all.
        return pricingRequestId;
    }

    private long createDeal(String dealName, String factoryName) {
        CustomerDto customer = customers.create(
            "บริษัท " + UUID.randomUUID() + " จำกัด", "010" + System.nanoTime() % 10000000L,
            "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        ProjectDto project = projects.create(customer.id(), "โครงการ " + dealName);
        TicketDto created = ticketService.create(
            new CreateTicketRequest(dealName, "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("Test", "Model", factoryName))),
            salesActor);
        return created.summary().id();
    }

    private long createTwoFactoryDeal(String dealName, String factoryA, String factoryB) {
        CustomerDto customer = customers.create(
            "บริษัท " + UUID.randomUUID() + " จำกัด", "010" + System.nanoTime() % 10000000L,
            "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        ProjectDto project = projects.create(customer.id(), "โครงการ " + dealName);
        TicketDto created = ticketService.create(
            new CreateTicketRequest(dealName, "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(
                    ticketItem("Brand", "Model NoSqm", factoryA),
                    ticketItem("Brand", "Model NoBox", factoryB))),
            salesActor);
        return created.summary().id();
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
