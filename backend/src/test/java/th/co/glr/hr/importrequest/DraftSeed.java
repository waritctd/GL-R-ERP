package th.co.glr.hr.importrequest;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;

/**
 * Test-only seed for leftover DRAFT rows. Since the 10-29 ruling {@code createDrafts} creates AND
 * ISSUES, so tests of the DRAFT lifecycle (edit, delete, first-issue checks) need a DRAFT that the
 * service no longer produces. This writes one per factory of the deal's lines (matched by
 * {@code ticket_item.factory} name) straight through the repository, with the country lead-time
 * default, exactly as the old create did. Skips factories that already have a live form.
 */
final class DraftSeed {

    private DraftSeed() { }

    static List<ImportRequestDto> seed(NamedParameterJdbcTemplate jdbc, long ticketId, UserPrincipal actor) {
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        var snapshot = new ImportRequestQueryRepository(jdbc).loadTicketSnapshot(ticketId).orElseThrow();
        List<Map<String, Object>> factories = jdbc.queryForList("""
            SELECT f.factory_id, f.name, f.country,
                   string_agg(DISTINCT ti.brand, ', ') AS brands, min(ti.sort_order) AS first_sort
              FROM sales.ticket_item ti
              JOIN price_catalog.factories f ON f.name = ti.factory
             WHERE ti.ticket_id = :t
             GROUP BY f.factory_id, f.name, f.country
             ORDER BY min(ti.sort_order)
            """, Map.of("t", ticketId));
        for (Map<String, Object> f : factories) {
            long factoryId = ((Number) f.get("factory_id")).longValue();
            if (stored.findByTicket(ticketId).stream().anyMatch(r -> r.factoryId() == factoryId
                && ImportRequestStatus.isLive(r.status()))) {
                continue;
            }
            var lead = LeadTimeDefaults.forCountry((String) f.get("country"));
            long id = stored.insertDraft(ticketId, factoryId, (String) f.get("name"), (String) f.get("brands"),
                stored.highestVersion(ticketId, factoryId) + 1, snapshot,
                lead == null ? null : lead.minDays(), lead == null ? null : lead.maxDays(), null, null,
                actor.id(), actor.name());
            jdbc.update("""
                INSERT INTO sales.import_request_item
                    (import_request_id, ticket_item_id, seq, code, size, qty, unit, brand, model)
                SELECT :id, ti.item_id, row_number() OVER (ORDER BY ti.sort_order), ti.model, ti.size,
                       ti.qty, ti.unit, ti.brand, ti.model
                  FROM sales.ticket_item ti WHERE ti.ticket_id = :t AND ti.factory = :name
                """, Map.of("id", id, "t", ticketId, "name", f.get("name")));
        }
        return stored.findByTicket(ticketId);
    }
}
