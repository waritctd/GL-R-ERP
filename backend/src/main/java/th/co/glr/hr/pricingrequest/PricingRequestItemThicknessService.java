package th.co.glr.hr.pricingrequest;

import java.math.BigDecimal;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.SetItemThicknessRequest;

/**
 * Sales must supply ความหนา when the catalog resolves none — moved here from ฝ่ายนำเข้า by an
 * owner-ruled SCOPE CHANGE, 2026-09-06 ("sales need to be forced to fill in the thickness, not
 * import"). This class used to authorize {@code import}/{@code ceo}; {@code import} is now
 * refused outright — the entire point of the change, pinned wrong-way-round by {@code
 * PricingRequestItemThicknessIntegrationTest#importIsRefused_theEntirePointOfThisChange}.
 *
 * <p><b>Why a separate service, not a new method on {@link PricingRequestService}.</b> That class
 * is constructed directly (not via Spring DI) by ~30 integration test files across the codebase, so
 * widening its constructor to take the extra collaborator this feature needs ({@link
 * CatalogRepository}) would force a mechanical but sprawling edit across every one of them for a
 * change none of them are testing. This class instead composes {@link PricingRequestService} (for
 * its already-correct, already-tested viewability/ownership/DRAFT-hiding scoping — see {@link
 * PricingRequestService#get}) alongside the catalog-side collaborator, so only the handful of test
 * files that actually exercise this endpoint need to know it exists.
 *
 * <p><b>Storage — routing DROPPED, 2026-09-06.</b> A hand-entered thickness used to route to one of
 * two stores depending on whether the line had a catalog link: a linked line's value upserted the
 * SHARED {@code price_catalog.collection_thickness_default} (via {@code ThicknessDefaultRepository}
 * — that class itself is untouched by this change; only this service's use of it is gone), an
 * unlinked line's went into THIS line's own {@code sales.pricing_request_item.thickness_mm_override}
 * (V164). That routing is gone: EVERY call now writes {@code thickness_mm_override} and ONLY that
 * column — this deal line, never the shared collection default. A rep's guess (or the CEO's
 * fallback guess) must not silently become every future deal's default; the shared default may
 * still only be set through {@code ThicknessDefaultController} (the CEO's own bulk gap editor, a
 * separate surface this change does not touch). {@code thicknessMm == null} clears the override,
 * symmetrically.
 *
 * <p><b>Refuses (409) when SETTING a thickness on a line that ALREADY resolves one FROM THE
 * CATALOG</b> — i.e. {@code price_catalog.v_priceable_product.thickness_mm} (the product's own
 * value, OR an existing collection default — both already COALESCEd by that view) is non-null.
 * There is nothing to fill, and writing anyway would either no-op or silently second-guess a real
 * workbook value with a guess. Deliberately does NOT check {@code thicknessMmOverride} for this
 * refusal — an override is meant to be correctable by calling this endpoint again, unlike a
 * catalog-sourced value, which this endpoint (and the bulk editor) are not a channel for
 * overriding per-deal. <b>The refusal is SET-only</b> — a CLEAR ({@code thicknessMm == null}) is
 * always allowed through, even once the catalog already resolves: the FIRST successful set on a
 * (now-hypothetical, since routing dropped) linked line used to be exactly what made the catalog
 * resolve, and refusing the clear too would have permanently locked such a line out of ever being
 * corrected through this endpoint again — see {@code
 * PricingRequestItemThicknessIntegrationTest#nullClearsALinkedLinesOwnOverrideToo} for why this
 * still matters even though the write itself no longer touches the catalog.
 *
 * <p><b>Authorization: {@code sales}/{@code ceo}.</b>
 * <ul>
 *   <li>{@code sales} is OWNER-SCOPED, but not by any check written in THIS class — it is inherited
 *       for free from {@link PricingRequestService#get} (via {@code requireViewable}), which already
 *       refuses a {@code sales} actor who is not {@code summary.ticketCreatedById()} for every
 *       status, the SAME check {@link PricingRequestService#submit} applies. A rep cannot set a
 *       thickness on another rep's deal.
 *   <li>{@code sales} may act only while the request is {@code DRAFT} ({@link
 *       #SALES_EDITABLE_STATUSES}) — once submitted, thickness is out of Sales's hands.
 *   <li>{@code ceo} is NOT owner-scoped — it is the fallback — and may act across a WIDER window
 *       ({@link #CEO_EDITABLE_STATUSES}: every Import/CEO stage PLUS {@code DRAFT} itself), because
 *       V156's uncostable-line safety net means a request can still arrive at the CEO — or sit
 *       stuck in DRAFT — with a line Sales never resolved.
 *   <li>{@code import} is refused. It is EXACTLY the role this change removes the capability from —
 *       refusing it is the point, not an oversight.
 * </ul>
 * Stated here per CLAUDE.md's "permission changes must ship evidence" — pinned against real
 * Postgres by {@code PricingRequestItemThicknessIntegrationTest}.
 */
