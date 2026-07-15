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

    @Transactional
    public String issue(long docId, long actorId, String actorName) {
        int thaiYear = Year.now().getValue() + 543;
        String docNumber = nextDocNumber("DEPOSIT_NOTICE", thaiYear);

        jdbc.update("""
            UPDATE sales.deposit_notice SET
                doc_number     = :num,
                issue_date     = CURRENT_DATE,
                status         = 'ISSUED',
                issued_by_id   = :actorId,
                issued_by_name = :actorName,
                updated_at     = now()
             WHERE deposit_notice_id = :id
            """,
            Map.of("num", docNumber, "actorId", actorId, "actorName", actorName, "id", docId));

        // Supersede all older versions for same ticket
        jdbc.update("""
            UPDATE sales.deposit_notice SET status = 'SUPERSEDED', updated_at = now()
             WHERE ticket_id = (SELECT ticket_id FROM sales.deposit_notice WHERE deposit_notice_id = :id)
               AND deposit_notice_id <> :id
               AND status = 'ISSUED'
            """, Map.of("id", docId));

        return docNumber;
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
