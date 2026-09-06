package th.co.glr.hr.pricingrequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.ThicknessDefaultRepository;
import th.co.glr.hr.catalog.ThicknessDefaultRequests.ThicknessDefaultEntry;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.SetItemThicknessRequest;

/**
 * ฝ่ายนำเข้า must supply ความหนา when the catalog resolves none (owner-ruled change, 2026-09).
 *
 * <p><b>Why a separate service, not a new method on {@link PricingRequestService}.</b> That class
 * is constructed directly (not via Spring DI) by ~30 integration test files across the codebase, so
 * widening its constructor to take the two new collaborators this feature needs ({@link
 * CatalogRepository}, {@link ThicknessDefaultRepository}) would force a mechanical but sprawling
 * edit across every one of them for a change none of them are testing. This class instead composes
 * {@link PricingRequestService} (for its already-correct, already-tested viewability/ownership/
 * DRAFT-hiding scoping — see {@link PricingRequestService#get}) alongside the two catalog-side
 * collaborators, so only the handful of test files that actually exercise this endpoint need to
 * know it exists.
 *
 * <p><b>Routing (the whole point of this class).</b> A line's catalog link — {@code
 * catalog_price_id ?? product_id}, the SAME precedence {@code LandedCostCalculator} uses — decides
 * WHERE a hand-entered thickness lands:
 * <ul>
 *   <li>Linked, to a real {@code price_catalog.product_prices} row: upserts the SHARED {@code
 *       price_catalog.collection_thickness_default} for that row's (factory, collection) — reusing
 *       {@link ThicknessDefaultRepository#saveAll} as-is, so filling a gap here also closes it in
 *       the CEO's own bulk thickness-gap editor ({@code ThicknessDefaultController}), and vice
 *       versa. The collection comes from the RESOLVED catalog row via {@link
 *       CatalogRepository#findFactoryAndCollection}, never from {@code
 *       PricingRequestItemDto#catalogCollection} — that column is a submit-time snapshot that can
 *       go stale the moment the catalog changes underneath it.
 *   <li>Not linked at all: writes this line's OWN {@code
 *       sales.pricing_request_item.thickness_mm_override} (V163) instead — there is no shared
 *       catalog row for an unlinked line to help, and {@code LandedCostCalculator#resolveThicknessMm}
 *       now reads this column FIRST, ahead of the catalog chain.
 * </ul>
 * {@code thicknessMm == null} CLEARS whichever of the two a line owns, symmetrically.
 *
 * <p><b>Refuses (409) when SETTING a thickness on a line that ALREADY resolves one FROM THE
 * CATALOG</b> — i.e. {@code price_catalog.v_priceable_product.thickness_mm} (the product's own
 * value, OR an existing collection default — both already COALESCEd by that view) is non-null.
 * There is nothing to fill, and for a linked line, writing anyway would either no-op or silently
 * second-guess a real workbook value with a guess. Deliberately does NOT check {@code
 * thicknessMmOverride} for this refusal — an override is meant to be correctable by re-submitting,
 * unlike a catalog-sourced value, which this endpoint (and the bulk editor) are not a channel for
 * overriding per-deal. <b>The refusal is SET-only</b> — a CLEAR ({@code thicknessMm == null}) is
 * always allowed through, even once the catalog already resolves: the FIRST successful set on a
 * linked line is exactly what makes the catalog resolve, so refusing the clear too would
 * permanently lock the line out of ever being corrected through this endpoint again.
 *
 * <p><b>Authorization: {@code import}/{@code ceo}</b> — an EXPANSION from the pre-existing {@code
 * ceo}-only {@code ThicknessDefaultController}. Import is the role that actually reads
 * factory-response evidence line by line and is the one stopped at ITS OWN stage by an unresolvable
 * thickness (see {@link LandedCostCalculator#isFullyResolvable}); ceo keeps the access it always
 * had, now reachable per-line instead of only through the bulk collection-level editor. Stated here
 * per CLAUDE.md's "permission changes must ship evidence" — pinned against real Postgres by {@code
 * PricingRequestItemThicknessIntegrationTest}.
 */
