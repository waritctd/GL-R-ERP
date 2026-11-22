package th.co.glr.hr.documentchain;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.deposit.DepositNoticeService.DealDocumentScope;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;

/** The few reads the document chain needs beyond the deposit / remaining-invoice / quotation services' own. */
@Repository
public class DocumentChainRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DocumentChainRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** When the order was confirmed: the latest confirm-order-from-quotation event, else the latest CUSTOMER_CONFIRMED
     * (the pricing-request route). The statement is TicketRepository#ORDER_CONFIRMED_AT_SQL, shared with the
     * delivery-date floor (slice 6d) so the two cannot disagree on "order confirmed". */
    public Optional<Instant> findOrderConfirmedAt(long ticketId) {
        return jdbc.query(TicketRepository.ORDER_CONFIRMED_AT_SQL, TicketRepository.orderConfirmedAtParams(ticketId),
            (rs, n) -> rs.getTimestamp("created_at").toInstant()).stream().findFirst();
    }

    /** One list row's chain facts. */
    public record ListFacts(DocumentChainRules.Facts facts, boolean promoted, Long confirmedQuotationId) {}

    /**
     * The chain facts of each listed APPROVED direct quotation's deal, in ONE statement, filtered IN THE WHERE CLAUSE to
     * the deals whose documents {@code scope} may read (the same decision DepositNoticeService#requireTicketViewer
     * makes per deal). A quotation outside the scope, not APPROVED, or not DEAL_DIRECT is simply absent.
     *
     * @param viewerId the viewer, for {@link DealDocumentScope#OWNER_OR_KEYER}
     */
    public Map<Long, ListFacts> findListFacts(Collection<Long> quotationIds, DealDocumentScope scope, long viewerId) {
        if (quotationIds.isEmpty() || scope == DealDocumentScope.NONE) return Map.of();
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("ids", quotationIds)
            .addValue("promoted", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION);
        String scopeClause = switch (scope) {
            case ALL -> "";
            case OWNER_OR_KEYER -> {
                params.addValue("viewerId", viewerId);
                yield " AND (t.created_by = :viewerId OR t.keyed_by = :viewerId)";
            }
            case ACCOUNT -> {
                // requireTicketViewer also admits account to a dead deal holding an issued notice; a dead deal never
                // carries a mark (DocumentChainRules#listStage), so the list scope alone is exact here.
                TicketRepository.addAccountScopeParams(params);
                yield " AND " + TicketRepository.accountScopePredicate();
            }
            case NONE -> throw new IllegalStateException("unreachable");
        };
        Map<Long, ListFacts> out = new HashMap<>();
        jdbc.query("""
            SELECT q.quotation_id,
                   t.status, t.lifecycle, t.deposit_policy, t.payment_status, t.fulfillment_status,
                   pe.event_id IS NOT NULL AS promoted, pe.related_document_id AS confirmed_quotation_id,
                   EXISTS (SELECT 1 FROM sales.deposit_notice d
                            WHERE d.ticket_id = t.ticket_id AND d.status = 'DRAFT') AS notice_draft,
                   EXISTS (SELECT 1 FROM sales.deposit_notice d
                            WHERE d.ticket_id = t.ticket_id AND d.status = 'ISSUED') AS notice_issued,
                   EXISTS (SELECT 1 FROM sales.remaining_invoice r
                            WHERE r.ticket_id = t.ticket_id AND r.status = 'DRAFT') AS invoice_draft,
                   EXISTS (SELECT 1 FROM sales.remaining_invoice r
                            WHERE r.ticket_id = t.ticket_id AND r.status = 'ISSUED') AS invoice_issued
              FROM sales.quotation q
              JOIN sales.ticket t ON t.ticket_id = q.ticket_id
              LEFT JOIN LATERAL (
                   SELECT e.event_id, e.related_document_id FROM sales.ticket_event e
                    WHERE e.ticket_id = t.ticket_id AND e.kind = :promoted
                    ORDER BY e.created_at DESC, e.event_id DESC
                    LIMIT 1) pe ON TRUE
             WHERE q.quotation_id IN (:ids)
               AND q.origin = 'DEAL_DIRECT' AND q.doc_status = 'APPROVED' AND NOT q.is_sample
            """ + scopeClause, params, rs -> {
            out.put(rs.getLong("quotation_id"), new ListFacts(
                new DocumentChainRules.Facts(rs.getString("status"), rs.getString("lifecycle"),
                    rs.getString("deposit_policy"), rs.getString("payment_status"), rs.getString("fulfillment_status"),
                    rs.getBoolean("notice_draft"), rs.getBoolean("notice_issued"),
                    rs.getBoolean("invoice_draft"), rs.getBoolean("invoice_issued")),
                rs.getBoolean("promoted"), (Long) rs.getObject("confirmed_quotation_id", Long.class)));
        });
        return out;
    }
}
