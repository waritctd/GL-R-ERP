package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code sales.remaining_invoice}/{@code sales.remaining_invoice_item} (V188) — the
 * STORED ใบแจ้งหนี้ส่วนที่เหลือ (GLA-99 step 2). Mirrors {@code ImportRequestRepository}'s own
 * DRAFT/ISSUED/SUPERSEDED write shape (V154) — compare-and-set on every state transition, a locked
 * predecessor read for revision carry-forward — except numbering: this document keeps ONE
 * {@code base_number} across every revision and only the {@code -<version>} suffix moves, where
 * ImportRequestRepository mints a brand-new number on every issue (see this class's own
 * {@link #issue}/{@link #lockIssuedPredecessor} Javadoc for the difference).
 */
@Repository
public class RemainingInvoiceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RemainingInvoiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_RI = """
        SELECT id, ticket_id, customer_quotation_id, deposit_notice_id, base_number, version,
               doc_number, status, superseded_by_id,
               reference, deposit_reference, doc_date, notes,
               customer_name, customer_tax_id, customer_branch, customer_address, project_name,
               items_total, deposit_deduction, net_amount, vat_amount, grand_total,
               created_by_id, created_by_name, created_at, updated_at,
               issued_by_id, issued_by_name, issued_at
          FROM sales.remaining_invoice
        """;

    public List<RemainingInvoiceDocumentDto> findByTicket(long ticketId) {
        List<RemainingInvoiceDocumentDto> rows = jdbc.query(
            SELECT_RI + " WHERE ticket_id = :id ORDER BY COALESCE(base_number, ''), version",
            Map.of("id", ticketId), (rs, n) -> mapRow(rs));
        return withItems(rows);
    }

    public Optional<RemainingInvoiceDocumentDto> findById(long id) {
        List<RemainingInvoiceDocumentDto> rows = jdbc.query(
            SELECT_RI + " WHERE id = :id", Map.of("id", id), (rs, n) -> mapRow(rs));
        return withItems(rows).stream().findFirst();
    }

    /** One query for every row's items, not one query per row — a deal with several revisions on
     * its document list would otherwise fan out, matching {@code ImportRequestRepository}'s own
     * {@code withItems} reasoning. */
    private List<RemainingInvoiceDocumentDto> withItems(List<RemainingInvoiceDocumentDto> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> ids = rows.stream().map(RemainingInvoiceDocumentDto::id).toList();
        Map<Long, List<RemainingInvoiceDocumentItemDto>> byParent = new LinkedHashMap<>();
        jdbc.query("""
            SELECT id, remaining_invoice_id, seq, description, qty, unit, unit_price,
                   discount_label, net_unit_price, amount
              FROM sales.remaining_invoice_item
             WHERE remaining_invoice_id IN (:ids)
             ORDER BY remaining_invoice_id, seq
            """, Map.of("ids", ids), rs -> {
                byParent.computeIfAbsent(rs.getLong("remaining_invoice_id"), k -> new ArrayList<>())
                    .add(new RemainingInvoiceDocumentItemDto(
                        rs.getLong("id"), rs.getLong("remaining_invoice_id"), rs.getInt("seq"),
                        rs.getString("description"), rs.getBigDecimal("qty"), rs.getString("unit"),
                        rs.getBigDecimal("unit_price"), rs.getString("discount_label"),
                        rs.getBigDecimal("net_unit_price"), rs.getBigDecimal("amount")));
            });
        return rows.stream().map(r -> withItems(r, byParent.getOrDefault(r.id(), List.of()))).toList();
    }

    private static RemainingInvoiceDocumentDto withItems(RemainingInvoiceDocumentDto r,
            List<RemainingInvoiceDocumentItemDto> items) {
        return new RemainingInvoiceDocumentDto(r.id(), r.ticketId(), r.customerQuotationId(),
            r.depositNoticeId(), r.baseNumber(), r.version(), r.docNumber(), r.status(), r.supersededById(),
            r.reference(), r.depositReference(), r.docDate(), r.notes(),
            r.customerName(), r.customerTaxId(), r.customerBranch(), r.customerAddress(), r.projectName(),
            r.itemsTotal(), r.depositDeduction(), r.netAmount(), r.vatAmount(), r.grandTotal(),
            r.createdById(), r.createdByName(), r.createdAt(), r.updatedAt(),
            r.issuedById(), r.issuedByName(), r.issuedAt(), items);
    }

    @SuppressWarnings("unchecked")
    private static RemainingInvoiceDocumentDto mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Array notesArr = rs.getArray("notes");
        List<String> notes = notesArr == null ? List.of() : List.of((String[]) notesArr.getArray());
        return new RemainingInvoiceDocumentDto(
            rs.getLong("id"), rs.getLong("ticket_id"),
            (Long) rs.getObject("customer_quotation_id"), (Long) rs.getObject("deposit_notice_id"),
            rs.getString("base_number"), rs.getInt("version"), rs.getString("doc_number"), rs.getString("status"),
            (Long) rs.getObject("superseded_by_id"),
            rs.getString("reference"), rs.getString("deposit_reference"), localDate(rs, "doc_date"), notes,
            rs.getString("customer_name"), rs.getString("customer_tax_id"), rs.getString("customer_branch"),
            rs.getString("customer_address"), rs.getString("project_name"),
            rs.getBigDecimal("items_total"), rs.getBigDecimal("deposit_deduction"),
            rs.getBigDecimal("net_amount"), rs.getBigDecimal("vat_amount"), rs.getBigDecimal("grand_total"),
            (Long) rs.getObject("created_by_id"), rs.getString("created_by_name"),
            offsetDateTime(rs, "created_at"), offsetDateTime(rs, "updated_at"),
            (Long) rs.getObject("issued_by_id"), rs.getString("issued_by_name"), offsetDateTime(rs, "issued_at"),
            List.of());
    }

    private static LocalDate localDate(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static OffsetDateTime offsetDateTime(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    // ── Writes ────────────────────────────────────────────────────────────────────────────────

    public long insertDraft(long ticketId, Long customerQuotationId, Long depositNoticeId,
            String reference, String depositReference, LocalDate docDate, List<String> notes,
            String customerName, String customerTaxId, String customerBranch, String customerAddress,
            String projectName, BigDecimal itemsTotal, BigDecimal depositDeduction, BigDecimal netAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, long actorId, String actorName) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.remaining_invoice
                (ticket_id, customer_quotation_id, deposit_notice_id, status,
                 reference, deposit_reference, doc_date, notes,
                 customer_name, customer_tax_id, customer_branch, customer_address, project_name,
                 items_total, deposit_deduction, net_amount, vat_amount, grand_total,
                 created_by_id, created_by_name)
            VALUES (:ticketId, :quotationId, :depositNoticeId, 'DRAFT',
                    :reference, :depositReference, :docDate, :notes,
                    :customerName, :customerTaxId, :customerBranch, :customerAddress, :projectName,
                    :itemsTotal, :depositDeduction, :netAmount, :vatAmount, :grandTotal,
                    :actorId, :actorName)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId).addValue("quotationId", customerQuotationId)
                .addValue("depositNoticeId", depositNoticeId)
                .addValue("reference", reference).addValue("depositReference", depositReference)
                .addValue("docDate", docDate)
                .addValue("notes", notes == null ? new String[0] : notes.toArray(new String[0]))
                .addValue("customerName", customerName).addValue("customerTaxId", customerTaxId)
                .addValue("customerBranch", customerBranch).addValue("customerAddress", customerAddress)
                .addValue("projectName", projectName)
                .addValue("itemsTotal", itemsTotal).addValue("depositDeduction", depositDeduction)
                .addValue("netAmount", netAmount).addValue("vatAmount", vatAmount).addValue("grandTotal", grandTotal)
                .addValue("actorId", actorId).addValue("actorName", actorName),
            keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    /** Replaces every line, renumbering {@code seq} from 1 in the order supplied. Draft-only by
     * convention (the caller never invokes this on an ISSUED/SUPERSEDED row — an issued document's
     * items are frozen for good, see {@link RemainingInvoiceDocumentDto}'s own Javadoc). */
    public void replaceItems(long remainingInvoiceId, List<RemainingInvoiceItemDto> items) {
        jdbc.update("DELETE FROM sales.remaining_invoice_item WHERE remaining_invoice_id = :id",
            Map.of("id", remainingInvoiceId));
        int seq = 1;
        for (RemainingInvoiceItemDto it : items) {
            jdbc.update("""
                INSERT INTO sales.remaining_invoice_item
                    (remaining_invoice_id, seq, description, qty, unit, unit_price, discount_label,
                     net_unit_price, amount)
                VALUES (:parent, :seq, :description, :qty, :unit, :unitPrice, :discountLabel,
                        :netUnitPrice, :amount)
                """,
                new MapSqlParameterSource()
                    .addValue("parent", remainingInvoiceId)
                    .addValue("seq", seq++)
                    .addValue("description", it.description())
                    .addValue("qty", it.qty()).addValue("unit", it.unit())
                    .addValue("unitPrice", it.unitPrice()).addValue("discountLabel", it.discountLabel())
                    .addValue("netUnitPrice", it.netUnitPrice()).addValue("amount", it.amount()));
        }
    }

    /** DRAFT-only, UNCONDITIONAL set of the dialog fields — the WHERE clause enforces DRAFT-only,
     * not the caller. Deliberately NOT a SQL-level COALESCE PATCH: {@code
     * DepositNoticeRepository#update} (the sibling document's own draft-edit write) is itself a
     * plain unconditional overwrite, not a partial one, and matching that avoids a real footgun —
     * binding a Java {@code null} against this column's {@code TEXT[]} type without an explicit SQL
     * type hint is a known pgjdbc trap ("Invalid column type"), which a COALESCE(:notes, notes)
     * would reintroduce for no benefit. {@link RemainingInvoiceService#updateDraft} resolves "leave
     * this field alone" itself, by carrying the EXISTING row's value forward for any request field
     * left {@code null}, before calling this method — so this method always receives concrete
     * values, the same discipline {@code DepositNoticeService#update}'s own caller already follows. */
    public int updateDraftFields(long id, String reference, String depositReference,
            LocalDate docDate, List<String> notes) {
        return jdbc.update("""
            UPDATE sales.remaining_invoice
               SET reference = :reference, deposit_reference = :depositReference, doc_date = :docDate,
                   notes = :notes, updated_at = now()
             WHERE id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reference", reference).addValue("depositReference", depositReference)
                .addValue("docDate", docDate)
                .addValue("notes", notes == null ? new String[0] : notes.toArray(new String[0])));
    }

    /** Re-snapshot the priced content (items applied separately via {@link #replaceItems}) — DRAFT
     * only. Used by {@code RemainingInvoiceService#updateDraft}'s own re-snapshot-from-the-latest-
     * issued-deposit-notice step (plan step 2). */
    public int updateSnapshot(long id, Long depositNoticeId, String customerName, String customerTaxId,
            String customerBranch, String customerAddress, String projectName,
            BigDecimal itemsTotal, BigDecimal depositDeduction, BigDecimal netAmount,
            BigDecimal vatAmount, BigDecimal grandTotal) {
        return jdbc.update("""
            UPDATE sales.remaining_invoice
               SET deposit_notice_id = :depositNoticeId,
                   customer_name = :customerName, customer_tax_id = :customerTaxId,
                   customer_branch = :customerBranch, customer_address = :customerAddress,
                   project_name = :projectName,
                   items_total = :itemsTotal, deposit_deduction = :depositDeduction,
                   net_amount = :netAmount, vat_amount = :vatAmount, grand_total = :grandTotal,
                   updated_at = now()
             WHERE id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource()
                .addValue("id", id).addValue("depositNoticeId", depositNoticeId)
                .addValue("customerName", customerName).addValue("customerTaxId", customerTaxId)
                .addValue("customerBranch", customerBranch).addValue("customerAddress", customerAddress)
                .addValue("projectName", projectName)
                .addValue("itemsTotal", itemsTotal).addValue("depositDeduction", depositDeduction)
                .addValue("netAmount", netAmount).addValue("vatAmount", vatAmount).addValue("grandTotal", grandTotal));
    }

    /** One (deal, source quotation)'s currently-ISSUED base_number/version, locked {@code FOR
     * UPDATE} for the remainder of the caller's transaction — read so a revision's issue can carry
     * the SAME base_number forward and bump only version, and locked so a concurrent issue racing
     * on the same predecessor cannot be lost between this read and the supersede that follows it
     * (same race {@code ImportRequestRepository#lockIssuedPredecessor}'s own Javadoc documents).
     * Empty when this is the FIRST issue for this (deal, quotation) — nothing to carry forward, the
     * caller mints a fresh base_number via {@link #nextDocNumber} instead. */
    public record PredecessorNumber(long id, String baseNumber, int version) {}

    public Optional<PredecessorNumber> lockIssuedPredecessor(long ticketId, Long customerQuotationId) {
        String where = customerQuotationId == null
            ? "ticket_id = :ticketId AND customer_quotation_id IS NULL AND status = 'ISSUED'"
            : "ticket_id = :ticketId AND customer_quotation_id = :quotationId AND status = 'ISSUED'";
        return jdbc.query("SELECT id, base_number, version FROM sales.remaining_invoice WHERE " + where + " FOR UPDATE",
            new MapSqlParameterSource().addValue("ticketId", ticketId).addValue("quotationId", customerQuotationId),
            rs -> {
                if (!rs.next()) return Optional.<PredecessorNumber>empty();
                return Optional.of(new PredecessorNumber(rs.getLong("id"), rs.getString("base_number"), rs.getInt("version")));
            });
    }

    /** Compare-and-set DRAFT -> ISSUED. Returns rows affected; 0 means someone else issued,
     * revised away, or deleted this DRAFT first — the caller must treat that as a 409, never as
     * success (same discipline {@code DepositNoticeRepository#issue}/{@code
     * ImportRequestRepository#issue} already document). */
    public int issue(long id, String baseNumber, int version, String docNumber, long actorId, String actorName) {
        return jdbc.update("""
            UPDATE sales.remaining_invoice
               SET status = 'ISSUED', base_number = :baseNumber, version = :version, doc_number = :docNumber,
                   issued_by_id = :actorId, issued_by_name = :actorName, issued_at = now(), updated_at = now()
             WHERE id = :id AND status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", id).addValue("baseNumber", baseNumber)
                .addValue("version", version).addValue("docNumber", docNumber)
                .addValue("actorId", actorId).addValue("actorName", actorName));
    }

    public int supersede(long oldId, long newId) {
        return jdbc.update("""
            UPDATE sales.remaining_invoice
               SET status = 'SUPERSEDED', superseded_by_id = :newId, updated_at = now()
             WHERE id = :oldId AND status = 'ISSUED'
            """, new MapSqlParameterSource().addValue("oldId", oldId).addValue("newId", newId));
    }

    public int deleteDraft(long id) {
        return jdbc.update("DELETE FROM sales.remaining_invoice WHERE id = :id AND status = 'DRAFT'", Map.of("id", id));
    }

    /** Next {@code GLR<yy><5-digit seq>} for the Buddhist year, from the shared {@code
     * sales.document_sequence} (doc_type {@code 'AR_GLR'}) — mechanism identical to {@code
     * DepositNoticeRepository#nextDocNumber}/{@code ImportRequestRepository#nextDocNumber}. Must be
     * called INSIDE the issuing transaction so a refused issue rolls the number back rather than
     * burning it (the same property those two methods document). */
    public String nextDocNumber(int yearTh) {
        jdbc.update("""
            INSERT INTO sales.document_sequence (doc_type, year_th, last_seq)
            VALUES ('AR_GLR', :y, 0) ON CONFLICT DO NOTHING
            """, Map.of("y", yearTh));
        Integer seq = jdbc.queryForObject("""
            UPDATE sales.document_sequence SET last_seq = last_seq + 1
             WHERE doc_type = 'AR_GLR' AND year_th = :y
            RETURNING last_seq
            """, Map.of("y", yearTh), Integer.class);
        return String.format("GLR%02d%05d", yearTh % 100, seq);
    }

}
