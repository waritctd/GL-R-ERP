package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CustomerChangeRevisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.SetItemFactoryRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;

/**
 * Slice 1, stock lines: SUBMIT ROUTING, the one READINESS predicate, factory-quote skipping, submit
 * validation, and the lock on the source after submit. Real services + real repositories + real
 * Postgres (no HTTP - this slice is backend only).
 *
 * <p>Tests marked <b>[PIN]</b> pass BEFORE the feature exists on purpose: they pin existing
 * behaviour the feature must not regress (a wrong-way-round guard), and are not evidence that the
 * feature works. Everything else must be red until the behaviour is implemented.
 *
 * <p>Readiness (IA 1.2): a request may reach the CEO only when EVERY import line is resolvable
 * (its factory quote is READY_FOR_COSTING) AND EVERY IN_TRANSIT line has an ETA, re-checked after
 * markReadyForCosting and after each ETA save, so whichever finishes last advances it.
 */
class StockLineRoutingIntegrationTest extends AbstractStockLineIntegrationTest {

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 1 - only สต็อกในไทย lines: straight to the CEO
    // ─────────────────────────────────────────────────────────────────────────────────────

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
    }

    @Test
    void onlyInThailandLines_importCannotPickItUp_itIsNotInImportsQueue() {
        long id = persistAndSubmit(stockLine("Stock TH 1", IN_THAILAND, 10));

        assertThatThrownBy(() -> pricingRequestService.pickup(id, importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(pricingRequests.findSummary(id).orElseThrow().assignedImportId())
            .as("import must not have been assigned").isNull();
    }

    @Test
    void serviceCreatedInThailandOnlyRequest_endToEnd_submitsStraightToCeoReview() {
        // Same as case 1 but through createDraft, i.e. with the REAL validation in the path.
        long id = pricingRequestService.createDraft(ticketId,
            requestOf(stockLine("Stock TH svc", IN_THAILAND, 10)), salesActor).summary().id();

        pricingRequestService.submit(id, salesActor);

        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 2 - import + สต็อกในไทย: import path, quotes contain ONLY import lines
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    @Test
    void factoryQuoteDrafts_containOnlyImportLines_evenWhenStockLinesResolveToAFactory() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            // the stock line points at a real catalog product of factory B: ONLY the source may
            // keep it out of factory B's quote, not the absence of a factory.
            stockLineWithCatalog("Stock TH", IN_THAILAND, catalogProductFactoryB, FACTORY_B, 5));

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(id, importActor);

        assertThat(drafts).extracting(FactoryQuoteDto::factoryName).containsExactly(FACTORY_A);
        assertThat(stockLinesInsideAnyFactoryQuote(id))
            .as("no stock line may appear in ANY factory_quote_item").isZero();
    }

    @Test
    void importQuotesReady_withAnInThailandLinePresent_autoAdvancesToCeoReview() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Stock TH", IN_THAILAND, 5));

        quoteEveryImportLineReady(id);

        assertThat(status(id))
            .as("a stock line has no factory quote and must not block readiness")
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 5 - submit validation
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createDraft_aStockLine_isAcceptedWithoutFactoryOriginCountryOrLeadTime() {
        PricingRequestDetailDto created = pricingRequestService.createDraft(ticketId,
            requestOf(stockLine("Stock TH", IN_THAILAND, 10)), salesActor);

        assertThat(created.items()).singleElement().satisfies(item -> {
            assertThat(item.factory()).isNull();
            assertThat(item.originCountry()).isNull();
            assertThat(item.leadTimeMinDays()).isNull();
            assertThat(item.leadTimeMaxDays()).isNull();
        });
    }

    /**
     * Walk 4 (W4-01): the import-only inputs are cleared for a stock line even if a client sends
     * them (the form hides them, but an old draft or a hand-built request can still carry them):
     * they would otherwise print on the quotation as an import lead time. Read from the stored
     * columns, not the response, so a response that merely hides them cannot pass.
     */
    @Test
    void b10_createDraft_anInThailandLineSentWithImportOnlyInputs_storesThemNull() {
        PricingRequestItemRequest full = withOriginAndLead(
            lineWithFullImportFields("Plumb", IN_THAILAND, 10), "อื่นๆ", 3, 7, "Zanzibar");

        long id = pricingRequestService.createDraft(ticketId, requestOf(full), salesActor).summary().id();

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT stock_source, origin_country, origin_country_other, lead_time_min_days, lead_time_max_days
              FROM sales.pricing_request_item WHERE pricing_request_id = :id
            """, Map.of("id", id));
        assertThat(row.get("stock_source")).isEqualTo(IN_THAILAND);
        assertThat(row.get("origin_country")).isNull();
        assertThat(row.get("origin_country_other")).isNull();
        assertThat(row.get("lead_time_min_days")).isNull();
        assertThat(row.get("lead_time_max_days")).isNull();
    }

    @Test
    void b10_updateDraft_switchingAnImportLineToStock_clearsItsStoredImportInputs() {
        long id = pricingRequestService.createDraft(ticketId,
            requestOf(importLine("Switch", null, null, 10)), salesActor).summary().id();
        assertThat(itemByModel(id, "Switch").leadTimeMinDays()).as("fixture: the import line carries a lead time")
            .isNotNull();

        pricingRequestService.updateDraft(id,
            draftUpdate(lineWithFullImportFields("Switch", IN_THAILAND, 10)), salesActor);

        assertThat(jdbc.queryForObject("""
            SELECT lead_time_min_days IS NULL AND lead_time_max_days IS NULL AND origin_country IS NULL
              FROM sales.pricing_request_item WHERE pricing_request_id = :id
            """, Map.of("id", id), Boolean.class)).isTrue();
    }

    /** Walk 4 (owner ruling): the สต็อกกำลังเดินทาง option is removed - a new line cannot be created as one. */
    @Test
    void b11_createDraft_anInTransitLine_isRefused400WithAThaiMessage() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            requestOf(stockLine("Transit", IN_TRANSIT, 4)), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                // The generic unknown-source 400: IN_TRANSIT is just not a source any more (no dedicated branch).
                assertThat(e.getMessage()).contains("รายการที่ 1").contains("ไม่รองรับแหล่งที่มาของสินค้า 'IN_TRANSIT'");
            });
    }

    @Test
    void b11_updateDraft_switchingALineToInTransit_isRefused400() {
        long id = pricingRequestService.createDraft(ticketId,
            requestOf(importLine("Switch", null, null, 10)), salesActor).summary().id();

        assertThatThrownBy(() -> pricingRequestService.updateDraft(id,
            draftUpdate(stockLine("Switch", IN_TRANSIT, 10)), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(storedStockSource(id)).as("the refused update changed nothing").isNull();
    }

    // setItemFactory on a stock line - moved here from the deleted StockLineInTransitIntegrationTest
    // (the in-transit write paths went with the option; this refusal stayed).

    @Test
    void setItemFactory_onAStockLine_isRefusedWith409_andTheLineKeepsNoFactory() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Stock", IN_THAILAND, 5));
        long stockItemId = itemId(id, "Stock");

        assertThatThrownBy(() -> pricingRequestService.setItemFactory(id, stockItemId,
            new SetItemFactoryRequest(factoryId(FACTORY_B)), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        PricingRequestItemDto after = itemByModel(id, "Stock");
        assertThat(after.factory()).isNull();
        assertThat(after.resolvedFactoryId()).isNull();
        assertThat(after.stockSource()).isEqualTo(IN_THAILAND);
    }

    @Test
    void setItemFactory_onAnImportLineWithoutAFactory_stillWorks_PIN() {
        long id = persistSubmitAndPickUp(
            importLineWithoutFactory("Free text", 5),
            stockLine("Stock TH", IN_THAILAND, 3));

        pricingRequestService.setItemFactory(id, itemId(id, "Free text"),
            new SetItemFactoryRequest(factoryId(FACTORY_B)), importActor);

        assertThat(itemByModel(id, "Free text").factory()).isEqualTo(FACTORY_B);
    }

    @Test
    void updateDraft_switchingAnImportLineToStock_dropsTheImportOnlyRequirements() {
        long id = pricingRequestService.createDraft(ticketId,
            requestOf(importLine("Switch", null, null, 10)), salesActor).summary().id();

        PricingRequestDetailDto updated = pricingRequestService.updateDraft(id,
            draftUpdate(stockLine("Switch", IN_THAILAND, 10)), salesActor);

        assertThat(updated.items()).singleElement().satisfies(i -> {
            assertThat(i.stockSource()).isEqualTo(IN_THAILAND);
            assertThat(i.originCountry()).isNull();
        });
    }

    @Test
    void importLine_missingOriginCountry_stillRefusedAtCreate_PIN() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            requestOf(importLineMissingOriginCountry("No origin", 5)), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ประเทศต้นทาง");
            });
    }

    @Test
    void importLine_withoutFactory_stillRefusedAtFactoryQuoteGeneration_PIN() {
        long id = persistSubmitAndPickUp(importLineWithoutFactory("Free text", 5));

        assertThatThrownBy(() -> factoryQuoteService.generateDrafts(id, importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    @Test
    void mixedRequest_aStockLineIsExempt_butAnImportLineOnTheSameRequestIsStillRefused() {
        // Row 1 is a stock line with none of the import inputs (fine); row 2 is an import line
        // missing its origin country: the refusal must name ROW 2, proving the exemption is per
        // line and not "this request has a stock line, skip validation".
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            requestOf(stockLine("Stock TH", IN_THAILAND, 10), importLineMissingOriginCountry("Imp", 5)),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("รายการที่ 2").contains("ประเทศต้นทาง");
            });
    }

    @Test
    void unknownStockSource_isRefusedWith400_notLeftToTheCheckConstraintAs500() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            requestOf(lineWithFullImportFields("Bad source", "IN_SPACE", 10)), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 6 - the source round-trips (create / update / read / customer-change revision)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createDraft_aStockSourceOnAnOtherwiseCompleteLine_isStored_notSilentlyDropped() {
        // Isolates the plumbing from the validation relaxation: this line carries origin country and
        // lead time, so today's validation accepts it - and today the source is dropped on the floor
        // when PricingRequestService#resolveItem rebuilds the item.
        long id = pricingRequestService.createDraft(ticketId,
            requestOf(lineWithFullImportFields("Plumbing", IN_THAILAND, 10)), salesActor).summary().id();

        assertThat(storedStockSource(id)).isEqualTo(IN_THAILAND);
        assertThat(pricingRequestService.get(id, salesActor).items().get(0).stockSource()).isEqualTo(IN_THAILAND);
    }

    @Test
    void stockSource_roundTripsThroughCreateUpdateAndRead_inDraft() {
        PricingRequestDetailDto created = pricingRequestService.createDraft(ticketId,
            requestOf(stockLine("RT", IN_THAILAND, 5)), salesActor);
        long id = created.summary().id();
        assertThat(created.items()).singleElement()
            .satisfies(i -> assertThat(i.stockSource()).isEqualTo(IN_THAILAND));
        assertThat(pricingRequestService.get(id, salesActor).items().get(0).stockSource()).isEqualTo(IN_THAILAND);
        assertThat(storedStockSource(id)).isEqualTo(IN_THAILAND);

        pricingRequestService.updateDraft(id, draftUpdate(importLine("RT", null, null, 5)), salesActor);
        assertThat(pricingRequestService.get(id, salesActor).items().get(0).stockSource())
            .as("back to สั่งนำเข้า").isNull();
        assertThat(storedStockSource(id)).isNull();
    }

    @Test
    void stockSource_isCarriedIntoACustomerChangeRevision() {
        long parentId = persistAndSubmit(importLine("Imp", catalogProductFactoryA, FACTORY_A, 10));

        PricingRequestDetailDto revision = pricingRequestService.createCustomerChangeRevision(parentId,
            new CustomerChangeRevisionRequest("ลูกค้าเปลี่ยน", UUID.randomUUID().toString(),
                PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
                null, "THB", null,
                List.of(stockLine("Rev TH", IN_THAILAND, 5),
                    importLine("Rev Imp", catalogProductFactoryA, FACTORY_A, 10))),
            salesActor);

        long childId = revision.summary().id();
        assertThat(itemByModel(childId, "Rev TH").stockSource()).isEqualTo(IN_THAILAND);
        assertThat(itemByModel(childId, "Rev Imp").stockSource()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 7 - locked for sales after submit
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void afterSubmit_salesCannotChangeTheSource_PIN() {
        long id = persistAndSubmit(importLine("Locked", catalogProductFactoryA, FACTORY_A, 10));

        assertThatThrownBy(() -> pricingRequestService.updateDraft(id,
            draftUpdate(stockLine("Locked", IN_THAILAND, 10)), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(storedStockSource(id)).as("source untouched by the refused update").isNull();
        assertThat(pricingRequests.findItems(id)).hasSize(1);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.SUBMITTED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private UpdatePricingRequestRequest draftUpdate(th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest... items) {
        return new UpdatePricingRequestRequest(PricingRequestRecipient.DESIGNER, null, "Designer Co.", null, null,
            "THB", null, List.of(items));
    }

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

    private String storedStockSource(long pricingRequestId) {
        return jdbc.queryForObject(
            "SELECT stock_source FROM sales.pricing_request_item WHERE pricing_request_id = :id ORDER BY sort_order LIMIT 1",
            Map.of("id", pricingRequestId), String.class);
    }

    private long stockLinesInsideAnyFactoryQuote(long pricingRequestId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.factory_quote_item fqi
              JOIN sales.factory_quote fq ON fq.factory_quote_id = fqi.factory_quote_id
              JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = fqi.pricing_request_item_id
             WHERE fq.pricing_request_id = :id AND pri.stock_source IS NOT NULL
            """, Map.of("id", pricingRequestId), Long.class);
    }
}
