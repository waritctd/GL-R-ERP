package th.co.glr.hr.deposit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.deposit.DealDocumentRows.Counts;
import th.co.glr.hr.deposit.DealDocumentRows.InvoiceRow;
import th.co.glr.hr.deposit.DealDocumentRows.InvoiceRef;
import th.co.glr.hr.deposit.DealDocumentRows.NoticeRef;
import th.co.glr.hr.deposit.DealDocumentRows.NoticeRow;
import th.co.glr.hr.deposit.DealDocumentRows.PendingRevision;
import th.co.glr.hr.deposit.DealDocumentRows.OlderVersion;
import th.co.glr.hr.deposit.DealDocumentRows.Paged;
import th.co.glr.hr.deposit.DealDocumentRows.Origin;
import th.co.glr.hr.deposit.DepositNoticeService.DealDocumentScope;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;

/** The cross-deal reads behind the เอกสารขาย tabs: every statement is filtered IN SQL to the deals the viewer may read
 * (the decision DepositNoticeService#requireTicketViewer makes per deal), so a list never holds a row the per-deal read
 * would refuse. */
@Repository
public class DealDocumentListRepository {

    /** Who is asking, reduced to what the SQL and the write flag need. */
    public record Viewer(DealDocumentScope scope, long id, boolean issuedOnly, boolean writesEverywhere, boolean sales) {}

    private final NamedParameterJdbcTemplate jdbc;

    public DealDocumentListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The deal-level scope plus the draft rule, spliced after a WHERE that already names {@code t} (sales.ticket) and
     * the document alias {@code d}. */
    private static String scopeClause(Viewer v, MapSqlParameterSource params, Collection<Long> ticketIds) {
        StringBuilder sql = new StringBuilder();
        switch (v.scope()) {
            case ALL -> { }
            case OWNER_OR_KEYER -> {
                params.addValue("viewerId", v.id());
                sql.append(" AND (t.created_by = :viewerId OR t.keyed_by = :viewerId)");
            }
            case ACCOUNT -> {
                // requireTicketViewer also admits a lost/cancelled deal holding an ISSUED notice; account sees ISSUED only.
                TicketRepository.addAccountScopeParams(params);
                sql.append(" AND (").append(TicketRepository.accountScopePredicate())
                    .append(" OR (").append(TicketRepository.DEAD_DEAL_SQL)
                    .append(" AND EXISTS (SELECT 1 FROM sales.deposit_notice dn WHERE dn.ticket_id = t.ticket_id")
                    .append(" AND dn.status = 'ISSUED')))");
            }
            case NONE -> sql.append(" AND FALSE");
        }
        if (ticketIds != null) {
            params.addValue("ticketIds", ticketIds);
            sql.append(" AND t.ticket_id IN (:ticketIds)");
        }
        return sql.toString();
    }

    private static String statusClause(Viewer v, String docAlias, String status) {
        String live = v.issuedOnly() ? "'ISSUED'" : "'DRAFT', 'ISSUED'";
        return " AND " + docAlias + ".status IN (" + live + ")"
            + (status == null ? "" : " AND " + docAlias + ".status = :status");
    }

    private static final String LATEST_CONFIRMED_QUOTATION = TicketRepository.confirmedQuotationIdSql("d.ticket_id");

    /** The quotation a notice belongs to: the deal quotation whose number is the notice's reference, else the one the
     * order was confirmed from (createDepositNoticeDraft.js#resolveNoticeQuotation, done on the server). */
    private static final String NOTICE_QUOTATION = """
        LEFT JOIN LATERAL (
            SELECT q.quotation_id, q.number, q.doc_status FROM sales.quotation q
             WHERE q.ticket_id = d.ticket_id
               AND (q.number = d.reference OR q.quotation_id = """ + LATEST_CONFIRMED_QUOTATION + """
)
             ORDER BY COALESCE(q.number = d.reference, FALSE) DESC, q.quotation_id DESC LIMIT 1) rq ON TRUE
        """;

