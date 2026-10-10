package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.SetItemFactoryRequest;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;

/**
 * Slice 1, stock lines: the two NEW write paths ({@code setInTransitArrival},
 * {@code convertInTransitToImport}), the {@code setItemFactory} refusal for stock lines, and their
 * authorization - written the wrong way round (who must NOT reach it, and the row is unchanged
 * afterwards), per CLAUDE.md "Permission changes must ship evidence".
 *
 * <p>Authz evidence here is REAL: real service, real repository, real Postgres, no mock. It is not
 * a mutation check - that is the implementer's/reviewer's step (drop the role gate, expect exactly
 * the matching case below to go red).
 *
 * <p>Tests marked <b>[PIN]</b> pass before the feature exists on purpose.
 *
 * <p>Contract choices this file makes that the design left open (each is stated so a reviewer can
 * overrule it): a wrong-KIND line or a wrong-STATUS request is a 409; a null date is a 400; a role
 * outside the allowed set is a 403; convert is ALSO allowed from READY_FOR_CEO_REVIEW (with a
 * pull-back), because IA 1.2 both lists the status guard as {IMPORT_REVIEWING,
 * AWAITING_FACTORY_RESPONSE} and specifies the READY_FOR_CEO_REVIEW pull-back - the pull-back would
 * be dead code otherwise, so the guard is read as including READY_FOR_CEO_REVIEW.
 */
