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
            // both stock kinds point at a real catalog product of factory B: ONLY the source may
            // keep them out of factory B's quote, not the absence of a factory.
            stockLineWithCatalog("Stock TH", IN_THAILAND, catalogProductFactoryB, FACTORY_B, 5),
            stockLineWithCatalog("Stock TR", IN_TRANSIT, catalogProductFactoryB, FACTORY_B, 4));

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
    // Case 3 - สต็อกกำลังเดินทาง with NO import lines: the new IMPORT_REVIEWING -> READY edge
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void inTransitLines_evenWithInThailand_submitGoesToImport_notStraightToCeo_PIN() {
        long id = persistDraft(
            stockLine("Transit 1", IN_TRANSIT, 10),
            stockLine("Stock TH", IN_THAILAND, 3));
        long importBefore = notificationCount(importUserId, id);

        pricingRequestService.submit(id, salesActor);

        assertThat(status(id)).isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(notificationCount(importUserId, id)).isGreaterThan(importBefore);
    }

    @Test
    void inTransitOnly_oneEtaStillMissing_staysWithImport_butTheSavedEtaIsPersisted() {
        long id = persistSubmitAndPickUp(
            stockLine("Transit 1", IN_TRANSIT, 10),
            stockLine("Transit 2", IN_TRANSIT, 4),
            stockLine("Stock TH", IN_THAILAND, 3));
        assertThat(status(id)).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);

        pricingRequestService.setInTransitArrival(id, itemId(id, "Transit 1"), eta(), importActor);

        assertThat(itemByModel(id, "Transit 1").expectedArrivalDate()).isEqualTo(eta());
        assertThat(itemByModel(id, "Transit 2").expectedArrivalDate()).isNull();
        assertThat(status(id))
            .as("Transit 2 still has no ETA: the request must NOT reach the CEO yet")
            .isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);
    }

    @Test
    void inTransitOnly_lastMissingEta_advancesImportReviewingToReadyForCeoReview_andNotifiesCeo() {
        long id = persistSubmitAndPickUp(
            stockLine("Transit 1", IN_TRANSIT, 10),
            stockLine("Transit 2", IN_TRANSIT, 4),
            stockLine("Stock TH", IN_THAILAND, 3));
        pricingRequestService.setInTransitArrival(id, itemId(id, "Transit 1"), eta(), importActor);
        long ceoBefore = notificationCount(ceoUserId, id);

        PricingRequestDetailDto detail = pricingRequestService.setInTransitArrival(
            id, itemId(id, "Transit 2"), eta().plusDays(3), importActor);

        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        assertThat(detail.summary().status()).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        assertThat(notificationCount(ceoUserId, id)).isGreaterThan(ceoBefore);
        assertThat(detail.events()).extracting(e -> e.toStatus())
            .as("the IMPORT_REVIEWING -> READY_FOR_CEO_REVIEW edge is on the audit trail")
            .contains(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 4 - import + สต็อกกำลังเดินทาง, in both orders
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void importAndInTransit_quotesReadyButEtaMissing_isNotReady_thenSavingTheEtaAdvances() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Transit", IN_TRANSIT, 4));

        quoteEveryImportLineReady(id);

        assertThat(status(id))
            .as("every quote is ready but the in-transit line has no ETA: NOT ready for the CEO")
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        pricingRequestService.setInTransitArrival(id, itemId(id, "Transit"), eta(), importActor);

        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    @Test
    void importAndInTransit_etaSavedFirst_thenMarkReadyForCostingAdvances() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Transit", IN_TRANSIT, 4));

        pricingRequestService.setInTransitArrival(id, itemId(id, "Transit"), eta(), importActor);

        assertThat(status(id))
            .as("ETA saved but the import line is not quoted yet: still with import")
            .isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);

        quoteEveryImportLineReady(id);

        assertThat(status(id))
            .as("the LAST of {quotes ready, ETAs set} to arrive must advance the request")
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    @Test
    void importAndInTransit_changingAnEtaWhileReadyForCeoReview_keepsItThere() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Transit", IN_TRANSIT, 4));
        pricingRequestService.setInTransitArrival(id, itemId(id, "Transit"), eta(), importActor);
        quoteEveryImportLineReady(id);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        pricingRequestService.setInTransitArrival(id, itemId(id, "Transit"), eta().plusDays(10), importActor);

        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isEqualTo(eta().plusDays(10));
        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 5 - submit validation
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createDraft_stockLinesOfBothKinds_acceptedWithoutFactoryOriginCountryOrLeadTime() {
        PricingRequestDetailDto created = pricingRequestService.createDraft(ticketId,
            requestOf(stockLine("Stock TH", IN_THAILAND, 10), stockLine("Stock TR", IN_TRANSIT, 4)), salesActor);

        assertThat(created.items()).hasSize(2);
        for (PricingRequestItemDto item : created.items()) {
            assertThat(item.factory()).isNull();
            assertThat(item.originCountry()).isNull();
            assertThat(item.leadTimeMinDays()).isNull();
            assertThat(item.leadTimeMaxDays()).isNull();
        }
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
    void importLine_missingLeadTime_stillRefusedAtCreate_PIN() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            requestOf(importLineMissingLeadTime("No lead time", 5)), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ระยะเวลานำเข้า");
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
            requestOf(stockLine("RT", IN_TRANSIT, 5)), salesActor);
        long id = created.summary().id();
        assertThat(created.items()).singleElement().satisfies(i -> {
            assertThat(i.stockSource()).isEqualTo(IN_TRANSIT);
            assertThat(i.expectedArrivalDate()).as("no ETA can exist in DRAFT").isNull();
        });
        assertThat(pricingRequestService.get(id, salesActor).items().get(0).stockSource()).isEqualTo(IN_TRANSIT);
        assertThat(storedStockSource(id)).isEqualTo(IN_TRANSIT);

        pricingRequestService.updateDraft(id, draftUpdate(stockLine("RT", IN_THAILAND, 5)), salesActor);
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
                List.of(stockLine("Rev TH", IN_THAILAND, 5), stockLine("Rev TR", IN_TRANSIT, 4),
                    importLine("Rev Imp", catalogProductFactoryA, FACTORY_A, 10))),
            salesActor);

        long childId = revision.summary().id();
        assertThat(itemByModel(childId, "Rev TH").stockSource()).isEqualTo(IN_THAILAND);
        assertThat(itemByModel(childId, "Rev TR").stockSource()).isEqualTo(IN_TRANSIT);
        assertThat(itemByModel(childId, "Rev Imp").stockSource()).isNull();
        assertThat(itemByModel(childId, "Rev TR").expectedArrivalDate())
            .as("a new revision starts without an ETA: import confirms it again").isNull();
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
        return withOriginAndLead(full, null, 3, 7);
    }

    private th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest importLineMissingLeadTime(
        String model, int pieces
    ) {
        var full = importLine(model, null, null, pieces);
        return withOriginAndLead(full, "ไทย-สต็อก", null, null);
    }

    private th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest withOriginAndLead(
        th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest i,
        String originCountry, Integer leadMin, Integer leadMax
    ) {
        return new th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest(
            i.sourceTicketItemId(), i.productId(), i.variantId(), i.brand(), i.model(), i.productDescription(),
            i.color(), i.texture(), i.size(), i.factory(), i.requestedQty(), i.requestedQtySqm(),
            i.requestedUnit(), i.requestedUnitBasis(), i.quantityType(), i.targetDeliveryDate(),
            i.deliveryLocation(), i.specialRequirement(), i.productCode(), i.thicknessMm(), i.sqmPerPiece(),
            i.quantityMode(), i.areaSqm(), i.piecesInput(), i.wastageMode(), i.wastageValue(),
            i.piecesPerBox(), i.sqmPerBox(), i.roundToFullBox(), originCountry, leadMin, leadMax,
            i.piecesBeforeWastage(), i.piecesAfterWastage(), i.boxes(), i.originCountryOther(), i.stockSource());
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
