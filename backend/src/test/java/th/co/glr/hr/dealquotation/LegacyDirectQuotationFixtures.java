package th.co.glr.hr.dealquotation;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;

/**
 * Test-only fixture for suites written BEFORE quotation ↔ deal linking slice 2's N6 (one live
 * DEAL_DIRECT quotation per deal, {@code DealQuotationService#create} → 409).
 *
 * <p>Those suites deliberately put several live direct quotations on ONE deal — the one-APPROVED-
 * per-deal approval sweep, the approve lock, list/count scoping — a state production can still hold
 * through pre-slice-2 data and through revisions/reorders (which N6 does not gate). This reaches that
 * state through the real {@code create} by hiding the deal's live direct rows from N6 for the one
 * call, and restoring them in {@code finally}. It bypasses ONLY N6: authz, the recipient rule and
 * the one-pricing-route rule all still run. N6 itself is pinned by
 * {@code DealQuotationCreateGuardsIntegrationTest}.
 *
 * <p><b>It hides them by {@code doc_status}, NOT by blanking {@code origin} — changed 2026-10-17
 * and the distinction is now load-bearing.</b> Blanking {@code origin} used to be safe because
 * nothing on the create path read it. Since the base number belongs to the DEAL, {@code create}
 * reads {@code DealQuotationRepository#nextNumberOnDeal} and {@code #nextRevisionNo}, both scoped
 * {@code origin IN ('DEAL_DIRECT','PRICING_REQUEST')} — so an {@code origin}-blanked row became
 * invisible to NUMBERING as well as to N6. That is a duplicate-key trap, not a cosmetic one: with a
 * visible row at {@code X-1} and a hidden live row already holding {@code X-2}, the counter would
 * hand the new document {@code X-2} and the insert would violate
 * {@code sales.quotation.number}'s UNIQUE constraint. {@code CANCELLED} hides the row from N6's
 * {@code doc_status IN ('DRAFT','PENDING_APPROVAL','APPROVED')} predicate while leaving it fully
 * visible to the counter, which is exactly the scope this fixture wants.
 */
final class LegacyDirectQuotationFixtures {
    private LegacyDirectQuotationFixtures() {}

    static DealQuotationDto createAlongsideLive(NamedParameterJdbcTemplate jdbc, DealQuotationService service,
                                                long ticketId, UpsertDealQuotationRequest request,
                                                UserPrincipal actor) {
        // id -> its OWN status, captured before hiding. These rows are deliberately a MIX of
        // DRAFT/PENDING_APPROVAL/APPROVED — that mix is what the suites using this fixture test —
        // so restoring one blanket status would quietly rewrite the scenario.
        List<Map<String, Object>> live = jdbc.queryForList("""
            SELECT quotation_id, doc_status FROM sales.quotation
             WHERE ticket_id = :ticketId AND origin = 'DEAL_DIRECT'
               AND doc_status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED')
            """, Map.of("ticketId", ticketId));
        for (Map<String, Object> row : live) {
            jdbc.update("UPDATE sales.quotation SET doc_status = 'CANCELLED' WHERE quotation_id = :id",
                Map.of("id", row.get("quotation_id")));
        }
        try {
            return service.create(ticketId, request, actor);
        } finally {
            for (Map<String, Object> row : live) {
                jdbc.update("UPDATE sales.quotation SET doc_status = :status WHERE quotation_id = :id",
                    Map.of("status", row.get("doc_status"), "id", row.get("quotation_id")));
            }
        }
    }
}
