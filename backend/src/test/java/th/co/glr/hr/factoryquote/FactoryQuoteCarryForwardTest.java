package th.co.glr.hr.factoryquote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;

/**
 * The item-equivalence decision that authorises a factory-quote carry-forward, unit-tested on its
 * own because the integration suite cannot isolate it.
 *
 * <p><strong>Why this file exists, stated plainly:</strong> a mutation check on
 * {@code ReissueThroughCeoChainIntegrationTest} found that removing
 * {@code equivalentItemMapping}'s size guard left the whole suite GREEN — not because the guard is
 * pointless, but because the second, fail-closed gate ({@code isFullyResolvable} on the child, plus
 * the compensating delete) happens to catch the same case downstream. A guard whose removal nothing
 * detects is a guard the next editor will delete. These tests pin the decision directly, so the two
 * gates are independently verified rather than one silently standing in for the other.
 *
 * <p>{@code equivalentItemMapping} is {@code static} and package-private precisely so it can be
 * reached like this: the decision is pure, and forcing it through a database to be tested would
 * make it slower to verify and no better verified.
 */
class FactoryQuoteCarryForwardTest {

    /**
     * The guard the mutation check exposed as unpinned. An item ADDED to the revision means the
     * child has a line no factory ever quoted; positional matching over a shorter list would build
     * a mapping that looks total and is not.
     */
    @Test
    void anAddedItem_makesTheListsInequivalent_soNoQuoteIsCarriedForward() {
        List<PricingRequestItemDto> parent = List.of(item(1L, "10"));
        List<PricingRequestItemDto> child = List.of(item(11L, "10"), item(12L, "3"));

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(parent, child)).isNull();
    }

    /** The mirror case: an item REMOVED. Same guard, other direction. */
    @Test
    void aRemovedItem_makesTheListsInequivalent_soNoQuoteIsCarriedForward() {
        List<PricingRequestItemDto> parent = List.of(item(1L, "10"), item(2L, "3"));
        List<PricingRequestItemDto> child = List.of(item(11L, "10"));

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(parent, child)).isNull();
    }

    /**
     * The rule this whole class of change exists to protect. Landed cost per unit is genuinely
     * quantity-dependent — freight and clearance are quantity-banded, and the factory quote carries
     * a {@code minimumOrderQuantity} a reduced order can fall below — so a carried-forward price
     * across a quantity change is void, not merely stale.
     */
    @Test
    void aChangedQuantity_makesTheListsInequivalent_evenWhenEveryOtherFieldMatches() {
        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(
            List.of(item(1L, "10")), List.of(item(11L, "25")))).isNull();
    }

    /**
     * The trap in the other direction, and the reason the comparison uses {@code compareTo} rather
     * than {@code equals}: scale is part of {@code BigDecimal} equality, so
     * {@code new BigDecimal("10").equals(new BigDecimal("10.00"))} is {@code false}. A quantity
     * re-entered with a different scale is the SAME quantity. Using {@code equals} here would
     * silently disable the shortcut for a large share of real input — a quiet performance bug
     * rather than a correctness one, which is exactly the kind that survives review.
     */
    @Test
    void aQuantityReEnteredAtADifferentScale_isStillTheSameQuantity() {
        Map<Long, Long> mapping = FactoryQuoteCarryForward.equivalentItemMapping(
            List.of(item(1L, "10")), List.of(item(11L, "10.00")));

        assertThat(mapping).containsExactly(Map.entry(1L, 11L));
    }

    /** A different product at the same quantity is not the same order. */
    @Test
    void aChangedProduct_makesTheListsInequivalent() {
        PricingRequestItemDto parent = item(1L, "10");
        PricingRequestItemDto child = new PricingRequestItemDto(11L, 99L, null, 777L, null, "SCG",
            "Tile B", "SCG Tile B", null, null, "60x60", "Factory X", new BigDecimal("10"),
            new BigDecimal("10"), "piece", UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null,
            0, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(List.of(parent), List.of(child))).isNull();
    }

    /**
     * An empty parent has no quotes to carry, so it can never authorise a shortcut. Asserted
     * because {@code size() == size()} alone would call two empty lists "equivalent" and hand back
     * an empty mapping — which the caller would read as success.
     */
    @Test
    void twoEmptyLists_areNotEquivalent_soAnEmptyMappingCannotBeReadAsSuccess() {
        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(List.of(), List.of())).isNull();
    }

    /** The path the shortcut exists for: identical order, mapping returned parent-id -> child-id. */
    @Test
    void identicalLists_mapEveryParentItemOntoItsChild() {
        Map<Long, Long> mapping = FactoryQuoteCarryForward.equivalentItemMapping(
            List.of(item(1L, "10"), item(2L, "3")), List.of(item(11L, "10"), item(12L, "3")));

        assertThat(mapping).containsExactly(Map.entry(1L, 11L), Map.entry(2L, 12L));
    }

    private static PricingRequestItemDto item(long id, String qty) {
        return new PricingRequestItemDto(id, 99L, null, 555L, null, "SCG", "Tile A", "SCG Tile A",
            null, null, "60x60", "Factory X", new BigDecimal(qty), new BigDecimal(qty), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null, 0, null, null, null, null,
            null, null, null, null, null, null, null, null, null);
    }
}
