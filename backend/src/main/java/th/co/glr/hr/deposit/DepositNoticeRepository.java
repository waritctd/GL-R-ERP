package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.dealquotation.DealQuotationRenderAdapter;
import th.co.glr.hr.ticket.QuotationRenderer;

@Repository
public class DepositNoticeRepository {
    static final String NOT_DRAFT = "ใบแจ้งรับมัดจำนี้ไม่ได้อยู่ในสถานะร่าง (DRAFT)";
    /** A SUPERSEDED notice/invoice is read-only: its replacement is the live document. */
    static final String SUPERSEDED = "เอกสารนี้ถูกแทนที่ด้วยฉบับใหม่แล้ว แก้ไขไม่ได้";
    static final String EDITED_ELSEWHERE = "ใบแจ้งรับมัดจำนี้ถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง";

    private final NamedParameterJdbcTemplate jdbc;

    public DepositNoticeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Note templates ────────────────────────────────────────────────────────

    /** The ผู้จัดทำ name of the employee aliased {@code e}: the Thai name, else the English one (null when neither). The
     * one definition both repositories splice into their SQL as {@code {NAME}}. */
    public static final String PREPARER_NAME_SQL =
        "COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''),"
        + " NULLIF(TRIM(CONCAT_WS(' ', e.first_name_en, e.last_name_en)), ''))";

    /** Who may be named ผู้จัดทำ on this deal's documents: its owner, its keyer, then the sales managers (the same
     * people as CommissionRepository#findSalesManagerApproverEmployeeIds), active only, each once. */
    public List<DocumentPreparerOption> findPreparerOptions(long ticketId) {
        String sql = "SELECT e.employee_id, COALESCE(" + PREPARER_NAME_SQL + ", '#' || e.employee_id) AS name"
            + " FROM (SELECT employee_id, MIN(ord) AS ord FROM ("
            + "   SELECT created_by AS employee_id, 1 AS ord FROM sales.ticket WHERE ticket_id = :ticketId"
            + "   UNION ALL SELECT keyed_by, 2 FROM sales.ticket WHERE ticket_id = :ticketId"
            + "   UNION ALL SELECT e.employee_id, 3 " + CommissionRepository.SALES_MANAGER_FROM_WHERE
            + " ) cand WHERE employee_id IS NOT NULL GROUP BY employee_id) c"
            + " JOIN hr.employee e ON e.employee_id = c.employee_id AND e.is_active = TRUE"
            + " ORDER BY c.ord, e.employee_id";
        return jdbc.query(sql, Map.of("ticketId", ticketId),
            (rs, i) -> new DocumentPreparerOption(rs.getLong("employee_id"), rs.getString("name")));
    }