@Service
public class PricingRequestItemThicknessService {
    private static final Set<String> ITEM_THICKNESS_ROLES = Set.of("sales", "ceo");
    /**
     * Statuses in which SALES may supply or correct a line's thickness — {@code DRAFT} only (owner
     * ruling 2026-09-06): the คำขอราคา draft is the rep's own scratchpad, and once {@code submit()}
     * moves it on, thickness becomes Import/CEO territory the same way an attachment does (see
     * {@code PricingRequestService#ATTACHMENT_EDITABLE_STATUSES} for the identical DRAFT-only
     * reasoning applied to a different field).
     */
    private static final Set<String> SALES_EDITABLE_STATUSES = Set.of(PricingRequestStatus.DRAFT);
    /**
     * Statuses in which the CEO — the fallback, never owner-scoped — may supply or correct a
     * line's thickness. Wider than Sales's window on BOTH ends: {@code DRAFT} itself (a stuck draft
     * Sales cannot or will not resolve) through every stage up to {@code CEO_REVIEWING}. V156's
     * uncostable-line safety net is why the Import/CEO stages stay reachable — a request created
     * before this feature existed, or one whose catalog link changed underneath it after
     * submission, can still surface an unresolved line well past DRAFT, and the CEO must be able to
     * fix it in place rather than being forced to send the whole request back to Sales first.
     * Closed once a selling price has been decided ({@code APPROVED_FOR_QUOTATION} onward) or the
     * request is dead.
     */
    private static final Set<String> CEO_EDITABLE_STATUSES = Set.of(
        PricingRequestStatus.DRAFT,
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.READY_FOR_CEO_REVIEW,
        PricingRequestStatus.CEO_REVIEWING);

    private final PricingRequestRepository requests;
    private final PricingRequestService pricingRequests;
    private final CatalogRepository catalog;

    public PricingRequestItemThicknessService(PricingRequestRepository requests,
                                              PricingRequestService pricingRequests,
                                              CatalogRepository catalog) {
        this.requests = requests;
        this.pricingRequests = pricingRequests;
        this.catalog = catalog;
    }

    @Transactional
    public PricingRequestDetailDto setItemThickness(long pricingRequestId, long itemId,
                                                     SetItemThicknessRequest request, UserPrincipal actor) {
        requireRole(actor, ITEM_THICKNESS_ROLES);
        // Reuses PricingRequestService's own viewability/ownership/DRAFT-hiding gate — see this
        // class's own Javadoc for why composing it, rather than duplicating its logic, is the
        // point of this class existing separately at all. For a `sales` actor this call is ALSO
        // where owner-scoping is enforced (requireViewable refuses a non-owner sales actor
        // outright) — there is no separate ownership check below because none is needed.
        PricingRequestDetailDto detail = pricingRequests.get(pricingRequestId, actor);
        PricingRequestSummaryDto summary = detail.summary();
        Set<String> editableStatuses = "ceo".equals(actor.role()) ? CEO_EDITABLE_STATUSES : SALES_EDITABLE_STATUSES;
        if (!editableStatuses.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ระบุความหนาได้เฉพาะคำขอราคาที่อยู่ระหว่างดำเนินการเท่านั้น (สถานะปัจจุบัน: '" + summary.status() + "')");
        }
        PricingRequestItemDto item = detail.items().stream()
            .filter(candidate -> candidate.id() == itemId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการสินค้านี้ในคำขอราคานี้"));

        BigDecimal thicknessMm = request.thicknessMm();
        // The refusal is a SET-only concern ("nothing to FILL") — a CLEAR (thicknessMm == null)
        // must always be allowed to proceed. See this class's own Javadoc for why, and
        // PricingRequestItemThicknessIntegrationTest#nullClearsALinkedLinesOwnOverrideToo for the
        // regression this guards.
        if (thicknessMm != null) {
            // The CURRENT catalog-side resolution — product's own thickness_mm, or an existing
            // collection default, whichever price_catalog.v_priceable_product already COALESCEs.
            // NOT item.resolvedThicknessMm(): that field also folds in thicknessMmOverride, and an
            // existing override must never block a correction here — see this class's own Javadoc
            // for why the refusal is deliberately catalog-only.
            Long priceId = item.catalogPriceId() != null ? item.catalogPriceId() : item.productId();
            BigDecimal catalogResolvedThicknessMm = priceId != null
                ? catalog.findPricingKeys(Set.of(priceId)).get(priceId).thicknessMm()
                : null;
            if (catalogResolvedThicknessMm != null) {
                throw new ApiException(HttpStatus.CONFLICT,
                    item.displayName() + " มีความหนาจาก Price Catalog อยู่แล้ว (" + catalogResolvedThicknessMm
                        + " มม.) — ไม่ต้องระบุเพิ่ม");
            }
        }

        requests.updateItemThicknessOverride(itemId, thicknessMm);
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
