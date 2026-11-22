package th.co.glr.hr.deliveryrequest;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.ApprovedQuotationDto;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.FileDto;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.LineDto;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.deposit.DepositNoticeRepository;

@Repository
public class DeliveryRequestRepository {
    private static final String NAME = "NULLIF(TRIM(CONCAT_WS(' ', %s.first_name_th, %s.last_name_th)), '')";

    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A round's own columns plus the quotation number and the names of the people who acted. */
    public record Row(long id, long ticketId, int roundNo, String status, Long quotationId, String quotationNumber,
                      String note, List<String> returnReasonCodes, String returnReasonText, String requestedByName,
                      Instant sentAt, String forwardedByName, Instant forwardedAt, List<String> untickedDocs,
                      Long requestedById, String invoiceNumber, java.time.LocalDate invoiceDate, BigDecimal invoiceTotal,
                      BigDecimal recordedAmount, Long receiptId, Long invoiceAttachmentId, String invoicedByName,
                      Instant invoicedAt) {}

    /** One ticket_item's coverage facts for a new round (see {@link #coverage}). */
    public record Coverage(long itemId, String description, String unit, BigDecimal qty, BigDecimal remaining) {}

    private static final String ROW_SELECT = """
        SELECT r.delivery_request_id, r.ticket_id, r.round_no, r.status, r.quotation_id, q.number AS quotation_number,
               r.note, r.return_reason_codes, r.return_reason_text, %s AS requested_by_name, r.sent_at,
               %s AS forwarded_by_name, r.forwarded_at, r.unticked_docs, r.requested_by,
               r.invoice_number, r.invoice_date, r.invoice_total, r.recorded_amount, r.receipt_id,
               r.invoice_attachment_id, %s AS invoiced_by_name, r.invoiced_at
          FROM sales.delivery_request r
          LEFT JOIN sales.quotation q ON q.quotation_id = r.quotation_id
          LEFT JOIN hr.employee rb ON rb.employee_id = r.requested_by
          LEFT JOIN hr.employee fb ON fb.employee_id = r.forwarded_by
          LEFT JOIN hr.employee ib ON ib.employee_id = r.invoiced_by
        """.formatted(NAME.formatted("rb", "rb"), NAME.formatted("fb", "fb"), NAME.formatted("ib", "ib"));

    public List<Row> findByTicket(long ticketId) {
        return jdbc.query(ROW_SELECT + " WHERE r.ticket_id = :t ORDER BY r.round_no DESC",
            Map.of("t", ticketId), (rs, n) -> map(rs));
    }

    public Optional<Row> findById(long id) {
        return jdbc.query(ROW_SELECT + " WHERE r.delivery_request_id = :id", Map.of("id", id), (rs, n) -> map(rs))
            .stream().findFirst();
    }

    public Optional<Row> findOpen(long ticketId) {
        return jdbc.query(ROW_SELECT + " WHERE r.ticket_id = :t AND r.status IN ('DRAFT','SENT','RETURNED','FORWARDED')"
            + " ORDER BY (r.status = 'FORWARDED'), r.round_no DESC LIMIT 1", // the sales-side round first
            Map.of("t", ticketId), (rs, n) -> map(rs)).stream().findFirst();
    }

