package th.co.glr.hr.salesboard.receipts;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ImportSummary;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Candidate;

/**
 * sales.receipt_import / sales.receipt_import_row (V213), the duplicate check against sales.payment_receipt, and the
 * candidate deals the matcher compares a row with. Candidates are every ACTIVE deal: its ticket customer name, its
 * deposit-notice / remaining-invoice / billing-note numbers, its quotation numbers and its OPEN expected payments.
 */
@Repository
public class ReceiptImportRepository {

    /** A candidate deal plus what the queue shows about it. */
    public record CandidateDeal(Candidate candidate, String title, long ownerId, Long keyedById, String ownerName,
                                List<OpenExpected> openExpected) {}

    /** One OPEN expected payment of a candidate deal. */
    public record OpenExpected(BigDecimal amount, LocalDate week, String kind) {}

    /** A queued row as stored. */
    public record QueuedRow(long rowId, long importId, ParsedReceipt receipt, String reason, String status,
                            Long resolvedTicketId) {}

    private final NamedParameterJdbcTemplate jdbc;

    public ReceiptImportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── imports ────────────────────────────────────────────────────────────

    public long insertImport(String fileName, long uploadedBy) {
        return jdbc.queryForObject("""
            INSERT INTO sales.receipt_import (file_name, uploaded_by) VALUES (:f, :by) RETURNING import_id
            """, Map.of("f", fileName, "by", uploadedBy), Long.class);
    }

    public void finishImport(long importId, int total, int matched, BigDecimal matchedAmount, int duplicates,
                             BigDecimal duplicateAmount, int unmatched, BigDecimal unmatchedAmount, int invalid) {
        jdbc.update("""
            UPDATE sales.receipt_import
               SET total_rows = :total, matched = :matched, matched_amount = :ma, duplicates = :dup,
                   duplicate_amount = :da, unmatched = :un, unmatched_amount = :ua, invalid = :inv
             WHERE import_id = :id
            """, new MapSqlParameterSource().addValue("id", importId).addValue("total", total)
                .addValue("matched", matched).addValue("ma", matchedAmount).addValue("dup", duplicates)
                .addValue("da", duplicateAmount).addValue("un", unmatched).addValue("ua", unmatchedAmount)
                .addValue("inv", invalid));
    }

    public Optional<ImportSummary> findImport(long importId) {
        return jdbc.query(IMPORT_SELECT + " WHERE i.import_id = :id", Map.of("id", importId),
            ReceiptImportRepository::mapImport).stream().findFirst();
    }

    public Optional<ImportSummary> latestImport() {
        return jdbc.query(IMPORT_SELECT + " ORDER BY i.uploaded_at DESC, i.import_id DESC LIMIT 1", Map.of(),
            ReceiptImportRepository::mapImport).stream().findFirst();
    }

    private static final String IMPORT_SELECT = """
        SELECT i.*, NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS uploaded_by_name
          FROM sales.receipt_import i
          JOIN hr.employee e ON e.employee_id = i.uploaded_by
        """;

    private static ImportSummary mapImport(ResultSet rs, int n) throws SQLException {
        return new ImportSummary(rs.getLong("import_id"), rs.getString("file_name"),
            rs.getTimestamp("uploaded_at").toInstant(), rs.getString("uploaded_by_name"), rs.getInt("total_rows"),
            rs.getInt("matched"), rs.getBigDecimal("matched_amount"), rs.getInt("duplicates"),
            rs.getBigDecimal("duplicate_amount"), rs.getInt("unmatched"), rs.getBigDecimal("unmatched_amount"),
            rs.getInt("invalid"), List.of());
    }

    // ── duplicates ─────────────────────────────────────────────────────────

