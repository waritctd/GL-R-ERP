package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.CatalogRepository.CatalogFactoryCollection;
import th.co.glr.hr.catalog.CatalogRepository.CatalogThicknessEstimationInputs;
import th.co.glr.hr.catalog.ThicknessEstimator;
import th.co.glr.hr.catalog.ThicknessEstimator.Suggestion;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;

/**
 * {@link PricingRequestThicknessSuggestionService}'s ORCHESTRATION — the rung order and the "only
 * for an item with no resolved thickness" gate — mocking both collaborators, since {@link
 * ThicknessEstimator} already has its own dedicated, pure-function test ({@code
 * ThicknessEstimatorTest}) and {@link CatalogRepository}'s two new queries have their own
 * real-Postgres one ({@code CatalogRepositoryThicknessEstimationIntegrationTest}). This class's
 * only job is proving the GLUE between them is correct: which items get looked up at all, and
 * which rung wins when both COULD apply.
 *
 * <p>Checked by hand to go RED, one test at a time, under each of: removing the {@code
 * resolvedThicknessMm() != null} gate (moves only {@link #anItemWithAResolvedThicknessIsNeverLookedUp});
 * swapping the rung order so box weight runs before siblings (moves only {@link
 * #siblingSuggestionWinsWithoutConsultingBoxWeight}) — and GREEN again once reverted.
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
     * but then discarded would still pass a same-value assertion.
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
        when(catalog.findFactoryAndCollection(777L)).thenReturn(Optional.empty());

        service.attachSuggestions(detailOf(item));

        verify(catalog).findFactoryAndCollection(777L);
    }

    /** A catalog link that no longer resolves an identity (deleted row, say) yields no suggestion and no further catalog calls. */
    @Test
    void aBrokenCatalogLinkIsNeverLookedUpFurther() {
        PricingRequestItemDto item = item(500L, null, null);
        when(catalog.findFactoryAndCollection(500L)).thenReturn(Optional.empty());

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(item));

        assertThat(result.items().get(0).thicknessSuggestion()).isNull();
        verify(catalog, never()).findSiblingThicknessesMm(anyLong(), eq((String) null), anyLong());
        verify(catalog, never()).findThicknessEstimationInputs(anyLong());
    }

    // ── rung order: siblings before box weight ──────────────────────────────────────────────────

    /** Sibling agreement wins outright — the box-weight rung must not even be consulted once rung 3 already yielded a value. */
    @Test
    void siblingSuggestionWinsWithoutConsultingBoxWeight() {
        PricingRequestItemDto item = item(500L, null, null);
        when(catalog.findFactoryAndCollection(500L))
            .thenReturn(Optional.of(new CatalogFactoryCollection(9L, "Padana", "Bianco")));
        when(catalog.findSiblingThicknessesMm(9L, "Bianco", 500L))
            .thenReturn(List.of(new BigDecimal("9"), new BigDecimal("9")));
        when(estimator.fromSiblings(List.of(new BigDecimal("9"), new BigDecimal("9"))))
            .thenReturn(Optional.of(new Suggestion(new BigDecimal("9"), "จากสินค้ารุ่นเดียวกัน 2 รายการ", "HIGH")));

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(item));

        assertThat(result.items().get(0).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9");
        assertThat(result.items().get(0).thicknessSuggestion().confidence()).isEqualTo("HIGH");
        verify(catalog, never()).findThicknessEstimationInputs(anyLong());
    }

    /** When siblings disagree (rung 3 empty), the box-weight rung (4) is tried next. */
    @Test
    void boxWeightIsTriedWhenSiblingsYieldNothing() {
        PricingRequestItemDto item = item(500L, null, null);
        when(catalog.findFactoryAndCollection(500L))
            .thenReturn(Optional.of(new CatalogFactoryCollection(9L, "Padana", "Bianco")));
        when(catalog.findSiblingThicknessesMm(9L, "Bianco", 500L)).thenReturn(List.of());
        when(estimator.fromSiblings(List.of())).thenReturn(Optional.empty());
        when(catalog.findThicknessEstimationInputs(500L))
            .thenReturn(Optional.of(new CatalogThicknessEstimationInputs("Padana", new BigDecimal("1.44"), new BigDecimal("22.5"))));
        when(estimator.fromBoxWeight("Padana", new BigDecimal("22.5"), new BigDecimal("1.44")))
            .thenReturn(Optional.of(new Suggestion(new BigDecimal("9.5"), "ประมาณจากน้ำหนักกล่อง", "HIGH")));

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(item));

        assertThat(result.items().get(0).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9.5");
    }

    /** Both rungs empty (e.g. Equipe, or an unmeasured factory): the item is left with no suggestion, not an error. */
    @Test
    void bothRungsEmptyLeavesNoSuggestion() {
        PricingRequestItemDto item = item(500L, null, null);
        when(catalog.findFactoryAndCollection(500L))
            .thenReturn(Optional.of(new CatalogFactoryCollection(9L, "Equipe", "Stromboli")));
        when(catalog.findSiblingThicknessesMm(9L, "Stromboli", 500L)).thenReturn(List.of());
        when(estimator.fromSiblings(List.of())).thenReturn(Optional.empty());
        when(catalog.findThicknessEstimationInputs(500L))
            .thenReturn(Optional.of(new CatalogThicknessEstimationInputs("Equipe", new BigDecimal("1"), new BigDecimal("22.5"))));
        when(estimator.fromBoxWeight("Equipe", new BigDecimal("22.5"), new BigDecimal("1"))).thenReturn(Optional.empty());

        PricingRequestDetailDto result = service.attachSuggestions(detailOf(item));

        assertThat(result.items().get(0).thicknessSuggestion()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private PricingRequestDetailDto detailOf(PricingRequestItemDto item) {
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
            1,                     // itemCount
            1,                     // revisionNo
            null,                  // parentPricingRequestId
            null,                  // submittedAt
            null,                  // pickedUpAt
            null,                  // cancelledAt
            null,                  // createdAt
            null,                  // updatedAt
            null);                 // orderConfirmedAt
        return new PricingRequestDetailDto(summary, List.of(item), List.of());
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
