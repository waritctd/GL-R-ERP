package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ReturnPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionStatus;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;

/**
 * Slice 1, stock lines: what the CEO sees and does - {@code startReview}, {@code update},
 * {@code approve}, {@code returnToImport} - for STOCK lines, plus the authorization of the CEO's
 * price on a stock line.
 *
 * <p><b>Field choice for the CEO's ราคาตั้ง on a stock line (case 12):</b> a NEW field,
 * {@code UpdatePricingDecisionItemRequest.listUnitPrice}, NOT a reuse of
 * {@code sellingPriceOverride}. Reasons (all read from {@code PricingDecisionService}):
 * (1) {@code sellingPriceOverride} ("ปรับราคาเอง") demands a written reason on every touch and is
 * audited as a manual override of a COMPUTED price - a stock line has no computed price to override,
 * typing ราคาตั้ง is its normal input; (2) it is consulted ONLY under NET
 * ({@code computeNetUnitPrice} ignores it under SPECIAL_SQM/DIRECT_NET), so the number
 * {@code approve()} gates on would not be the number the CEO typed and {@code list_unit_price} (which
 * IA 1.3 says ราคาตั้ง "becomes") would never be written; (3) the owner correction of 2026-09-19
 * keeps {@code list_unit_price} server-maintained for COSTED lines, so the new field is refused (400)
 * on an import line (pinned below). It is set-only: a stock line cannot be un-priced.
 *
 * <p><b>How the fixtures isolate each test.</b> The three tests of {@code startReview} drive the real
 * {@code startReview}. Every test of {@code update}/{@code approve}/{@code returnToImport} on a
 * STOCK-ONLY request starts from {@link #seedStockOnlyDecision} (exactly the shape the
 * {@code startReview} tests assert), so it fails on its OWN assertion. Tests with an IMPORT line in
 * the decision must go through the real {@code startReview} (a costing row cannot be seeded without
 * a real factory quote) - those are marked <b>[via startReview]</b> and will stay red at
 * {@code startReview} until it supports stock lines, which is itself behaviour they cover.
 *
 * <p>Tests marked <b>[PIN]</b> pass before the feature exists on purpose.
 */
