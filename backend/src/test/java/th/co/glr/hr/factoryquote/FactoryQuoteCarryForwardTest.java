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
            0, null, null, null, null, null, null, null, null, null, null, null, null);

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

    /**
     * V185 direct-deal-form parity (Opus review finding #4, 2026-09-18): thicknessMm now
     * participates in the equivalence check. Before this fix, a customer-change revision that
     * changed ONLY thickness (every other field, including the requested quantity, identical)
     * would be treated as "the same order" and carry forward the parent's factory quote — priced
     * for a physically different product.
     */
    @Test
    void aChangedThickness_makesTheListsInequivalent_evenWhenEveryOtherFieldMatches() {
        PricingRequestItemDto parent = itemWithThickness(1L, "10", new BigDecimal("8"));
        PricingRequestItemDto child = itemWithThickness(11L, "10", new BigDecimal("10"));

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(List.of(parent), List.of(child))).isNull();
    }

    /** productCode joined the same comparison, for the identical reason. */
    @Test
    void aChangedProductCode_makesTheListsInequivalent() {
        PricingRequestItemDto parent = itemWithProductCode(1L, "10", "PC-OLD");
        PricingRequestItemDto child = itemWithProductCode(11L, "10", "PC-NEW");

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(List.of(parent), List.of(child))).isNull();
    }

    /** originCountry joined the same comparison, for the identical reason. */
    @Test
    void aChangedOriginCountry_makesTheListsInequivalent() {
        PricingRequestItemDto parent = itemWithOriginCountry(1L, "10", "TH");
        PricingRequestItemDto child = itemWithOriginCountry(11L, "10", "CN");

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(List.of(parent), List.of(child))).isNull();
    }

    /** Two legacy (pre-V185) rows both carry a null thicknessMm — null-safe equality must still
     * treat that as "unchanged", not disqualify every pre-V185 revision from the shortcut. */
    @Test
    void twoItemsWithNullThickness_areStillEquivalent() {
        PricingRequestItemDto parent = itemWithThickness(1L, "10", null);
        PricingRequestItemDto child = itemWithThickness(11L, "10", null);

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(List.of(parent), List.of(child)))
            .containsExactly(Map.entry(1L, 11L));
    }

    /**
     * GLA-125 (second review pass, finding N3): originCountryOther (the typed name under the
     * "อื่นๆ" origin_country sentinel) now participates too. Both items here share the SAME
     * origin_country ("อื่นๆ") — only the typed country changes — so this pins the field that
     * would otherwise have let two genuinely different products (a factory quoted for Vietnam vs.
     * one quoted for Cambodia, say) carry forward as if they were the same order.
     */
    @Test
    void aChangedOriginCountryOther_makesTheListsInequivalent_evenWithTheSameOriginCountrySentinel() {
        PricingRequestItemDto parent = itemWithOriginCountryOther(1L, "10", "เวียดนาม");
        PricingRequestItemDto child = itemWithOriginCountryOther(11L, "10", "กัมพูชา");

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(List.of(parent), List.of(child))).isNull();
    }

    /** Two legacy (pre-GLA-125) rows both carry a null originCountryOther — null-safe equality
     * must still treat that as "unchanged". */
    @Test
    void twoItemsWithNullOriginCountryOther_areStillEquivalent() {
        PricingRequestItemDto parent = itemWithOriginCountryOther(1L, "10", null);
        PricingRequestItemDto child = itemWithOriginCountryOther(11L, "10", null);

        assertThat(FactoryQuoteCarryForward.equivalentItemMapping(List.of(parent), List.of(child)))
            .containsExactly(Map.entry(1L, 11L));
    }

    private static PricingRequestItemDto item(long id, String qty) {
        return new PricingRequestItemDto(id, 99L, null, 555L, null, "SCG", "Tile A", "SCG Tile A",
            null, null, "60x60", "Factory X", new BigDecimal(qty), new BigDecimal(qty), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null, 0, null, null, null, null,
            null, null, null, null, null, null, null, null);
    }

    /** Same base fixture as {@link #item}, but reaching the CANONICAL (V185, 48-arg) constructor
     * directly so a V185 tile field can be set — the 33-arg constructor {@code item()} uses
     * defaults every one of them to null/true. */
    private static PricingRequestItemDto itemWithThickness(long id, String qty, BigDecimal thicknessMm) {
        return new PricingRequestItemDto(id, 99L, null, 555L, null, "SCG", "Tile A", "SCG Tile A",
            null, null, "60x60", "Factory X", new BigDecimal(qty), new BigDecimal(qty), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null, 0, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, thicknessMm, null, null, null, null, null, null, null, null, null, null, null,
            true, null, null, null);
    }

    private static PricingRequestItemDto itemWithProductCode(long id, String qty, String productCode) {
        return new PricingRequestItemDto(id, 99L, null, 555L, null, "SCG", "Tile A", "SCG Tile A",
            null, null, "60x60", "Factory X", new BigDecimal(qty), new BigDecimal(qty), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null, 0, null, null, null, null,
            null, null, null, null, null, null, null, null,
            productCode, null, null, null, null, null, null, null, null, null, null, null, null,
            true, null, null, null);
    }

    private static PricingRequestItemDto itemWithOriginCountry(long id, String qty, String originCountry) {
        return new PricingRequestItemDto(id, 99L, null, 555L, null, "SCG", "Tile A", "SCG Tile A",
            null, null, "60x60", "Factory X", new BigDecimal(qty), new BigDecimal(qty), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null, 0, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            true, originCountry, null, null);
    }

    /** Same base fixture, reaching the FULL canonical (49-arg, originCountryOther appended)
     * constructor directly so that trailing field can be set — every other helper above uses the
     * 48-arg (pre-GLA-125) constructor, which defaults it to null. */
    private static PricingRequestItemDto itemWithOriginCountryOther(long id, String qty, String originCountryOther) {
        return new PricingRequestItemDto(id, 99L, null, 555L, null, "SCG", "Tile A", "SCG Tile A",
            null, null, "60x60", "Factory X", new BigDecimal(qty), new BigDecimal(qty), "piece",
            UnitBasis.PER_PIECE, QuantityType.CONFIRMED, null, null, null, 0, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            true, "อื่นๆ", null, null, originCountryOther);
    }
}
