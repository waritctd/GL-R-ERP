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
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionStatus;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;

/** Slice 1, stock lines: what the CEO sees and does - startReview, update, approve, returnToImport - for STOCK
 * lines, plus the authorization of the CEO's price on a stock line. It is set-only: a stock line cannot be
 * un-priced. */
class StockLineCeoDecisionIntegrationTest extends AbstractStockLineIntegrationTest {

    // Case 12 - CEO update: ราคาตั้ง on a stock line

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

    // Case 13 - approve

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
                // The existing 'ทุกรายการต้องมีต้นทุน' 422 would also be a 422; the NEW rule has its own message (IA 2:
                // "รายการจากสต็อกต้องมีราคาตั้ง"), so a stock line failing for the OLD reason cannot pass this test.
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
        long id = persistSubmitAndPickUp(importLineUncostable("Uncosted", "Factory Uncostable-StockLine", 10));
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

    // Case 16 - authz, wrong way round: nobody but the CEO prices a stock line

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

}
