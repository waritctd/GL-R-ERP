package th.co.glr.hr.salesboard.receipts;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.DealOption;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.FlaggedRowDto;
import th.co.glr.hr.salesboard.receipts.ReceiptImportDtos.ImportSummary;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Candidate;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * sales.receipt_import / receipt_import_row / receipt_import_allocation (V213, V214), the duplicate and manual-twin
 * checks against sales.payment_receipt, and the candidate deals the matcher compares a row with.
 *
 * <p>Candidates are the ACTIVE deals inside ACCOUNT's finance scope — {@link TicketRepository#accountScopePredicate},
 * the predicate account's own deal list runs (review M4) — with every customer name the deal is known by (the
 * ticket's, and the names printed on its ISSUED deposit notices, remaining invoices and billing notes: M5), the
 * numbers of those live documents only (superseded / cancelled / draft documents, and billing-note lines that were
 * RELEASED from their note, never count: M7, N4), its live
 * quotation numbers, and the OPEN remainder of each expected payment.
 */
@Repository
public class ReceiptImportRepository {

    /** A candidate deal plus what the queue shows about it. */
    public record CandidateDeal(Candidate candidate, String title, long ownerId, Long keyedById, String ownerName,
                                List<OpenExpected> openExpected) {}

    /** One OPEN expected payment of a candidate deal; {@code amount} is what is still to come. */
    public record OpenExpected(BigDecimal amount, LocalDate week, String kind) {}

    /** A queued row as stored. {@code suggestedReceiptId}: the MANUAL receipt a POSSIBLE_MANUAL_DUPLICATE row may be. */
    public record QueuedRow(long rowId, long importId, ParsedReceipt receipt, String reason, String status,
                            Long resolvedTicketId, java.time.Instant managerFlaggedAt, Long suggestedReceiptId) {}

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

