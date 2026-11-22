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

/** Persistence for sales.remaining_invoice/sales.remaining_invoice_item (V188) — the STORED
 * ใบแจ้งหนี้ส่วนที่เหลือ (GLA-99 step 2). */
@Repository
public class RemainingInvoiceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RemainingInvoiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The customer header is the QUOTATION's party customer whenever the reference names a new-engine quotation of this
     * deal that has a party (a deal can hold several BUYER parties, and the stored snapshot may name the headline one);
     * the stored columns are used only when no such quotation resolves (legacy reference, no party). */
    private static final String SELECT_RI = """
        SELECT r.id, r.ticket_id, r.customer_quotation_id, r.deposit_notice_id, r.base_number, r.version,
               r.doc_number, r.status, r.superseded_by_id,
               r.reference, r.deposit_reference, r.doc_date, r.notes,
               CASE WHEN qc.name IS NOT NULL THEN qc.name ELSE r.customer_name END AS customer_name,
               CASE WHEN qc.name IS NOT NULL THEN qc.tax_id ELSE r.customer_tax_id END AS customer_tax_id,
               CASE WHEN qc.name IS NOT NULL THEN qc.branch ELSE r.customer_branch END AS customer_branch,
               CASE WHEN qc.name IS NOT NULL THEN qc.address ELSE r.customer_address END AS customer_address,
               r.project_name,
               r.items_total, r.deposit_deduction, r.net_amount, r.vat_amount, r.grand_total,
               r.created_by_id, r.created_by_name, r.preparer_id, r.preparer_name, r.created_at, r.updated_at,
               r.issued_by_id, r.issued_by_name, r.issued_at
          FROM sales.remaining_invoice r
          LEFT JOIN LATERAL (
              SELECT c.name, c.tax_id, c.address, c.branch
                FROM sales.quotation q
                JOIN sales.deal_party dp ON dp.party_id = q.party_id
                JOIN customers.customer c ON c.customer_id = dp.customer_id
               WHERE q.ticket_id = r.ticket_id AND q.origin IS NOT NULL
                 AND (q.quotation_id = r.customer_quotation_id
                      OR (r.customer_quotation_id IS NULL AND q.number = r.reference))
               ORDER BY q.quotation_id DESC LIMIT 1) qc ON TRUE
        """;

    public List<RemainingInvoiceDocumentDto> findByTicket(long ticketId) {
        List<RemainingInvoiceDocumentDto> rows = jdbc.query(
            SELECT_RI + " WHERE r.ticket_id = :id ORDER BY COALESCE(r.base_number, ''), r.version",
            Map.of("id", ticketId), (rs, n) -> mapRow(rs));
        return withItems(rows);
    }

    public Optional<RemainingInvoiceDocumentDto> findById(long id) {
        List<RemainingInvoiceDocumentDto> rows = jdbc.query(
            SELECT_RI + " WHERE r.id = :id", Map.of("id", id), (rs, n) -> mapRow(rs));
        return withItems(rows).stream().findFirst();
    }

    /** One query for every row's items, not one query per row — a deal with several revisions on its document list
     * would otherwise fan out, matching ImportRequestRepository's own withItems reasoning. */
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
            r.createdById(), r.createdByName(), r.preparerId(), r.preparerName(), r.createdAt(), r.updatedAt(),
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
            rs.getString("customer_name"), rs.getString("customer_tax_id"),
            th.co.glr.hr.customer.CustomerPrintRule.branch(rs.getString("customer_branch"), rs.getString("customer_tax_id")),
            th.co.glr.hr.customer.CustomerPrintRule.storedAddress(rs.getString("customer_address"), rs.getString("customer_tax_id")),
            rs.getString("project_name"),
            rs.getBigDecimal("items_total"), rs.getBigDecimal("deposit_deduction"),
            rs.getBigDecimal("net_amount"), rs.getBigDecimal("vat_amount"), rs.getBigDecimal("grand_total"),
            (Long) rs.getObject("created_by_id"), rs.getString("created_by_name"),
            (Long) rs.getObject("preparer_id"), rs.getString("preparer_name"),
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
                 created_by_id, created_by_name, preparer_id, preparer_name)
            VALUES (:ticketId, :quotationId, :depositNoticeId, 'DRAFT',
                    :reference, :depositReference, :docDate, :notes,
                    :customerName, :customerTaxId, :customerBranch, :customerAddress, :projectName,
                    :itemsTotal, :depositDeduction, :netAmount, :vatAmount, :grandTotal,
                    :actorId, :actorName,
                    -- ผู้จัดทำ defaults to the deal's owner (the actor only when the ticket has none)
                    COALESCE((SELECT t.created_by FROM sales.ticket t WHERE t.ticket_id = :ticketId), :actorId),
                    COALESCE((SELECT {NAME}
                                FROM sales.ticket t JOIN hr.employee e ON e.employee_id = t.created_by
                               WHERE t.ticket_id = :ticketId), :actorName))
            """.replace("{NAME}", DepositNoticeRepository.PREPARER_NAME_SQL),
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

    /** A revision keeps the preparer chosen on the document it corrects. */
    public void copyPreparer(long id, long preparerId, String preparerName) {
        jdbc.update("UPDATE sales.remaining_invoice SET preparer_id = :p, preparer_name = :n WHERE id = :id",
            Map.of("p", preparerId, "n", preparerName == null ? "" : preparerName, "id", id));
    }

    /** Replaces every line, renumbering seq from 1 in the order supplied. Callers use it on DRAFT and ISSUED rows
     * only (an issued one is edited in place); a SUPERSEDED row's items stay frozen. */
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

    /** UNCONDITIONAL set of the dialog fields on a DRAFT or an ISSUED row (an issued one is edited in place) — the
     * WHERE clause refuses SUPERSEDED, not the caller. */
    public int updateDraftFields(long id, String reference, String depositReference,
            LocalDate docDate, List<String> notes, Long preparerId, String expectedStatus) {
        return jdbc.update("""
            UPDATE sales.remaining_invoice
               SET reference = :reference, deposit_reference = :depositReference, doc_date = :docDate,
                   notes = :notes,
                   -- a null preparerId keeps the stored preparer
                   preparer_id = COALESCE(CAST(:preparerId AS bigint), preparer_id),
                   preparer_name = CASE WHEN CAST(:preparerId AS bigint) IS NULL THEN preparer_name ELSE
                       (SELECT {NAME}
                          FROM hr.employee e WHERE e.employee_id = :preparerId) END,
                   updated_at = now()
             WHERE id = :id AND status = :expectedStatus
            """.replace("{NAME}", DepositNoticeRepository.PREPARER_NAME_SQL), new MapSqlParameterSource()
                .addValue("id", id).addValue("expectedStatus", expectedStatus)
                .addValue("reference", reference).addValue("depositReference", depositReference)
                .addValue("docDate", docDate).addValue("preparerId", preparerId)
                .addValue("notes", notes == null ? new String[0] : notes.toArray(new String[0])));
    }

    /** Re-snapshot the priced content (items applied separately via #replaceItems) — DRAFT or ISSUED (an issued
     * invoice follows an edited deposit notice), never SUPERSEDED. Used by RemainingInvoiceService#updateDraft's own
     * re-snapshot step and DepositNoticeService#refreshRemainingInvoices. */
    public int updateSnapshot(long id, Long depositNoticeId, String customerName, String customerTaxId,
            String customerBranch, String customerAddress, String projectName,
            BigDecimal itemsTotal, BigDecimal depositDeduction, BigDecimal netAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String expectedStatus) {
        return jdbc.update("""
            UPDATE sales.remaining_invoice
               SET deposit_notice_id = :depositNoticeId,
                   customer_name = :customerName, customer_tax_id = :customerTaxId,
                   customer_branch = :customerBranch, customer_address = :customerAddress,
                   project_name = :projectName,
                   items_total = :itemsTotal, deposit_deduction = :depositDeduction,
                   net_amount = :netAmount, vat_amount = :vatAmount, grand_total = :grandTotal,
                   updated_at = now()
             WHERE id = :id AND status = :expectedStatus
            """, new MapSqlParameterSource()
                .addValue("id", id).addValue("expectedStatus", expectedStatus).addValue("depositNoticeId", depositNoticeId)
                .addValue("customerName", customerName).addValue("customerTaxId", customerTaxId)
                .addValue("customerBranch", customerBranch).addValue("customerAddress", customerAddress)
                .addValue("projectName", projectName)
                .addValue("itemsTotal", itemsTotal).addValue("depositDeduction", depositDeduction)
                .addValue("netAmount", netAmount).addValue("vatAmount", vatAmount).addValue("grandTotal", grandTotal));
    }

    /** One's currently-ISSUED base_number/version, locked FOR UPDATE for the remainder of the caller's transaction.
     * Empty when this is the FIRST issue for this (deal, quotation) — nothing to carry forward, the caller mints a
     * fresh base_number via #nextDocNumber instead. */
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

    /** Compare-and-set DRAFT -> ISSUED. Returns rows affected; 0 means someone else issued, revised away, or
     * deleted this DRAFT first — the caller must treat that as a 409, never as success (same discipline
     * DepositNoticeRepository#issue/ImportRequestRepository#issue already document). */
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

    /** Next GLR<yy><5-digit seq> for the Buddhist year, from the shared sales.document_sequence (doc_type 'AR_GLR')
     * — mechanism identical to DepositNoticeRepository#nextDocNumber/ImportRequestRepository#nextDocNumber. */
    public String nextDocNumber(int yearTh) {
        return th.co.glr.hr.common.ArGlrSequence.next(jdbc, yearTh);
    }

}