    /** An invoice belongs to its customer_quotation_id; one without (older rows) falls back like a notice. */
    private static final String INVOICE_QUOTATION = """
        LEFT JOIN LATERAL (
            SELECT q.quotation_id, q.number, q.doc_status FROM sales.quotation q
             WHERE q.ticket_id = d.ticket_id
               AND (q.quotation_id = d.customer_quotation_id
                    OR (d.customer_quotation_id IS NULL
                        AND (q.number = d.reference OR q.quotation_id = """ + LATEST_CONFIRMED_QUOTATION + """
)))
             ORDER BY (q.quotation_id = d.customer_quotation_id) DESC NULLS LAST,
                      COALESCE(q.number = d.reference, FALSE) DESC, q.quotation_id DESC LIMIT 1) rq ON TRUE
        """;

    /** Same party lookup as RemainingInvoiceRepository#SELECT_RI, so the name is the one printed. */
    private static final String INVOICE_CUSTOMER = """
        LEFT JOIN LATERAL (
            SELECT c.name FROM sales.quotation q
              JOIN sales.deal_party dp ON dp.party_id = q.party_id
              JOIN customers.customer c ON c.customer_id = dp.customer_id
             WHERE q.ticket_id = d.ticket_id AND q.origin IS NOT NULL
               AND (q.quotation_id = d.customer_quotation_id
                    OR (d.customer_quotation_id IS NULL AND q.number = d.reference))
             ORDER BY q.quotation_id DESC LIMIT 1) qc ON TRUE
        """;

    private static final String OWNER_JOIN = " LEFT JOIN hr.employee e ON e.employee_id = t.created_by ";

    /** The deal's lifecycle as the write gate sees it: a cancelled status counts as CANCELLED. */
    private static final String LIFECYCLE_COL = ", CASE WHEN t.status = 'cancelled' THEN 'CANCELLED' ELSE t.lifecycle END AS lifecycle ";

    private static final String OWNER_COLS = ", t.code AS ticket_code, t.created_by, t.keyed_by, "
        + DepositNoticeRepository.PREPARER_NAME_SQL + " AS owner_name " + LIFECYCLE_COL;

    /** The resolved quotation's id, only when the viewer sees that quotation in GET /api/deal-quotations: account sees
     * customer-facing statuses of deals inside its scope; every other role that reaches here sees the deals it may read.
     * The number is printed on the document anyway, so it is always returned. */
    private static String quotationCols(Viewer v, MapSqlParameterSource params) {
        String visible = "TRUE";
        if (v.scope() == DealDocumentScope.ACCOUNT) {
            TicketRepository.addAccountScopeParams(params);
            params.addValue("quotationStatuses", DealQuotationService.ACCOUNT_VISIBLE_STATUSES);
            visible = "(rq.doc_status IN (:quotationStatuses) AND " + TicketRepository.accountScopePredicate() + ")";
        }
        return ", CASE WHEN " + visible + " THEN rq.quotation_id END AS quotation_id, rq.number AS quotation_number ";
    }

    /** The owner rule AND an ACTIVE deal: the server refuses a write on any other lifecycle with 409. */
    private static boolean mayWrite(Viewer v, ResultSet rs) throws SQLException {
        return DealLifecycle.ACTIVE.equals(rs.getString("lifecycle")) && (v.writesEverywhere() || (v.sales() && (rs.getLong("created_by") == v.id()
            || (rs.getObject("keyed_by") != null && rs.getLong("keyed_by") == v.id()))));
    }

    private static Origin origin(ResultSet rs, String customerName) throws SQLException {
        return new Origin(rs.getLong("ticket_id"), rs.getString("ticket_code"), rs.getString("project_name"),
            customerName, rs.getLong("created_by"), rs.getString("owner_name"),
            (Long) rs.getObject("quotation_id", Long.class), rs.getString("quotation_number"), rs.getString("lifecycle"));
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource().addValue("promoted", TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION);
    }