    /** The preparer's own signature image (hr.employee_signature), looked up live at render time like the quotation's. */
    public Optional<th.co.glr.hr.dealquotation.EmployeeSignatureRepository.SignatureImage> findPreparerSignature(Long employeeId) {
        return employeeId == null ? Optional.empty()
            : new th.co.glr.hr.dealquotation.EmployeeSignatureRepository(jdbc).find(employeeId);
    }

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
                   d.issued_by_name, d.preparer_name, d.preparer_id,
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
                   d.issued_by_name, d.preparer_name, d.preparer_id,
                   d.created_at, d.updated_at
              FROM sales.deposit_notice d
             WHERE d.ticket_id = :ticketId
             ORDER BY d.version DESC
            """,
            Map.of("ticketId", ticketId),
            (rs, i) -> withItems(mapDoc(rs), findItems(rs.getLong("deposit_notice_id")))
        );
    }

    /** The quotation a ticket-level deposit notice bills: always its percent (a fraction,
     * 30 -&gt; 0.30); its lines and its number as the reference unless the caller or legacy
     * approved_price rows supply the lines. See {@link #findGoverningQuotation}. */
    public record GoverningQuotation(long quotationId, boolean legacy, String number, BigDecimal depositPercent) {}

    /**
     * The ONE pick of the quotation a ticket-level deposit notice bills, across both engines, so
     * when it supplies the lines they and the percent come from the same row. The PRICING-REQUEST
     * route ({@code OrderConfirmationService#createDepositNoticeFromQuotation}) already knows its
     * quotation and does not call this.
     *
     * <p>Live rows: a legacy pricing-chain quotation ({@code origin IS NULL}, {@code
     * pricing_request_id IS NOT NULL}, the scope {@code CustomerQuotationRepository#findByTicket}
     * uses) that is ACCEPTED or ISSUED, a {@code PRICING_REQUEST}-origin one that is ACCEPTED or
     * ISSUED, or a {@code DEAL_DIRECT} one that is ACCEPTED, ISSUED or APPROVED (DEAL_DIRECT
     * ACCEPTED/ISSUED are admitted but no live route produces them). APPROVED is
     * DEAL_DIRECT only: a PRICING_REQUEST approval lands on ISSUED ({@code DealQuotationRepository
     * #approve}). Engines are exclusive per pricing request, not per ticket, so one ticket can hold
     * both.
     *
     * <p>A case needing no pre-lock data: a DEAL_DIRECT quotation stays revisable after its order
     * is confirmed ({@code DealQuotationService#requireRevisableStatus}), and once that revision
     * is APPROVED it governs the ticket-route deposit while the deal's ticket_item rows still hold
     * the confirmed quotation's lines. (Revisions are planned to lock after confirm.)
     *
     * <p>Ranking: the latest quotation the customer ANSWERED wins: ACCEPTED on either engine, or
     * APPROVED on a DEAL_DIRECT quotation (its terminal state in practice). Only when
     * nothing was answered does the newest ISSUED row win. "Latest" is {@code quotation_id DESC}:
     * one sequence across every engine and pricing request, set at creation. Not {@code
     * accepted_at}/{@code approved_at}: those time different events (a customer answer against an
     * internal approval), and either can be NULL on older or imported rows.
     *
     * <p>Percent: what that quotation's PDF printed. A legacy row is always {@link
     * QuotationRenderer#LEGACY_DEPOSIT_PERCENT}: the legacy PDF never reads the stored column, so
     * this does not either. A new-engine row is its own {@code deposit_percent}, a NULL one {@link
     * DealQuotationRenderAdapter#DEFAULT_DEPOSIT_PERCENT}. Empty when the ticket has no live
     * quotation; {@link DepositNoticeService#createDraft} then bills 0.50.
     */
    public Optional<GoverningQuotation> findGoverningQuotation(long ticketId) {
        return jdbc.query("""
            SELECT quotation_id, origin IS NULL AS legacy, number, deposit_percent
              FROM sales.quotation
             WHERE ticket_id = :ticketId
               AND ((origin IS NULL AND pricing_request_id IS NOT NULL AND doc_status IN ('ACCEPTED','ISSUED'))
                 OR (origin = 'PRICING_REQUEST' AND doc_status IN ('ACCEPTED','ISSUED'))
                 OR (origin = 'DEAL_DIRECT' AND doc_status IN ('ACCEPTED','ISSUED','APPROVED')))
             ORDER BY CASE WHEN doc_status IN ('ACCEPTED','APPROVED') THEN 0 ELSE 1 END, quotation_id DESC
             LIMIT 1
            """, Map.of("ticketId", ticketId), (rs, i) -> {
                boolean legacy = rs.getBoolean("legacy");
                Integer stored = (Integer) rs.getObject("deposit_percent");
                int percent = legacy ? QuotationRenderer.LEGACY_DEPOSIT_PERCENT
                    : stored != null ? stored : DealQuotationRenderAdapter.DEFAULT_DEPOSIT_PERCENT;
                return new GoverningQuotation(rs.getLong("quotation_id"), legacy, rs.getString("number"),
                    BigDecimal.valueOf(percent, 2));
            }).stream().findFirst();
    }

    @Transactional
    public long createDraft(long ticketId, DepositNoticeDraftRequest req, List<DepositNoticeItemRequest> items,
                            String preparerName) {
        BigDecimal depositPct = req.depositPercent() != null ? req.depositPercent() : new BigDecimal("0.50");
        BigDecimal vatPct = new BigDecimal("0.07");

        BigDecimal subtotal = items.stream()
            .map(DepositNoticeRepository::lineAmount)
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
            .addValue("notes",           notesArr)
            // NOT NULL column; blank prints no name (DocumentSheet.preparer)
            // the fallback only: the INSERT below stamps the deal's owner when the ticket has one
            .addValue("preparerName",    preparerName == null ? "" : preparerName)
            .addValue("preparerId",      req.preparerId());

        var keys = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.deposit_notice
                (ticket_id, version, customer_name, customer_tax_id, customer_address,
                 project_name, reference, deposit_percent, subtotal, deposit_amount,
                 vat_percent, vat_amount, total_payable, notes, preparer_name, preparer_id)
            VALUES
                (:ticketId, :version, :customerName, :customerTaxId, :customerAddress,
                 :projectName, :reference, :depositPercent, :subtotal, :depositAmount,
                 :vatPercent, :vatAmount, :totalPayable, :notes,
                 -- ผู้จัดทำ: the latest earlier notice's chosen preparer, else the deal's owner, else the actor
                 COALESCE((SELECT p.preparer_name FROM sales.deposit_notice p
                            WHERE p.ticket_id = :ticketId AND p.preparer_id IS NOT NULL
                              AND p.version = (SELECT MAX(version) FROM sales.deposit_notice WHERE ticket_id = :ticketId)),
                          (SELECT {NAME}
                             FROM sales.ticket t JOIN hr.employee e ON e.employee_id = t.created_by
                            WHERE t.ticket_id = :ticketId), :preparerName),
                 COALESCE((SELECT p.preparer_id FROM sales.deposit_notice p
                            WHERE p.ticket_id = :ticketId
                              AND p.version = (SELECT MAX(version) FROM sales.deposit_notice WHERE ticket_id = :ticketId)),
                          (SELECT t.created_by FROM sales.ticket t WHERE t.ticket_id = :ticketId), :preparerId))
            """.replace("{NAME}", PREPARER_NAME_SQL), params, keys, new String[]{"deposit_notice_id"});

        long docId = keys.getKey().longValue();
        insertItems(docId, items);
        return docId;
    }

    /**
     * Saves a DRAFT or an ISSUED notice (an issued one is edited in place: same number, same status). Refuses with 409
     * before touching the lines when the row is no longer in {@code expectedStatus} (the status the service read: a save
     * must not land on a row issued or superseded meanwhile), or, given {@code req.expectedUpdatedAt()}, someone else
     * saved it since that stamp. {@code req.depositPercent()} is always set by the service.
     */
    @Transactional
    public void update(long docId, DepositNoticeDraftRequest req, List<DepositNoticeItemDto> storedItems,
            String expectedStatus) {
        BigDecimal depositPct = req.depositPercent();
        BigDecimal vatPct = new BigDecimal("0.07");
        List<DepositNoticeItemRequest> items = req.items() != null ? req.items() : List.of();

        // No items in the request (null or empty) keeps the stored lines (they are only replaced
        // below when items are sent), so the subtotal must come from those same stored lines.
        BigDecimal subtotal = (items.isEmpty()
                ? storedItems.stream().map(DepositNoticeItemDto::amount)
                : items.stream().map(DepositNoticeRepository::lineAmount))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = subtotal.multiply(depositPct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = deposit.multiply(vatPct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = deposit.add(vat).setScale(2, RoundingMode.HALF_UP);

        String[] notesArr = req.notes() != null ? req.notes().toArray(String[]::new) : new String[0];

        int rows = jdbc.update("""
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
                -- a null preparerId keeps the stored preparer
                preparer_id      = COALESCE(CAST(:preparerId AS bigint), preparer_id),
                preparer_name    = CASE WHEN CAST(:preparerId AS bigint) IS NULL THEN preparer_name ELSE COALESCE(
                    (SELECT {NAME}
                       FROM hr.employee e WHERE e.employee_id = :preparerId), '') END,
                updated_at       = now()
             WHERE deposit_notice_id = :id AND status = :expectedStatus
               AND (CAST(:expectedUpdatedAt AS timestamptz) IS NULL OR updated_at = :expectedUpdatedAt)
            """.replace("{NAME}", PREPARER_NAME_SQL),
            new MapSqlParameterSource()
                .addValue("id",              docId)
                .addValue("expectedUpdatedAt", req.expectedUpdatedAt())
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
                .addValue("preparerId",      req.preparerId())
                .addValue("expectedStatus",  expectedStatus)
        );
        if (rows == 0) {
            List<String> now = jdbc.queryForList(
                "SELECT status FROM sales.deposit_notice WHERE deposit_notice_id = :id", Map.of("id", docId), String.class);
            String status = now.isEmpty() ? null : now.get(0);
            throw new th.co.glr.hr.common.ApiException(HttpStatus.CONFLICT,
                "SUPERSEDED".equals(status) ? SUPERSEDED
                    : expectedStatus.equals(status) && req.expectedUpdatedAt() != null ? EDITED_ELSEWHERE : NOT_DRAFT);
        }
        if (!items.isEmpty()) {
            jdbc.update("DELETE FROM sales.deposit_notice_item WHERE deposit_notice_id = :id", Map.of("id", docId));
            insertItems(docId, items);
        }
    }

    /** Assigns the doc number and flips the row to ISSUED — but only if it is still DRAFT at the moment this UPDATE
     * runs. It is the only thing that closes it; the service-level check alone cannot, since it reads before
     * either write commits. */
    public Optional<String> issue(long docId, long actorId, String actorName) {
        return issue(docId, actorId, actorName, null);
    }

    @Transactional
    public Optional<String> issue(long docId, long actorId, String actorName, java.time.OffsetDateTime expectedUpdatedAt) {
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
               AND (CAST(:expectedUpdatedAt AS timestamptz) IS NULL OR updated_at = :expectedUpdatedAt)
            """,
            new MapSqlParameterSource()
                .addValue("num", docNumber).addValue("actorId", actorId).addValue("actorName", actorName)
                .addValue("id", docId).addValue("expectedUpdatedAt", expectedUpdatedAt));

        if (rows == 0) {
            // Still a draft with a stamp given => it was saved after the caller read it.
            boolean editedSince = expectedUpdatedAt != null && !jdbc.queryForList(
                "SELECT 1 FROM sales.deposit_notice WHERE deposit_notice_id = :id AND status = 'DRAFT'",
                Map.of("id", docId), Integer.class).isEmpty();
            if (editedSince) {
                throw new th.co.glr.hr.common.ApiException(HttpStatus.CONFLICT, EDITED_ELSEWHERE);
            }
            return Optional.empty();
        }

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

    /** A line's stored amount when supplied (a quotation's own, rounded once), else net x qty. */
    private static BigDecimal lineAmount(DepositNoticeItemRequest it) {
        BigDecimal amount = it.amount() != null ? it.amount() : it.netUnitPrice().multiply(it.qty());
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private void insertItems(long docId, List<DepositNoticeItemRequest> items) {
        for (var it : items) {
            BigDecimal amount = lineAmount(it);
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

    /** The new-engine (origin set) quotation of this deal with this number: the one a notice's reference names. */
    public Optional<Long> findNewEngineQuotationId(long ticketId, String number) {
        return jdbc.queryForList("SELECT quotation_id FROM sales.quotation WHERE ticket_id = :t AND number = :n"
            + " AND origin IS NOT NULL ORDER BY quotation_id DESC LIMIT 1",
            Map.of("t", ticketId, "n", number), Long.class).stream().findFirst();
    }

    /** One stored line of a new-engine quotation, as the notice and the remaining invoice print it: the STORED amount
     * (never recomputed), the quotation's own ส่วนลด cell text ({@code discountLabel}), the description with the
     * quotation's size line on a second line, and for an ADJUSTMENT (ส่วนลดพิเศษ) qty -1 with its own negative amount. */
    public record QuotationInvoiceLine(int seq, String description, BigDecimal qty, String unit, BigDecimal unitPrice,
                                       String discountLabel, BigDecimal netUnitPrice, BigDecimal amount,
                                       boolean adjustment) {}

    public List<QuotationInvoiceLine> findQuotationInvoiceLines(long quotationId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT qi.seq, qi.description, qi.qty, qi.raw_unit, qi.unit_price, qi.final_unit_price, qi.amount,
                   qi.line_type, qi.discount_pct, qi.size, qi.thickness_mm, pp.width_mm, pp.height_mm,
                   qi.price_mode AS row_price_mode, q.price_mode AS doc_price_mode, q.document_language
              FROM sales.quotation_item qi
              JOIN sales.quotation q ON q.quotation_id = qi.quotation_id
              LEFT JOIN price_catalog.product_prices pp ON pp.price_id = qi.catalog_price_id
             WHERE qi.quotation_id = :quotationId
             ORDER BY qi.seq
            """, Map.of("quotationId", quotationId));
        List<QuotationInvoiceLine> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String lineType = (String) r.get("line_type");
            boolean adjustment = "ADJUSTMENT".equals(lineType);
            boolean tile = lineType == null || "TILE".equals(lineType);
            boolean english = th.co.glr.hr.dealquotation.WastageCalculator.DOCUMENT_LANGUAGE_EN
                .equals(r.get("document_language"));
            BigDecimal unitPrice = (BigDecimal) r.get("unit_price");
            BigDecimal net = (BigDecimal) r.get("final_unit_price");
            String priceMode = th.co.glr.hr.dealquotation.WastageCalculator.rowPriceMode(
                (String) r.get("doc_price_mode"), (String) r.get("row_price_mode"));
            String label = th.co.glr.hr.dealquotation.DealQuotationLines.discountCellText(
                lineType, priceMode, (BigDecimal) r.get("discount_pct"), unitPrice, net, english);
            String description = (String) r.get("description");
            description = description == null || description.isBlank() ? "รายการสินค้า" : description;
            if (tile) {
                BigDecimal width = (BigDecimal) r.get("width_mm");
                BigDecimal height = (BigDecimal) r.get("height_mm");
                String sizeLine = th.co.glr.hr.dealquotation.DealQuotationLines.sizeLine(
                    english ? th.co.glr.hr.dealquotation.WastageCalculator.DOCUMENT_LANGUAGE_EN
                        : th.co.glr.hr.dealquotation.WastageCalculator.DOCUMENT_LANGUAGE_TH,
                    (String) r.get("size"), (BigDecimal) r.get("thickness_mm"), width, height);
                if (sizeLine != null) {
                    description = description + "\n" + sizeLine;
                }
            }
            String unit = (String) r.get("raw_unit");
            out.add(new QuotationInvoiceLine(((Number) r.get("seq")).intValue(), description,
                (BigDecimal) r.get("qty"),
                adjustment ? null : (unit == null || unit.isBlank() ? "หน่วย" : unit),
                unitPrice, label, net, (BigDecimal) r.get("amount"), adjustment));
        }
        return out;
    }

    /** The quotation payable reads: id, number, stored total, document language (null = TH), and whether it is a
     * new-engine row (origin set) - legacy rows (origin NULL) belong to the legacy resolver. */
    public record AnsweredQuotation(long id, String number, BigDecimal totalAmount, String documentLanguage,
                                    boolean newEngine) {}

    /**
     * Exactly payable's ANSWERED selection ({@link th.co.glr.hr.ticket.TicketRepository#ANSWERED_QUOTATION_SQL}, no
     * extra origin filter, same pick order: ACCEPTED first, BUYER over OWNER over the rest, newest), so payable and
     * the invoice can never choose different quotations.
     */
    public Optional<AnsweredQuotation> findAnsweredQuotation(long ticketId) {
        List<AnsweredQuotation> rows = jdbc.query("SELECT q.quotation_id, q.number, q.total_amount, q.document_language,"
            + " q.origin IS NOT NULL AS new_engine FROM sales.quotation q"
            + " WHERE q.ticket_id = :ticketId AND "
            + th.co.glr.hr.ticket.TicketRepository.ANSWERED_QUOTATION_SQL
            + " ORDER BY CASE WHEN q.doc_status = 'ACCEPTED' THEN 0 ELSE 1 END,"
            + " CASE q.recipient_type WHEN 'BUYER' THEN 0 WHEN 'OWNER' THEN 1 ELSE 2 END,"
            + " q.accepted_at DESC NULLS LAST, q.issued_at DESC, q.quotation_id DESC LIMIT 1",
            Map.of("ticketId", ticketId),
            (rs, rowNum) -> new AnsweredQuotation(rs.getLong("quotation_id"), rs.getString("number"),
                rs.getBigDecimal("total_amount"), rs.getString("document_language"), rs.getBoolean("new_engine")));
        return rows.stream().findFirst();
    }

    /** One deal quotation's {@code sales.quotation_item} lines, by {@code seq}, as deposit-notice
     * items, each with the quotation's own stored {@code amount}. Reads the RESOLVED display columns
     * the quotation itself prints ({@code qty}/{@code raw_unit}/{@code unit_price}/{@code
     * final_unit_price}), NOT the legacy {@code requested_*}/{@code approved_unit_price} columns the
     * V2 chain leaves null. The discount-label wording matches {@link
     * DepositNoticeService#itemsFromQuotation}. An ADJUSTMENT row (ส่วนลดพิเศษ) is its own line: qty
     * -1, no unit, negative amount. */
    public List<DepositNoticeItemRequest> findDealQuotationItems(long quotationId) {
        List<DepositNoticeItemRequest> out = new ArrayList<>();
        for (QuotationInvoiceLine line : findQuotationInvoiceLines(quotationId)) {
            BigDecimal net = line.netUnitPrice();
            if (line.adjustment() && net == null && line.qty() != null && line.qty().signum() != 0) {
                // a hand-written row without a net price: derive it so the discount is kept, not lost
                net = line.amount().abs().divide(line.qty().abs(), 2, java.math.RoundingMode.HALF_UP);
            }
            out.add(new DepositNoticeItemRequest(line.seq(), line.description(), line.qty(),
                line.adjustment() ? "" : line.unit(), line.unitPrice(), line.discountLabel(), net, line.amount()));
        }
        return out;
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
            (Long) rs.getObject("preparer_id"),
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
            doc.issuedByName(), doc.preparerName(), doc.preparerId(),
            doc.createdAt(), doc.updatedAt(), items
        );
    }
}