@Service
public class PricingRequestItemThicknessService {
    private static final Set<String> ITEM_THICKNESS_ROLES = Set.of("import", "ceo");
    /**
     * Statuses in which a line's thickness may be supplied or corrected. Wider than {@code
     * PricingRequestService#FACTORY_ROUTING_STATUSES} on the early end is not needed (a factory
     * quote line only exists once Import has picked the request up), but this set stays open
     * through {@code CEO_REVIEWING} deliberately: V156's uncostable-line safety net means a request
     * created BEFORE this feature existed can still reach the CEO with an unresolved thickness, and
     * the CEO must be able to fix it in place rather than being forced to send the whole request
     * back to Import first. Closed once a selling price has been decided ({@code
     * APPROVED_FOR_QUOTATION} onward) or the request is dead.
     */
    private static final Set<String> EDITABLE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.READY_FOR_CEO_REVIEW,
        PricingRequestStatus.CEO_REVIEWING);

    private final PricingRequestRepository requests;
    private final PricingRequestService pricingRequests;
    private final CatalogRepository catalog;
    private final ThicknessDefaultRepository thicknessDefaults;

    public PricingRequestItemThicknessService(PricingRequestRepository requests,
                                              PricingRequestService pricingRequests,
                                              CatalogRepository catalog,
                                              ThicknessDefaultRepository thicknessDefaults) {
        this.requests = requests;
        this.pricingRequests = pricingRequests;
        this.catalog = catalog;
        this.thicknessDefaults = thicknessDefaults;
    }

    @Transactional
    public PricingRequestDetailDto setItemThickness(long pricingRequestId, long itemId,
                                                     SetItemThicknessRequest request, UserPrincipal actor) {
        requireRole(actor, ITEM_THICKNESS_ROLES);
        // Reuses PricingRequestService's own viewability/ownership/DRAFT-hiding gate — see this
        // class's own Javadoc for why composing it, rather than duplicating its logic, is the
        // point of this class existing separately at all.
        PricingRequestDetailDto detail = pricingRequests.get(pricingRequestId, actor);
        PricingRequestSummaryDto summary = detail.summary();
        if (!EDITABLE_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ระบุความหนาได้เฉพาะคำขอราคาที่อยู่ระหว่างดำเนินการเท่านั้น (สถานะปัจจุบัน: '" + summary.status() + "')");
        }
        PricingRequestItemDto item = detail.items().stream()
            .filter(candidate -> candidate.id() == itemId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการสินค้านี้ในคำขอราคานี้"));

        Long priceId = item.catalogPriceId() != null ? item.catalogPriceId() : item.productId();
        CatalogRepository.CatalogFactoryCollection catalogLink = priceId != null
            ? catalog.findFactoryAndCollection(priceId).orElse(null)
            : null;
        BigDecimal thicknessMm = request.thicknessMm();
        // The refusal is a SET-only concern ("nothing to FILL") — a CLEAR (thicknessMm == null)
        // must always be allowed to proceed, or a linked line could never be corrected through
        // this endpoint again once its FIRST call had already made the catalog resolve: the very
        // write this method just made would permanently lock out any further call to it, since
        // v_priceable_product would then always show a non-null value here. This is not a
        // hypothetical: the FIRST version of this method applied the refusal unconditionally, and
        // PricingRequestItemThicknessIntegrationTest#nullClearsALinkedLinesSharedCollectionDefault
        // caught it (409 on the clear call) before this ever shipped.
        if (thicknessMm != null) {
            // The CURRENT catalog-side resolution — product's own thickness_mm, or an existing
            // collection default, whichever price_catalog.v_priceable_product already COALESCEs.
            // NOT item.resolvedThicknessMm(): that field also folds in thicknessMmOverride (V163's
            // own higher-priority rung), and an existing override must never block a correction
            // here — see this class's own Javadoc for why the refusal is deliberately
            // catalog-only.
            BigDecimal catalogResolvedThicknessMm = priceId != null
                ? catalog.findPricingKeys(Set.of(priceId)).get(priceId).thicknessMm()
                : null;
            if (catalogResolvedThicknessMm != null) {
                throw new ApiException(HttpStatus.CONFLICT,
                    item.displayName() + " มีความหนาจาก Price Catalog อยู่แล้ว (" + catalogResolvedThicknessMm
                        + " มม.) — ไม่ต้องระบุเพิ่ม");
            }
        }

        if (catalogLink != null) {
            thicknessDefaults.saveAll(
                List.of(new ThicknessDefaultEntry(catalogLink.factoryId(), catalogLink.collection(), thicknessMm)),
                actor.id());
        } else {
            requests.updateItemThicknessOverride(itemId, thicknessMm);
        }
        requests.addEvent(pricingRequestId, summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.PRICING_REQUEST_ITEM_THICKNESS_SET, summary.status(), summary.status(),
            (thicknessMm == null ? "ล้างค่าความหนาของ " : "ระบุความหนา " + thicknessMm + " มม. ให้ ") + item.displayName(),
            null);
        return pricingRequests.get(pricingRequestId, actor);
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }
}
