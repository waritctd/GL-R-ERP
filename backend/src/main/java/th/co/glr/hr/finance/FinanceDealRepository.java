package th.co.glr.hr.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The few reads the finance deal view needs that no existing repository exposes, each selecting an
 * explicit allowlist of columns -- never {@code SELECT *}, never a cost/FX/margin column.
 */
@Repository
public class FinanceDealRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FinanceDealRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Header of the quotation the deal's amount payable comes from. */
    public record QuotationHeader(long id, String number, String status, BigDecimal totalAmount, String currency,
                                  java.time.Instant issuedAt, java.time.Instant acceptedAt, String origin,
                                  Long pricingRequestId) {}

    /**
     * The customer quotation that defines what the customer owes: ACCEPTED first, else
     * ISSUED/SENT, in exactly the order {@code TicketRepository#payableAmountSelect} picks its
     * total, so the document shown here is the one the payable figure was read from.
     */
    public Optional<QuotationHeader> findPayableQuotation(long ticketId) {
        return jdbc.query("""
            SELECT q.quotation_id, q.number, q.doc_status, q.total_amount, q.currency,
                   q.issued_at, q.accepted_at, q.origin, q.pricing_request_id
              FROM sales.quotation q
             WHERE q.ticket_id = :id AND q.doc_status IN ('ACCEPTED', 'ISSUED', 'SENT')
             ORDER BY CASE WHEN q.doc_status = 'ACCEPTED' THEN 0 ELSE 1 END,
                      CASE q.recipient_type WHEN 'BUYER' THEN 0 WHEN 'OWNER' THEN 1 ELSE 2 END,
                      q.accepted_at DESC NULLS LAST, q.issued_at DESC, q.quotation_id DESC
             LIMIT 1
            """, Map.of("id", ticketId), (rs, n) -> new QuotationHeader(
                rs.getLong("quotation_id"), rs.getString("number"), rs.getString("doc_status"),
                rs.getBigDecimal("total_amount"), rs.getString("currency"),
                rs.getTimestamp("issued_at") == null ? null : rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("accepted_at") == null ? null : rs.getTimestamp("accepted_at").toInstant(),
                rs.getString("origin"), (Long) rs.getObject("pricing_request_id"))).stream().findFirst();
    }

    /** Customer-facing selling lines of a quotation (pre-VAT line total). No pricing/cost columns. */
    public List<FinanceDealDto.Item> findQuotationItems(long quotationId) {
        return jdbc.query("""
            SELECT COALESCE(NULLIF(TRIM(qi.description), ''),
                            NULLIF(TRIM(CONCAT_WS(' ', qi.brand, qi.model, qi.color, qi.texture, qi.size)), '')) AS description,
                   COALESCE(qi.requested_quantity, qi.qty) AS qty,
                   -- raw_unit first: it is the HUMAN unit (ตร.ม., แผ่น, ...) and the ONLY unit column the
                   -- DEAL_DIRECT / PRICING_REQUEST insert path writes (DealQuotationRepository.INSERT_ITEM_SQL
                   -- never sets unit_basis / requested_unit_basis). The canonical codes (PER_SQM, ...) are only
                   -- a fallback for legacy Step-4 rows that lack a raw_unit.
                   COALESCE(NULLIF(TRIM(qi.raw_unit), ''), qi.requested_unit_basis, qi.unit_basis) AS unit,
                   COALESCE(qi.final_unit_price, qi.unit_price) AS unit_price,
                   COALESCE(qi.line_subtotal, qi.amount) AS line_total
              FROM sales.quotation_item qi
             WHERE qi.quotation_id = :id
             ORDER BY qi.seq, qi.quotation_item_id
            """, Map.of("id", quotationId), (rs, n) -> new FinanceDealDto.Item(
                rs.getString("description"), rs.getBigDecimal("qty"), rs.getString("unit"),
                rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_total"), FinanceDealDto.EXCLUDING_VAT));
    }

    /**
     * Fallback for a deal with no live quotation: its own lines at the APPROVED selling price only
     * (the same figure {@code payableAmountSelect}'s last fallback sums). Raw/proposed/calculated
     * price columns are deliberately not selected.
     */
    public List<FinanceDealDto.Item> findTicketItemsAtApprovedPrice(long ticketId) {
        return jdbc.query("""
            SELECT NULLIF(TRIM(CONCAT_WS(' ', ti.brand, ti.model, ti.color, ti.texture, ti.size)), '') AS description,
                   ti.qty, ti.unit_basis AS unit, ti.approved_price AS unit_price,
                   ti.approved_price * ti.qty AS line_total
              FROM sales.ticket_item ti
             WHERE ti.ticket_id = :id
             ORDER BY ti.sort_order, ti.item_id
            """, Map.of("id", ticketId), (rs, n) -> new FinanceDealDto.Item(
                rs.getString("description"), rs.getBigDecimal("qty"), rs.getString("unit"),
                rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_total"), FinanceDealDto.EXCLUDING_VAT));
    }

    /**
     * Live billing notes (ISSUED or SETTLED) that bill this deal, with the amount of THIS deal's
     * lines (a note is a per-customer cover sheet that may bill several deals).
     */
    public List<FinanceDealDto.BillingNoteDoc> findLiveBillingNotesForTicket(long ticketId) {
        return jdbc.query("""
            SELECT bn.id, bn.doc_number, bn.status, bn.bill_date, SUM(l.amount) AS amount
              FROM sales.billing_note bn
              JOIN sales.billing_note_line l ON l.billing_note_id = bn.id
             WHERE l.ticket_id = :id AND bn.status IN ('ISSUED', 'SETTLED')
             GROUP BY bn.id, bn.doc_number, bn.status, bn.bill_date
             ORDER BY bn.id
            """, Map.of("id", ticketId), (rs, n) -> {
                LocalDate billDate = rs.getObject("bill_date", LocalDate.class);
                long id = rs.getLong("id");
                return new FinanceDealDto.BillingNoteDoc(id, rs.getString("doc_number"), rs.getString("status"),
                    billDate, rs.getBigDecimal("amount"), FinanceDealDto.INCLUDING_VAT, "/api/billing-notes/" + id + "/file");
            });
    }

    /** The deal's COMMENT events only (every author), oldest first. Never any other event kind, never a snapshot. */
    public List<FinanceDealDto.Comment> findComments(long ticketId) {
        return jdbc.query("""
            SELECT e.event_id, e.actor_name, e.created_at, e.message
              FROM sales.ticket_event e
             WHERE e.ticket_id = :id AND e.kind = 'COMMENTED'
             ORDER BY e.created_at ASC, e.event_id ASC
            """, Map.of("id", ticketId), (rs, n) -> new FinanceDealDto.Comment(
                rs.getLong("event_id"), rs.getString("actor_name"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("message")));
    }

    /**
     * The deal's LATEST (highest commission_id) non-VOID SALE commission joined to its invoice, as the
     * finance view may see it: invoice fields and approval status only. Explicit allowlist -- never a
     * commission amount, weight, tier, payroll month or rep. {@code downloadPath} points at the deal's
     * INVOICE ticket attachment whose file_path equals the commission invoice file's (createFromDeal
     * writes the same physical file to both); the highest such attachment id wins, null when none.
     */
    public Optional<FinanceDealDto.CommissionInvoice> findCommissionInvoice(long ticketId) {
        return jdbc.query("""
            SELECT inv.invoice_number, inv.invoice_date, inv.gross_amount, inv.bank_fees, inv.suspense_vat,
                   inv.transport_fee, inv.cut_fee, inv.shortfall, inv.withholding_tax, inv.overpayment,
                   fa.file_name AS file_name,
                   (SELECT MAX(a.attachment_id) FROM sales.attachment a
                     WHERE a.ticket_id = cr.source_ticket_id AND a.attach_type = 'INVOICE'
                       AND a.file_path = fa.file_path) AS ticket_attachment_id,
                   cr.status, cr.rejection_reason, cr.created_at
              FROM sales.commission_record cr
              JOIN sales.invoice_details inv ON inv.invoice_id = cr.invoice_id
              LEFT JOIN hr.file_attachment fa ON fa.attachment_id = inv.invoice_attachment_id
             WHERE cr.source_ticket_id = :id AND cr.kind = 'SALE' AND cr.status <> 'VOID'
             ORDER BY cr.commission_id DESC
             LIMIT 1
            """, Map.of("id", ticketId), (rs, n) -> {
                Long attachmentId = (Long) rs.getObject("ticket_attachment_id");
                String status = rs.getString("status");
                return new FinanceDealDto.CommissionInvoice(
                    rs.getString("invoice_number"), rs.getObject("invoice_date", LocalDate.class),
                    rs.getBigDecimal("gross_amount"), rs.getBigDecimal("bank_fees"), rs.getBigDecimal("suspense_vat"),
                    rs.getBigDecimal("transport_fee"), rs.getBigDecimal("cut_fee"), rs.getBigDecimal("shortfall"),
                    rs.getBigDecimal("withholding_tax"), rs.getBigDecimal("overpayment"),
                    rs.getString("file_name"),
                    attachmentId == null ? null : "/api/attachments/" + attachmentId + "/file",
                    status, "REJECTED".equals(status) ? rs.getString("rejection_reason") : null,
                    rs.getTimestamp("created_at").toInstant());
            }).stream().findFirst();
    }
}