class StockLineInTransitIntegrationTest extends AbstractStockLineIntegrationTest {

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 8 - setItemFactory on a stock line
    // ─────────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {IN_THAILAND, IN_TRANSIT})
    void setItemFactory_onAStockLine_isRefusedWith409_andTheLineKeepsNoFactory(String source) {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Stock", source, 5));
        long stockItemId = itemId(id, "Stock");

        assertThatThrownBy(() -> pricingRequestService.setItemFactory(id, stockItemId,
            new SetItemFactoryRequest(factoryId(FACTORY_B)), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        PricingRequestItemDto after = itemByModel(id, "Stock");
        assertThat(after.factory()).isNull();
        assertThat(after.resolvedFactoryId()).isNull();
        assertThat(after.stockSource()).isEqualTo(source);
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 6 (tail) + case 10 - setInTransitArrival
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void setInTransitArrival_byImport_persistsTheDate_andWhoSetIt() {
        long id = persistSubmitAndPickUp(stockLine("Transit", IN_TRANSIT, 4), importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));

        pricingRequestService.setInTransitArrival(id, itemId(id, "Transit"), eta(), importActor);

        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isEqualTo(eta());
        assertThat(etaSetBy(id, "Transit")).isEqualTo(importUserId);
        assertThat(etaSetAtIsSet(id, "Transit")).isTrue();
    }

    @Test
    void setInTransitArrival_byTheCeo_isAllowed() {
        long id = persistSubmitAndPickUp(stockLine("Transit", IN_TRANSIT, 4), importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));

        pricingRequestService.setInTransitArrival(id, itemId(id, "Transit"), eta(), ceoActor);

        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isEqualTo(eta());
        assertThat(etaSetBy(id, "Transit")).isEqualTo(ceoUserId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"salesOwner", "otherSales", "salesManager", "account"})
    void setInTransitArrival_byAnyoneButImportAndCeo_isRefusedWith403_andNothingChanges(String who) {
        long id = persistSubmitAndPickUp(stockLine("Transit", IN_TRANSIT, 4), importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));
        Snapshot before = snapshot(id, "Transit");

        assertThatThrownBy(() -> pricingRequestService.setInTransitArrival(
            id, itemId(id, "Transit"), eta(), actorFor(who)))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(snapshot(id, "Transit")).as("the refused write must leave no trace").isEqualTo(before);
        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {IN_THAILAND, "IMPORT"})
    void setInTransitArrival_onALineThatIsNotInTransit_isRefusedWith409_andNoEtaIsWritten(String kind) {
        long id = persistSubmitAndPickUp(
            IN_THAILAND.equals(kind) ? stockLine("Wrong kind", IN_THAILAND, 4)
                : importLine("Wrong kind", catalogProductFactoryA, FACTORY_A, 4),
            stockLine("Transit", IN_TRANSIT, 4));
        Snapshot before = snapshot(id, "Wrong kind");

        assertThatThrownBy(() -> pricingRequestService.setInTransitArrival(
            id, itemId(id, "Wrong kind"), eta(), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(snapshot(id, "Wrong kind")).isEqualTo(before);
        assertThat(itemByModel(id, "Wrong kind").expectedArrivalDate()).isNull();
    }

    @Test
    void setInTransitArrival_withANullDate_isRefusedWith400() {
        long id = persistSubmitAndPickUp(stockLine("Transit", IN_TRANSIT, 4), importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));

        assertThatThrownBy(() -> pricingRequestService.setInTransitArrival(
            id, itemId(id, "Transit"), null, importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {PricingRequestStatus.SUBMITTED, PricingRequestStatus.CEO_REVIEWING,
        PricingRequestStatus.APPROVED_FOR_QUOTATION, PricingRequestStatus.CANCELLED})
    void setInTransitArrival_outsideImportReviewingAwaitingFactoryOrReady_isRefusedWith409(String forced) {
        long id = persistAndSubmit(stockLine("Transit", IN_TRANSIT, 4), importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));
        if (!PricingRequestStatus.SUBMITTED.equals(forced)) {
            forceStatus(id, forced);
        }
        Snapshot before = snapshot(id, "Transit");

        assertThatThrownBy(() -> pricingRequestService.setInTransitArrival(
            id, itemId(id, "Transit"), eta(), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(snapshot(id, "Transit")).isEqualTo(before);
        assertThat(status(id)).isEqualTo(forced);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Case 9 - convertInTransitToImport
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void convert_byImport_clearsSourceAndEta_notifiesTheOwningRep_andTheLineBecomesAnImportLine() {
        long id = persistSubmitAndPickUp(
            stockLine("Transit", IN_TRANSIT, 4),
            stockLine("Stock TH", IN_THAILAND, 3));
        seedEta(id, "Transit", eta());
        long repBefore = notificationCount(salesRepId, id);
        long otherRepBefore = notificationCount(otherSalesRepId, id);
        long transitItemId = itemId(id, "Transit");

        pricingRequestService.convertInTransitToImport(id, transitItemId, importActor);

        PricingRequestItemDto converted = itemByModel(id, "Transit");
        assertThat(converted.stockSource()).as("R13: now สั่งนำเข้า").isNull();
        assertThat(converted.expectedArrivalDate()).isNull();
        assertThat(etaSetBy(id, "Transit")).isNull();
        assertThat(etaSetAtIsSet(id, "Transit")).isFalse();
        assertThat(itemByModel(id, "Stock TH").stockSource()).as("other lines untouched").isEqualTo(IN_THAILAND);
        assertThat(notificationCount(salesRepId, id)).as("the OWNING rep is told").isEqualTo(repBefore + 1);
        assertThat(notificationCount(otherSalesRepId, id)).as("nobody else is").isEqualTo(otherRepBefore);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);

        // ...and it is now a NORMAL import line: import can give it a factory, and the next
        // factory-quote generation includes it (and still never the in-Thailand line).
        pricingRequestService.setItemFactory(id, transitItemId, new SetItemFactoryRequest(factoryId(FACTORY_B)),
            importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(id, importActor);
        assertThat(drafts).flatExtracting(FactoryQuoteDto::items)
            .extracting(item -> item.pricingRequestItemId())
            .containsExactly(transitItemId);
    }

    @Test
    void convert_whileReadyForCeoReview_pullsTheRequestBackToAwaitingFactoryResponse() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Transit", IN_TRANSIT, 4));
        quoteEveryImportLineReady(id);
        seedEta(id, "Transit", eta());
        forceStatus(id, PricingRequestStatus.READY_FOR_CEO_REVIEW); // precondition, see class Javadoc

        pricingRequestService.convertInTransitToImport(id, itemId(id, "Transit"), importActor);

        assertThat(status(id))
            .as("the converted line has no factory quote yet: the request is no longer ready")
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        assertThat(itemByModel(id, "Transit").stockSource()).isNull();
        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isNull();
    }

    @Test
    void convert_thenTheLineFollowsTheNormalImportFlow_andTheRequestIsReadyAgainOnceItIsQuoted() {
        long id = persistSubmitAndPickUp(
            importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Transit", IN_TRANSIT, 4));
        quoteEveryImportLineReady(id);
        seedEta(id, "Transit", eta());
        forceStatus(id, PricingRequestStatus.READY_FOR_CEO_REVIEW);
        pricingRequestService.convertInTransitToImport(id, itemId(id, "Transit"), importActor);
        pricingRequestService.setItemFactory(id, itemId(id, "Transit"),
            new SetItemFactoryRequest(factoryId(FACTORY_B)), importActor);

        quoteEveryImportLineReady(id);

        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
    }

    @ParameterizedTest
    @ValueSource(strings = {IN_THAILAND, "IMPORT"})
    void convert_onALineThatIsNotInTransit_isRefusedWith409_andNothingChanges(String kind) {
        long id = persistSubmitAndPickUp(
            IN_THAILAND.equals(kind) ? stockLine("Wrong kind", IN_THAILAND, 4)
                : importLine("Wrong kind", catalogProductFactoryA, FACTORY_A, 4),
            stockLine("Transit", IN_TRANSIT, 4));
        Snapshot before = snapshot(id, "Wrong kind");

        assertThatThrownBy(() -> pricingRequestService.convertInTransitToImport(
            id, itemId(id, "Wrong kind"), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(snapshot(id, "Wrong kind")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {PricingRequestStatus.SUBMITTED, PricingRequestStatus.CEO_REVIEWING,
        PricingRequestStatus.APPROVED_FOR_QUOTATION, PricingRequestStatus.CANCELLED})
    void convert_outsideImportReviewingAwaitingFactoryOrReady_isRefusedWith409_andTheLineIsUnchanged(String forced) {
        long id = persistAndSubmit(stockLine("Transit", IN_TRANSIT, 4), importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));
        seedEta(id, "Transit", eta());
        if (!PricingRequestStatus.SUBMITTED.equals(forced)) {
            forceStatus(id, forced);
        }
        Snapshot before = snapshot(id, "Transit");

        assertThatThrownBy(() -> pricingRequestService.convertInTransitToImport(
            id, itemId(id, "Transit"), importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(snapshot(id, "Transit")).isEqualTo(before);
        assertThat(itemByModel(id, "Transit").stockSource()).isEqualTo(IN_TRANSIT);
        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isEqualTo(eta());
    }

    // ── case 10, convert half: IMPORT ONLY - the CEO is refused too ─────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"salesOwner", "otherSales", "salesManager", "account", "ceo"})
    void convert_byAnyoneButImport_isRefusedWith403_theCeoIncluded_andTheLineIsUnchanged(String who) {
        long id = persistSubmitAndPickUp(stockLine("Transit", IN_TRANSIT, 4), importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));
        seedEta(id, "Transit", eta());
        Snapshot before = snapshot(id, "Transit");

        assertThatThrownBy(() -> pricingRequestService.convertInTransitToImport(
            id, itemId(id, "Transit"), actorFor(who)))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(snapshot(id, "Transit")).as("the refused conversion must leave no trace").isEqualTo(before);
        assertThat(itemByModel(id, "Transit").stockSource()).isEqualTo(IN_TRANSIT);
        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isEqualTo(eta());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private UserPrincipal actorFor(String who) {
        return switch (who) {
            case "salesOwner" -> salesActor;
            case "otherSales" -> otherSalesActor;
            case "salesManager" -> salesManagerActor;
            case "account" -> accountActor;
            case "ceo" -> ceoActor;
            case "import" -> importActor;
            default -> throw new IllegalArgumentException(who);
        };
    }

    /** Everything a refused write could have touched, so "refused" means "no trace at all". */
    private record Snapshot(String stockSource, LocalDate eta, Long etaSetBy, String status, long events,
                            long repNotifications, long ceoNotifications, long importNotifications) {}

    private Snapshot snapshot(long id, String model) {
        PricingRequestItemDto item = itemByModel(id, model);
        return new Snapshot(item.stockSource(), item.expectedArrivalDate(), etaSetBy(id, model), status(id),
            eventCount(id), notificationCount(salesRepId, id), notificationCount(ceoUserId, id),
            notificationCount(importUserId, id));
    }

    private Long etaSetBy(long id, String model) {
        return jdbc.queryForObject("""
            SELECT expected_arrival_set_by_id FROM sales.pricing_request_item
             WHERE pricing_request_id = :id AND model = :model
            """, Map.of("id", id, "model", model), Long.class);
    }

    private boolean etaSetAtIsSet(long id, String model) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT expected_arrival_set_at IS NOT NULL FROM sales.pricing_request_item
             WHERE pricing_request_id = :id AND model = :model
            """, Map.of("id", id, "model", model), Boolean.class));
    }
}