    /** All non-DRAFT rounds plus one: the number a new draft takes. */
    public int nextRoundNo(long ticketId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.delivery_request WHERE ticket_id = :t AND status <> 'DRAFT'",
            Map.of("t", ticketId), Integer.class);
        return (n == null ? 0 : n) + 1;
    }

    public long insertDraft(long ticketId, int roundNo, Long quotationId, long requestedBy) {
        return jdbc.queryForObject("""
            INSERT INTO sales.delivery_request (ticket_id, round_no, status, quotation_id, requested_by)
            VALUES (:t, :n, 'DRAFT', :q, :by) RETURNING delivery_request_id
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("n", roundNo)
                .addValue("q", quotationId).addValue("by", requestedBy), Long.class);
    }

    /** {@code untickedDocs} null keeps what is stored. */
    public void updateDraft(long id, Long quotationId, String note, List<String> untickedDocs) {
        jdbc.update("""
            UPDATE sales.delivery_request SET quotation_id = :q, note = :note,
                   unticked_docs = COALESCE(CAST(:unticked AS TEXT), unticked_docs)
             WHERE delivery_request_id = :id
            """, new MapSqlParameterSource().addValue("id", id).addValue("q", quotationId).addValue("note", note)
                .addValue("unticked", untickedDocs == null ? null : String.join(",", untickedDocs)));
    }

    public void markSent(long id, long by) {
        jdbc.update("""
            UPDATE sales.delivery_request SET status = 'SENT', sent_at = now(), requested_by = :by,
                   return_reason_codes = NULL, return_reason_text = NULL, returned_by = NULL, returned_at = NULL
             WHERE delivery_request_id = :id
            """, Map.of("id", id, "by", by));
    }

    /** SENT to FORWARDED, once: false when the round was no longer SENT (someone else acted first). */
    public boolean markForwarded(long id, long by) {
        return jdbc.update("""
            UPDATE sales.delivery_request SET status = 'FORWARDED', forwarded_by = :by, forwarded_at = now()
             WHERE delivery_request_id = :id AND status = 'SENT'
            """, Map.of("id", id, "by", by)) == 1;
    }

    /** SENT to RETURNED with the reason; sales edits and sends it again (markSent clears the reason). */
    public boolean markReturned(long id, long by, List<String> codes, String text) {
        return jdbc.update("""
            UPDATE sales.delivery_request SET status = 'RETURNED', returned_by = :by, returned_at = now(),
                   return_reason_codes = CAST(string_to_array(:codes, ',') AS TEXT[]), return_reason_text = :text
             WHERE delivery_request_id = :id AND status = 'SENT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("by", by)
                .addValue("codes", codes.isEmpty() ? null : String.join(",", codes)).addValue("text", text)) == 1;
    }

    /** FORWARDED to INVOICED, once: false when the round was no longer FORWARDED. */
    public boolean markInvoiced(long id, long by, String number, java.time.LocalDate date, BigDecimal total,
                                BigDecimal recorded, Long receiptId, Long attachmentId, String expectedDeltasJson) {
        return jdbc.update("""
            UPDATE sales.delivery_request SET status = 'INVOICED', invoice_number = :no, invoice_date = :date,
                   invoice_total = :total, recorded_amount = :rec, receipt_id = :receipt,
                   invoice_attachment_id = :att, expected_deltas = CAST(:deltas AS jsonb), invoiced_by = :by, invoiced_at = clock_timestamp()
             WHERE delivery_request_id = :id AND status = 'FORWARDED'
            """, new MapSqlParameterSource().addValue("id", id).addValue("by", by).addValue("no", number)
                .addValue("date", date).addValue("total", total).addValue("rec", recorded)
                .addValue("receipt", receiptId).addValue("att", attachmentId).addValue("deltas", expectedDeltasJson)) == 1;
    }

    /** INVOICED back to FORWARDED with every invoice column cleared (the void); false when the round was not INVOICED. */
    public boolean clearInvoice(long id) {
        return jdbc.update("""
            UPDATE sales.delivery_request SET status = 'FORWARDED', invoice_number = NULL, invoice_date = NULL,
                   invoice_total = NULL, recorded_amount = NULL, receipt_id = NULL, invoice_attachment_id = NULL,
                   expected_deltas = NULL, invoiced_by = NULL, invoiced_at = NULL
             WHERE delivery_request_id = :id AND status = 'INVOICED'
            """, Map.of("id", id)) == 1;
    }

    /** Has a delivery been recorded against this round? (V236 column; a later slice writes it.) */
    public boolean hasDelivery(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.delivery_record WHERE delivery_request_id = :id)", Map.of("id", id), Boolean.class));
    }

    /** The expected-payment deltas stored at confirm (JSON text, null when none). */
    public String expectedDeltasJson(long id) {
        return jdbc.queryForObject("SELECT CAST(expected_deltas AS text) FROM sales.delivery_request WHERE delivery_request_id = :id",
            Map.of("id", id), String.class);
    }

    /** The order's most recently CONFIRMED invoice (by invoiced_at, then id): the only one a void may touch. */
    public Optional<Row> latestConfirmed(long ticketId) {
        return jdbc.query(ROW_SELECT + " WHERE r.ticket_id = :t AND r.status = 'INVOICED'"
            + " ORDER BY r.invoiced_at DESC, r.delivery_request_id DESC LIMIT 1",
            Map.of("t", ticketId), (rs, n) -> map(rs)).stream().findFirst();
    }

    /** The newest receipt of any source on the ticket: id and creation time (null when none). */
    public Map<String, Object> newestReceipt(long ticketId) {
        return jdbc.queryForList("SELECT receipt_id, created_at FROM sales.payment_receipt WHERE ticket_id = :t"
            + " ORDER BY receipt_id DESC LIMIT 1", Map.of("t", ticketId)).stream().findFirst().orElse(null);
    }

    /** Receipts of this order's rounds that an Express import row is RESOLVED against (one query for a list). */
    public java.util.Set<Long> receiptsSettledByImportRows(long ticketId) {
        return new java.util.HashSet<>(jdbc.queryForList("""
            SELECT DISTINCT i.receipt_id FROM sales.receipt_import_row i
             WHERE i.status = 'RESOLVED' AND i.receipt_id IN
                   (SELECT r.receipt_id FROM sales.delivery_request r WHERE r.ticket_id = :t AND r.receipt_id IS NOT NULL)
            """, Map.of("t", ticketId), Long.class));
    }

    /** The ids of this order's rounds that have a delivery recorded against them (one query for a whole list). */
    private static final String LINKED_DELIVERED = """
        COALESCE((SELECT SUM(dri.qty) FROM sales.delivery_record_item dri
                    JOIN sales.delivery_record dr ON dr.delivery_id = dri.delivery_id
                   WHERE dr.delivery_request_id = l.delivery_request_id AND dri.item_id = l.item_id), 0)""";

    /** Rounds with a delivery recorded against them, or whose goods have all already left (recorded without the round). */
    public java.util.Set<Long> deliveredRoundIds(long ticketId) {
        return new java.util.HashSet<>(jdbc.queryForList("""
            SELECT DISTINCT d.delivery_request_id FROM sales.delivery_record d
              JOIN sales.delivery_request r ON r.delivery_request_id = d.delivery_request_id
             WHERE r.ticket_id = :t
            UNION
            SELECT r.delivery_request_id FROM sales.delivery_request r
             WHERE r.ticket_id = :t AND r.status = 'INVOICED'
               AND EXISTS (SELECT 1 FROM sales.delivery_request_line l WHERE l.delivery_request_id = r.delivery_request_id)
               AND NOT EXISTS (SELECT 1 FROM sales.delivery_request_line l JOIN sales.ticket_item ti ON ti.item_id = l.item_id
                                WHERE l.delivery_request_id = r.delivery_request_id
                                  AND LEAST(l.qty - %s, ti.qty - ti.qty_not_delivered - COALESCE(ti.qty_delivered, 0)) > 0)
            """.formatted(LINKED_DELIVERED), Map.of("t", ticketId), Long.class));
    }

    /** The round's current INVOICE files with who attached them (for the replaced-file event). */
    public List<Map<String, Object>> invoiceFileOwners(long rid) {
        return jdbc.queryForList("""
            SELECT a.file_name, a.uploaded_by, %s AS uploader_name FROM sales.attachment a
              LEFT JOIN hr.employee e ON e.employee_id = a.uploaded_by
             WHERE a.delivery_request_id = :id AND a.attach_type = 'INVOICE'
            """.formatted(NAME.formatted("e", "e")), Map.of("id", rid));
    }

    /** Σ invoice_total of the order's INVOICED rounds. */
    public BigDecimal invoicedTotal(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(invoice_total), 0) FROM sales.delivery_request
             WHERE ticket_id = :t AND status = 'INVOICED'
            """, Map.of("t", ticketId), BigDecimal.class);
    }

    /** Is this invoice number already confirmed on any round (any order)? */
    public boolean invoiceNumberUsed(String number) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.delivery_request
                            WHERE status = 'INVOICED' AND upper(btrim(invoice_number)) = upper(btrim(:no)))
            """, Map.of("no", number), Boolean.class));
    }

    /** The newest INVOICE-type file attached to the round, if any. */
    public Optional<Long> invoiceFileId(long rid) {
        return jdbc.query("""
            SELECT attachment_id FROM sales.attachment WHERE delivery_request_id = :id AND attach_type = 'INVOICE'
             ORDER BY attachment_id DESC LIMIT 1
            """, Map.of("id", rid), (rs, n) -> rs.getLong(1)).stream().findFirst();
    }

    /** Drops the round's earlier INVOICE file(s) (a re-upload replaces an unconfirmed one); returns their paths. */
    public List<String> deleteInvoiceFiles(long rid) {
        return jdbc.query("""
            DELETE FROM sales.attachment WHERE delivery_request_id = :id AND attach_type = 'INVOICE' RETURNING file_path
            """, Map.of("id", rid), (rs, n) -> rs.getString(1));
    }

    /** FORWARDED or INVOICED: the part of an order an invoice issuer may see. */
    public boolean hasForwardedRound(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.delivery_request WHERE ticket_id = :t AND status IN ('FORWARDED','INVOICED'))",
            Map.of("t", ticketId), Boolean.class));
    }

    /** Has the sales side sent this order's request at all (any round past DRAFT)? The warehouse reads nothing before. */
    public boolean hasSentRound(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.delivery_request WHERE ticket_id = :t AND status <> 'DRAFT')",
            Map.of("t", ticketId), Boolean.class));
    }

    public void replaceLines(long id, List<Coverage> lines) {
        jdbc.update("DELETE FROM sales.delivery_request_line WHERE delivery_request_id = :id", Map.of("id", id));
        for (Coverage l : lines) {
            jdbc.update("INSERT INTO sales.delivery_request_line (delivery_request_id, item_id, qty) VALUES (:id, :i, :q)",
                Map.of("id", id, "i", l.itemId(), "q", l.remaining()));
        }
    }

    /**
     * Per item of the order: ordered qty and what no round covers yet = qty minus deliveries recorded outside any
     * round, minus the lines of the order's other non-DRAFT rounds ({@code excludeRound} is the round being sent).
     */
    public List<Coverage> coverage(long ticketId, long excludeRound) {
        return jdbc.query("""
            SELECT ti.item_id, CONCAT_WS(' ', ti.brand, ti.model, ti.color, ti.texture, ti.size) AS description,
                   'แผ่น' AS unit, ti.qty,
                   GREATEST(ti.qty - ti.qty_not_delivered -- a quantity marked ไม่ได้รับของ cannot go on a new round or invoice
                   - COALESCE((SELECT SUM(dri.qty) FROM sales.delivery_record_item dri
                                 JOIN sales.delivery_record dr ON dr.delivery_id = dri.delivery_id
                                WHERE dri.item_id = ti.item_id AND dr.delivery_request_id IS NULL), 0)
                   - COALESCE((SELECT SUM(l.qty) FROM sales.delivery_request_line l
                                 JOIN sales.delivery_request r ON r.delivery_request_id = l.delivery_request_id
                                WHERE l.item_id = ti.item_id AND r.ticket_id = ti.ticket_id
                                  AND r.status <> 'DRAFT' AND r.delivery_request_id <> :ex), 0), 0) AS remaining
              FROM sales.ticket_item ti
             WHERE ti.ticket_id = :t
             ORDER BY ti.sort_order, ti.item_id
            """, Map.of("t", ticketId, "ex", excludeRound),
            (rs, n) -> new Coverage(rs.getLong("item_id"), rs.getString("description"), rs.getString("unit"),
                rs.getBigDecimal("qty"), rs.getBigDecimal("remaining")));
    }

    public List<LineDto> lines(long id) {
        return jdbc.query("""
            SELECT l.item_id, CONCAT_WS(' ', ti.brand, ti.model, ti.color, ti.texture, ti.size) AS description,
                   'แผ่น' AS unit,
                   -- what was marked ไม่ได้รับของ leaves the covered quantity; it never reads as delivered
                   l.qty - GREATEST(LEAST(l.qty - %1$s, ti.qty - COALESCE(ti.qty_delivered, 0)), 0)
                     + GREATEST(LEAST(l.qty - %1$s, ti.qty - ti.qty_not_delivered - COALESCE(ti.qty_delivered, 0)), 0) AS covered,
                   l.qty - GREATEST(LEAST(l.qty - %1$s, ti.qty - COALESCE(ti.qty_delivered, 0)), 0) AS delivered
              FROM sales.delivery_request_line l
              JOIN sales.ticket_item ti ON ti.item_id = l.item_id
             WHERE l.delivery_request_id = :id
             ORDER BY ti.sort_order, ti.item_id
            """.formatted(LINKED_DELIVERED), Map.of("id", id),
            (rs, n) -> new LineDto(rs.getLong("item_id"), rs.getString("description"), rs.getString("unit"),
                rs.getBigDecimal("covered"), rs.getBigDecimal("delivered")));
    }

    public List<FileDto> files(long id) {
        return jdbc.query("""
            SELECT a.attachment_id, a.file_name, a.mime_type, a.file_size, a.attach_type, a.uploader_side, a.uploaded_by,
                   %s AS uploaded_by_name, a.uploaded_at
              FROM sales.attachment a
              LEFT JOIN hr.employee e ON e.employee_id = a.uploaded_by
             WHERE a.delivery_request_id = :id
             ORDER BY a.uploaded_at, a.attachment_id
            """.formatted(NAME.formatted("e", "e")), Map.of("id", id),
            (rs, n) -> {
                long size = rs.getLong("file_size");
                Long fileSize = rs.wasNull() ? null : size;
                return new FileDto(rs.getLong("attachment_id"), rs.getString("file_name"), rs.getString("mime_type"),
                    fileSize, rs.getString("attach_type"),
                    rs.getString("uploader_side") == null ? "SYSTEM" : rs.getString("uploader_side"),
                    rs.getObject("uploaded_by", Long.class), rs.getString("uploaded_by_name"), rs.getTimestamp("uploaded_at").toInstant());
            });
    }

    /** Recording ACCEPTED moves only the pricing request: the deal itself is an order from ORDER_RECEIVED on. */
    private static final String ACCEPTED_PRICING = "((origin = 'PRICING_REQUEST' OR (origin IS NULL AND pricing_request_id IS NOT NULL))"
        + " AND doc_status = 'ACCEPTED')"; // both writers of a pricing-request quotation; a NULL origin is CustomerQuotationService's

    private static final String PRICING_ORDER = "(" + ACCEPTED_PRICING + " AND ticket_id IN"
        + " (SELECT ticket_id FROM sales.ticket WHERE sales_stage IN ('"
        + String.join("','", th.co.glr.hr.ticket.DealStage.ORDER.subList(
            th.co.glr.hr.ticket.DealStage.indexOf(th.co.glr.hr.ticket.DealStage.ORDER_RECEIVED), th.co.glr.hr.ticket.DealStage.ORDER.size()))
        + "')))";

    /** A quotation an order can be delivered against: a direct one from APPROVED on, or the accepted pricing-request one of a confirmed order. */
    private static final String ORDER_QUOTATION = "((origin = 'DEAL_DIRECT' AND NOT is_sample AND doc_status IN ('APPROVED','ISSUED','SENT','ACCEPTED'))"
        + " OR " + PRICING_ORDER + ")";

    /** The order came through a pricing request, the customer accepted it and the deal is an order: nothing is left to confirm. */
    public boolean isConfirmedPricingOrder(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.quotation WHERE ticket_id = :t AND " + PRICING_ORDER + ")",
            Map.of("t", ticketId), Boolean.class));
    }

    /** The customer accepted a pricing-request quotation, whatever stage the deal is at. */
    public boolean hasAcceptedPricingQuotation(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.quotation WHERE ticket_id = :t AND " + ACCEPTED_PRICING + ")",
            Map.of("t", ticketId), Boolean.class));
    }

    /** A pricing-request quotation of the older writer: its PDF comes from CustomerQuotationService. */
    public boolean isLegacyPricingQuotation(long quotationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.quotation
                            WHERE quotation_id = :q AND origin IS NULL AND pricing_request_id IS NOT NULL)
            """, Map.of("q", quotationId), Boolean.class));
    }

    /** The order's quotations ({@link #ORDER_QUOTATION}), oldest first. */
    public List<ApprovedQuotationDto> approvedQuotations(long ticketId) {
        return jdbc.query("""
            SELECT quotation_id, number, doc_status, total_amount, document_language, deposit_percent, origin IS NULL AS legacy
              FROM sales.quotation
             WHERE ticket_id = :t AND %s
             ORDER BY quotation_id
            """.formatted(ORDER_QUOTATION), Map.of("t", ticketId), (rs, n) -> {
                BigDecimal sub = rs.getBigDecimal("total_amount") == null ? BigDecimal.ZERO : rs.getBigDecimal("total_amount");
                BigDecimal total = WastageCalculator.grandTotal(sub, WastageCalculator.vat(sub, rs.getString("document_language")));
                String status = rs.getString("doc_status");
                Integer depositPercent = (Integer) rs.getObject("deposit_percent");
                String blocked = !DepositNoticeRepository.DEAL_DIRECT_DEPOSIT_SOURCE_STATUSES.contains(status)
                    ? "ใบเสนอราคานี้ส่งลูกค้าแล้ว ใช้ออกใบแจ้งยอดเงินรับมัดจำไม่ได้"
                    : depositPercent != null && depositPercent == 0
                        ? "ใบเสนอราคานี้ระบุไม่มีมัดจำ (ชำระเต็มจำนวน) จึงออกใบแจ้งยอดเงินรับมัดจำไม่ได้" : null;
                return new ApprovedQuotationDto(rs.getLong("quotation_id"), rs.getString("number"), total, status, blocked, null, rs.getBoolean("legacy"));
            });
    }

    /** The document number when {@code id} is an issued or approved document of this deal of that kind, else empty. */
    public java.util.Optional<String> systemDocNumber(long ticketId, String kind, long id) {
        String sql = switch (kind) {
            case "QUOTATION" -> "SELECT number FROM sales.quotation WHERE quotation_id = :id AND ticket_id = :t AND " + ORDER_QUOTATION;
            case "DEPOSIT_NOTICE" -> "SELECT doc_number FROM sales.deposit_notice WHERE deposit_notice_id = :id"
                + " AND ticket_id = :t AND status = 'ISSUED'";
            case "REMAINING_INVOICE" -> "SELECT doc_number FROM sales.remaining_invoice WHERE id = :id"
                + " AND ticket_id = :t AND status = 'ISSUED'";
            case "IMPORT_REQUEST" -> "SELECT doc_number FROM sales.import_request WHERE import_request_id = :id"
                + " AND ticket_id = :t AND status = 'ISSUED'";
            default -> null;
        };
        if (sql == null) {
            return java.util.Optional.empty();
        }
        return jdbc.query(sql, Map.of("id", id, "t", ticketId), (rs, n) -> rs.getString(1)).stream()
            .filter(java.util.Objects::nonNull).findFirst();
    }

    /** Every {@code KIND:id} key {@link #systemDocNumber} would accept for this order: the documents a round may attach. */
    public java.util.Set<String> candidateDocKeys(long ticketId) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        String[][] sources = {
            {"QUOTATION", "SELECT quotation_id FROM sales.quotation WHERE ticket_id = :t AND " + ORDER_QUOTATION},
            {"DEPOSIT_NOTICE", "SELECT deposit_notice_id FROM sales.deposit_notice WHERE ticket_id = :t AND status = 'ISSUED'"},
            {"REMAINING_INVOICE", "SELECT id FROM sales.remaining_invoice WHERE ticket_id = :t AND status = 'ISSUED'"},
            {"IMPORT_REQUEST", "SELECT import_request_id FROM sales.import_request WHERE ticket_id = :t AND status = 'ISSUED'"},
        };
        for (String[] src : sources) {
            jdbc.query(src[1], Map.of("t", ticketId), (rs, n) -> src[0] + ":" + rs.getLong(1)).forEach(keys::add);
        }
        return keys;
    }

    /** The number of the quotation a deposit notice (its reference) or remaining invoice names; null when it names none. */
    public String docQuotationNumber(long ticketId, String kind, long id) {
        String sql = switch (kind) {
            case "DEPOSIT_NOTICE" -> "SELECT reference FROM sales.deposit_notice WHERE deposit_notice_id = :id AND ticket_id = :t";
            case "REMAINING_INVOICE" -> "SELECT q.number FROM sales.remaining_invoice r"
                + " JOIN sales.quotation q ON q.quotation_id = r.customer_quotation_id WHERE r.id = :id AND r.ticket_id = :t";
            default -> null;
        };
        return sql == null ? null : jdbc.query(sql, Map.of("id", id, "t", ticketId), (rs, n) -> rs.getString(1)).stream()
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** Drops this round's stored system-document copies (a re-send replaces them); returns their file paths. */
    public List<String> deleteSystemDocs(long rid) {
        return jdbc.query("""
            DELETE FROM sales.attachment WHERE delivery_request_id = :id AND attach_type = 'SYSTEM_DOC'
            RETURNING file_path
            """, Map.of("id", rid), (rs, n) -> rs.getString(1));
    }

    public boolean hasItems(long ticketId) {
        Boolean b = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sales.ticket_item WHERE ticket_id = :t)",
            Map.of("t", ticketId), Boolean.class);
        return Boolean.TRUE.equals(b);
    }

    private static Row map(ResultSet rs) throws SQLException {
        long q = rs.getLong("quotation_id");
        Long quotationId = rs.wasNull() ? null : q;
        Array codes = rs.getArray("return_reason_codes");
        List<String> reasonCodes = codes == null ? List.of() : List.of((String[]) codes.getArray());
        java.sql.Timestamp sent = rs.getTimestamp("sent_at");
        java.sql.Timestamp fwd = rs.getTimestamp("forwarded_at");
        String unticked = rs.getString("unticked_docs");
        long rb = rs.getLong("requested_by");
        Long requestedBy = rs.wasNull() ? null : rb;
        java.sql.Date invDate = rs.getDate("invoice_date");
        long rcpt = rs.getLong("receipt_id");
        Long receiptId = rs.wasNull() ? null : rcpt;
        long invAtt = rs.getLong("invoice_attachment_id");
        Long invoiceAttachmentId = rs.wasNull() ? null : invAtt;
        java.sql.Timestamp invoicedAt = rs.getTimestamp("invoiced_at");
        return new Row(rs.getLong("delivery_request_id"), rs.getLong("ticket_id"), rs.getInt("round_no"),
            rs.getString("status"), quotationId, rs.getString("quotation_number"), rs.getString("note"),
            reasonCodes, rs.getString("return_reason_text"), rs.getString("requested_by_name"),
            sent == null ? null : sent.toInstant(), rs.getString("forwarded_by_name"),
            fwd == null ? null : fwd.toInstant(), unticked == null || unticked.isBlank() ? List.of()
                : List.of(unticked.split(",")), requestedBy, rs.getString("invoice_number"),
            invDate == null ? null : invDate.toLocalDate(), rs.getBigDecimal("invoice_total"),
            rs.getBigDecimal("recorded_amount"), receiptId, invoiceAttachmentId, rs.getString("invoiced_by_name"),
            invoicedAt == null ? null : invoicedAt.toInstant());
    }
}