class StockLineCeoDecisionIntegrationTest extends AbstractStockLineIntegrationTest {

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 11 - startReview
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void startReview_onlyStockLines_succeeds_itemsCarrySourceAndEta_noCostingLink_noCost_noListPrice() {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10), stockLine("Stock TR", IN_TRANSIT, 4));
        seedEta(id, "Stock TR", eta());
        forceStatus(id, PricingRequestStatus.READY_FOR_CEO_REVIEW); // precondition (routing has its own tests)

        PricingDecisionDto decision = decisionService.startReview(id,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);

        assertThat(status(id)).isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        assertThat(decision.status()).isEqualTo(PricingDecisionStatus.DRAFT);
        assertThat(decision.items()).hasSize(2);
        PricingDecisionItemDto th = decisionItemFor(decision, itemId(id, "Stock TH"));
        PricingDecisionItemDto tr = decisionItemFor(decision, itemId(id, "Stock TR"));
        assertThat(th.stockSource()).isEqualTo(IN_THAILAND);
        assertThat(th.expectedArrivalDate()).isNull();
        assertThat(tr.stockSource()).isEqualTo(IN_TRANSIT);
        assertThat(tr.expectedArrivalDate()).isEqualTo(eta());
        for (PricingDecisionItemDto item : decision.items()) {
            assertThat(storedCostingLink(item.id())).as("a stock line has no costing row").isNull();
            assertThat(item.frozenLandedCostPerPieceThb()).isNull();
            assertThat(item.frozenLandedCostPerRequestedUnitThb()).isNull();
            assertThat(item.listUnitPrice()).as("the CEO types ราคาตั้ง; nothing is pre-filled").isNull();
            assertThat(item.proposedSellingPricePerRequestedUnit()).isNull();
            assertThat(item.requestedUnitBasis()).isEqualTo("PER_PIECE");
            assertThat(item.sqmPerPiece()).as("the decision stays 'new form' eligible").isNotNull();
        }
        assertThat(costedRowCount(id)).as("landed-cost calculation is not needed and not run").isZero();
        assertThat(factoryQuoteCount(id)).isZero();
    }

    @Test
    void startReview_mixedRequest_importLineCostedAsToday_stockLineNot_viaStartReview() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Stock TH", IN_THAILAND, 5));
        quoteEveryImportLineReady(id);
        forceStatus(id, PricingRequestStatus.READY_FOR_CEO_REVIEW); // precondition

        PricingDecisionDto decision = decisionService.startReview(id,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);

        PricingDecisionItemDto imp = decisionItemFor(decision, itemId(id, "Imp A"));
        PricingDecisionItemDto stock = decisionItemFor(decision, itemId(id, "Stock TH"));
        assertThat(storedCostingLink(imp.id())).as("import line is costed exactly as today").isNotNull();
        assertThat(imp.frozenLandedCostPerRequestedUnitThb()).isNotNull();
        assertThat(imp.listUnitPrice()).isNotNull();
        assertThat(imp.stockSource()).isNull();
        assertThat(storedCostingLink(stock.id())).isNull();
        assertThat(stock.frozenLandedCostPerRequestedUnitThb()).isNull();
        assertThat(stock.listUnitPrice()).isNull();
        assertThat(stock.stockSource()).isEqualTo(IN_THAILAND);
        assertThat(costedRowCount(id)).as("exactly ONE costing row: the import line's").isEqualTo(1L);
    }

    @Test
    void startReview_importOnlyRequest_isUnchanged_PIN() {
        long id = persistSubmitAndPickUp(importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));
        quoteEveryImportLineReady(id);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        PricingDecisionDto decision = decisionService.startReview(id,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);

        assertThat(decision.items()).singleElement().satisfies(i -> {
            assertThat(i.stockSource()).isNull();
            assertThat(i.frozenLandedCostPerRequestedUnitThb()).isNotNull();
            assertThat(storedCostingLink(i.id())).isNotNull();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 12 - CEO update: ราคาตั้ง on a stock line
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void update_ceoPriceOnAStockLine_becomesItsListUnitPrice_andTheNetModeDiscountApplies() {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10), stockLine("Stock TR", IN_TRANSIT, 4));
        long decisionId = seedStockOnlyDecision(id);
        long th = itemId(id, "Stock TH");
        long tr = itemId(id, "Stock TR");

        PricingDecisionDto updated = decisionService.update(decisionId,
            new UpdatePricingDecisionRequest(null, "NET", List.of(
                stockPrice(decisionItemId(decisionId, th), "1000.00", "10"),
                stockPrice(decisionItemId(decisionId, tr), "500.00", null))),
            ceoActor);

        PricingDecisionItemDto thItem = decisionItemFor(updated, th);
        PricingDecisionItemDto trItem = decisionItemFor(updated, tr);
        assertThat(thItem.listUnitPrice()).isEqualByComparingTo("1000.00");
        assertThat(thItem.discountPct()).isEqualByComparingTo("10");
        assertThat(thItem.netUnitPrice()).as("net = list x (1 - 10/100)").isEqualByComparingTo("900.00");
        assertThat(trItem.listUnitPrice()).isEqualByComparingTo("500.00");
        assertThat(trItem.netUnitPrice()).as("no discount typed: net = list").isEqualByComparingTo("500.00");
        assertThat(thItem.manualSellingPricePerRequestedUnit())
            .as("this is NOT the 'ปรับราคาเอง' override").isNull();
        assertThat(updated.priceMode()).isEqualTo("NET");
    }

    @Test
    void update_ceoPriceOnAStockLine_directNetMode_netIsTheDirectNet_listPriceStillRecorded() {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10));
        long decisionId = seedStockOnlyDecision(id);
        long th = itemId(id, "Stock TH");

        PricingDecisionDto updated = decisionService.update(decisionId,
            new UpdatePricingDecisionRequest(null, "DIRECT_NET", List.of(
                new UpdatePricingDecisionItemRequest(decisionItemId(decisionId, th), null, null, null, null, false,
                    null, false, null, false, new BigDecimal("850.00"), false, new BigDecimal("1000.00")))),
            ceoActor);

        PricingDecisionItemDto item = decisionItemFor(updated, th);
        assertThat(item.netUnitPrice()).isEqualByComparingTo("850.00");
        assertThat(item.listUnitPrice())
            .as("R6: ราคาตั้ง is required and kept whichever mode then applies").isEqualByComparingTo("1000.00");
    }

    @Test
    void update_aNegativeListPriceOnAStockLine_isRefusedWith400_andTheEarlierValidPriceSurvives() {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10));
        long decisionId = seedStockOnlyDecision(id);
        long th = itemId(id, "Stock TH");
        long decisionItemId = decisionItemId(decisionId, th);
        // Positive control FIRST, on the same item: a valid price is accepted and stored. Without it a
        // 400 here could just be "the decision cannot see its stock lines yet" (findItems inner-joins
        // the costing row today), i.e. the test would pass for the wrong reason.
        decisionService.update(decisionId, new UpdatePricingDecisionRequest(null, "NET", List.of(
            stockPrice(decisionItemId, "1000.00", null))), ceoActor);
        assertThat(storedListPrice(decisionItemId)).isEqualByComparingTo("1000.00");

        assertThatThrownBy(() -> decisionService.update(decisionId,
            new UpdatePricingDecisionRequest(null, "NET", List.of(stockPrice(decisionItemId, "-1.00", null))),
            ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(storedListPrice(decisionItemId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void update_ceoCannotTypeAListPriceOnAnImportLine_isRefusedWith400_viaStartReview() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Stock TH", IN_THAILAND, 5));
        quoteEveryImportLineReady(id);
        forceStatus(id, PricingRequestStatus.READY_FOR_CEO_REVIEW);
        PricingDecisionDto decision = decisionService.startReview(id,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        PricingDecisionItemDto imp = decisionItemFor(decision, itemId(id, "Imp A"));
        BigDecimal formulaPrice = imp.listUnitPrice();

        assertThatThrownBy(() -> decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "NET", List.of(stockPrice(imp.id(), "1.00", null))), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(storedListPrice(imp.id()))
            .as("a costed line's list price stays server-maintained").isEqualByComparingTo(formulaPrice);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 13 - approve
    // ─────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"missing", "zero"})
    void approve_aStockLineWithoutAListPriceAboveZero_isRefusedWith422_namingTheListPrice_andNothingIsApproved(
        String flavour
    ) {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10));
        long decisionId = seedStockOnlyDecision(id);
        seedPriceMode(decisionId, "NET");
        long th = itemId(id, "Stock TH");
        if ("zero".equals(flavour)) {
            seedListPriceAndNet(decisionId, th, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        assertThatThrownBy(() -> decisionService.approve(decisionId,
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                // The existing 'ทุกรายการต้องมีต้นทุน' 422 would also be a 422; the NEW rule has its own
                // message (IA 2: "รายการจากสต็อกต้องมีราคาตั้ง"), so a stock line failing for the OLD
                // reason cannot pass this test.
                assertThat(e.getMessage()).contains("ราคาตั้ง");
            });

        assertThat(storedDecisionStatus(decisionId)).isEqualTo(PricingDecisionStatus.DRAFT);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        assertThat(storedApprovedPrice(decisionItemId(decisionId, th))).isNull();
    }

    @Test
    void approve_aStockLineWithAListPrice_succeeds_despiteHavingNoCost() {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10), stockLine("Stock TR", IN_TRANSIT, 4));
        seedEta(id, "Stock TR", eta());
        long decisionId = seedStockOnlyDecision(id);
        seedPriceMode(decisionId, "NET");
        seedListPriceAndNet(decisionId, itemId(id, "Stock TH"), new BigDecimal("1200.00"), new BigDecimal("1080.00"));
        seedListPriceAndNet(decisionId, itemId(id, "Stock TR"), new BigDecimal("700.00"), new BigDecimal("700.00"));

        PricingDecisionDto approved = decisionService.approve(decisionId,
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);

        assertThat(approved.status()).isEqualTo(PricingDecisionStatus.APPROVED);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);
        PricingDecisionItemDto th = decisionItemFor(approved, itemId(id, "Stock TH"));
        assertThat(th.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo("1080.00");
        assertThat(th.minimumSellingPricePerRequestedUnit())
            .as("the CEO's own price is pre-approved: no discount may go below it").isEqualByComparingTo("1080.00");
        assertThat(th.approvedMarginPct()).as("no cost, so no margin concept").isNull();
        assertThat(decisionItemFor(approved, itemId(id, "Stock TR")).approvedSellingPricePerRequestedUnit())
            .isEqualByComparingTo("700.00");
    }

    @Test
    void approve_anImportLineWithoutACost_isStillRefused_PIN() {
        long id = persistSubmitAndPickUp(importLine("Uncosted", catalogProductUncostable,
            "Factory Uncostable-StockLine", 10));
        quoteEveryImportLineReady(id);
        PricingDecisionDto decision = decisionService.startReview(id,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        assertThat(decision.items().get(0).frozenLandedCostPerRequestedUnitThb()).isNull();

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains("ต้องมีต้นทุน");
            });
        assertThat(storedDecisionStatus(decision.id())).isEqualTo(PricingDecisionStatus.DRAFT);
    }

    @Test
    void approve_aPricedStockLine_doesNotExemptAnUncostedImportLineOnTheSameRequest_viaStartReview() {
        long id = persistSubmitAndPickUp(
            importLine("Uncosted", catalogProductUncostable, "Factory Uncostable-StockLine", 10),
            stockLine("Stock TH", IN_THAILAND, 5));
        quoteEveryImportLineReady(id);
        forceStatus(id, PricingRequestStatus.READY_FOR_CEO_REVIEW);
        PricingDecisionDto decision = decisionService.startReview(id,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, "NET", List.of(
            stockPrice(decisionItemFor(decision, itemId(id, "Stock TH")).id(), "1200.00", null))), ceoActor);

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage()).contains("ต้องมีต้นทุน");
            });
        assertThat(storedDecisionStatus(decision.id())).isEqualTo(PricingDecisionStatus.DRAFT);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 14 - return-to-import
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void returnToImport_onARequestWithOnlyInThailandLines_isRefusedWith409_andNothingMoves() {
        long id = persistDraft(stockLine("Stock TH 1", IN_THAILAND, 10), stockLine("Stock TH 2", IN_THAILAND, 5));
        long decisionId = seedStockOnlyDecision(id);
        long importBefore = notificationCount(importUserId, id);

        assertThatThrownBy(() -> decisionService.returnToImport(decisionId,
            new ReturnPricingDecisionRequest("ตีกลับ"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(storedDecisionStatus(decisionId)).isEqualTo(PricingDecisionStatus.DRAFT);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        assertThat(notificationCount(importUserId, id)).as("import must not be pulled in").isEqualTo(importBefore);
    }

    @Test
    void returnToImport_onARequestWithAnInTransitLine_stillWorks_PIN() {
        long id = persistDraft(stockLine("Stock TR", IN_TRANSIT, 4), stockLine("Stock TH", IN_THAILAND, 5));
        seedEta(id, "Stock TR", eta());
        long decisionId = seedStockOnlyDecision(id);

        decisionService.returnToImport(decisionId, new ReturnPricingDecisionRequest("ETA ไม่ตรง"), ceoActor);

        assertThat(storedDecisionStatus(decisionId)).isEqualTo(PricingDecisionStatus.RETURNED);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
    }

    @Test
    void returnToImport_onAMixedRequest_stillWorks_viaStartReview() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Stock TH", IN_THAILAND, 5));
        quoteEveryImportLineReady(id);
        forceStatus(id, PricingRequestStatus.READY_FOR_CEO_REVIEW);
        PricingDecisionDto decision = decisionService.startReview(id,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);

        decisionService.returnToImport(decision.id(), new ReturnPricingDecisionRequest("ต้นทุนคลาดเคลื่อน"), ceoActor);

        assertThat(storedDecisionStatus(decision.id())).isEqualTo(PricingDecisionStatus.RETURNED);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 16 - authz, wrong way round: nobody but the CEO prices a stock line
    // ─────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"salesOwner", "otherSales", "salesManager", "import", "account"})
    void update_settingAStockLinesPrice_byAnyoneButTheCeo_isRefusedWith403_andNoPriceIsStored_PIN(String who) {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10));
        long decisionId = seedStockOnlyDecision(id);
        seedPriceMode(decisionId, "NET");
        long decisionItemId = decisionItemId(decisionId, itemId(id, "Stock TH"));

        assertThatThrownBy(() -> decisionService.update(decisionId,
            new UpdatePricingDecisionRequest(null, "NET", List.of(stockPrice(decisionItemId, "999.00", null))),
            actorFor(who)))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(storedListPrice(decisionItemId)).isNull();
        assertThat(storedNetPrice(decisionItemId)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"salesOwner", "otherSales", "salesManager", "import", "account"})
    void approve_aStockDecision_byAnyoneButTheCeo_isRefusedWith403_andItStaysDraft_PIN(String who) {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10));
        long decisionId = seedStockOnlyDecision(id);
        seedPriceMode(decisionId, "NET");
        seedListPriceAndNet(decisionId, itemId(id, "Stock TH"), new BigDecimal("1200.00"), new BigDecimal("1200.00"));

        assertThatThrownBy(() -> decisionService.approve(decisionId,
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), actorFor(who)))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(storedDecisionStatus(decisionId)).isEqualTo(PricingDecisionStatus.DRAFT);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.CEO_REVIEWING);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /** The CEO's ราคาตั้ง (+ optional NET discount %) for ONE stock decision item. */
    private UpdatePricingDecisionItemRequest stockPrice(long decisionItemId, String listPrice, String discountPct) {
        return new UpdatePricingDecisionItemRequest(decisionItemId, null, null, null, null, false,
            discountPct == null ? null : new BigDecimal(discountPct), false, null, false, null, false,
            new BigDecimal(listPrice));
    }

    private PricingDecisionItemDto decisionItemFor(PricingDecisionDto decision, long pricingRequestItemId) {
        return decision.items().stream()
            .filter(i -> i.pricingRequestItemId() == pricingRequestItemId)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the decision has no item for pricing_request_item " + pricingRequestItemId
                    + " (items: " + decision.items().size() + ")"));
    }

    private UserPrincipal actorFor(String who) {
        return switch (who) {
            case "salesOwner" -> salesActor;
            case "otherSales" -> otherSalesActor;
            case "salesManager" -> salesManagerActor;
            case "import" -> importActor;
            case "account" -> accountActor;
            default -> throw new IllegalArgumentException(who);
        };
    }

    private Long storedCostingLink(long decisionItemId) {
        return jdbc.queryForObject(
            "SELECT pricing_costing_item_id FROM sales.pricing_decision_item WHERE pricing_decision_item_id = :id",
            Map.of("id", decisionItemId), Long.class);
    }

    private BigDecimal storedListPrice(long decisionItemId) {
        return jdbc.queryForObject(
            "SELECT list_unit_price FROM sales.pricing_decision_item WHERE pricing_decision_item_id = :id",
            Map.of("id", decisionItemId), BigDecimal.class);
    }

    private BigDecimal storedNetPrice(long decisionItemId) {
        return jdbc.queryForObject(
            "SELECT net_unit_price FROM sales.pricing_decision_item WHERE pricing_decision_item_id = :id",
            Map.of("id", decisionItemId), BigDecimal.class);
    }

    private BigDecimal storedApprovedPrice(long decisionItemId) {
        return jdbc.queryForObject("""
            SELECT approved_selling_price_per_requested_unit FROM sales.pricing_decision_item
             WHERE pricing_decision_item_id = :id
            """, Map.of("id", decisionItemId), BigDecimal.class);
    }

    private String storedDecisionStatus(long decisionId) {
        return jdbc.queryForObject("SELECT status FROM sales.pricing_decision WHERE pricing_decision_id = :id",
            Map.of("id", decisionId), String.class);
    }

    private long costedRowCount(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_costing_item pci
              JOIN sales.pricing_costing pc ON pc.pricing_costing_id = pci.pricing_costing_id
             WHERE pc.pricing_request_id = :id
            """, Map.of("id", pricingRequestId), Long.class);
    }
}
