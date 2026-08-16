package th.co.glr.hr.pricingcosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ProductTypeOverrideRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
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
 * Real-DB (Postgres, real V109-seeded {@code sales.pricing_formula_config}) proof that {@link
 * LandedCostCalculator} and {@link PricingDecisionService#computeSellingPrice} actually implement
 * V109's formula — not mocked, not simulated. Every expected figure below is derived BY HAND in
 * each test's own comment, independently of the code under test (an expectation produced by
 * running the code proves nothing — see this branch's own brief).
 *
 * <p>Fixture convention: every factory is seeded with {@code country = 'Italy'} and every catalog
 * product defaults to {@code thickness_mm = 10} (inside V109's seeded Italy [8,12)mm band, whose
 * top quantity band is open-ended — see {@link AbstractPostgresIntegrationTest#insertCatalogProduct}),
 * so a plain-vanilla item is always costable; individual tests override country/thickness/quantity
 * to probe a specific band edge or a specific failure.
 */
class LandedCostCalculatorFormulaIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingDecisionRepository decisionRepository;
    private PricingDecisionService decisionService;
    private PricingFormulaConfigRepository formulaConfigRepository;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private long ticketId;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-formula-engine-test-uploads");
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
        formulaConfigRepository = new PricingFormulaConfigRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(formulaConfigRepository);
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);
        costingRepository = new PricingCostingRepository(jdbc);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        th.co.glr.hr.pricing.PriceCalcService priceCalcMock = mock(th.co.glr.hr.pricing.PriceCalcService.class);
        TicketService ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย V109", "sales-v109@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า V109", "import-v109@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร V109", "ceo-v109@glr.co.th", "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Formula Factory', 'formula-factory@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());

        CustomerDto customer = customers.create(
            "บริษัท V109 จำกัด", "0100000000109", "109 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0109");
        ProjectDto project = projects.create(customer.id(), "โครงการ V109");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล V109", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("Test", "Tile V109", "Formula Factory"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Hand-computed happy path: every formula step independently verified
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * ONE item, ONE factory shipment, thickness 10mm (Italy [8,12)mm band), 100 pieces @ 1
     * sqm/piece = 100 sqm total, quoted PER_PIECE at 100.00 THB/piece, requested PER_PIECE.
     *
     * <p>Hand-computed (every intermediate independently, base-10 arithmetic shown so it can be
     * checked without a calculator):
     * <pre>
     * C  (goods, total)   = 100.00 x 100 pieces                       = 10,000.0000
     * i  (insurance)      = 10000 x 1.15 x 0.0045 x 1.07               = 55.3725
     * F  (freight)        = Italy [8,12)mm, qty=100 -> band [1,101)   = 50,000.0000 (looked up, not computed)
     * cif = C + i + F     = 10000 + 55.3725 + 50000                    = 60,055.3725
     * T  (duty, TILE)     = 30% (default -- no override set)
     * duty amount = cif x T = 60055.3725 x 0.30 = 18016.61175, HALF_UP (exact ...5 midpoint,
     *               rounds away from zero)                              = 18,016.6118
     * (cif+duty) x cost_buffer(1.07) = 78071.9843 x 1.07 = 83537.023201, rounds to 83,537.0232
     * S  (clearance)      = qty=100 -> band [1,101)                    = 8,000.0000 (looked up)
     * TC (total)          = 83537.0232 + 8000.0000                     = 91,537.0232
     * UC (per sqm)        = TC / Q(100 sqm)                            = 915.370232
     * landed cost/piece   = UC x sqmPerPiece(1)                        = 915.3702 (money4)
     * total landed cost   = 915.3702 x 100 pieces                      = 91,537.0200
     *
     * Selling price (margin 20%, no manual override):
     * raw = 915.3702 x 1.20 x selling_buffer(1.07) = 1175.3353368
     * RoundUp to nearest ฿10: 1175.3353368 / 10 = 117.53... -> ceiling 118 -> x10             = 1,180.0000
     * </pre>
     */
    @Test
    void singleItem_fullFormulaPipeline_everyStepHandVerified() {
        long pricingRequestId = readyForReview(new BigDecimal("100"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE,
            new BigDecimal("100"), "100.00", new BigDecimal("1"), null, null);

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);
        PricingCostingItemDto costingItem = costingItemFor(decision);

        // Duty defaults to TILE (owner ruling — no product_type in deal data).
        assertThat(costingItem.productType()).isEqualTo("TILE");

        assertThat(costingItem.goodsCostThb()).isEqualByComparingTo("100.0000");
        assertThat(costingItem.insuranceCostThb()).isEqualByComparingTo("0.5537");
        assertThat(costingItem.freightCostThb()).isEqualByComparingTo("500.0000");
        assertThat(costingItem.cifCostThb()).isEqualByComparingTo("600.5537");
        assertThat(costingItem.importDutyThb()).isEqualByComparingTo("180.1661");
        assertThat(costingItem.clearanceFeeThb()).isEqualByComparingTo("80.0000");
        assertThat(costingItem.inlandTransportCostThb()).isEqualByComparingTo("0.0000");
        assertThat(costingItem.otherCostThb()).isEqualByComparingTo("0.0000");
        assertThat(costingItem.landedCostPerUnitThb()).isEqualByComparingTo("915.3702");
        assertThat(costingItem.totalLandedCostThb()).isEqualByComparingTo("91537.0200");

        assertThat(item.frozenLandedCostPerRequestedUnitThb()).isEqualByComparingTo("915.3702");
        assertThat(item.proposedMarginPct()).isEqualByComparingTo("0.20");
        assertThat(item.proposedSellingPricePerRequestedUnit()).isEqualByComparingTo("1180.0000");
    }

    /**
     * Same physical item, requested at the qty=101 sqm freight-band EDGE — min-inclusive means
     * 101 belongs to the NEXT band ([101,451) -> ฿80,000), not the previous one ([1,101) ->
     * ฿50,000). Proves the boundary through the REAL pipeline (real seeded config, real
     * LandedCostCalculator), not just the isolated PricingFormulaEngineTest.
     */
    @Test
    void bandEdge_qty101_landsInSecondFreightBand_throughTheRealPipeline() {
        long pricingRequestId = readyForReview(new BigDecimal("101"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE,
            new BigDecimal("101"), "100.00", new BigDecimal("1"), null, null);
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingCostingItemDto costingItem = costingItemFor(decision);
        // Freight PER PIECE: 80000 total / 101 pieces = 792.0792079... -> money4 792.0792.
        assertThat(costingItem.freightCostThb()).isEqualByComparingTo("792.0792");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Multi-item factory shipment: F/S are looked up ONCE and allocated proportionally by sqm
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * TWO items, SAME factory (so the SAME sales.factory_quote / "factory shipment"), SAME
     * thickness. Item A: 60 sqm. Item B: 40 sqm. Shipment total Q = 100 sqm -> the SAME freight
     * (฿50,000, band [1,101)) and clearance (฿8,000, band [1,101)) bands as the single-item test
     * above, but now split 60/40 between the two items instead of one item getting the whole
     * flat amount — proving F and S are shipment-level, not per-item, and are allocated fairly
     * rather than either double-charged or dropped.
     *
     * <p>Hand-computed allocation: item A gets 60% of both flats (F=30,000.0000, S=4,800.0000),
     * item B gets 40% (F=20,000.0000, S=3,200.0000) — and 30000+20000=50000,
     * 4800+3200=8000 confirms nothing was lost or duplicated across the pair.
     */
    @Test
    void multiItemShipment_freightAndClearance_allocatedProportionallyBySqm_notDuplicatedNorDropped() {
        long pricingRequestId = twoItemSameFactoryReadyForReview(
            new BigDecimal("60"), new BigDecimal("40")); // 60 sqm and 40 sqm, 1 sqm/piece each

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<PricingCostingItemDto> costingItems = costingRepository.find(decision.pricingCostingId()).orElseThrow().items();
        assertThat(costingItems).hasSize(2);

        PricingCostingItemDto itemA = costingItems.stream()
            .filter(i -> i.requestedQuantity().compareTo(new BigDecimal("60")) == 0).findFirst().orElseThrow();
        PricingCostingItemDto itemB = costingItems.stream()
            .filter(i -> i.requestedQuantity().compareTo(new BigDecimal("40")) == 0).findFirst().orElseThrow();

        // Freight PER PIECE: itemA share 30000/60=500.0000, itemB share 20000/40=500.0000 (same
        // rate per sqm/piece here since both share sqmPerPiece=1 -- the ALLOCATION is what this
        // test is really proving, made visible via the TOTAL freight each item's row carries).
        assertThat(itemA.freightCostThb().multiply(itemA.normalizedQuantityPieces()))
            .isEqualByComparingTo("30000.0000");
        assertThat(itemB.freightCostThb().multiply(itemB.normalizedQuantityPieces()))
            .isEqualByComparingTo("20000.0000");
        // The two shares reconstruct the shipment's single flat freight lookup exactly.
        assertThat(itemA.freightCostThb().multiply(itemA.normalizedQuantityPieces())
            .add(itemB.freightCostThb().multiply(itemB.normalizedQuantityPieces())))
            .isEqualByComparingTo("50000.0000");

        assertThat(itemA.clearanceFeeThb().multiply(itemA.normalizedQuantityPieces()))
            .isEqualByComparingTo("4800.0000");
        assertThat(itemB.clearanceFeeThb().multiply(itemB.normalizedQuantityPieces()))
            .isEqualByComparingTo("3200.0000");
        assertThat(itemA.clearanceFeeThb().multiply(itemA.normalizedQuantityPieces())
            .add(itemB.clearanceFeeThb().multiply(itemB.normalizedQuantityPieces())))
            .isEqualByComparingTo("8000.0000");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Missing inputs / bands MUST fail loudly, naming the item — never silently price as ฿0
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void missingThickness_refusesToPriceAndNamesTheItem() {
        // A catalog product with NULL thickness_mm (the 7-arg overload with an explicit null) —
        // a legitimate real-world state (owner ruling 2026-08-11: a free-text/unmatched line may
        // be submitted with no catalog snapshot).
        long noThicknessProductId = insertCatalogProduct("No Thickness Factory", "IT", "NO-THICKNESS-001",
            new BigDecimal("100.00"), "THB", "per_piece", "ACTIVE", null);
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('No Thickness Factory', 'nt@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE SET country = EXCLUDED.country
            """, Map.of());
        long pricingRequestId = readyForReviewWithProduct(noThicknessProductId, "No Thickness Factory",
            new BigDecimal("10"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE, new BigDecimal("10"), "100.00",
            new BigDecimal("1"), null, null);

        assertThatThrownBy(() -> decisionService.startReview(pricingRequestId,
                new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains("ความหนา");
                assertThat(e.getMessage()).containsPattern("รายการที่ \\d+");
            });
    }

    @Test
    void missingFreightBand_countryNotInFormulaConfig_refusesToPrice_neverZero() {
        long pricingRequestId = readyForReviewInCountry("Thailand", new BigDecimal("10"), "100.00");
        assertThatThrownBy(() -> decisionService.startReview(pricingRequestId,
                new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains("ไม่พบอัตราค่าขนส่ง");
            });
    }

    @Test
    void missingClearanceBand_gapInTheLadder_refusesToPrice_neverZero_evenWhenFreightResolvesFine() {
        // V109's real seed happens to give freight and clearance the SAME effective coverage
        // (both start at qty=1, both end open-ended), so a qty that misses clearance would ALSO
        // miss freight there -- not a clean isolation of "clearance specifically can miss".
        // Publish a config whose clearance ladder has a deliberate GAP ([1,50) then [100,NULL),
        // nothing in between) while keeping FULL freight/duty coverage, so qty=75 resolves
        // freight fine and fails ONLY on clearance -- proving clearance's own band-miss path,
        // independent of freight's.
        publishConfigWithGappedClearanceLadder();
        long pricingRequestId = readyForReview(new BigDecimal("75"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE,
            new BigDecimal("75"), "100.00", new BigDecimal("1"), null, null);
        assertThatThrownBy(() -> decisionService.startReview(pricingRequestId,
                new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains("ไม่พบค่าธรรมเนียมพิธีการ");
            });
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Duty default TILE + CEO per-item override
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The owner's own worked example: โมเสคแก้ว defaults to TILE (30%) and would be over-taxed
     * unless the CEO overrides it to GLASS_MOSAIC (10%). Hand-computed delta at the SAME
     * inputs as the happy-path test above (cif=600.5537 per piece): TILE duty = 600.5537 x 0.30 =
     * 180.1661; GLASS_MOSAIC duty = 600.5537 x 0.10 = 60.0554 (60.05537, HALF_UP rounds the 5th
     * decimal "7" up) -- a real, visible difference the CEO's override must actually produce.
     */
    @Test
    void ceoOverridesProductType_glassMosaic_isTaxedAt10PercentNot30_andReachableViaTheDecisionService() {
        long pricingRequestId = readyForReview(new BigDecimal("100"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE,
            new BigDecimal("100"), "100.00", new BigDecimal("1"), null, null);
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingCostingItemDto beforeOverride = costingItemFor(decision);
        assertThat(beforeOverride.productType()).isEqualTo("TILE");
        assertThat(beforeOverride.importDutyThb()).isEqualByComparingTo("180.1661");

        PricingDecisionItemDto item = decision.items().get(0);
        PricingDecisionDto updated = decisionService.overrideItemProductType(decision.id(), item.id(),
            new ProductTypeOverrideRequest("GLASS_MOSAIC"), ceoActor);

        PricingCostingItemDto afterOverride = costingItemFor(updated);
        assertThat(afterOverride.productType()).isEqualTo("GLASS_MOSAIC");
        assertThat(afterOverride.importDutyThb()).isEqualByComparingTo("60.0554");
        // Recomputing recomputed the WHOLE line, not just duty -- selling price moved too.
        PricingDecisionItemDto itemAfter = updated.items().get(0);
        assertThat(itemAfter.frozenLandedCostPerRequestedUnitThb())
            .isNotEqualByComparingTo(item.frozenLandedCostPerRequestedUnitThb());

        // Clearing the override reverts to TILE on the next recalculation.
        PricingDecisionDto cleared = decisionService.overrideItemProductType(updated.id(), item.id(),
            new ProductTypeOverrideRequest(null), ceoActor);
        assertThat(costingItemFor(cleared).productType()).isEqualTo("TILE");
        assertThat(costingItemFor(cleared).importDutyThb()).isEqualByComparingTo("180.1661");
    }

    @Test
    void ceoOverridesProductType_unknownCode_rejectedWith422_neverSilentlyAccepted() {
        long pricingRequestId = readyForReview(new BigDecimal("100"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE,
            new BigDecimal("100"), "100.00", new BigDecimal("1"), null, null);
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);
        assertThatThrownBy(() -> decisionService.overrideItemProductType(decision.id(), item.id(),
                new ProductTypeOverrideRequest("SANITARY_WARE"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Already-approved decisions must not move
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Approve a decision under the CURRENT formula config, then publish a BRAND NEW config
     * version with materially different numbers (double the margin default, different buffers),
     * and prove the already-approved decision's price is byte-identical to what it was at
     * approval — the frozen figures never re-read a config that has since moved on. Also proves
     * the freeze is ENFORCED, not just incidentally true: a mutation attempt on the now-approved
     * decision still 409s.
     */
    @Test
    void approvedDecision_priceUnchanged_evenAfterTheLiveFormulaConfigChanges() {
        long pricingRequestId = readyForReview(new BigDecimal("100"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE,
            new BigDecimal("100"), "100.00", new BigDecimal("1"), null, null);
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
        BigDecimal approvedPriceBefore = approved.items().get(0).approvedSellingPricePerRequestedUnit();
        BigDecimal approvedMarginBefore = approved.items().get(0).approvedMarginPct();
        assertThat(approvedPriceBefore).isEqualByComparingTo("1180.0000");

        // Publish a new config version with DIFFERENT numbers.
        publishNewFormulaConfigVersion();

        PricingDecisionDto reread = decisionRepository.find(decision.id()).orElseThrow();
        assertThat(reread.items().get(0).approvedSellingPricePerRequestedUnit())
            .isEqualByComparingTo(approvedPriceBefore);
        assertThat(reread.items().get(0).approvedMarginPct()).isEqualByComparingTo(approvedMarginBefore);

        // The freeze is ENFORCED, not incidental: an approved decision refuses further mutation.
        assertThatThrownBy(() -> decisionService.recalculateCost(decision.id(), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * The OTHER half of the same rule: a DRAFT decision (never approved) DOES pick up the new
     * formula once the CEO explicitly recalculates — this is the existing recalculateCost path,
     * unchanged mechanism, now driven by V109 instead of V26. No auto-migration: the row only
     * moves because recalculateCost was called, not on its own.
     */
    @Test
    void draftDecision_picksUpANewFormulaConfigVersion_onlyWhenExplicitlyRecalculated() {
        long pricingRequestId = readyForReview(new BigDecimal("100"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE,
            new BigDecimal("100"), "100.00", new BigDecimal("1"), null, null);
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        BigDecimal costBefore = decision.items().get(0).frozenLandedCostPerRequestedUnitThb();
        assertThat(costBefore).isEqualByComparingTo("915.3702");

        // The live config changes underneath the still-open DRAFT...
        publishNewFormulaConfigVersion();
        // ...but the DRAFT's own stored figures do NOT move on their own.
        PricingDecisionDto stillDraft = decisionRepository.find(decision.id()).orElseThrow();
        assertThat(stillDraft.items().get(0).frozenLandedCostPerRequestedUnitThb()).isEqualByComparingTo(costBefore);

        // Only an explicit recalculateCost re-prices it under the NEW config.
        PricingDecisionDto recalculated = decisionService.recalculateCost(decision.id(), ceoActor);
        assertThat(recalculated.items().get(0).frozenLandedCostPerRequestedUnitThb())
            .isNotEqualByComparingTo(costBefore);
    }

    /** Publishes a new current pricing_formula_config version with different insurance/cost/
     * selling buffers and a different margin default, keeping the SAME freight/duty/clearance
     * bands (so the same fixture quantities stay costable) — enough to prove a config change is
     * genuinely picked up (or not) without needing to duplicate V109's entire seed matrix. */
    private void publishNewFormulaConfigVersion() {
        Long currentId = jdbc.queryForObject(
            "SELECT formula_config_id FROM sales.pricing_formula_config WHERE is_current = TRUE",
            Map.of(), Long.class);
        jdbc.update("UPDATE sales.pricing_formula_config SET is_current = FALSE WHERE is_current = TRUE", Map.of());
        Long newId = jdbc.queryForObject("""
            INSERT INTO sales.pricing_formula_config
                (version, insurance_value_factor, insurance_rate, insurance_buffer, cost_buffer,
                 selling_buffer, default_margin_pct, selling_price_round_up_to, is_current, effective_from)
            VALUES (999, 1.150000, 0.004500, 1.200000, 1.200000, 1.200000, 0.500000, 50.0000, TRUE, CURRENT_DATE)
            RETURNING formula_config_id
            """, Map.of(), Long.class);
        jdbc.update("""
            INSERT INTO sales.pricing_freight_rate (formula_config_id, origin_country, thickness_min_mm,
                thickness_max_mm, qty_min_sqm, qty_max_sqm, amount_thb)
            SELECT :newId, origin_country, thickness_min_mm, thickness_max_mm, qty_min_sqm, qty_max_sqm, amount_thb
              FROM sales.pricing_freight_rate WHERE formula_config_id = :oldId
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("newId", newId).addValue("oldId", currentId));
        jdbc.update("""
            INSERT INTO sales.pricing_duty_rate (formula_config_id, product_type, product_label, duty_pct)
            SELECT :newId, product_type, product_label, duty_pct
              FROM sales.pricing_duty_rate WHERE formula_config_id = :oldId
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("newId", newId).addValue("oldId", currentId));
        jdbc.update("""
            INSERT INTO sales.pricing_clearance_fee (formula_config_id, qty_min_sqm, qty_max_sqm, amount_thb)
            SELECT :newId, qty_min_sqm, qty_max_sqm, amount_thb
              FROM sales.pricing_clearance_fee WHERE formula_config_id = :oldId
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("newId", newId).addValue("oldId", currentId));
    }

    /** Publishes a new current config with FULL freight/duty coverage (copied verbatim from the
     * current version) but a clearance ladder with a deliberate GAP: [1,50) and [100,NULL), with
     * [50,100) uncovered — so a qty inside the gap fails clearance specifically while freight
     * still resolves normally. See {@link #missingClearanceBand_gapInTheLadder_refusesToPrice_neverZero_evenWhenFreightResolvesFine}. */
    private void publishConfigWithGappedClearanceLadder() {
        Long currentId = jdbc.queryForObject(
            "SELECT formula_config_id FROM sales.pricing_formula_config WHERE is_current = TRUE",
            Map.of(), Long.class);
        jdbc.update("UPDATE sales.pricing_formula_config SET is_current = FALSE WHERE is_current = TRUE", Map.of());
        Long newId = jdbc.queryForObject("""
            INSERT INTO sales.pricing_formula_config
                (version, insurance_value_factor, insurance_rate, insurance_buffer, cost_buffer,
                 selling_buffer, default_margin_pct, selling_price_round_up_to, is_current, effective_from)
            SELECT 998, insurance_value_factor, insurance_rate, insurance_buffer, cost_buffer,
                   selling_buffer, default_margin_pct, selling_price_round_up_to, TRUE, CURRENT_DATE
              FROM sales.pricing_formula_config WHERE formula_config_id = :oldId
            RETURNING formula_config_id
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("oldId", currentId),
            Long.class);
        jdbc.update("""
            INSERT INTO sales.pricing_freight_rate (formula_config_id, origin_country, thickness_min_mm,
                thickness_max_mm, qty_min_sqm, qty_max_sqm, amount_thb)
            SELECT :newId, origin_country, thickness_min_mm, thickness_max_mm, qty_min_sqm, qty_max_sqm, amount_thb
              FROM sales.pricing_freight_rate WHERE formula_config_id = :oldId
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("newId", newId).addValue("oldId", currentId));
        jdbc.update("""
            INSERT INTO sales.pricing_duty_rate (formula_config_id, product_type, product_label, duty_pct)
            SELECT :newId, product_type, product_label, duty_pct
              FROM sales.pricing_duty_rate WHERE formula_config_id = :oldId
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("newId", newId).addValue("oldId", currentId));
        jdbc.update("""
            INSERT INTO sales.pricing_clearance_fee (formula_config_id, qty_min_sqm, qty_max_sqm, amount_thb)
            VALUES (:newId, 1, 50, 8000), (:newId, 100, NULL, 20000)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("newId", newId));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private PricingCostingItemDto costingItemFor(PricingDecisionDto decision) {
        return costingRepository.find(decision.pricingCostingId()).orElseThrow().items().get(0);
    }

    private long readyForReview(BigDecimal requestedQty, String requestedUnitBasis, String quotedUnitBasis,
                                BigDecimal quotedQuantity, String rawPrice, BigDecimal sqmPerUnit,
                                BigDecimal piecesPerBox, BigDecimal linearMPerUnit) {
        long productId = insertCatalogProduct("Formula Factory", "IT", "V109-TEST-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        return readyForReviewWithProduct(productId, "Formula Factory", requestedQty, requestedUnitBasis,
            quotedUnitBasis, quotedQuantity, rawPrice, sqmPerUnit, piecesPerBox, linearMPerUnit);
    }

    private long readyForReviewInCountry(String country, BigDecimal requestedQty, String rawPrice) {
        String factoryName = "Country Test Factory " + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES (:name, 'ct@example.com', 'THB', 'piece', :country)
            """, Map.of("name", factoryName, "country", country));
        long productId = insertCatalogProduct(factoryName, "XX", "COUNTRY-TEST-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        return readyForReviewWithProduct(productId, factoryName, requestedQty, UnitBasis.PER_PIECE,
            UnitBasis.PER_PIECE, requestedQty, rawPrice, new BigDecimal("1"), null, null);
    }

    private long readyForReviewWithProduct(long productId, String factoryName, BigDecimal requestedQty,
                                           String requestedUnitBasis, String quotedUnitBasis, BigDecimal quotedQuantity,
                                           String rawPrice, BigDecimal sqmPerUnit, BigDecimal piecesPerBox,
                                           BigDecimal linearMPerUnit) {
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, productId, null, "Test", "Model", "Test Model", null, null, "1x1", factoryName,
            requestedQty, requestedQty, "unit", requestedUnitBasis, QuantityType.CONFIRMED, null, null, null);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "V109 formula test", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).stream()
            .filter(q -> factoryName.equals(q.factoryName())).findFirst().orElseThrow();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-V109", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, quotedQuantity, quotedUnitBasis, quotedUnitBasis,
                new BigDecimal(rawPrice), "THB", null, sqmPerUnit, piecesPerBox, linearMPerUnit,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
    }

    /** Two items sourced from the SAME factory (so the SAME factory_quote/"shipment"), each
     * PER_SQM requested at 1 sqm/piece so requestedQty == qtySqm directly, for a simple hand-
     * computable allocation ratio. */
    private long twoItemSameFactoryReadyForReview(BigDecimal qtyA, BigDecimal qtyB) {
        long productIdA = insertCatalogProduct("Formula Factory", "IT", "V109-A-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        long productIdB = insertCatalogProduct("Formula Factory", "IT", "V109-B-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        PricingRequestItemRequest itemA = new PricingRequestItemRequest(
            null, productIdA, null, "Test", "ModelA", "Test ModelA", null, null, "1x1", "Formula Factory",
            qtyA, qtyA, "piece", UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
        PricingRequestItemRequest itemB = new PricingRequestItemRequest(
            null, productIdB, null, "Test", "ModelB", "Test ModelB", null, null, "1x1", "Formula Factory",
            qtyB, qtyB, "piece", UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "V109 multi-item test", UUID.randomUUID().toString(), List.of(itemA, itemB));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).stream()
            .filter(q -> "Formula Factory".equals(q.factoryName())).findFirst().orElseThrow();
        List<ReceiveFactoryQuoteItemRequest> quoteItems = draft.items().stream()
            .map(qi -> new ReceiveFactoryQuoteItemRequest(qi.pricingRequestItemId(), null, null,
                qi.pricingRequestItemId() == draft.items().get(0).pricingRequestItemId() ? qtyA : qtyB,
                UnitBasis.PER_PIECE, UnitBasis.PER_PIECE, new BigDecimal("100.00"), "THB", null,
                new BigDecimal("1"), null, null, "45 days", null, null))
            .toList();
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-MULTI", "THB", "30 days", "45 days",
            "revision", "note", quoteItems, UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
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
