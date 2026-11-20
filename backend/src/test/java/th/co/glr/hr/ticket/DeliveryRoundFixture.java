package th.co.glr.hr.ticket;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Test fixture: gives a deal an INVOICED delivery round (raw rows, no service walk), so a delivery links to it and needs
 * no note. One shared way, so the delivery tests keep testing delivery.
 */
public final class DeliveryRoundFixture {
    private DeliveryRoundFixture() {
    }

    /**
     * Makes sure the deal has an INVOICED round with quantity still to deliver; if not, adds one covering everything not
     * yet delivered. Safe to call before every delivery. Returns the id of the round a delivery will resolve to.
     */
    public static long ensureInvoicedRound(NamedParameterJdbcTemplate jdbc, long ticketId) {
        var open = jdbc.queryForList("""
            SELECT r.delivery_request_id FROM sales.delivery_request r
              JOIN sales.delivery_request_line l ON l.delivery_request_id = r.delivery_request_id
             WHERE r.ticket_id = :t AND r.status = 'INVOICED'
             GROUP BY r.delivery_request_id, r.round_no
            HAVING SUM(l.qty) > COALESCE((SELECT SUM(dri.qty) FROM sales.delivery_record_item dri
                      JOIN sales.delivery_record dr ON dr.delivery_id = dri.delivery_id
                     WHERE dr.delivery_request_id = r.delivery_request_id), 0)
             ORDER BY r.round_no LIMIT 1
            """, Map.of("t", ticketId), Long.class);
        if (!open.isEmpty()) {
            return open.get(0);
        }
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.delivery_request (ticket_id, round_no, status, invoice_number, invoice_date, invoice_total,
                                                recorded_amount, invoiced_at)
            SELECT :t, COALESCE(MAX(round_no), 0) + 1, 'INVOICED',
                   'FX-' || :t || '-' || (COALESCE(MAX(round_no), 0) + 1), current_date, 0, 0, now()
              FROM sales.delivery_request WHERE ticket_id = :t
            RETURNING delivery_request_id
            """, Map.of("t", ticketId), Long.class);
        jdbc.update("""
            INSERT INTO sales.delivery_request_line (delivery_request_id, item_id, qty)
            SELECT :id, item_id, qty - qty_delivered FROM sales.ticket_item
             WHERE ticket_id = :t AND qty - qty_delivered > 0
            """, Map.of("id", id, "t", ticketId));
        return id;
    }

    /** A round that is not yet invoiced (e.g. SENT) covering everything not yet delivered; returns its id. */
    public static long addRound(NamedParameterJdbcTemplate jdbc, long ticketId, String status) {
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.delivery_request (ticket_id, round_no, status)
            SELECT :t, COALESCE(MAX(round_no), 0) + 1, :s FROM sales.delivery_request WHERE ticket_id = :t
            RETURNING delivery_request_id
            """, Map.of("t", ticketId, "s", status), Long.class);
        jdbc.update("""
            INSERT INTO sales.delivery_request_line (delivery_request_id, item_id, qty)
            SELECT :id, item_id, qty - qty_delivered FROM sales.ticket_item
             WHERE ticket_id = :t AND qty - qty_delivered > 0
            """, Map.of("id", id, "t", ticketId));
        return id;
    }
}
