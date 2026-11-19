package th.co.glr.hr.factoryquote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;

/** The item-equivalence decision that authorises a factory-quote carry-forward, unit-tested on its own because
 * the integration suite cannot isolate it. A guard whose removal nothing detects is a guard the next editor
 * will delete. */
class FactoryQuoteCarryForwardTest {

    /** The guard the mutation check exposed as unpinned. An item ADDED to the revision means the child has a line
     * no factory ever quoted; positional matching over a shorter list would build a mapping that looks total and
     * is not. */
    @Test
    void anAddedItem_makesTheListsInequivalent_soNoQuoteIsCarriedForward() {
        List<PricingRequestItemDto> parent = List.of(item(1L, "10"));
        List<PricingRequestItemDto> child = List.of(item(11L, "10"), item(12L, "3"));

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(parent, child)).isNull();
    }

    /** The trap in the other direction, and the reason the comparison uses compareTo rather than equals: scale is
     * part of BigDecimal equality, so new BigDecimal("10").equals(new BigDecimal("10.00")) is false. A quantity
     * re-entered with a different scale is the SAME quantity. */
    @Test
    void aQuantityReEnteredAtADifferentScale_isStillTheSameQuantity() {
        Map<Long, Long> mapping = FactoryQuoteCarryForward.equivalentItemMapping(
            List.of(item(1L, "10")), List.of(item(11L, "10.00")));

        assertThat(mapping).containsExactly(Map.entry(1L, 11L));
    }

    /** The path the shortcut exists for: identical order, mapping returned parent-id -> child-id. */
    @Test
    void identicalLists_mapEveryParentItemOntoItsChild() {
        Map<Long, Long> mapping = FactoryQuoteCarryForward.equivalentItemMapping(
            List.of(item(1L, "10"), item(2L, "3")), List.of(item(11L, "10"), item(12L, "3")));

        assertThat(mapping).containsExactly(Map.entry(1L, 11L), Map.entry(2L, 12L));
    }

private static PricingRequestItemDto itemWithTerms(long id, String currency, String unitBasis) {
        return new PricingRequestItemDto(id, 99L, null, 555L, null, "SCG", "Tile A", "SCG Tile A",
            null, null, "60x60", "Factory X", new BigDecimal("10"), new BigDecimal("10"), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null, 0, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            true, null, null, null, null, null, null, currency, unitBasis);
    }

    private static PricingRequestItemDto item(long id, String qty) {
        return new PricingRequestItemDto(id, 99L, null, 555L, null, "SCG", "Tile A", "SCG Tile A",
            null, null, "60x60", "Factory X", new BigDecimal(qty), new BigDecimal(qty), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null, 0, null, null, null, null,
            null, null, null, null, null, null, null, null);
    }

}