    /**
     * True when this Express document number is already a receipt of ANY source on ANY deal (so a receipt account
     * typed by hand with the same number is never counted twice), or is already in the queue in any status.
     * Compared trimmed and case-insensitively.
     */
    public boolean isKnownDocNo(String docNo) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.payment_receipt WHERE upper(btrim(receipt_ref)) = upper(btrim(:d)))
                OR EXISTS (SELECT 1 FROM sales.receipt_import_row WHERE upper(btrim(doc_no)) = upper(btrim(:d)))
            """, Map.of("d", docNo), Boolean.class));
    }

    // ── the queue ──────────────────────────────────────────────────────────

    public long insertUnmatched(long importId, ParsedReceipt r, String reason) {
        return jdbc.queryForObject("""
            INSERT INTO sales.receipt_import_row (import_id, doc_no, doc_date, customer_name, amount, reference, reason)
            VALUES (:imp, :doc, :date, :cust, :amount, :ref, :reason) RETURNING row_id
            """, new MapSqlParameterSource().addValue("imp", importId).addValue("doc", r.docNo())
                .addValue("date", r.docDate()).addValue("cust", r.customerName()).addValue("amount", r.amount())
                .addValue("ref", r.reference()).addValue("reason", reason), Long.class);
    }

    public List<QueuedRow> findUnmatched() {
        return jdbc.query(ROW_SELECT + " WHERE status = 'UNMATCHED' ORDER BY doc_date, row_id", Map.of(),
            ReceiptImportRepository::mapRow);
    }

    public Optional<QueuedRow> findRow(long rowId) {
        return jdbc.query(ROW_SELECT + " WHERE row_id = :id", Map.of("id", rowId), ReceiptImportRepository::mapRow)
            .stream().findFirst();
    }

    /** The row, locked, so two people attaching it at once cannot both write a receipt. */
    public Optional<QueuedRow> lockRow(long rowId) {
        return jdbc.query(ROW_SELECT + " WHERE row_id = :id FOR UPDATE", Map.of("id", rowId),
            ReceiptImportRepository::mapRow).stream().findFirst();
    }

    public int countUnmatched() {
        return jdbc.queryForObject("SELECT count(*) FROM sales.receipt_import_row WHERE status = 'UNMATCHED'",
            Map.of(), Integer.class);
    }

    public int markResolved(long rowId, long ticketId, long receiptId, long by) {
        return jdbc.update("""
            UPDATE sales.receipt_import_row
               SET status = 'RESOLVED', resolved_ticket_id = :t, receipt_id = :r, resolved_by = :by, resolved_at = now()
             WHERE row_id = :id AND status = 'UNMATCHED'
            """, Map.of("id", rowId, "t", ticketId, "r", receiptId, "by", by));
    }

    public int markIgnored(long rowId, long by) {
        return jdbc.update("""
            UPDATE sales.receipt_import_row SET status = 'IGNORED', resolved_by = :by, resolved_at = now()
             WHERE row_id = :id AND status = 'UNMATCHED'
            """, Map.of("id", rowId, "by", by));
    }

    private static final String ROW_SELECT = """
        SELECT row_id, import_id, doc_no, doc_date, customer_name, amount, reference, reason, status, resolved_ticket_id
          FROM sales.receipt_import_row
        """;

    private static QueuedRow mapRow(ResultSet rs, int n) throws SQLException {
        long t = rs.getLong("resolved_ticket_id");
        Long resolved = rs.wasNull() ? null : t;
        ParsedReceipt r = new ParsedReceipt(0, rs.getString("doc_no"), rs.getObject("doc_date", LocalDate.class),
            rs.getString("customer_name"), rs.getBigDecimal("amount"), rs.getString("reference"));
        return new QueuedRow(rs.getLong("row_id"), rs.getLong("import_id"), r, rs.getString("reason"),
            rs.getString("status"), resolved);
    }

    // ── candidates ─────────────────────────────────────────────────────────

    /** Every ACTIVE deal as a matcher candidate, in ticket id order. */
    public List<CandidateDeal> activeCandidates() {
        Map<Long, Set<String>> docs = new LinkedHashMap<>();
        jdbc.query("""
            SELECT dn.ticket_id, dn.doc_number FROM sales.deposit_notice dn
              JOIN sales.ticket t ON t.ticket_id = dn.ticket_id
             WHERE t.lifecycle = 'ACTIVE' AND dn.doc_number IS NOT NULL
            UNION
            SELECT ri.ticket_id, ri.doc_number FROM sales.remaining_invoice ri
              JOIN sales.ticket t ON t.ticket_id = ri.ticket_id
             WHERE t.lifecycle = 'ACTIVE' AND ri.doc_number IS NOT NULL
            UNION
            SELECT bl.ticket_id, bn.doc_number FROM sales.billing_note_line bl
              JOIN sales.billing_note bn ON bn.id = bl.billing_note_id
              JOIN sales.ticket t ON t.ticket_id = bl.ticket_id
             WHERE t.lifecycle = 'ACTIVE' AND bn.doc_number IS NOT NULL
            """, Map.of(), rs -> {
            docs.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getString(2));
        });
        Map<Long, Set<String>> quotes = new LinkedHashMap<>();
        jdbc.query("""
            SELECT q.ticket_id, q.number FROM sales.quotation q
              JOIN sales.ticket t ON t.ticket_id = q.ticket_id
             WHERE t.lifecycle = 'ACTIVE' AND q.number IS NOT NULL
            """, Map.of(), rs -> {
            quotes.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getString(2));
        });
        Map<Long, List<OpenExpected>> expected = new LinkedHashMap<>();
        jdbc.query("""
            SELECT ep.ticket_id, ep.amount - ep.received_amount, ep.expected_week, ep.kind
              FROM sales.expected_payment ep
              JOIN sales.ticket t ON t.ticket_id = ep.ticket_id
             WHERE t.lifecycle = 'ACTIVE' AND ep.status = 'OPEN'
             ORDER BY ep.ticket_id, ep.expected_week, ep.expected_payment_id
            """, Map.of(), rs -> {
            expected.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new OpenExpected(rs.getBigDecimal(2),
                rs.getObject(3, LocalDate.class), rs.getString(4)));
        });
        return jdbc.query("""
            SELECT t.ticket_id, COALESCE(NULLIF(TRIM(t.title), ''), t.customer_name) AS title, t.customer_name,
                   t.created_by, t.keyed_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS owner_name
              FROM sales.ticket t
              JOIN hr.employee e ON e.employee_id = t.created_by
             WHERE t.lifecycle = 'ACTIVE'
             ORDER BY t.ticket_id
            """, Map.of(), (rs, n) -> {
            long id = rs.getLong("ticket_id");
            long keyed = rs.getLong("keyed_by");
            Long keyedBy = rs.wasNull() ? null : keyed;
            List<OpenExpected> open = expected.getOrDefault(id, List.of());
            Candidate c = new Candidate(id, rs.getString("customer_name"), docs.getOrDefault(id, Set.of()),
                quotes.getOrDefault(id, Set.of()), open.stream().map(OpenExpected::amount).toList());
            return new CandidateDeal(c, rs.getString("title"), rs.getLong("created_by"), keyedBy,
                rs.getString("owner_name"), open);
        });
    }

    /** Bangkok midday of the document date, as the receipt's received_at (its Bangkok date is the document date). */
    static Timestamp receivedAt(LocalDate docDate) {
        return Timestamp.from(docDate.atTime(12, 0).atZone(java.time.ZoneId.of("Asia/Bangkok")).toInstant());
    }
}