    /** Live notices (DRAFT/ISSUED as the viewer may see them), newest first; optionally one status. */
    public Paged<NoticeRow> findNotices(Viewer v, String status, int limit, int offset) {
        MapSqlParameterSource params = params();
        if (status != null) params.addValue("status", status);
        params.addValue("limit", limit).addValue("offset", offset);
        int[] total = {0};
        String sql = """
            SELECT COUNT(*) OVER () AS total_rows, d.deposit_notice_id, d.ticket_id, d.status, d.doc_number, d.version, d.issue_date, d.updated_at,
                   d.project_name, d.deposit_percent, d.deposit_amount, d.vat_amount, d.total_payable,
                   CASE WHEN qc.name IS NOT NULL THEN qc.name ELSE d.customer_name END AS customer_name,
                   -- PRE_PORTAL deposits are kind DEPOSIT receipts too, so they count here
                   (SELECT %s FROM sales.payment_receipt
                     WHERE ticket_id = d.ticket_id AND kind IN ('DEPOSIT', 'ADJUSTMENT')) > 0 AS money_received
            """.formatted(TicketRepository.SIGNED_RECEIPT_SUM)
            + OWNER_COLS + quotationCols(v, params) + """
              FROM sales.deposit_notice d
              JOIN sales.ticket t ON t.ticket_id = d.ticket_id
            """ + OWNER_JOIN + DepositNoticeRepository.QUOTATION_CUSTOMER_JOIN + NOTICE_QUOTATION + "WHERE TRUE"
            + statusClause(v, "d", status) + scopeClause(v, params, null)
            + " ORDER BY d.updated_at DESC, d.deposit_notice_id DESC LIMIT :limit OFFSET :offset";
        List<NoticeRow> rows = jdbc.query(sql, params, (rs, n) -> { total[0] = rs.getInt("total_rows"); return new NoticeRow(rs.getLong("deposit_notice_id"), rs.getString("status"),
            rs.getString("doc_number"), rs.getInt("version"), rs.getObject("issue_date", java.time.LocalDate.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class), rs.getBigDecimal("deposit_percent"),
            rs.getBigDecimal("deposit_amount"), rs.getBigDecimal("vat_amount"), rs.getBigDecimal("total_payable"),
            rs.getBoolean("money_received"), origin(rs, rs.getString("customer_name")), mayWrite(v, rs)); });
        // ponytail: a page past the end has no row to carry the total; the UI never asks for one
        return new Paged<>(rows, total[0]);
    }

    /** One invoice row plus what collapsing a draft revision into its ISSUED version needs. */
    private record Raw(InvoiceRow row, String base, Long customerQuotationId) {}

