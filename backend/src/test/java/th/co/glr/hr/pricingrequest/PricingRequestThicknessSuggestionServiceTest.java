package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.CatalogRepository.CatalogFactoryCollection;
import th.co.glr.hr.catalog.CatalogRepository.CatalogThicknessEstimationInputs;
import th.co.glr.hr.catalog.CatalogRepository.FactoryCollectionKey;
import th.co.glr.hr.catalog.CatalogRepository.SiblingThicknessRow;
import th.co.glr.hr.catalog.ThicknessEstimator;
import th.co.glr.hr.catalog.ThicknessEstimator.Suggestion;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;

/**
 * {@link PricingRequestThicknessSuggestionService}'s ORCHESTRATION — the rung order and the "only
 * for an item with no resolved thickness" gate — mocking both collaborators, since {@link
 * ThicknessEstimator} already has its own dedicated, pure-function test ({@code
 * ThicknessEstimatorTest}) and {@link CatalogRepository}'s batched queries have their own
 * real-Postgres one ({@code CatalogRepositoryThicknessEstimationIntegrationTest}). This class's
 * job is proving the GLUE between them is correct: which items get looked up at all, which rung
 * wins when both COULD apply, and — SPEC-N1 (2026-09 review) — that the lookups are BATCHED rather
 * than issued once per item.
 *
 * <p><b>Checked by hand to go RED, one test at a time</b>, under each of: removing the {@code
 * resolvedThicknessMm() != null} gate (moves only {@link #anItemWithAResolvedThicknessIsNeverLookedUp});
 * swapping the rung order so box weight runs before siblings (moves only {@link
 * #siblingSuggestionWinsWithoutConsultingBoxWeight}) — and GREEN again once reverted.
 *
 * <p><b>The two batching-count tests below were independently checked against the OLD per-item
 * {@code attachSuggestions}</b> (looping and calling the single-id {@code CatalogRepository}
 * overloads once per eligible item) — both went RED under it, with {@code times(1)} failing as
 * "wanted 1, but was 2" for a two-eligible-item request. This is the proof the spec asks for: a
 * test that only passes because the queries are genuinely batched, not one that would pass under
 * either implementation.
 */
class PricingRequestThicknessSuggestionServiceTest {
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ThicknessEstimator estimator = mock(ThicknessEstimator.class);
    private final PricingRequestThicknessSuggestionService service =
        new PricingRequestThicknessSuggestionService(catalog, estimator);

    // ── the gate: only an item with NO resolved thickness is ever looked up ────────────────────

    /**
     * An item that already resolves a thickness (catalog or override) is KNOWN, not a candidate for
     * an ESTIMATE — SPEC-PREFILL.md's own governing distinction. Verifies NO interaction with
     * either collaborator, not merely that the returned item is unchanged: a suggestion computed
     * but then discarded would still pass a same-value assertion. Also proves the batching itself
     * does not call through with an empty argument when nothing is eligible — a call with an empty
     * collection would still count as an interaction and fail this exact assertion.
     */
    @Test
    void anItemWithAResolvedThicknessIsNeverLookedUp() {
        PricingRequestItemDto resolved = item(500L, null, new BigDecimal("10"));

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(resolved));