    /** The import's counts. Called from a finally block, so the stored summary is always what happened (M3). */
    public void finishImport(long importId, ReceiptImportService.Counts c) {
        jdbc.update("""
            UPDATE sales.receipt_import
               SET total_rows = :total, matched = :matched, matched_amount = :ma, matched_manual = :mm,
                   duplicates = :dup, duplicate_amount = :da, unmatched = :un, unmatched_amount = :ua,
                   non_cash = :nc, invalid = :inv, failed = :failed
             WHERE import_id = :id
            """, new MapSqlParameterSource().addValue("id", importId).addValue("total", c.total())
                .addValue("matched", c.matched.count).addValue("ma", c.matched.amount)
                .addValue("mm", c.possibleManual.count)
                .addValue("dup", c.duplicates.count).addValue("da", c.duplicates.amount)
                .addValue("un", c.unmatched.count).addValue("ua", c.unmatched.amount)
                .addValue("nc", c.nonCash.count).addValue("inv", c.invalid).addValue("failed", c.failed));
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
            rs.getInt("matched"), rs.getBigDecimal("matched_amount"), rs.getInt("matched_manual"),
            rs.getInt("duplicates"), rs.getBigDecimal("duplicate_amount"), rs.getInt("unmatched"),
            rs.getBigDecimal("unmatched_amount"), rs.getInt("non_cash"), rs.getInt("invalid"), rs.getInt("failed"),
            List.of());
    }

    // ── duplicates and the manual twin (H1) ────────────────────────────────

    /**
     * True when this Express document number is already known: a receipt of ANY source whose reference it is, a
     * MANUAL receipt already linked to it, or a queued row in any status. Trimmed and case-insensitive.
     */
    public boolean isKnownDocNo(String docNo) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.payment_receipt
                            WHERE upper(btrim(receipt_ref)) = upper(btrim(:d))
                               OR upper(btrim(express_doc_no)) = upper(btrim(:d)))
                OR EXISTS (SELECT 1 FROM sales.receipt_import_row WHERE upper(btrim(doc_no)) = upper(btrim(:d)))
            """, Map.of("d", docNo), Boolean.class));
    }

    /**
     * The MANUAL receipt on this deal that this Express row most likely IS (review H1): same exact amount, a money kind
     * (DEPOSIT / BALANCE, never ADJUSTMENT), received within ±{@value #TWIN_DAYS} days of the document date (Bangkok),
     * and not yet linked to any Express number — nearest date first, then oldest.
     *
     * <p>Not "same kind" literally: an Express row's kind is derived from the deal's state, and the hand-typed receipt
     * has already moved that state (a confirmed deposit makes a later row read as BALANCE), so requiring equal kinds
     * would let exactly the reported double count through.
     */
    public Optional<Long> findManualTwin(long ticketId, BigDecimal amount, LocalDate docDate) {
        return jdbc.query("""
            SELECT receipt_id FROM sales.payment_receipt
             WHERE ticket_id = :t AND source IN ('MANUAL', 'TAX_INVOICE', 'PRE_PORTAL') AND express_doc_no IS NULL
               AND kind IN ('DEPOSIT', 'BALANCE') AND amount = :a
               AND NOT EXISTS (SELECT 1 FROM sales.receipt_import_row w
                                WHERE w.receipt_id = payment_receipt.receipt_id AND w.status = 'RESOLVED')
               AND (source <> 'PRE_PORTAL' OR kind = 'DEPOSIT')
               AND abs((received_at AT TIME ZONE 'Asia/Bangkok')::date - CAST(:d AS date)) <= :days
             ORDER BY abs((received_at AT TIME ZONE 'Asia/Bangkok')::date - CAST(:d AS date)), receipt_id
             LIMIT 1
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("a", amount).addValue("d", docDate)
                .addValue("days", TWIN_DAYS), (rs, n) -> rs.getLong(1)).stream().findFirst();
    }

    public static final int TWIN_DAYS = 7;

    /** Row-locks the receipt for the rest of the transaction, so two rows resolved against it at once serialise. */
    public void lockReceipt(long receiptId) {
        jdbc.queryForList("SELECT receipt_id FROM sales.payment_receipt WHERE receipt_id = :r FOR UPDATE",
            Map.of("r", receiptId));
    }

    /** Is an Express row already RESOLVED against this receipt? A tax invoice's money settles at most one row. */
    public boolean isSettledByRow(long receiptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.receipt_import_row WHERE receipt_id = :r AND status = 'RESOLVED')",
            Map.of("r", receiptId), Boolean.class));
    }

    /** Links the manual receipt to the Express number; only that column changes. 0 when already linked. */
    public int linkManualReceipt(long receiptId, String expressDocNo) {
        return jdbc.update("""
            UPDATE sales.payment_receipt SET express_doc_no = :d
             WHERE receipt_id = :id AND source IN ('MANUAL', 'PRE_PORTAL') AND express_doc_no IS NULL
            """, Map.of("id", receiptId, "d", expressDocNo.trim()));
    }

    // ── the queue ──────────────────────────────────────────────────────────

    public long insertUnmatched(long importId, ParsedReceipt r, String reason) {
        return insertUnmatched(importId, r, reason, null);
    }

    /** Throws DataIntegrityViolationException when the number is already queued (a concurrent upload: review N4). */
    public long insertUnmatched(long importId, ParsedReceipt r, String reason, Long suggestedReceiptId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.receipt_import_row (import_id, doc_no, doc_date, customer_name, amount, reference, reason,
                                                 suggested_receipt_id)
            VALUES (:imp, :doc, :date, :cust, :amount, :ref, :reason, :sug) RETURNING row_id
            """, new MapSqlParameterSource().addValue("imp", importId).addValue("doc", r.docNo().trim())
                .addValue("date", r.docDate()).addValue("cust", r.customerName()).addValue("amount", r.amount())
                .addValue("ref", r.reference()).addValue("reason", reason).addValue("sug", suggestedReceiptId),
            Long.class);
    }

    /** The MANUAL or TAX_INVOICE receipt a queued row may duplicate, with its deal; empty when none or it is no longer one.
     * For a tax invoice {@code reference} is the invoice number and {@code roundNo} its delivery round. */
    public Optional<ReceiptImportDtos.PossibleManual> manualReceipt(long receiptId) {
        return jdbc.query("""
            SELECT r.receipt_id, r.ticket_id, r.kind, r.amount, r.received_at, r.source, r.receipt_ref,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS recorded_by_name,
                   (r.express_doc_no IS NOT NULL) AS already_linked,
                   (SELECT d.round_no FROM sales.delivery_request d WHERE d.receipt_id = r.receipt_id) AS round_no
              FROM sales.payment_receipt r
              JOIN hr.employee e ON e.employee_id = r.recorded_by
             WHERE r.receipt_id = :id AND r.source IN ('MANUAL', 'TAX_INVOICE', 'PRE_PORTAL')
            """, Map.of("id", receiptId), (rs, n) -> {
                Integer round = rs.getObject("round_no", Integer.class);
                return new ReceiptImportDtos.PossibleManual(rs.getLong("receipt_id"),
                    rs.getLong("ticket_id"), rs.getString("kind"), rs.getBigDecimal("amount"),
                    rs.getTimestamp("received_at").toInstant(), rs.getString("recorded_by_name"),
                    rs.getBoolean("already_linked"), rs.getString("source"),
                    "TAX_INVOICE".equals(rs.getString("source")) ? rs.getString("receipt_ref") : null,
                    round);
            }).stream().findFirst();
    }

    public List<QueuedRow> findByStatus(String status) {
        return jdbc.query(ROW_SELECT + " WHERE status = :s ORDER BY doc_date, row_id", Map.of("s", status),
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

    public List<FlaggedRowDto> flaggedUnmatched() {
        return jdbc.query("""
            SELECT row_id, doc_no, doc_date, customer_name, amount, reference, manager_flagged_at
              FROM sales.receipt_import_row
             WHERE status = 'UNMATCHED' AND manager_flagged_at IS NOT NULL
             ORDER BY manager_flagged_at, row_id
            """, Map.of(), (rs, n) -> new FlaggedRowDto(rs.getLong("row_id"), rs.getString("doc_no"),
                rs.getObject("doc_date", LocalDate.class), rs.getString("customer_name"), rs.getBigDecimal("amount"),
                rs.getString("reference"), rs.getTimestamp("manager_flagged_at").toInstant()));
    }

    public int markResolved(long rowId, long ticketId, long receiptId, long by) {
        return jdbc.update("""
            UPDATE sales.receipt_import_row
               SET status = 'RESOLVED', resolved_ticket_id = :t, receipt_id = :r, resolved_by = :by, resolved_at = now()
             WHERE row_id = :id AND status = 'UNMATCHED'
            """, Map.of("id", rowId, "t", ticketId, "r", receiptId, "by", by));
    }

    public void insertAllocation(long rowId, long ticketId, long receiptId, BigDecimal amount) {
        jdbc.update("""
            INSERT INTO sales.receipt_import_allocation (row_id, ticket_id, receipt_id, amount)
            VALUES (:r, :t, :rc, :a)
            """, Map.of("r", rowId, "t", ticketId, "rc", receiptId, "a", amount));
    }

    public int markIgnored(long rowId, long by) {
        return jdbc.update("""
            UPDATE sales.receipt_import_row SET status = 'IGNORED', resolved_by = :by, resolved_at = now()
             WHERE row_id = :id AND status = 'UNMATCHED'
            """, Map.of("id", rowId, "by", by));
    }

    public int markUnignored(long rowId) {
        return jdbc.update("""
            UPDATE sales.receipt_import_row SET status = 'UNMATCHED', resolved_by = NULL, resolved_at = NULL
             WHERE row_id = :id AND status = 'IGNORED'
            """, Map.of("id", rowId));
    }

    public int flagForManager(long rowId, long by) {
        return jdbc.update("""
            UPDATE sales.receipt_import_row SET manager_flagged_at = now(), manager_flagged_by = :by
             WHERE row_id = :id AND status = 'UNMATCHED'
            """, Map.of("id", rowId, "by", by));
    }

    private static final String ROW_SELECT = """
        SELECT row_id, import_id, doc_no, doc_date, customer_name, amount, reference, reason, status, resolved_ticket_id,
               manager_flagged_at, suggested_receipt_id
          FROM sales.receipt_import_row
        """;

    private static QueuedRow mapRow(ResultSet rs, int n) throws SQLException {
        long t = rs.getLong("resolved_ticket_id");
        Long resolved = rs.wasNull() ? null : t;
        Timestamp flagged = rs.getTimestamp("manager_flagged_at");
        ParsedReceipt r = new ParsedReceipt(0, rs.getString("doc_no"), null, rs.getObject("doc_date", LocalDate.class),
            rs.getString("customer_name"), rs.getBigDecimal("amount"), rs.getString("reference"));
        long sug = rs.getLong("suggested_receipt_id");
        Long suggested = rs.wasNull() ? null : sug;
        return new QueuedRow(rs.getLong("row_id"), rs.getLong("import_id"), r, rs.getString("reason"),
            rs.getString("status"), resolved, flagged == null ? null : flagged.toInstant(), suggested);
    }

    // ── account scope, candidates, the picker ──────────────────────────────

    private static MapSqlParameterSource scoped() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        TicketRepository.addAccountScopeParams(p);
        return p;
    }

    private static final String SCOPE = " AND " + TicketRepository.accountScopePredicate() + " ";

    /** Inside account's finance scope AND ACTIVE: the only deals a receipt may be placed on. */
    public boolean isCandidateDeal(long ticketId) {
        MapSqlParameterSource p = scoped().addValue("id", ticketId);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM sales.ticket t WHERE t.ticket_id = :id"
            + " AND t.lifecycle = 'ACTIVE'" + SCOPE, p, Integer.class);
        return n != null && n > 0;
    }

    /** The picker (M5): ACTIVE deals in account's scope whose code / title / customer contains {@code q}, max 20. */
    public List<DealOption> searchDeals(String q) {
        MapSqlParameterSource p = scoped().addValue("q", "%" + (q == null ? "" : q.trim()) + "%");
        return jdbc.query("""
            SELECT t.ticket_id, t.code, COALESCE(NULLIF(TRIM(t.title), ''), t.customer_name) AS title, t.customer_name,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS owner_name
              FROM sales.ticket t
              JOIN hr.employee e ON e.employee_id = t.created_by
             WHERE t.lifecycle = 'ACTIVE'
               AND (t.code ILIKE :q OR t.title ILIKE :q OR t.customer_name ILIKE :q)
            """ + SCOPE + " ORDER BY t.ticket_id DESC LIMIT 20", p,
            (rs, n) -> new DealOption(rs.getLong("ticket_id"), rs.getString("code"), rs.getString("title"),
                rs.getString("customer_name"), rs.getString("owner_name")));
    }

    /** Every candidate deal (ACTIVE, in account's scope), in ticket id order. */
    public List<CandidateDeal> activeCandidates() {
        MapSqlParameterSource p = scoped();
        String deals = "SELECT t.ticket_id FROM sales.ticket t WHERE t.lifecycle = 'ACTIVE'" + SCOPE;
        Map<Long, Set<String>> docs = new LinkedHashMap<>();
        Map<Long, Set<String>> names = new LinkedHashMap<>();
        jdbc.query("""
            SELECT dn.ticket_id, dn.doc_number, dn.customer_name FROM sales.deposit_notice dn
             WHERE dn.status = 'ISSUED' AND dn.ticket_id IN (""" + deals + """
            )
            UNION ALL
            SELECT ri.ticket_id, ri.doc_number, ri.customer_name FROM sales.remaining_invoice ri
             WHERE ri.status = 'ISSUED' AND ri.ticket_id IN (""" + deals + """
            )
            UNION ALL
            SELECT bl.ticket_id, bn.doc_number, bn.customer_name FROM sales.billing_note_line bl
              JOIN sales.billing_note bn ON bn.id = bl.billing_note_id
             WHERE bn.status IN ('ISSUED', 'SETTLED') AND bl.note_status <> 'RELEASED' AND bl.ticket_id IN (""" + deals + """
            )
            """, p, rs -> {
            long id = rs.getLong(1);
            if (rs.getString(2) != null) {
                docs.computeIfAbsent(id, k -> new HashSet<>()).add(rs.getString(2));
            }
            if (rs.getString(3) != null) {
                names.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(rs.getString(3));
            }
        });
        Map<Long, Set<String>> quotes = new LinkedHashMap<>();
        jdbc.query("""
            SELECT q.ticket_id, q.number FROM sales.quotation q
             WHERE q.number IS NOT NULL AND NOT q.is_sample AND q.doc_status NOT IN ('CANCELLED', 'SUPERSEDED', 'REJECTED')
               AND q.ticket_id IN (""" + deals + ")", p, rs -> {
            quotes.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getString(2));
        });
        Map<Long, List<OpenExpected>> expected = new LinkedHashMap<>();
        jdbc.query("""
            SELECT ep.ticket_id, ep.amount - ep.received_amount, ep.expected_week, ep.kind
              FROM sales.expected_payment ep
             WHERE ep.status = 'OPEN' AND ep.ticket_id IN (""" + deals + """
            )
             ORDER BY ep.ticket_id, ep.expected_week, ep.expected_payment_id
            """, p, rs -> {
            expected.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new OpenExpected(rs.getBigDecimal(2),
                rs.getObject(3, LocalDate.class), rs.getString(4)));
        });
        return jdbc.query("""
            SELECT t.ticket_id, COALESCE(NULLIF(TRIM(t.title), ''), t.customer_name) AS title, t.customer_name,
                   t.created_by, t.keyed_by,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS owner_name
              FROM sales.ticket t
              JOIN hr.employee e ON e.employee_id = t.created_by
             WHERE t.lifecycle = 'ACTIVE'""" + SCOPE + " ORDER BY t.ticket_id", p, (rs, n) -> {
            long id = rs.getLong("ticket_id");
            long keyed = rs.getLong("keyed_by");
            Long keyedBy = rs.wasNull() ? null : keyed;
            List<OpenExpected> open = expected.getOrDefault(id, List.of());
            Set<String> allNames = new LinkedHashSet<>();
            if (rs.getString("customer_name") != null) {
                allNames.add(rs.getString("customer_name"));
            }
            allNames.addAll(names.getOrDefault(id, Set.of()));
            Candidate c = new Candidate(id, allNames, docs.getOrDefault(id, Set.of()),
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
