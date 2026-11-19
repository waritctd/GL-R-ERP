package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;

/** Slice 1, stock lines: SUBMIT ROUTING, the one READINESS predicate, factory-quote skipping, submit
 * validation, and the lock on the source after submit. Real services + real repositories + real Postgres (no
 * HTTP - this slice is backend only). */
class StockLineRoutingIntegrationTest extends AbstractStockLineIntegrationTest {

    // Case 1 - only สต็อกในไทย lines: straight to the CEO

    @Test
    void onlyInThailandLines_submitGoesStraightToCeoReview_ceoNotified_importNotNotified_zeroFactoryQuotes() {
        long id = persistDraft(
            stockLine("Stock TH 1", IN_THAILAND, 10),
            // a stock line that WOULD resolve to a factory must still never be sent to one
            stockLineWithCatalog("Stock TH 2", IN_THAILAND, catalogProductFactoryB, FACTORY_B, 5));
        long importBefore = notificationCount(importUserId, id);
        long ceoBefore = notificationCount(ceoUserId, id);

        pricingRequestService.submit(id, salesActor);

        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        assertThat(notificationCount(ceoUserId, id))
            .as("the CEO is told the request is waiting for a price").isGreaterThan(ceoBefore);
        assertThat(notificationCount(importUserId, id))
            .as("import has nothing to do on an all-in-Thailand request and must NOT be notified")
            .isEqualTo(importBefore);
        assertThat(factoryQuoteCount(id)).as("R3: stock lines are never sent to a factory").isZero();

        // The CEO's bell row is the detailed notice: headline 2, stock source per line, no cost, no total.
        String message = jdbc.queryForObject(
            "SELECT message FROM hr.notification WHERE employee_id = :e AND link = :link"
                + " AND type = 'PRICING_COSTING_SUBMITTED' ORDER BY notification_id DESC LIMIT 1",
            Map.of("e", ceoUserId, "link", "/pricing-requests/" + id), String.class);
        assertThat(message)
            .startsWith(salesActor.name() + " ส่งคำขอราคาสต็อกในไทยทั้งหมด — รอ CEO ตั้งราคา\n")
            .contains("\n\nรายการ (2):\n")
            .contains("• SCG Stock TH 1 60x60 — 3.6 ตร.ม. · สต็อกในไทย")
            .contains("• SCG Stock TH 2 60x60 — 1.8 ตร.ม. · สต็อกในไทย")
            .doesNotContain("ต้นทุนรวม")
            .doesNotContain("ต้นทุน -");
    }

    // Case 2 - import + สต็อกในไทย: import path, quotes contain ONLY import lines

    @Test
    void importPlusInThailand_submitGoesToImport_importNotified_notStraightToCeo_PIN() {
        long id = persistDraft(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Stock TH", IN_THAILAND, 5));
        long importBefore = notificationCount(importUserId, id);

        pricingRequestService.submit(id, salesActor);

        assertThat(status(id)).isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(notificationCount(importUserId, id)).isGreaterThan(importBefore);
    }

    // Case 5 - submit validation

    // setItemFactory on a stock line - moved here from the deleted StockLineInTransitIntegrationTest
    // (the in-transit write paths went with the option; this refusal stayed).

    @Test
    void mixedRequest_aStockLineIsExempt_butAnImportLineOnTheSameRequestIsStillRefused() {
        // Row 1 is a stock line with none of the import inputs (fine); row 2 is an import line missing its origin
        // country: the refusal must name ROW 2, proving the exemption is per line and not "this request has a stock
        // line, skip validation".
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            requestOf(stockLine("Stock TH", IN_THAILAND, 10), importLineMissingOriginCountry("Imp", 5)),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("รายการที่ 2").contains("ประเทศต้นทาง");
            });
    }

    // Case 6 - the source round-trips (create / update / read / customer-change revision)

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest importLineMissingOriginCountry(
        String model, int pieces
    ) {
        var full = importLine(model, null, null, pieces);
        return withOriginAndLead(full, null, 3, 7, null);
    }

    private th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest withOriginAndLead(
        th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest i,
        String originCountry, Integer leadMin, Integer leadMax, String originCountryOther
    ) {
        return new th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest(
            i.sourceTicketItemId(), i.productId(), i.variantId(), i.brand(), i.model(), i.productDescription(),
            i.color(), i.texture(), i.size(), i.factory(), i.requestedQty(), i.requestedQtySqm(),
            i.requestedUnit(), i.requestedUnitBasis(), i.quantityType(), i.targetDeliveryDate(),
            i.deliveryLocation(), i.specialRequirement(), i.productCode(), i.thicknessMm(), i.sqmPerPiece(),
            i.quantityMode(), i.areaSqm(), i.piecesInput(), i.wastageMode(), i.wastageValue(),
            i.piecesPerBox(), i.sqmPerBox(), i.roundToFullBox(), originCountry, leadMin, leadMax,
            i.piecesBeforeWastage(), i.piecesAfterWastage(), i.boxes(), originCountryOther, i.stockSource());
    }

}