        assertThat(result.items().get(0).thicknessSuggestion()).isNull();
        verifyNoInteractions(catalog);
        verifyNoInteractions(estimator);
    }

    /** A line with no catalog link at all (a free-text line, owner ruling 2026-08-11) has no identity to look siblings up from and no box-weight row to read. */
    @Test
    void anItemWithNoCatalogLinkIsNeverLookedUp() {
        PricingRequestItemDto unlinked = item(null, null, null);

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(unlinked));

        assertThat(result.items().get(0).thicknessSuggestion()).isNull();
        verifyNoInteractions(catalog);
        verifyNoInteractions(estimator);
    }

    /** productId is the fallback catalog link when catalogPriceId is absent — same precedence LandedCostCalculator/PricingRequestItemThicknessService both use. */
    @Test
    void productIdIsUsedAsTheCatalogLinkWhenCatalogPriceIdIsAbsent() {
        PricingRequestItemDto item = item(null, 777L, null);
        when(catalog.findFactoryAndCollection(Set.of(777L))).thenReturn(Map.of());

        service.attachSuggestions(detailOf(item));

        verify(catalog).findFactoryAndCollection(Set.of(777L));
    }

    /** A catalog link that no longer resolves an identity (deleted row, say) yields no suggestion and no further catalog calls. */
    @Test
    void aBrokenCatalogLinkIsNeverLookedUpFurther() {
        PricingRequestItemDto item = item(500L, null, null);
        when(catalog.findFactoryAndCollection(Set.of(500L))).thenReturn(Map.of());

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(item));

        assertThat(result.items().get(0).thicknessSuggestion()).isNull();
        verify(catalog, never()).findSiblingThicknessesMm(anyCollection());
        verify(catalog, never()).findThicknessEstimationInputs(anyCollection());
    }

    // ── rung order: siblings before box weight ──────────────────────────────────────────────────

    /** Sibling agreement wins outright — the box-weight rung must not even be consulted once rung 3 already yielded a value. */
    @Test
    void siblingSuggestionWinsWithoutConsultingBoxWeight() {
        PricingRequestItemDto item = item(500L, null, null);
        CatalogFactoryCollection link = new CatalogFactoryCollection(9L, "Padana", "Bianco");
        when(catalog.findFactoryAndCollection(Set.of(500L))).thenReturn(Map.of(500L, link));
        FactoryCollectionKey key = new FactoryCollectionKey(9L, "Bianco");
        when(catalog.findSiblingThicknessesMm(Set.of(key))).thenReturn(Map.of(key, List.of(
            new SiblingThicknessRow(601L, new BigDecimal("9")),
            new SiblingThicknessRow(602L, new BigDecimal("9")))));
        when(estimator.fromSiblings(List.of(new BigDecimal("9"), new BigDecimal("9"))))
            .thenReturn(Optional.of(new Suggestion(new BigDecimal("9"), "จากสินค้ารุ่นเดียวกัน 2 รายการ", "HIGH")));

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(item));

        assertThat(result.items().get(0).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9");
        assertThat(result.items().get(0).thicknessSuggestion().confidence()).isEqualTo("HIGH");
        verify(catalog, never()).findThicknessEstimationInputs(anyCollection());
    }

    /** When siblings disagree (rung 3 empty), the box-weight rung (4) is tried next. */
    @Test
    void boxWeightIsTriedWhenSiblingsYieldNothing() {
        PricingRequestItemDto item = item(500L, null, null);
        CatalogFactoryCollection link = new CatalogFactoryCollection(9L, "Padana", "Bianco");
        when(catalog.findFactoryAndCollection(Set.of(500L))).thenReturn(Map.of(500L, link));
        FactoryCollectionKey key = new FactoryCollectionKey(9L, "Bianco");
        when(catalog.findSiblingThicknessesMm(Set.of(key))).thenReturn(Map.of());
        when(estimator.fromSiblings(List.of())).thenReturn(Optional.empty());
        when(catalog.findThicknessEstimationInputs(Set.of(500L))).thenReturn(Map.of(500L,
            new CatalogThicknessEstimationInputs("Padana", new BigDecimal("1.44"), new BigDecimal("22.5"))));
        when(estimator.fromBoxWeight("Padana", new BigDecimal("22.5"), new BigDecimal("1.44")))
            .thenReturn(Optional.of(new Suggestion(new BigDecimal("9.5"), "ประมาณจากน้ำหนักกล่อง", "HIGH")));

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(item));

        assertThat(result.items().get(0).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9.5");
    }

    /** Both rungs empty (e.g. Equipe, or an unmeasured factory): the item is left with no suggestion, not an error. */
    @Test
    void bothRungsEmptyLeavesNoSuggestion() {
        PricingRequestItemDto item = item(500L, null, null);
        CatalogFactoryCollection link = new CatalogFactoryCollection(9L, "Equipe", "Stromboli");
        when(catalog.findFactoryAndCollection(Set.of(500L))).thenReturn(Map.of(500L, link));
        FactoryCollectionKey key = new FactoryCollectionKey(9L, "Stromboli");
        when(catalog.findSiblingThicknessesMm(Set.of(key))).thenReturn(Map.of());
        when(estimator.fromSiblings(List.of())).thenReturn(Optional.empty());
        when(catalog.findThicknessEstimationInputs(Set.of(500L))).thenReturn(Map.of(500L,
            new CatalogThicknessEstimationInputs("Equipe", new BigDecimal("1"), new BigDecimal("22.5"))));
        when(estimator.fromBoxWeight("Equipe", new BigDecimal("22.5"), new BigDecimal("1"))).thenReturn(Optional.empty());

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(item));

        assertThat(result.items().get(0).thicknessSuggestion()).isNull();
    }

    // ── SPEC-N1: the batching itself — a constant number of calls, not one per item ─────────────

    /**
     * TWO eligible items sharing the SAME (factory, collection) — plus an already-resolved item and
     * an unlinked one, both of which must add NOTHING to the batch. Proves both the call COUNT
     * (one round trip serves both eligible items, not one each) and that a SHARED sibling group
     * resolves the SAME correct suggestion for each of them independently.
     */
    @Test
    void multipleItemsSharingAFactoryAndCollectionResolveFromOneBatchedRoundTrip() {
        PricingRequestItemDto shared1 = item(500L, null, null);
        PricingRequestItemDto shared2 = item(600L, null, null);
        PricingRequestItemDto alreadyResolved = item(700L, null, new BigDecimal("12"));
        PricingRequestItemDto unlinked = item(null, null, null);

        CatalogFactoryCollection link = new CatalogFactoryCollection(9L, "Padana", "Bianco");
        when(catalog.findFactoryAndCollection(Set.of(500L, 600L))).thenReturn(Map.of(500L, link, 600L, link));
        FactoryCollectionKey key = new FactoryCollectionKey(9L, "Bianco");
        List<SiblingThicknessRow> sharedGroup = List.of(
            new SiblingThicknessRow(901L, new BigDecimal("9")),
            new SiblingThicknessRow(902L, new BigDecimal("9")));
        when(catalog.findSiblingThicknessesMm(Set.of(key))).thenReturn(Map.of(key, sharedGroup));
        when(estimator.fromSiblings(List.of(new BigDecimal("9"), new BigDecimal("9"))))
            .thenReturn(Optional.of(new Suggestion(new BigDecimal("9"), "จากสินค้ารุ่นเดียวกัน 2 รายการ", "HIGH")));

        PricingRequestDetailDto result = service.attachSuggestions(
            detailOfMany(shared1, shared2, alreadyResolved, unlinked));

        assertThat(result.items().get(0).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9");
        assertThat(result.items().get(1).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9");
        assertThat(result.items().get(2).thicknessSuggestion()).isNull();
        assertThat(result.items().get(3).thicknessSuggestion()).isNull();

        // The N+1 proof: exactly ONE call per batched method for a FOUR-item request carrying TWO
        // eligible items — the old per-item code called findFactoryAndCollection and
        // findSiblingThicknessesMm once PER ELIGIBLE ITEM (twice here), so times(1) fails under it.
        verify(catalog, times(1)).findFactoryAndCollection(anyCollection());
        verify(catalog, times(1)).findSiblingThicknessesMm(anyCollection());
        verify(catalog, never()).findThicknessEstimationInputs(anyCollection());
    }

    /**
     * TWO eligible items at DIFFERENT (factory, collection) pairs, both falling through to the
     * box-weight rung — proves {@code findThicknessEstimationInputs} batches across items that do
     * NOT share a catalog identity too, and that each independently reads its OWN factory's inputs
     * back out of the shared map.
     */
    @Test
    void multipleItemsFallingThroughToBoxWeightShareOneBatchedRoundTrip() {
        PricingRequestItemDto itemA = item(500L, null, null);
        PricingRequestItemDto itemB = item(600L, null, null);

        CatalogFactoryCollection linkA = new CatalogFactoryCollection(9L, "Padana", "Bianco");
        CatalogFactoryCollection linkB = new CatalogFactoryCollection(11L, "LEA", "Rosso");
        when(catalog.findFactoryAndCollection(Set.of(500L, 600L))).thenReturn(Map.of(500L, linkA, 600L, linkB));
        when(catalog.findSiblingThicknessesMm(anyCollection())).thenReturn(Map.of());
        when(estimator.fromSiblings(List.of())).thenReturn(Optional.empty());
        when(catalog.findThicknessEstimationInputs(Set.of(500L, 600L))).thenReturn(Map.of(
            500L, new CatalogThicknessEstimationInputs("Padana", new BigDecimal("1.44"), new BigDecimal("22.5")),
            600L, new CatalogThicknessEstimationInputs("LEA", new BigDecimal("1.2"), new BigDecimal("20"))));
        when(estimator.fromBoxWeight("Padana", new BigDecimal("22.5"), new BigDecimal("1.44")))
            .thenReturn(Optional.of(new Suggestion(new BigDecimal("9.5"), "basisA", "HIGH")));
        when(estimator.fromBoxWeight("LEA", new BigDecimal("20"), new BigDecimal("1.2")))
            .thenReturn(Optional.of(new Suggestion(new BigDecimal("7"), "basisB", "MEDIUM")));

        PricingRequestDetailDto result = service.attachSuggestions(detailOfMany(itemA, itemB));

        assertThat(result.items().get(0).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9.5");
        assertThat(result.items().get(1).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("7");
        verify(catalog, times(1)).findFactoryAndCollection(anyCollection());
        verify(catalog, times(1)).findThicknessEstimationInputs(anyCollection());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private PricingRequestDetailDto detailOf(PricingRequestItemDto item) {
        return detailOfMany(item);
    }

    private PricingRequestDetailDto detailOfMany(PricingRequestItemDto... items) {
        PricingRequestSummaryDto summary = new PricingRequestSummaryDto(
            1L,                    // id
            "PCR-1",               // requestCode
            1L,                    // ticketId
            "PR-1",                // ticketCode
            null,                  // projectName
            null,                  // customerName
            1L,                    // ticketCreatedById
            null,                  // recipientType
            null,                  // recipientContactId
            null,                  // recipientLabel
            "IMPORT_REVIEWING",    // status
            1L,                    // requestedById
            null,                  // requestedByName
            null,                  // assignedImportId
            null,                  // assignedImportName
            null,                  // requiredDate
            null,                  // customerTargetPrice
            null,                  // targetCurrency
            null,                  // note
            items.length,          // itemCount
            1,                     // revisionNo
            null,                  // parentPricingRequestId
            null,                  // submittedAt
            null,                  // pickedUpAt
            null,                  // cancelledAt
            null,                  // createdAt
            null,                  // updatedAt
            null);                 // orderConfirmedAt
        return new PricingRequestDetailDto(summary, List.of(items), List.of());
    }

    private PricingRequestItemDto item(Long catalogPriceId, Long productId, BigDecimal resolvedThicknessMm) {
        return new PricingRequestItemDto(
            1L,                    // id
            1L,                    // pricingRequestId
            null,                  // sourceTicketItemId
            productId,             // productId
            null,                  // variantId
            null,                  // brand
            null,                  // model
            null,                  // productDescription
            null,                  // color
            null,                  // texture
            null,                  // size
            null,                  // factory
            new BigDecimal("1"),   // requestedQty
            null,                  // requestedQtySqm
            "PIECE",               // requestedUnit
            "PER_PIECE",           // requestedUnitBasis
            "CONFIRMED",           // quantityType
            null,                  // targetDeliveryDate
            null,                  // deliveryLocation
            null,                  // specialRequirement
            0,                     // sortOrder
            null,                  // priceListVersionId
            catalogPriceId,        // catalogPriceId
            null,                  // catalogBasePrice
            null,                  // catalogCurrency
            null,                  // catalogEffectiveDate
            null,                  // resolvedFactoryId
            null,                  // resolvedFactoryName
            null,                  // catalogProductCode
            null,                  // catalogBrand
            null,                  // catalogCollection
            null,                  // catalogModel
            null,                  // productTypeOverride
            resolvedThicknessMm,   // resolvedThicknessMm
            false,                 // thicknessIsDefault
            null,                  // thicknessMmOverride
            null,                  // catalogSqmPerPiece
            null,                  // catalogProductName
            null,                  // catalogSizeRaw
            null,                  // catalogSqmPerBox
            null,                  // catalogPcsPerBox
            null,                  // catalogSqmPerLinearM
            null);                 // thicknessSuggestion
    }
}