    /**
     * Live invoices as ONE row per document: an ISSUED invoice carries its DRAFT revision (same deal, same quotation) as
     * {@code pendingRevision} instead of a second row, and its SUPERSEDED earlier versions as {@code olderVersions}. A first
     * draft with no issued version stays a DRAFT row. {@code status} filters the collapsed rows.
     */
    public Paged<InvoiceRow> findInvoices(Viewer v, String status, int limit, int offset) {
        MapSqlParameterSource params = params();
        String sql = """
            SELECT d.id, d.ticket_id, d.status, d.doc_number, d.base_number, d.version, d.doc_date, d.updated_at,
                   d.customer_quotation_id, d.project_name, d.items_total, d.deposit_deduction, d.net_amount,
                   d.vat_amount, d.grand_total,
                   CASE WHEN qc.name IS NOT NULL THEN qc.name ELSE d.customer_name END AS customer_name
            """ + OWNER_COLS + quotationCols(v, params) + """
              FROM sales.remaining_invoice d
              JOIN sales.ticket t ON t.ticket_id = d.ticket_id
            """ + OWNER_JOIN + INVOICE_CUSTOMER + INVOICE_QUOTATION + "WHERE TRUE"
            + statusClause(v, "d", null) + scopeClause(v, params, null)
            + " ORDER BY d.updated_at DESC, d.id DESC";
        List<Raw> raw = jdbc.query(sql, params, (rs, n) -> new Raw(new InvoiceRow(rs.getLong("id"), rs.getString("status"),
            rs.getString("doc_number"), rs.getInt("version"), rs.getObject("doc_date", java.time.LocalDate.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class), rs.getBigDecimal("items_total"),
            rs.getBigDecimal("deposit_deduction"), rs.getBigDecimal("net_amount"), rs.getBigDecimal("vat_amount"),
            rs.getBigDecimal("grand_total"), origin(rs, rs.getString("customer_name")), mayWrite(v, rs), null, List.of(),
            (Long) rs.getObject("customer_quotation_id", Long.class)),
            rs.getString("base_number"), (Long) rs.getObject("customer_quotation_id", Long.class)));
        if (raw.isEmpty()) return new Paged<>(List.of(), 0);

        // One more statement for every row's history; the viewer may read the deal, so may read its earlier versions.
        Map<String, List<OlderVersion>> older = new HashMap<>();
        jdbc.query("""
            SELECT ticket_id, base_number, id, doc_number, version, doc_date, grand_total
              FROM sales.remaining_invoice
             WHERE status = 'SUPERSEDED' AND ticket_id IN (:tickets) AND base_number IS NOT NULL
             ORDER BY version DESC
            """, new MapSqlParameterSource("tickets", raw.stream().map(r -> r.row().origin().ticketId()).distinct().toList()),
            rs -> {
                older.computeIfAbsent(rs.getLong("ticket_id") + "|" + rs.getString("base_number"), k -> new ArrayList<>())
                    .add(new OlderVersion(rs.getLong("id"), rs.getString("doc_number"), rs.getInt("version"),
                        rs.getObject("doc_date", java.time.LocalDate.class), rs.getBigDecimal("grand_total")));
            });

        List<Raw> drafts = raw.stream().filter(r -> "DRAFT".equals(r.row().status())).toList();
        List<InvoiceRow> out = new ArrayList<>();
        java.util.Set<Raw> absorbed = new java.util.HashSet<>();
        for (Raw r : raw) {
            final InvoiceRow row = r.row();
            InvoiceRow built = row;
            if ("ISSUED".equals(row.status())) {
                Raw draft = drafts.stream().filter(d -> !absorbed.contains(d)
                    && d.row().origin().ticketId() == row.origin().ticketId()
                    && java.util.Objects.equals(d.customerQuotationId(), r.customerQuotationId())).findFirst().orElse(null);
                if (draft != null) absorbed.add(draft);
                List<OlderVersion> history = older.getOrDefault(row.origin().ticketId() + "|" + r.base(), List.of()).stream()
                    .filter(o -> o.version() < row.version()).toList();
                built = new InvoiceRow(row.id(), row.status(), row.docNumber(), row.version(), row.docDate(), row.updatedAt(),
                    row.itemsTotal(), row.depositDeduction(), row.netAmount(), row.vatAmount(), row.grandTotal(),
                    row.origin(), row.viewerMayWrite(), draft == null ? null
                        : new PendingRevision(draft.row().id(), row.version() + 1), history, row.customerQuotationId());
            }
            out.add(built);
        }
        // the absorbed drafts are not rows of their own
        List<InvoiceRow> rows = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            if (!absorbed.contains(raw.get(i))) rows.add(out.get(i));
        }
        List<InvoiceRow> all = status == null ? rows : rows.stream().filter(r -> status.equals(r.status())).toList();
        // ponytail: collapsing drafts into their issued rows needs every row, so paging is in memory; SQL paging if it gets slow
        return new Paged<>(all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size())), all.size());
    }

    public record LeanNotice(long ticketId, long id, String status, String docNumber, Long quotationId, boolean mayWrite) {}

    public record LeanInvoice(long quotationId, InvoiceRef ref, boolean mayWrite) {}

    /** What a quotation row needs of its deals' notices: ids, status, number and the resolved quotation. No money, owner,
     * customer name; the write flag is the list rows' own rule. */
    public List<LeanNotice> findLeanNotices(Viewer v, Collection<Long> ticketIds) {
        MapSqlParameterSource params = params();
        String sql = "SELECT d.deposit_notice_id, d.ticket_id, d.status, d.doc_number, t.created_by, t.keyed_by " + LIFECYCLE_COL + quotationCols(v, params) + """
              FROM sales.deposit_notice d
              JOIN sales.ticket t ON t.ticket_id = d.ticket_id
            """ + NOTICE_QUOTATION + "WHERE TRUE" + statusClause(v, "d", null) + scopeClause(v, params, ticketIds);
        return jdbc.query(sql, params, (rs, n) -> new LeanNotice(rs.getLong("ticket_id"), rs.getLong("deposit_notice_id"),
            rs.getString("status"), rs.getString("doc_number"), (Long) rs.getObject("quotation_id", Long.class), mayWrite(v, rs)));
    }

    /** Live invoices of those deals that resolve to a quotation the viewer sees; the same lean shape. */
    public List<LeanInvoice> findLeanInvoices(Viewer v, Collection<Long> ticketIds) {
        MapSqlParameterSource params = params();
        String sql = "SELECT d.id, d.status, d.doc_number, d.version, t.created_by, t.keyed_by " + LIFECYCLE_COL + quotationCols(v, params) + """
              FROM sales.remaining_invoice d
              JOIN sales.ticket t ON t.ticket_id = d.ticket_id
            """ + INVOICE_QUOTATION + "WHERE TRUE" + statusClause(v, "d", null) + scopeClause(v, params, ticketIds)
            + " ORDER BY d.version, d.id";
        List<LeanInvoice> rows = jdbc.query(sql, params, (rs, n) -> {
            Long q = (Long) rs.getObject("quotation_id", Long.class);
            return q == null ? null : new LeanInvoice(q, new InvoiceRef(rs.getLong("id"), rs.getString("status"),
                rs.getString("doc_number"), rs.getInt("version")), mayWrite(v, rs));
        }).stream().filter(java.util.Objects::nonNull).toList();
        // A revision draft is stored as version 1 until issued: show the version it will get, and none for a first draft.
        Map<Long, Integer> issuedVersion = new HashMap<>();
        rows.stream().filter(r -> "ISSUED".equals(r.ref().status()))
            .forEach(r -> issuedVersion.merge(r.quotationId(), r.ref().version(), Math::max));
        return rows.stream().map(r -> !"DRAFT".equals(r.ref().status()) ? r
            : new LeanInvoice(r.quotationId(), new InvoiceRef(r.ref().id(), r.ref().status(), r.ref().docNumber(),
                issuedVersion.containsKey(r.quotationId()) ? issuedVersion.get(r.quotationId()) + 1 : null), r.mayWrite())).toList();
    }

    /** all / draft / issued of the viewer's own list; a draft revision counts with the ISSUED invoice it replaces. */
    public Counts countNotices(Viewer v) {
        return counts(v, "sales.deposit_notice", "");
    }

    public Counts countInvoices(Viewer v) {
        return counts(v, "sales.remaining_invoice", """
             AND NOT (d.status = 'DRAFT' AND EXISTS (SELECT 1 FROM sales.remaining_invoice i
                       WHERE i.ticket_id = d.ticket_id AND i.status = 'ISSUED'
                         AND i.customer_quotation_id IS NOT DISTINCT FROM d.customer_quotation_id))""");
    }

    private Counts counts(Viewer v, String table, String extra) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT COUNT(*) AS n, COUNT(*) FILTER (WHERE d.status = 'DRAFT') AS draft,"
            + " COUNT(*) FILTER (WHERE d.status = 'ISSUED') AS issued FROM " + table + " d"
            + " JOIN sales.ticket t ON t.ticket_id = d.ticket_id WHERE TRUE"
            + statusClause(v, "d", null) + extra + scopeClause(v, params, null);
        return jdbc.queryForObject(sql, params, (rs, n) -> new Counts(rs.getInt("n"), rs.getInt("draft"), rs.getInt("issued")));
    }
}
