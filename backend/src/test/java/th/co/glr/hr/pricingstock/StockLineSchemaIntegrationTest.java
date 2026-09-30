package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository.WriteItem;
import th.co.glr.hr.pricingrequest.UnitBasis;

/**
 * Slice 1, stock lines: the SCHEMA half of the rules (V194) - what the database itself refuses
 * regardless of which service is (or is not) in front of it.
 *
 * <p><b>Every test in this class is a [SCHEMA PIN]: it passes as soon as the V194 scaffold exists,
 * BEFORE any service behaviour is built, by design</b> - the migration is part of the scaffold the
 * task allows. They are still worth having: they are the only thing that catches an implementer who
 * weakens/renames a constraint, and the last two pin the two implementation traps the constraints
 * create for {@code convertInTransitToImport} and {@code startReview}. To mutation-check one, drop
 * the matching constraint/trigger in a scratch DB and expect exactly that test red.
 *
 * <p><b>How "NULL costing link only for a stock line" is modelled (case 17).</b> A CHECK cannot see
 * another table, and a composite FK onto {@code pricing_request_item(id, stock_source)} would make
 * {@code convertInTransitToImport} (which CLEARS {@code stock_source} on the request item) fail
 * whenever a RETURNED decision still references the line. So it is a BEFORE INSERT/UPDATE trigger on
 * {@code pricing_decision_item}: checked when the decision item is written, history left alone (see
 * {@link #convertingAStockLine_afterADecisionItemWasWrittenForIt_isNotBlockedByThatDecisionItem}).
 * Not modelled: the reverse ("a stock line must have NO costing link") - that is enforced by
 * {@code startReview} never creating one, not by the schema.
 */
class StockLineSchemaIntegrationTest extends AbstractStockLineIntegrationTest {

    // ── pricing_request_item: ETA only on an in-transit row ─────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {IN_THAILAND, "IMPORT"})
    void anEtaOnARowThatIsNotInTransit_isRejectedByTheDatabase(String kind) {
        long id = IN_THAILAND.equals(kind)
            ? persistDraft(stockLine("Not transit", IN_THAILAND, 4))
            : persistDraft(importLine("Not transit", catalogProductFactoryA, FACTORY_A, 4));

        assertThatThrownBy(() -> seedEta(id, "Not transit", eta()))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("chk_pricing_request_item_eta_only_in_transit");
        assertThat(itemByModel(id, "Not transit").expectedArrivalDate()).isNull();
    }

    @Test
    void anEtaOnAnInTransitRow_isAccepted() {
        long id = persistDraft(stockLine("Transit", IN_TRANSIT, 4));

        seedEta(id, "Transit", eta());

        assertThat(itemByModel(id, "Transit").expectedArrivalDate()).isEqualTo(eta());
    }

    @Test
    void clearingTheSourceWhileAnEtaIsStillSet_isRejected_soConvertMustClearBothInOneStatement() {
        long id = persistDraft(stockLine("Transit", IN_TRANSIT, 4));
        seedEta(id, "Transit", eta());

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE sales.pricing_request_item SET stock_source = NULL
             WHERE pricing_request_id = :id AND model = 'Transit'
            """, Map.of("id", id)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("chk_pricing_request_item_eta_only_in_transit");

        // the legal way: source and ETA cleared together
        jdbc.update("""
            UPDATE sales.pricing_request_item SET stock_source = NULL, expected_arrival_date = NULL
             WHERE pricing_request_id = :id AND model = 'Transit'
            """, Map.of("id", id));
        assertThat(itemByModel(id, "Transit").stockSource()).isNull();
    }

    @Test
    void anUnknownStockSourceValue_isRejectedByTheDatabase() {
        long id = persistDraft(stockLine("Transit", IN_TRANSIT, 4));

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE sales.pricing_request_item SET stock_source = 'IN_SPACE'
             WHERE pricing_request_id = :id
            """, Map.of("id", id)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("chk_pricing_request_item_stock_source");
    }

    // ── pricing_decision_item: NULL costing link only for a stock line (case 17) ───────────

    @Test
    void aDecisionItemWithANullCostingLink_isRejectedWhenItsRequestItemIsAnImportLine() {
        long id = persistDraft(importLine("Imp", catalogProductFactoryA, FACTORY_A, 10),
            stockLine("Stock TH", IN_THAILAND, 5));
        long decisionId = emptyDecisionFor(id);

        assertThatThrownBy(() -> decisionRepository.insertItems(decisionId, List.of(nullLinkItem(itemId(id, "Imp")))))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("only for a stock line");

        assertThat(decisionItemCount(decisionId)).isZero();
    }

    @Test
    void aDecisionItemWithANullCostingLink_isAcceptedForBothKindsOfStockLine() {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 5), stockLine("Stock TR", IN_TRANSIT, 4));
        long decisionId = emptyDecisionFor(id);

        decisionRepository.insertItems(decisionId,
            List.of(nullLinkItem(itemId(id, "Stock TH")), nullLinkItem(itemId(id, "Stock TR"))));

        assertThat(decisionItemCount(decisionId)).isEqualTo(2L);
    }

    @Test
    void convertingAStockLine_afterADecisionItemWasWrittenForIt_isNotBlockedByThatDecisionItem() {
        // A RETURNED decision keeps its items; import may then convert the in-transit line to
        // import. No FK may make that UPDATE fail, and the old decision item stays editable.
        long id = persistDraft(stockLine("Stock TR", IN_TRANSIT, 4));
        long decisionId = emptyDecisionFor(id);
        decisionRepository.insertItems(decisionId, List.of(nullLinkItem(itemId(id, "Stock TR"))));

        jdbc.update("""
            UPDATE sales.pricing_request_item SET stock_source = NULL, expected_arrival_date = NULL
             WHERE pricing_request_id = :id
            """, Map.of("id", id));
        int noteRows = jdbc.update("""
            UPDATE sales.pricing_decision_item SET decision_note = 'history'
             WHERE pricing_decision_id = :d
            """, Map.of("d", decisionId));

        assertThat(itemByModel(id, "Stock TR").stockSource()).isNull();
        assertThat(noteRows).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private long emptyDecisionFor(long pricingRequestId) {
        long costingId = costingRepository.createComputed(pricingRequestId, null, ceoUserId, BigDecimal.ZERO);
        return decisionRepository.createDraft(pricingRequestId, costingId, new BigDecimal("0.20"), "THB",
            BigDecimal.ONE, "THB", LocalDate.now(), null, null, ceoUserId).decisionId();
    }

    private WriteItem nullLinkItem(long pricingRequestItemId) {
        BigDecimal qty = new BigDecimal("5");
        return new WriteItem(pricingRequestItemId, null, UnitBasis.PER_PIECE, qty, qty, null, null, "THB",
            null, null, null);
    }

    private long decisionItemCount(long decisionId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_decision_item WHERE pricing_decision_id = :d",
            Map.of("d", decisionId), Long.class);
    }
}
