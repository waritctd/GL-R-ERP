package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.ThicknessEstimator;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * SPEC-N1 (2026-09 review): pins the BATCHING itself, through the real {@link
 * PricingRequestThicknessSuggestionService} against real Postgres — several items sharing a
 * factory/collection resolve correctly from ONE batched pass, not one per item, and an item whose
 * catalog link does not resolve at all stays completely untouched.
 *
 * <p>{@link PricingRequestThicknessSuggestionServiceTest} (Mockito) already pins the call COUNT
 * and the rung order in isolation, and {@code CatalogRepositoryThicknessEstimationIntegrationTest}
 * already pins the single-key SQL against real Postgres. This class is the one place both are
 * exercised TOGETHER through the real repository, so a wiring mistake between the batched maps and
 * the per-item decision logic (a key built the wrong way, an id read out of the wrong map) would
 * surface here even if it happened to look right under mocks.
 */
class PricingRequestThicknessSuggestionServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    private PricingRequestThicknessSuggestionService service() {
        return new PricingRequestThicknessSuggestionService(new CatalogRepository(jdbc), new ThicknessEstimator());
    }

    private void setCollection(long priceId, String collection) {
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("c", collection));
    }

    private void setThickness(long priceId, BigDecimal thicknessMm) {
        jdbc.update("UPDATE price_catalog.product_prices SET thickness_mm = :t WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("t", thicknessMm));
    }

    /**
     * TWO pricing-request items whose catalog rows share a (factory, collection) with each other
     * AND with two real sibling rows carrying an AGREEING thickness — both items must independently
     * resolve the SAME sibling-agreement suggestion from data fetched in one batched pass. A THIRD
     * item's {@code catalogPriceId} points at an id that resolves no catalog row at all (mirroring
     * {@code CatalogRepositoryFindPricingKeysIntegrationTest}'s own absent-id fixture) and must come
     * back with NO suggestion — proving the batching does not manufacture one for an unresolved
     * link, and does not let an unrelated item's presence in the same request corrupt it.
     *
     * <p>Each subject row's OWN {@code thickness_mm} is explicitly cleared to {@code NULL} — the
     * load-bearing setup step. {@code insertCatalogProduct}'s short overload defaults it to 10,
     * and left at that default a subject row would itself satisfy the sibling query's {@code
     * thickness_mm IS NOT NULL} filter and be read back as a (wrong) THIRD sibling for the OTHER
     * subject item, which shares its batched group but must exclude only ITS OWN price id.
     */
    @Test
    void severalItemsSharingAFactoryAndCollectionResolveInOnePassAndAnUnlinkedItemStaysUntouched() {
        long subjectA = insertCatalogProduct("N1 Batch Factory", "IT", "N1-SUBJECT-A",
            new BigDecimal("40"), "EUR", "per_sqm");
        long subjectB = insertCatalogProduct("N1 Batch Factory", "IT", "N1-SUBJECT-B",
            new BigDecimal("40"), "EUR", "per_sqm");
        long sibling1 = insertCatalogProduct("N1 Batch Factory", "IT", "N1-SIB-1",
            new BigDecimal("40"), "EUR", "per_sqm");
        long sibling2 = insertCatalogProduct("N1 Batch Factory", "IT", "N1-SIB-2",
            new BigDecimal("40"), "EUR", "per_sqm");
        for (long id : List.of(subjectA, subjectB, sibling1, sibling2)) {
            setCollection(id, "N1 Bianco");
        }
        setThickness(subjectA, null);
        setThickness(subjectB, null);
        setThickness(sibling1, new BigDecimal("9"));
        setThickness(sibling2, new BigDecimal("9"));

        long absentPriceId = -918_273_645L;
        PricingRequestItemDto itemA = item(subjectA, null, null);
        PricingRequestItemDto itemB = item(subjectB, null, null);
        PricingRequestItemDto itemUnlinked = item(absentPriceId, null, null);

        PricingRequestDetailDto result = service().attachSuggestions(detailOf(itemA, itemB, itemUnlinked));

        assertThat(result.items().get(0).thicknessSuggestion())
            .as("subjectA resolves the sibling agreement")
            .isNotNull();
        assertThat(result.items().get(0).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9");
        assertThat(result.items().get(0).thicknessSuggestion().basis()).contains("2 รายการ");
        assertThat(result.items().get(0).thicknessSuggestion().confidence()).isEqualTo("HIGH");

        assertThat(result.items().get(1).thicknessSuggestion())
            .as("subjectB shares the SAME batched group and resolves independently, excluding only itself")
            .isNotNull();
        assertThat(result.items().get(1).thicknessSuggestion().thicknessMm()).isEqualByComparingTo("9");
        assertThat(result.items().get(1).thicknessSuggestion().basis()).contains("2 รายการ");

        assertThat(result.items().get(2).thicknessSuggestion())
            .as("an item whose catalog link resolves nothing stays untouched")
            .isNull();
    }

    // ── helpers (mirroring PricingRequestThicknessSuggestionServiceTest's own fixture shape) ─────

    private PricingRequestDetailDto detailOf(PricingRequestItemDto... items) {
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
