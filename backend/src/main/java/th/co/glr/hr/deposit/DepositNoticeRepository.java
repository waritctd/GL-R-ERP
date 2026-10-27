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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.dealquotation.DealQuotationRenderAdapter;
import th.co.glr.hr.ticket.QuotationRenderer;

@Repository
public class DepositNoticeRepository {
    static final String NOT_DRAFT = "ใบแจ้งรับมัดจำนี้ไม่ได้อยู่ในสถานะร่าง (DRAFT)";
    static final String EDITED_ELSEWHERE = "ใบแจ้งรับมัดจำนี้ถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง";

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

    /** The quotation a ticket-level deposit notice bills: always its percent (a fraction,
     * 30 -&gt; 0.30); its lines and its number as the reference unless the caller or legacy
     * approved_price rows supply the lines. See {@link #findGoverningQuotation}. */
    public record GoverningQuotation(long quotationId, boolean legacy, String number, BigDecimal depositPercent) {}

    /**
     * The ONE pick of the quotation a ticket-level deposit notice bills, across both engines, so
     * when it supplies the lines they and the percent come from the same row (owner rulings
     * 2026-09-29 and 2026-10-23). The PRICING-REQUEST route ({@code OrderConfirmationService
     * #createDepositNoticeFromQuotation}) already knows its quotation and does not call this.
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
     * the confirmed quotation's lines. Ploy 2026-10-27: revisions will lock after confirm
     * (follow-up PR).
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

    /**
     * Saves a DRAFT. Refuses with 409 before touching the lines when the row is no longer DRAFT
     * (issued after the service read it) or, given {@code req.expectedUpdatedAt()}, someone else
     * saved it since that stamp. {@code req.depositPercent()} is always set by the service.
     */
    @Transactional
    public void update(long docId, DepositNoticeDraftRequest req, List<DepositNoticeItemDto> storedItems) {
        BigDecimal depositPct = req.depositPercent();
        BigDecimal vatPct = new BigDecimal("0.07");
        List<DepositNoticeItemRequest> items = req.items() != null ? req.items() : List.of();

        // No items in the request (null or empty) keeps the stored lines (they are only replaced
        // below when items are sent), so the subtotal must come from those same stored lines.
        BigDecimal subtotal = (items.isEmpty()
                ? storedItems.stream().map(it -> it.netUnitPrice().multiply(it.qty()))
                : items.stream().map(it -> it.netUnitPrice().multiply(it.qty())))
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
                updated_at       = now()
             WHERE deposit_notice_id = :id AND status = 'DRAFT'
               AND (CAST(:expectedUpdatedAt AS timestamptz) IS NULL OR updated_at = :expectedUpdatedAt)
            """,
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
        );
        if (rows == 0) {
            boolean stillDraft = req.expectedUpdatedAt() != null && !jdbc.queryForList(
                "SELECT 1 FROM sales.deposit_notice WHERE deposit_notice_id = :id AND status = 'DRAFT'",
                Map.of("id", docId), Integer.class).isEmpty();
            throw new th.co.glr.hr.common.ApiException(HttpStatus.CONFLICT, stillDraft ? EDITED_ELSEWHERE : NOT_DRAFT);
        }
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

    /** One deal quotation's {@code sales.quotation_item} lines, by {@code seq}, as deposit-notice
     * items. Reads the RESOLVED display columns the quotation itself prints
     * ({@code qty}/{@code raw_unit}/{@code unit_price}/{@code final_unit_price}), NOT the legacy
     * {@code requested_*}/{@code approved_unit_price} columns the V2 chain leaves null. The
     * discount-label wording matches {@link DepositNoticeService#itemsFromQuotation}. An ADJUSTMENT
     * row is folded into the goods lines, see {@link #foldAdjustments}. */
    public List<DepositNoticeItemRequest> findDealQuotationItems(long quotationId) {
        List<DepositNoticeItemRequest> goods = new java.util.ArrayList<>();
        BigDecimal[] adjustment = {BigDecimal.ZERO};
        jdbc.query("""
            SELECT seq, description, qty, raw_unit, unit_price, sales_discount, final_unit_price, line_type
              FROM sales.quotation_item
             WHERE quotation_id = :quotationId
             ORDER BY seq
            """, Map.of("quotationId", quotationId), rs -> {
                if ("ADJUSTMENT".equals(rs.getString("line_type"))) {
                    BigDecimal net = rs.getBigDecimal("final_unit_price");
                    BigDecimal qty = rs.getBigDecimal("qty");
                    if (net != null && qty != null) adjustment[0] = adjustment[0].add(net.multiply(qty));
                    return;
                }
                String description = rs.getString("description");
                if (description == null || description.isBlank()) {
                    description = "รายการสินค้า";
                }
                String unit = rs.getString("raw_unit");
                if (unit == null || unit.isBlank()) {
                    unit = "หน่วย";
                }
                BigDecimal discount = rs.getBigDecimal("sales_discount");
                String discountLabel = discount != null && discount.signum() > 0
                    ? "ส่วนลด " + discount.stripTrailingZeros().toPlainString() + " ต่อหน่วย"
                    : null;
                goods.add(new DepositNoticeItemRequest(
                    rs.getInt("seq"), description, rs.getBigDecimal("qty"), unit,
                    rs.getBigDecimal("unit_price"), discountLabel, rs.getBigDecimal("final_unit_price")));
            });
        return foldAdjustments(goods, adjustment[0]);
    }

    /**
     * Owner rulings 2026-10-23: a DEAL_DIRECT ส่วนลดพิเศษ row (ADJUSTMENT, qty -1) cannot be a
     * deposit line (the editor's PUT requires qty &gt;= 0), so its amount is spread over the goods
     * lines in proportion to their amount. "2 decimals for all": each share is HALF_UP to 2 dp and
     * each line's new net price is 2 dp, with no remainder pushed onto a last line. The notice
     * totals are the sum of its own stored 2 dp lines ({@code amount = net x qty}), so they can
     * differ from the quotation's total by under qty x 0.005 baht per line; that is the ruled
     * outcome, not a bug. A line keeps the quotation's own discount wording, and gains the folded
     * discount after it only when the fold lowered its price. A free line stays as it is.
     */
    static List<DepositNoticeItemRequest> foldAdjustments(List<DepositNoticeItemRequest> goods,
                                                          BigDecimal adjustment) {
        BigDecimal goodsTotal = goods.stream().map(it -> it.netUnitPrice().multiply(it.qty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (adjustment.signum() == 0 || goodsTotal.signum() == 0) {
            return goods;
        }
        List<DepositNoticeItemRequest> folded = new java.util.ArrayList<>();
        for (DepositNoticeItemRequest it : goods) {
            BigDecimal amount = it.netUnitPrice().multiply(it.qty());
            if (amount.signum() == 0) {
                folded.add(it);
                continue;
            }
            BigDecimal share = adjustment.multiply(amount).divide(goodsTotal, 2, RoundingMode.HALF_UP);
            BigDecimal net = amount.add(share).divide(it.qty(), 2, RoundingMode.HALF_UP);
            BigDecimal foldedPerUnit = it.netUnitPrice().subtract(net);
            String label = it.discountLabel();
            if (foldedPerUnit.signum() > 0) {
                String extra = "ส่วนลดพิเศษ " + foldedPerUnit.stripTrailingZeros().toPlainString() + " ต่อหน่วย";
                label = label == null || label.isBlank() ? extra : label + " + " + extra;
            }
            folded.add(new DepositNoticeItemRequest(it.seq(), it.description(), it.qty(), it.unit(), it.unitPrice(),
                label, net));
        }
        return folded;
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
