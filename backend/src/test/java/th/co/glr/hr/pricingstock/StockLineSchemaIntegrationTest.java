package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

/** Slice 1, stock lines: the SCHEMA half of the rules (V194) - what the database itself refuses regardless of
 * which service is (or is not) in front of it. How "NULL costing link only for a stock line" is modelled (case
 * 17). */
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
}
