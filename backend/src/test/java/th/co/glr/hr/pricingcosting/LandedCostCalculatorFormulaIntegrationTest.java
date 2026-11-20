package th.co.glr.hr.pricingcosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.FreightRequest;
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

/** Real-DB (Postgres, real V109-seeded sales.pricing_formula_config) proof that LandedCostCalculator and
 * PricingDecisionService#computeSellingPrice actually implement V109's formula — not mocked, not simulated. */
class LandedCostCalculatorFormulaIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingDecisionRepository decisionRepository;
    private PricingDecisionService decisionService;
    private LandedCostCalculator landedCostSpy;
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
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        formulaConfigRepository = new PricingFormulaConfigRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(formulaConfigRepository);
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        landedCostSpy = org.mockito.Mockito.spy(landedCostCalculator); // counts calculate() calls
        costingRepository = new PricingCostingRepository(jdbc);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostSpy, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        salesRepId = createEmployee(employees, "พนักงานขาย V109", "sales-v109@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า V109", "import-v109@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร V109", "ceo-v109@glr.co.th", "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        CustomerDto customer = customers.create(
            "บริษัท V109 จำกัด", "0100000000109", "109 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0109");
        ProjectDto project = projects.create(customer.id(), "โครงการ V109");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล V109", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("Test", "Tile V109", "Formula Factory"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // Hand-computed happy path: every formula step independently verified

    /** ONE item, ONE factory shipment, thickness 10mm (Italy [8,12)mm band), 100 pieces @ 1 sqm/piece = 100 sqm
     * total, quoted PER_PIECE at 100.00 THB/piece, requested PER_PIECE. */
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
        assertThat(item.proposedSellingPricePerRequestedUnit()).isEqualByComparingTo("1175.34");
    }

    // Multi-item factory shipment: F/S are looked up ONCE and allocated proportionally by sqm

    /**
     * CEO ruling 2026-11-06 (capacity by thickness; thicknesses combine per factory shipment).
     * ONE factory, 400 sqm @10 mm + 225 sqm @19 mm: container space E = 400/800 + 225/450 = 1.0, so
     * ONE full container for the whole shipment (the old code split by thickness: 400 @10 in the
     * 101–451 band + 225 @19 in the 101–451 band). Seeded Italy: 8–12 full (801+) = 100,000, 17–21
     * band holding 450 (101–451) = 100,000 → F = 100,000; clearance 1 × 20,000. Shares by container
     * space are 50% / 50% (NOT 64% / 36% by sqm): F 50,000 each, S 10,000 each.
     */
    @Test
    void mixedThicknessShipment_combinesIntoOneContainer_andSharesByContainerSpace() {
        long pricingRequestId = twoItemSameFactoryReadyForReview(
            new BigDecimal("400"), new BigDecimal("10"), new BigDecimal("225"), new BigDecimal("19"));

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<PricingCostingItemDto> costingItems = costingRepository.find(decision.pricingCostingId()).orElseThrow().items();
        PricingCostingItemDto thin = costingItems.stream()
            .filter(i -> i.requestedQuantity().compareTo(new BigDecimal("400")) == 0).findFirst().orElseThrow();
        PricingCostingItemDto thick = costingItems.stream()
            .filter(i -> i.requestedQuantity().compareTo(new BigDecimal("225")) == 0).findFirst().orElseThrow();

        // Exact money: the stored snapshot shares (the per-piece x pieces product is rounded, so it is not asserted).
        com.fasterxml.jackson.databind.JsonNode thinMoney = snapshot(thin);
        com.fasterxml.jackson.databind.JsonNode thickMoney = snapshot(thick);
        for (com.fasterxml.jackson.databind.JsonNode m : List.of(thinMoney, thickMoney)) {
            assertThat(new BigDecimal(m.path("freightAmountThbForGroup").asText())).isEqualByComparingTo("100000");
            assertThat(new BigDecimal(m.path("clearanceAmountThbForShipment").asText())).isEqualByComparingTo("20000");
            assertThat(new BigDecimal(m.path("freightShareThb").asText())).isEqualByComparingTo("50000");
            assertThat(new BigDecimal(m.path("clearanceShareThb").asText())).isEqualByComparingTo("10000");
            assertThat(m.path("freightRoundUpAboveSqm").asText()).isEqualTo("600");
        }

        // Snapshot: existing keys kept (containerSqm is now THIS item's capacity), new unit keys added.
        com.fasterxml.jackson.databind.JsonNode thinSnap = snapshot(thin);
        com.fasterxml.jackson.databind.JsonNode thickSnap = snapshot(thick);
        assertThat(thinSnap.path("containerSqm").asText()).isEqualTo("800");
        assertThat(thickSnap.path("containerSqm").asText()).isEqualTo("450");
        assertThat(new BigDecimal(thinSnap.path("itemContainerUnits").asText())).isEqualByComparingTo("0.5");
        assertThat(new BigDecimal(thickSnap.path("itemContainerUnits").asText())).isEqualByComparingTo("0.5");
        for (com.fasterxml.jackson.databind.JsonNode snap : List.of(thinSnap, thickSnap)) {
            assertThat(new BigDecimal(snap.path("freightGroupContainerUnits").asText())).isEqualByComparingTo("1");
            assertThat(new BigDecimal(snap.path("shipmentContainerUnits").asText())).isEqualByComparingTo("1");
            assertThat(snap.path("freightFullContainers").asText()).isEqualTo("1");
            assertThat(snap.path("clearanceFullContainers").asText()).isEqualTo("1");
            assertThat(new BigDecimal(snap.path("freightRemainderSqm").asText())).isEqualByComparingTo("0");
            assertThat(new BigDecimal(snap.path("freightGroupQtySqm").asText())).isEqualByComparingTo("625");
        }
    }

    /**
     * Same shipment shape with a leftover: 300 sqm @19 mm + 300 sqm @10 mm. E = 0.6667 + 0.375 = 1.0417
     * → 1 full + r = 0.0417 (mixed → normal rule, r × 800 = 33.3 ≤ 600, kept as a leftover). F = 100,000
     * (full) + highest leftover band (10 mm @33.3 = 50,000; 19 mm @18.75 = 60,000) = 60,000 → 160,000.
     * S = 20,000 + the 1–101 clearance band (8,000) = 28,000. Shares by container space:
     * 19 mm 0.6667/1.0417 = 64%, 10 mm 36% → F 102,400 / 57,600.
     */
    @Test
    void mixedThicknessShipment_withLeftover_chargesHighestLeftoverBand_andSharesByContainerSpace() {
        long pricingRequestId = twoItemSameFactoryReadyForReview(
            new BigDecimal("300"), new BigDecimal("19"), new BigDecimal("300"), new BigDecimal("10"));

        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        List<PricingCostingItemDto> costingItems = costingRepository.find(decision.pricingCostingId()).orElseThrow().items();
        PricingCostingItemDto thick = costingItems.stream()
            .filter(i -> snapshot(i).path("thicknessMm").asText().startsWith("19")).findFirst().orElseThrow();
        PricingCostingItemDto thin = costingItems.stream()
            .filter(i -> snapshot(i).path("thicknessMm").asText().startsWith("10")).findFirst().orElseThrow();

        // Exact money from the stored snapshot: share = group amount x (item units / group units) = x 0.64 / 0.36.
        com.fasterxml.jackson.databind.JsonNode thickMoney = snapshot(thick);
        com.fasterxml.jackson.databind.JsonNode thinMoney = snapshot(thin);
        for (com.fasterxml.jackson.databind.JsonNode m : List.of(thickMoney, thinMoney)) {
            assertThat(new BigDecimal(m.path("freightAmountThbForGroup").asText())).isEqualByComparingTo("160000");
            assertThat(new BigDecimal(m.path("clearanceAmountThbForShipment").asText())).isEqualByComparingTo("28000");
            assertThat(m.path("freightRoundUpAboveSqm").asText()).isEqualTo("600");
        }
        assertThat(new BigDecimal(thickMoney.path("freightShareThb").asText())).isEqualByComparingTo("102400");
        assertThat(new BigDecimal(thinMoney.path("freightShareThb").asText())).isEqualByComparingTo("57600");
        assertThat(new BigDecimal(thickMoney.path("clearanceShareThb").asText())).isEqualByComparingTo("17920");
        assertThat(new BigDecimal(thinMoney.path("clearanceShareThb").asText())).isEqualByComparingTo("10080");
        // Kept as tolerances (they genuinely round): the leftover r x 800 (33.333333, 6 dp) and the 4-dp unit figures.

        com.fasterxml.jackson.databind.JsonNode snap = snapshot(thick);
        assertThat(snap.path("freightFullContainers").asText()).isEqualTo("1");
        assertThat(snap.path("freightRemainderSqm").asDouble()).isCloseTo(33.33, org.assertj.core.data.Offset.offset(0.01));
        assertThat(snap.path("freightGroupContainerUnits").asDouble()).isCloseTo(1.0417, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(snap.path("shipmentContainerUnits").asDouble()).isCloseTo(1.0417, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(snap.path("itemContainerUnits").asDouble()).isCloseTo(0.6667, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(snap.path("containerSqm").asText()).isEqualTo("450");
    }

    private static com.fasterxml.jackson.databind.JsonNode snapshot(PricingCostingItemDto item) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(item.calculationSnapshot());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // Missing inputs / bands MUST fail loudly, naming the item — never silently price as ฿0

    @Test
    void missingSqm_perPieceQuote_setAsideAsUncostable_withoutAbortingTheOtherLines() {
        // THE P0 LEGACY CASE (QA 2026-10): import quoted a price per แผ่น (PER_PIECE) with a BLANK
        // sqm_per_unit, on a request item that also carries no requested_qty_sqm — a pre-V185 row
        // (V185 now derives requested_qty_sqm on every NEW request, so this shape is only reachable
        // for legacy data). V109's freight lookup needs sqm-per-piece for EVERY item (Q is in sqm),
        // so this line cannot be costed.
        //
        // Before this fix resolveSqmPerPiece threw 422, which aggregateProblems turned into ONE 422
        // that aborted startReview for the WHOLE request — poisoning the costable line beside it and
        // blocking the CEO from the screen that owns the manual-cost override. It now behaves exactly
        // like a missing thickness (V156): the line is set ASIDE as uncostable, the rest of the
        // request still costs, and approve() is the gate that refuses it.
        long pricingRequestId = mixedCostableAndNoSqmSameFactory();

        // (a) startReview SUCCEEDS — no aggregated 422. RED before the fix (resolveSqmPerPiece throws,
        // aggregateProblems aborts the whole calculation).
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(decision.items()).hasSize(2);

        List<PricingCostingItemDto> costingItems =
            costingRepository.find(decision.pricingCostingId()).orElseThrow().items();
        assertThat(costingItems).hasSize(2);

        PricingCostingItemDto noSqm = costingItems.stream()
            .filter(i -> i.uncostableReason() != null).findFirst()
            .orElseThrow(() -> new AssertionError("expected one line set aside as uncostable"));
        PricingCostingItemDto costable = costingItems.stream()
            .filter(i -> i.uncostableReason() == null).findFirst()
            .orElseThrow(() -> new AssertionError("expected one line to cost cleanly"));

        // (b) the uncostable line: freight-dependent costs NULL, reason names the missing sqm — but
        // goods cost IS still recorded (computable for a per-piece quote without sqm; this is why no
        // schema change was needed — goods_cost_thb/insurance_cost_thb stay NOT NULL per V156).
        assertThat(noSqm.sqmPerUnit()).isNull();
        assertThat(noSqm.totalLandedCostThb()).isNull();
        assertThat(noSqm.landedCostPerUnitThb()).isNull();
        assertThat(noSqm.goodsCostThb()).isNotNull();
        assertThat(noSqm.uncostableReason())
            .as("the row states that it is the sqm-per-piece conversion that is missing, naming the item")
            .contains("ตร.ม.")
            .containsPattern("รายการที่ \\d+");

        // (c) the line BESIDE it still costs cleanly — one unresolvable line no longer aborts the
        // whole request.
        assertThat(costable.totalLandedCostThb()).isNotNull();

        // (d) the decision snapshot for the uncostable line carries NO frozen cost...
        PricingDecisionItemDto noSqmDecisionItem = decision.items().stream()
            .filter(i -> i.frozenLandedCostPerRequestedUnitThb() == null).findFirst()
            .orElseThrow(() -> new AssertionError("the uncostable line's decision item should have no frozen cost"));
        assertThat(noSqmDecisionItem.frozenLandedCostPerRequestedUnitThb()).isNull();

        // (e) ...and the guarantee only MOVED to approve(): it refuses while the line is still
        // uncostable and un-overridden (wrong-way-round, so it goes red the moment the sqm path ever
        // starts silently pricing freight at zero).
        assertThatThrownBy(() -> decisionService.approve(decision.id(),
                new ApprovePricingDecisionRequest(null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains("ต้นทุน");
            });
    }

    // ไทย-สต็อก (goods already in Thailand): a DOMESTIC purchase, owner ruling.
    // cost = Import's price in THB only. No freight lookup, insurance, duty, cost buffer or clearance.
    @Test
    void thaiStockOrigin_noCatalogCountry_sameFormulaWithIFTSZero_everyImportComponentZeroNotNull() {
        // 100 pieces @ 100.00 THB, 1 sqm/piece: goods = 100.0000/pc; TC = (goods + 0 + 0) x (1 + 0) x 1.07
        // + 0 = 10,700.0000 -> 107.0000/pc (the sheet's x1.07 stays; i = F = T = S = 0).
        PricingDecisionDto decision = startedDecisionWithoutCatalogLink("ไทย-สต็อก");
        PricingCostingItemDto item = costingItemFor(decision);

        assertThat(item.uncostableReason()).isNull();
        assertThat(item.goodsCostThb()).isEqualByComparingTo("100.0000");
        assertThat(item.freightCostThb()).isEqualByComparingTo("0");
        assertThat(item.insuranceCostThb()).isEqualByComparingTo("0");
        assertThat(item.importDutyThb()).isEqualByComparingTo("0");
        assertThat(item.inlandTransportCostThb()).isEqualByComparingTo("0");
        assertThat(item.clearanceFeeThb()).isEqualByComparingTo("0");
        assertThat(item.cifCostThb()).isEqualByComparingTo("100.0000");
        assertThat(item.landedCostPerUnitThb()).isEqualByComparingTo("107.0000");
        assertThat(item.totalLandedCostThb()).isEqualByComparingTo("10700.0000");
        assertThat(jdbc.queryForObject("SELECT calculation_snapshot::text FROM sales.pricing_costing_item WHERE pricing_costing_id = :id",
            Map.of("id", decision.pricingCostingId()), String.class)).contains("DOMESTIC");

        // The table never prices a domestic line, so a CEO freight is refused WITHOUT claiming the table has a rate.
        assertThatThrownBy(() -> decisionService.setItemFreight(decision.pricingRequestId(), item.pricingRequestItemId(),
                new FreightRequest(new BigDecimal("1000")), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ไม่ได้ใช้ตารางค่าขนส่ง").doesNotContain("ตารางค่าขนส่งมีอัตรา");
            });
    }

    /**
     * Gap line (no band), CEO F = 12,000.00 THB for the line. Same formula, hand-computed:
     * C = 10,000.0000; i = 55.3725; F = 12,000; cif = 22,055.3725; duty 30% = 6,616.61175 -> 6,616.6118;
     * (cif+duty) x 1.07 = 28,671.9843 x 1.07 = 30,679.0232; S (qty 100) = 8,000; TC = 38,679.0232;
     * per piece = 386.7902 (money4); total = 386.7902 x 100 = 38,679.0200.
     */
    @Test
    void freightGap_ceoEntersF_sameFormulaWithThatF_everyComponent_andItSurvivesRecalculation() {
        // A catalogue Italy line at 100 mm: outside every seeded freight band.
        long productId = insertCatalogProduct("Formula Factory", "IT", "GAP-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece", "ACTIVE", new BigDecimal("100"));
        long prId = readyForReviewWithProduct(productId, "Formula Factory", new BigDecimal("100"), UnitBasis.PER_PIECE,
            UnitBasis.PER_PIECE, new BigDecimal("100"), "100.00", new BigDecimal("1"), null, null);
        assertThatCode(() -> decisionService.startReview(prId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor))
            .as("a freight gap must not abort the whole costing").doesNotThrowAnyException();
        PricingDecisionDto decision = decisionRepository.findOpenDraft(prId).orElseThrow();
        PricingCostingItemDto gap = costingItemFor(decision);
        assertThat(gap.uncostableReason()).contains("ไม่มีอัตราค่าขนส่ง").doesNotContain("ประเทศต้นทาง");
        assertThat(gap.uncostableCode()).isEqualTo("NO_FREIGHT_BAND");

        long itemId = decision.items().get(0).pricingRequestItemId();
        PricingDecisionDto after = decisionService.setItemFreight(prId, itemId, new FreightRequest(new BigDecimal("12000")), ceoActor).decision();

        PricingCostingItemDto item = costingItemFor(after);
        assertThat(item.uncostableReason()).isNull();
        assertThat(item.goodsCostThb()).isEqualByComparingTo("100.0000");
        assertThat(item.insuranceCostThb()).isEqualByComparingTo("0.5537");
        assertThat(item.freightCostThb()).isEqualByComparingTo("120.0000");
        assertThat(item.cifCostThb()).isEqualByComparingTo("220.5537");
        assertThat(item.importDutyThb()).isEqualByComparingTo("66.1661");
        assertThat(item.clearanceFeeThb()).isEqualByComparingTo("80.0000");
        assertThat(item.landedCostPerUnitThb()).isEqualByComparingTo("386.7902");
        assertThat(item.totalLandedCostThb()).isEqualByComparingTo("38679.0200");
        assertThat(jdbc.queryForList("SELECT message FROM sales.pricing_request_event WHERE pricing_request_id = :id AND event_kind = :k",
            Map.of("id", prId, "k", "PRICING_REQUEST_ITEM_FREIGHT_SET"), String.class)).singleElement()
            .satisfies(m -> assertThat(m).contains("12000"));
        assertThat(jdbc.queryForObject("SELECT calculation_snapshot::text FROM sales.pricing_costing_item WHERE pricing_costing_id = :id AND uncostable_reason IS NULL",
            Map.of("id", after.pricingCostingId()), String.class)).contains("CEO_MANUAL");

        // Survives a recalculation (persisted on the item) ...
        PricingDecisionDto recalculated = decisionService.recalculateCost(after.id(), ceoActor);
        assertThat(costingItemFor(recalculated).landedCostPerUnitThb()).isEqualByComparingTo("386.7902");
        // ... and clearing it returns the line to uncostable.
        PricingDecisionDto cleared = decisionService.setItemFreight(prId, itemId, new FreightRequest(null), ceoActor).decision();
        assertThat(costingItemFor(cleared).uncostableReason()).contains("ไม่มีอัตราค่าขนส่ง");
    }

    // Fixture helpers

    private PricingCostingItemDto costingItemFor(PricingDecisionDto decision) {
        return costingRepository.find(decision.pricingCostingId()).orElseThrow().items().get(0);
    }

    /** Free-text line: no catalog link, so only the item's own origin_country/thickness can feed freight. */
    private PricingDecisionDto startedDecisionWithoutCatalogLink(String originCountry) {
        long prId = readyForReview(new BigDecimal("100"), UnitBasis.PER_PIECE, UnitBasis.PER_PIECE,
            new BigDecimal("100"), "100.00", new BigDecimal("1"), null, null);
        jdbc.update("UPDATE sales.pricing_request_item SET product_id = NULL, catalog_price_id = NULL, thickness_mm = :t, origin_country = :o WHERE pricing_request_id = :id",
            Map.of("o", originCountry, "id", prId, "t", 10));
        return decisionService.startReview(prId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
    }

    private long readyForReview(BigDecimal requestedQty, String requestedUnitBasis, String quotedUnitBasis,
                                BigDecimal quotedQuantity, String rawPrice, BigDecimal sqmPerUnit,
                                BigDecimal piecesPerBox, BigDecimal linearMPerUnit) {
        long productId = insertCatalogProduct("Formula Factory", "IT", "V109-TEST-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        return readyForReviewWithProduct(productId, "Formula Factory", requestedQty, requestedUnitBasis,
            quotedUnitBasis, quotedQuantity, rawPrice, sqmPerUnit, piecesPerBox, linearMPerUnit);
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
        // V185: bypasses PricingRequestService.createDraft on purpose -- that method now forces

        // every item.s requestedUnitBasis to PER_PIECE (the new sales form never types a unit/basis

        // directly), which would make it impossible to construct a PER_SQM/PER_BOX/PER_LINEAR_M-basis

        // item for this costing-conversion test. PricingRequestRepository.create performs the exact

        // same DB write createDraft would (persistence only, per that class.s own header Javadoc), just

        // without the sales-form-specific derivation/validation -- LandedCostCalculator does not care

        // how an item.s requested_unit_basis got set, only what it does with the value.

        long pricingRequestId = pricingRequests.create(ticketId, pricingRequests.nextRequestCode(), request, salesRepId);
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
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
    }

    /** Two items sourced from the SAME factory (so the SAME factory_quote/"shipment"), each PER_SQM requested at 1
     * sqm/piece so requestedQty == qtySqm directly, for a simple hand- computable allocation ratio. */
    private long twoItemSameFactoryReadyForReview(BigDecimal qtyA, BigDecimal qtyB) {
        return twoItemSameFactoryReadyForReview(qtyA, new BigDecimal("10"), qtyB, new BigDecimal("10"));
    }

    private long twoItemSameFactoryReadyForReview(BigDecimal qtyA, BigDecimal thicknessA,
                                                  BigDecimal qtyB, BigDecimal thicknessB) {
        long productIdA = insertCatalogProduct("Formula Factory", "IT", "V109-A-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece", "ACTIVE", thicknessA);
        long productIdB = insertCatalogProduct("Formula Factory", "IT", "V109-B-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece", "ACTIVE", thicknessB);
        PricingRequestItemRequest itemA = new PricingRequestItemRequest(
            null, productIdA, null, "Test", "ModelA", "Test ModelA", null, null, "1x1", "Formula Factory",
            qtyA, qtyA, "piece", UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
        PricingRequestItemRequest itemB = new PricingRequestItemRequest(
            null, productIdB, null, "Test", "ModelB", "Test ModelB", null, null, "1x1", "Formula Factory",
            qtyB, qtyB, "piece", UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "V109 multi-item test", UUID.randomUUID().toString(), List.of(itemA, itemB));
        // V185: bypasses PricingRequestService.createDraft on purpose -- that method now forces

        // every item.s requestedUnitBasis to PER_PIECE (the new sales form never types a unit/basis

        // directly), which would make it impossible to construct a PER_SQM/PER_BOX/PER_LINEAR_M-basis

        // item for this costing-conversion test. PricingRequestRepository.create performs the exact

        // same DB write createDraft would (persistence only, per that class.s own header Javadoc), just

        // without the sales-form-specific derivation/validation -- LandedCostCalculator does not care

        // how an item.s requested_unit_basis got set, only what it does with the value.

        long pricingRequestId = pricingRequests.create(ticketId, pricingRequests.nextRequestCode(), request, salesRepId);
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
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
    }

    /**
     * One costable item and one whose sqm-per-piece CANNOT be resolved (a PER_PIECE factory quote
     * with a blank {@code sqm_per_unit} AND a legacy request row with no {@code requested_qty_sqm}),
     * both sourced from the SAME factory so they share one shipment — the fixture for proving the
     * unresolvable line is set ASIDE as uncostable without aborting the costable one beside it.
     * Wiring mirrors {@link #twoItemSameFactoryReadyForReview}; the only differences are item B's
     * null {@code requested_qty_sqm} (position 12 of the compat constructor) and its quote item's
     * null {@code sqmPerUnit}.
     */
    private long mixedCostableAndNoSqmSameFactory() {
        return mixedCostableAndNoSqmSameFactory(UnitBasis.PER_PIECE);
    }

    private long mixedCostableAndNoSqmSameFactory(String noSqmRequestedBasis) {
        long productCostable = insertCatalogProduct("Formula Factory", "IT", "V109-SQM-OK-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        long productNoSqm = insertCatalogProduct("Formula Factory", "IT", "V109-SQM-MISSING-" + UUID.randomUUID(),
            new BigDecimal("100.00"), "THB", "per_piece");
        // item A: requested_qty_sqm = 10 (sqm/piece = 1) -> sqm resolves -> costable.
        PricingRequestItemRequest costable = new PricingRequestItemRequest(
            null, productCostable, null, "Test", "ModelOK", "Test ModelOK", null, null, "1x1", "Formula Factory",
            new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null);
        // item B: requested_qty_sqm = null (legacy) -> paired with a blank quote sqm_per_unit below,
        // sqm is unresolvable.
        PricingRequestItemRequest noSqm = new PricingRequestItemRequest(
            null, productNoSqm, null, "Test", "ModelNoSqm", "Test ModelNoSqm", null, null, "1x1", "Formula Factory",
            new BigDecimal("10"), null, "piece", noSqmRequestedBasis, QuantityType.CONFIRMED, null, null, null);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "V109 missing-sqm test", UUID.randomUUID().toString(), List.of(costable, noSqm));
        // V185: bypasses PricingRequestService.createDraft on purpose (same reason as the helpers
        // above) — submit() does NOT re-derive requested_qty_sqm, so item B's null survives to the
        // calculator.
        long pricingRequestId = pricingRequests.create(ticketId, pricingRequests.nextRequestCode(), request, salesRepId);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = factoryQuoteService.generateDrafts(pricingRequestId, importActor).stream()
            .filter(q -> "Formula Factory".equals(q.factoryName())).findFirst().orElseThrow();
        // draft.items() preserves request-item order (item A first, item B second) — the same
        // assumption twoItemSameFactoryReadyForReview relies on.
        ReceiveFactoryQuoteItemRequest costableQuote = new ReceiveFactoryQuoteItemRequest(
            draft.items().get(0).pricingRequestItemId(), null, null, new BigDecimal("10"),
            UnitBasis.PER_PIECE, UnitBasis.PER_PIECE, new BigDecimal("100.00"), "THB", null,
            new BigDecimal("1"), null, null, "45 days", null, null);
        ReceiveFactoryQuoteItemRequest noSqmQuote = new ReceiveFactoryQuoteItemRequest(
            draft.items().get(1).pricingRequestItemId(), null, null, new BigDecimal("10"),
            UnitBasis.PER_PIECE, UnitBasis.PER_PIECE, new BigDecimal("100.00"), "THB", null,
            null, null, null, "45 days", null, null); // sqmPerUnit = null — blank on a PER_PIECE quote
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-NOSQM", "THB", "30 days", "45 days",
            "revision", "note", List.of(costableQuote, noSqmQuote), UUID.randomUUID().toString());
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
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
