package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DepositNoticeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DepositNoticeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Note templates ────────────────────────────────────────────────────────

    public List<DocumentNoteTemplateDto> findNoteTemplates() {
        return jdbc.query(
            "SELECT note_id, text, default_selected, sort_order FROM sales.document_note_template ORDER BY sort_order",
            Map.of(),
            (rs, i) -> new DocumentNoteTemplateDto(
                rs.getLong("note_id"),
                rs.getString("text"),
                rs.getBoolean("default_selected"),
                rs.getInt("sort_order")
            )
        );
    }

    // ── Deposit notice CRUD ─────────────────────────────────────────────────────────

    public Optional<DepositNoticeDto> findById(long docId) {
        List<DepositNoticeDto> docs = jdbc.query(
            """
            SELECT d.deposit_notice_id, d.ticket_id, d.doc_type, d.version, d.doc_number,
                   d.issue_date, d.status,
                   d.customer_name, d.customer_tax_id, d.customer_address,
                   d.project_name, d.reference, d.currency,
                   d.deposit_percent, d.subtotal, d.deposit_amount,
                   d.vat_percent, d.vat_amount, d.total_payable,
                   d.notes, d.pdf_path, d.xlsx_path,
                   d.issued_by_name, d.preparer_name,
                   d.created_at, d.updated_at
              FROM sales.deposit_notice d
             WHERE d.deposit_notice_id = :id
            """,
            Map.of("id", docId),
            (rs, i) -> mapDoc(rs)
        );
        if (docs.isEmpty()) return Optional.empty();
        DepositNoticeDto doc = docs.get(0);
        List<DepositNoticeItemDto> items = findItems(docId);
        return Optional.of(withItems(doc, items));
    }

    public List<DepositNoticeDto> findByTicket(long ticketId) {
        return jdbc.query(
            """
            SELECT d.deposit_notice_id, d.ticket_id, d.doc_type, d.version, d.doc_number,
                   d.issue_date, d.status,
                   d.customer_name, d.customer_tax_id, d.customer_address,
                   d.project_name, d.reference, d.currency,
                   d.deposit_percent, d.subtotal, d.deposit_amount,
                   d.vat_percent, d.vat_amount, d.total_payable,
                   d.notes, d.pdf_path, d.xlsx_path,
                   d.issued_by_name, d.preparer_name,
                   d.created_at, d.updated_at
              FROM sales.deposit_notice d
             WHERE d.ticket_id = :ticketId
             ORDER BY d.version DESC
            """,
            Map.of("ticketId", ticketId),
            (rs, i) -> withItems(mapDoc(rs), findItems(rs.getLong("deposit_notice_id")))
        );
    }

    /**
     * Owner ruling (2026-09-29): the deposit PERCENTAGE on a deposit notice is LOCKED to the
     * source quotation's own {@code sales.quotation.deposit_percent} — the value sales set when
     * creating the quotation — and is never entered or edited at the deposit-notice stage.
     * {@code deposit_percent} is a nullable SMALLINT whole-number percent (30/50/100); this
     * returns it as a fraction (50 -&gt; 0.50) via {@code BigDecimal.valueOf(pct, 2)}, which is
     * exact for any integer/100 (denominator 100 = 2^2*5^2 always terminates).
     *
     * <p>Pick order deliberately mirrors {@link DepositNoticeService}'s own quotation selection
     * for deposit-notice ITEMS, so the percent this returns always comes from the same row the
     * items would be sourced from:
     * <ul>
     *   <li>legacy customer-quotation chain ({@code origin IS NULL}, {@code pricing_request_id
     *       IS NOT NULL} — the exact scope {@code CustomerQuotationRepository#findByTicket} uses):
     *       ACCEPTED preferred, else ISSUED — the same rule {@code
     *       DepositNoticeService#pickQuotation} applies for items. Read as raw SQL rather than via
     *       {@code CustomerQuotationRepository}'s own DTO because {@code CustomerQuotationDto}
     *       does not carry {@code deposit_percent} at all (it predates that column and nothing
     *       else on that DTO's surface needed it) — adding a field there would widen every one of
     *       that repository's other read paths for a value only this one caller needs.</li>
     *   <li>v2 deal-quotation chain ({@code origin IN ('PRICING_REQUEST','DEAL_DIRECT')}): the
     *       single live quotation, preferring ACCEPTED, then ISSUED, then APPROVED (DEAL_DIRECT's
     *       own only live terminal state), newest by id.</li>
     * </ul>
     * A given ticket's quotations are only ever ALL-legacy or ALL-origin-tagged (each engine
     * stamps {@code origin} consistently for every row it writes), so at most one branch below
     * ever matches — trying legacy first is a fixed, harmless order, not a real preference
     * between the two lineages. Returns empty when there is no live (ACCEPTED/ISSUED/APPROVED)
     * quotation for this ticket, or its {@code deposit_percent} is null — the caller ({@link
     * DepositNoticeService#createDraft}) falls back to 0.50 in that case, this document's
     * long-standing default.
     */
    public Optional<BigDecimal> findDealQuotationDepositPercent(long ticketId) {
        Integer legacyPct = firstOrNull(jdbc.query("""
            SELECT deposit_percent FROM sales.quotation
             WHERE ticket_id = :ticketId AND pricing_request_id IS NOT NULL AND origin IS NULL
               AND doc_status IN ('ACCEPTED','ISSUED')
             ORDER BY CASE doc_status WHEN 'ACCEPTED' THEN 0 ELSE 1 END, quotation_id DESC
             LIMIT 1
            """, Map.of("ticketId", ticketId),
            (rs, i) -> (Integer) rs.getObject("deposit_percent")
        ));
        if (legacyPct != null) {
            return Optional.of(BigDecimal.valueOf(legacyPct, 2));
        }

        Integer v2Pct = firstOrNull(jdbc.query("""
            SELECT deposit_percent FROM sales.quotation
             WHERE ticket_id = :ticketId AND origin IN ('PRICING_REQUEST','DEAL_DIRECT')
               AND doc_status IN ('ACCEPTED','ISSUED','APPROVED')
             ORDER BY CASE doc_status WHEN 'ACCEPTED' THEN 0 WHEN 'ISSUED' THEN 1 ELSE 2 END,
                      quotation_id DESC
             LIMIT 1
            """, Map.of("ticketId", ticketId),
            (rs, i) -> (Integer) rs.getObject("deposit_percent")
        ));
        return v2Pct != null ? Optional.of(BigDecimal.valueOf(v2Pct, 2)) : Optional.empty();
    }

    /** {@code list.stream().findFirst()} throws NPE the moment the single mapped row's own value
     * is {@code null} ({@code Stream.findFirst}/{@code Optional.of} both reject a null element
     * outright) — exactly the case a LIVE quotation with a not-yet-set {@code deposit_percent}
     * hits. A plain index avoids that without changing what "no row" vs "row, null value" mean to
     * the caller (both still read as "nothing usable here"). */
    private static <T> T firstOrNull(List<T> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public long createDraft(long ticketId, DepositNoticeDraftRequest req, List<DepositNoticeItemRequest> items) {
        BigDecimal depositPct = req.depositPercent() != null ? req.depositPercent() : new BigDecimal("0.50");
        BigDecimal vatPct = new BigDecimal("0.07");

        BigDecimal subtotal = items.stream()
            .map(it -> it.netUnitPrice().multiply(it.qty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = subtotal.multiply(depositPct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = deposit.multiply(vatPct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = deposit.add(vat).setScale(2, RoundingMode.HALF_UP);

        int version = nextVersion(ticketId);
        String[] notesArr = req.notes() != null ? req.notes().toArray(String[]::new) : new String[0];

        var params = new MapSqlParameterSource()
            .addValue("ticketId",        ticketId)
            .addValue("version",         version)
            .addValue("customerName",    req.customerName())
            .addValue("customerTaxId",   req.customerTaxId())
            .addValue("customerAddress", req.customerAddress())
            .addValue("projectName",     req.projectName())
            .addValue("reference",       req.reference())
            .addValue("depositPercent",  depositPct)
            .addValue("subtotal",        subtotal)
            .addValue("depositAmount",   deposit)
            .addValue("vatPercent",      vatPct)
            .addValue("vatAmount",       vat)
            .addValue("totalPayable",    total)
            .addValue("notes",           notesArr);

        var keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.deposit_notice
                (ticket_id, version, customer_name, customer_tax_id, customer_address,
                 project_name, reference, deposit_percent, subtotal, deposit_amount,
                 vat_percent, vat_amount, total_payable, notes)
            VALUES
                (:ticketId, :version, :customerName, :customerTaxId, :customerAddress,
                 :projectName, :reference, :depositPercent, :subtotal, :depositAmount,
                 :vatPercent, :vatAmount, :totalPayable, :notes)
            """, params, keys, new String[]{"deposit_notice_id"});

        long docId = keys.getKey().longValue();
        insertItems(docId, items);
        return docId;
    }

    @Transactional
    public void update(long docId, DepositNoticeDraftRequest req) {
        BigDecimal depositPct = req.depositPercent() != null ? req.depositPercent() : new BigDecimal("0.50");
        BigDecimal vatPct = new BigDecimal("0.07");
        List<DepositNoticeItemRequest> items = req.items() != null ? req.items() : List.of();

        BigDecimal subtotal = items.stream()
            .map(it -> it.netUnitPrice().multiply(it.qty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = subtotal.multiply(depositPct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = deposit.multiply(vatPct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = deposit.add(vat).setScale(2, RoundingMode.HALF_UP);

        String[] notesArr = req.notes() != null ? req.notes().toArray(String[]::new) : new String[0];

        jdbc.update("""
            UPDATE sales.deposit_notice SET
                customer_name    = :customerName,
                customer_tax_id  = :customerTaxId,
                customer_address = :customerAddress,
                project_name     = :projectName,
                reference        = :reference,
                deposit_percent  = :depositPercent,
                subtotal         = :subtotal,
                deposit_amount   = :depositAmount,
                vat_amount       = :vatAmount,
                total_payable    = :totalPayable,
                notes            = :notes,
                updated_at       = now()
             WHERE deposit_notice_id = :id AND status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("id",              docId)
                .addValue("customerName",    req.customerName())
                .addValue("customerTaxId",   req.customerTaxId())
                .addValue("customerAddress", req.customerAddress())
                .addValue("projectName",     req.projectName())
                .addValue("reference",       req.reference())
                .addValue("depositPercent",  depositPct)
                .addValue("subtotal",        subtotal)
                .addValue("depositAmount",   deposit)
                .addValue("vatAmount",       vat)
                .addValue("totalPayable",    total)
                .addValue("notes",           notesArr)
        );
        if (!items.isEmpty()) {
            jdbc.update("DELETE FROM sales.deposit_notice_item WHERE deposit_notice_id = :id", Map.of("id", docId));
            insertItems(docId, items);
        }
    }

    /**
     * Assigns the doc number and flips the row to {@code ISSUED} — but only if it is still
     * {@code DRAFT} at the moment this UPDATE runs. {@code DepositNoticeService.issue} calls
     * {@code requireDraft} first, but that is a plain read: roughly 20 lines (ticket lookup,
     * status check, paymentStatus check, lifecycle check) run between that read and this UPDATE,
     * so two concurrent {@code issue()} calls on the same DRAFT row can both pass {@code
     * requireDraft} and both reach here. Without {@code AND status = 'DRAFT'} in the WHERE, the
     * second UPDATE would re-mint a fresh {@code doc_number} in place on an already-issued
     * document — the read-then-write race this predicate closes. It is the only thing that closes
     * it; the service-level check alone cannot, since it reads before either write commits.
     *
     * <p>An empty return means the row was no longer DRAFT when this UPDATE ran (already issued,
     * superseded, etc.) — the caller MUST treat that as a refusal, not silently ignore it. Note
     * that on a refusal the {@code sales.document_sequence} number minted by {@link
     * #nextDocNumber} just above is NOT wasted in production: it rolls back with the enclosing
     * {@code @Transactional} transaction along with everything else in this method.
     *
     * @return the new doc number, or {@link Optional#empty()} if the row was not DRAFT.
     */
    @Transactional
    public Optional<String> issue(long docId, long actorId, String actorName) {
        int thaiYear = Year.now().getValue() + 543;
        String docNumber = nextDocNumber("DEPOSIT_NOTICE", thaiYear);

        int rows = jdbc.update("""
            UPDATE sales.deposit_notice SET
                doc_number     = :num,
                issue_date     = CURRENT_DATE,
                status         = 'ISSUED',
                issued_by_id   = :actorId,
                issued_by_name = :actorName,
                updated_at     = now()
             WHERE deposit_notice_id = :id
               AND status = 'DRAFT'
            """,
            Map.of("num", docNumber, "actorId", actorId, "actorName", actorName, "id", docId));

        if (rows == 0) {
            return Optional.empty();
        }

        // Supersede all older versions for same ticket
        jdbc.update("""
            UPDATE sales.deposit_notice SET status = 'SUPERSEDED', updated_at = now()
             WHERE ticket_id = (SELECT ticket_id FROM sales.deposit_notice WHERE deposit_notice_id = :id)
               AND deposit_notice_id <> :id
               AND status = 'ISSUED'
            """, Map.of("id", docId));

        return Optional.of(docNumber);
    }

    public void setFilePaths(long docId, String pdfPath, String xlsxPath) {
        jdbc.update("""
            UPDATE sales.deposit_notice SET pdf_path = :pdf, xlsx_path = :xlsx, updated_at = now()
             WHERE deposit_notice_id = :id
            """, Map.of("id", docId, "pdf", pdfPath, "xlsx", xlsxPath));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int nextVersion(long ticketId) {
        Integer max = jdbc.queryForObject(
            "SELECT COALESCE(MAX(version), 0) FROM sales.deposit_notice WHERE ticket_id = :t",
            Map.of("t", ticketId), Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    private String nextDocNumber(String docType, int yearTh) {
        // Upsert + atomic increment
        jdbc.update("""
            INSERT INTO sales.document_sequence (doc_type, year_th, last_seq)
            VALUES (:dt, :yr, 0)
            ON CONFLICT (doc_type, year_th) DO NOTHING
            """, Map.of("dt", docType, "yr", yearTh));

        Integer seq = jdbc.queryForObject("""
            UPDATE sales.document_sequence SET last_seq = last_seq + 1
             WHERE doc_type = :dt AND year_th = :yr
            RETURNING last_seq
            """, Map.of("dt", docType, "yr", yearTh), Integer.class);

        return String.format("GLRD%02d%03d", yearTh % 100, seq);
    }

    private void insertItems(long docId, List<DepositNoticeItemRequest> items) {
        for (var it : items) {
            BigDecimal amount = it.netUnitPrice().multiply(it.qty()).setScale(2, RoundingMode.HALF_UP);
            jdbc.update("""
                INSERT INTO sales.deposit_notice_item
                    (deposit_notice_id, seq, description, qty, unit, unit_price,
                     discount_label, net_unit_price, amount)
                VALUES
                    (:docId, :seq, :desc, :qty, :unit, :unitPrice,
                     :discountLabel, :netUnitPrice, :amount)
                """,
                new MapSqlParameterSource()
                    .addValue("docId",         docId)
                    .addValue("seq",           it.seq())
                    .addValue("desc",          it.description())
                    .addValue("qty",           it.qty())
                    .addValue("unit",          it.unit() != null ? it.unit() : "แผ่น")
                    .addValue("unitPrice",     it.unitPrice())
                    .addValue("discountLabel", it.discountLabel())
                    .addValue("netUnitPrice",  it.netUnitPrice())
                    .addValue("amount",        amount)
            );
        }
    }

    private List<DepositNoticeItemDto> findItems(long docId) {
        return jdbc.query(
            "SELECT * FROM sales.deposit_notice_item WHERE deposit_notice_id = :id ORDER BY seq",
            Map.of("id", docId),
            (rs, i) -> new DepositNoticeItemDto(
                rs.getLong("item_id"),
                rs.getInt("seq"),
                rs.getString("description"),
                rs.getBigDecimal("qty"),
                rs.getString("unit"),
                rs.getBigDecimal("unit_price"),
                rs.getString("discount_label"),
                rs.getBigDecimal("net_unit_price"),
                rs.getBigDecimal("amount")
            )
        );
    }

    private DepositNoticeDto mapDoc(ResultSet rs) throws SQLException {
        Array notesArr = rs.getArray("notes");
        List<String> notes = notesArr != null
            ? Arrays.asList((String[]) notesArr.getArray())
            : Collections.emptyList();

        return new DepositNoticeDto(
            rs.getLong("deposit_notice_id"),
            rs.getLong("ticket_id"),
            rs.getString("doc_type"),
            rs.getInt("version"),
            rs.getString("doc_number"),
            rs.getObject("issue_date", LocalDate.class),
            rs.getString("status"),
            rs.getString("customer_name"),
            rs.getString("customer_tax_id"),
            rs.getString("customer_address"),
            rs.getString("project_name"),
            rs.getString("reference"),
            rs.getString("currency"),
            rs.getBigDecimal("deposit_percent"),
            rs.getBigDecimal("subtotal"),
            rs.getBigDecimal("deposit_amount"),
            rs.getBigDecimal("vat_percent"),
            rs.getBigDecimal("vat_amount"),
            rs.getBigDecimal("total_payable"),
            notes,
            rs.getString("pdf_path") != null,
            rs.getString("xlsx_path") != null,
            rs.getString("issued_by_name"),
            rs.getString("preparer_name"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            List.of()
        );
    }

    private DepositNoticeDto withItems(DepositNoticeDto doc, List<DepositNoticeItemDto> items) {
        return new DepositNoticeDto(
            doc.id(), doc.ticketId(), doc.docType(), doc.version(), doc.docNumber(),
            doc.issueDate(), doc.status(), doc.customerName(), doc.customerTaxId(),
            doc.customerAddress(), doc.projectName(), doc.reference(), doc.currency(),
            doc.depositPercent(), doc.subtotal(), doc.depositAmount(),
            doc.vatPercent(), doc.vatAmount(), doc.totalPayable(),
            doc.notes(), doc.hasPdf(), doc.hasXlsx(),
            doc.issuedByName(), doc.preparerName(),
            doc.createdAt(), doc.updatedAt(), items
        );
    }
}
