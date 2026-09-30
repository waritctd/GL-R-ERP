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
 * call ({@code origin} briefly NULL — the legacy value, which nothing on the create path reads), and
 * restores them in {@code finally}. It bypasses ONLY N6: authz, the recipient rule and the
 * one-pricing-route rule all still run. N6 itself is pinned by
 * {@code DealQuotationCreateGuardsIntegrationTest}.
 */
final class LegacyDirectQuotationFixtures {
    private LegacyDirectQuotationFixtures() {}

    static DealQuotationDto createAlongsideLive(NamedParameterJdbcTemplate jdbc, DealQuotationService service,
                                                long ticketId, UpsertDealQuotationRequest request,
                                                UserPrincipal actor) {
        List<Long> live = jdbc.queryForList("""
            SELECT quotation_id FROM sales.quotation
             WHERE ticket_id = :ticketId AND origin = 'DEAL_DIRECT'
               AND doc_status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED')
            """, Map.of("ticketId", ticketId), Long.class);
        if (!live.isEmpty()) {
            jdbc.update("UPDATE sales.quotation SET origin = NULL WHERE quotation_id IN (:ids)", Map.of("ids", live));
        }
        try {
            return service.create(ticketId, request, actor);
        } finally {
            if (!live.isEmpty()) {
                jdbc.update("UPDATE sales.quotation SET origin = 'DEAL_DIRECT' WHERE quotation_id IN (:ids)",
                    Map.of("ids", live));
            }
        }
    }
}
