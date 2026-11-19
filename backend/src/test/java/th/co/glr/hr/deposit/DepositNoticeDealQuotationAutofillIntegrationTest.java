package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-DB proof for the V2 deal-quotation item source of a ticket-level deposit notice (the
 * 2026-09-29 fix): {@link DepositNoticeRepository#findGoverningQuotation} picks the quotation and
 * {@link DepositNoticeRepository#findDealQuotationItems} reads its lines, the pair {@code
 * DepositNoticeService#itemsFromGoverning} uses once the legacy {@code
 * ticket_item.approved_price} path yields nothing. Mockito cannot exercise this: the job is the
 * SQL (the status/origin filter, the {@code ORDER BY} preference, the column mapping), so this
 * needs the real queries against real Postgres. Which quotation wins (the ranking across origins
 * and insertion order) is pinned in {@code DepositNoticeRepositoryDepositPercentIntegrationTest};
 * this class's non-live test is the only pin for some excluded statuses, so it is not a duplicate.
 */
class DepositNoticeDealQuotationAutofillIntegrationTest extends AbstractPostgresIntegrationTest {

    private DepositNoticeRepository repo;
    private long employeeId;
    private int quotationSeq;

    @BeforeEach
    void wireRepository() {
        repo = new DepositNoticeRepository(jdbc);
        employeeId = insertEmployee("DEPNOT-IT");
    }

    // ── (a) PRICING_REQUEST / ISSUED, two items (one discounted, one not) ───────────────────────

    @Test
    void pricingRequestIssuedQuotation_returnsItemsInSeqOrderWithDiscountLabels() {
        long ticketId = insertTicket("DEPNOT-A");
        long quotationId = insertQuotation(ticketId, "PRICING_REQUEST", "ISSUED");
        // Seq 2 inserted first, seq 1 second — proves the repository orders by seq, not insert order.
        insertQuotationItem(quotationId, 2, "กระเบื้อง B", new BigDecimal("5"), "ตร.ม.",
            new BigDecimal("500.00"), BigDecimal.ZERO, new BigDecimal("500.00"));
        long discounted = insertQuotationItem(quotationId, 1, "กระเบื้อง A", new BigDecimal("10"), "แผ่น",
            new BigDecimal("4365.29"), new BigDecimal("873.06"), new BigDecimal("3492.23"));
        jdbc.update("UPDATE sales.quotation_item SET discount_pct = 20 WHERE quotation_item_id = :id", Map.of("id", discounted));

        List<DepositNoticeItemRequest> items = itemsForDeposit(ticketId);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).seq()).isEqualTo(1);
        assertThat(items.get(0).description()).isEqualTo("กระเบื้อง A");
        assertThat(items.get(0).unit()).isEqualTo("แผ่น");
        assertThat(items.get(0).unitPrice()).isEqualByComparingTo("4365.29");
        assertThat(items.get(0).netUnitPrice()).isEqualByComparingTo("3492.23");
        // the quotation's own ส่วนลด cell: 20% — not the computed per-unit amount, and no "ลด "/"ส่วนลด " wording
        assertThat(items.get(0).discountLabel()).isEqualTo("20%");

        assertThat(items.get(1).seq()).isEqualTo(2);
        assertThat(items.get(1).description()).isEqualTo("กระเบื้อง B");
        assertThat(items.get(1).unit()).isEqualTo("ตร.ม.");
        assertThat(items.get(1).unitPrice()).isEqualByComparingTo("500.00");
        assertThat(items.get(1).netUnitPrice()).isEqualByComparingTo("500.00");
        // no discount_pct: the quotation prints "Net" in that cell, so the notice does too (never "ส่วนลด 0 ต่อหน่วย").
        assertThat(items.get(1).discountLabel()).isEqualTo("Net");
    }

    // ── (a2) the ส่วนลด cell follows the QUOTATION renderer's own rule, per price mode and line type ─────

    /** SPECIAL_SQM tile -> พิเศษ; a plain line -> Net; an ADJUSTMENT (ส่วนลดพิเศษ, qty -1) keeps an EMPTY cell. */
    @Test
    void specialSqmQuotation_printsSpecialOnTheTileLine_netOnThePlainLine_andNothingOnTheDiscountLine() {
        long ticketId = insertTicket("DEPNOT-S");
        long q = insertQuotation(ticketId, "DEAL_DIRECT", "APPROVED");
        jdbc.update("UPDATE sales.quotation SET price_mode = 'SPECIAL_SQM' WHERE quotation_id = :id", Map.of("id", q));
        insertLine(q, 1, "TILE", "กระเบื้อง Tribeca", "10", "1210.25", "531.16", null, null, null);
        insertLine(q, 2, "PLAIN", "ค่าขนส่ง", "2", "500.00", "500.00", null, null, null);
        insertLine(q, 3, "ADJUSTMENT", "ส่วนลดพิเศษ 100 บาท", "-1", "100.00", "100.00", null, null, null);

        List<DepositNoticeItemRequest> items = repo.findDealQuotationItems(q);

        assertThat(items).extracting(DepositNoticeItemRequest::discountLabel)
            .satisfiesExactly(
                tile -> assertThat(tile).isEqualTo("พิเศษ"),
                plain -> assertThat(plain).isEqualTo("Net"),
                adjustment -> assertThat(adjustment).isNullOrEmpty());
    }

    /** DIRECT_NET: พิเศษ only when the net really differs from the list price, else Net. NET + a percent: "N%". */
    @Test
    void directNetAndNetModes_printTheSameTextTheQuotationDoes() {
        long ticketId = insertTicket("DEPNOT-N");
        long direct = insertQuotation(ticketId, "DEAL_DIRECT", "APPROVED");
        jdbc.update("UPDATE sales.quotation SET price_mode = 'DIRECT_NET' WHERE quotation_id = :id", Map.of("id", direct));
        insertLine(direct, 1, "TILE", "ต่างจากราคาตั้ง", "1", "100.00", "80.00", null, null, null);
        insertLine(direct, 2, "TILE", "เท่าราคาตั้ง", "1", "100.00", "100.00", null, null, null);
        long net = insertQuotation(ticketId, "DEAL_DIRECT", "SUPERSEDED");
        insertLine(net, 1, "TILE", "ลด 12.5%", "1", "100.00", "87.50", "12.5", null, null);
        insertLine(net, 2, "TILE", "ไม่ลด", "1", "100.00", "100.00", null, null, null);

        assertThat(repo.findDealQuotationItems(direct)).extracting(DepositNoticeItemRequest::discountLabel)
            .containsExactly("พิเศษ", "Net");
        assertThat(repo.findDealQuotationItems(net)).extracting(DepositNoticeItemRequest::discountLabel)
            .containsExactly("12.5%", "Net");
    }

    // ── (a3) the quotation's size line is copied under the product, from the SAME formatter ──────────

    /** The quotation prints "ขนาด 6 cm x 24.6 cm x 8 mm (ขนาดโดยประมาณ)" under the product; the notice line's
     * description = the stored description, a newline, that exact text (DealQuotationLines#sizeLine, not a copy of it).
     * A row with no thickness has no size line (sizeLine returns null) and a PLAIN row never has one. */
    @Test
    void tileLine_descriptionCarriesTheQuotationsSizeLineOnASecondLine() {
        long ticketId = insertTicket("DEPNOT-Z");
        long q = insertQuotation(ticketId, "DEAL_DIRECT", "APPROVED");
        insertLine(q, 1, "TILE", "กระเบื้อง รุ่น Tribeca สี Sage green ผิว Glossy", "10", "100.00", "100.00", null,
            "6x24.6", "8");
        insertLine(q, 2, "TILE", "ไม่มีความหนา", "1", "100.00", "100.00", null, "60x60", null);
        insertLine(q, 3, "PLAIN", "ค่าขนส่ง", "1", "500.00", "500.00", null, null, null);

        List<DepositNoticeItemRequest> items = repo.findDealQuotationItems(q);

        String size = "ขนาด 6 cm x 24.6 cm x 8 mm (ขนาดโดยประมาณ)";
        assertThat(size).as("the fixture is the owner's example, via the shared formatter")
            .isEqualTo(th.co.glr.hr.dealquotation.DealQuotationLines.sizeLine("6x24.6", new BigDecimal("8"), null, null));
        assertThat(items.get(0).description())
            .isEqualTo("กระเบื้อง รุ่น Tribeca สี Sage green ผิว Glossy\n" + size);
        assertThat(items.get(1).description()).as("no thickness -> no size line, no dangling newline")
            .isEqualTo("ไม่มีความหนา");
        assertThat(items.get(2).description()).isEqualTo("ค่าขนส่ง");
    }

    // ── (b) NEGATIVE, wrong-way-round: nothing in a live status -> empty list ───────────────────

    /** Written wrong-way-round per CLAUDE.md's "Permission changes must ship evidence" guidance, applied here to
     * the status filter: a ticket whose ONLY quotations sit in every non-live status (DRAFT, CANCELLED,
     * SUPERSEDED, PENDING_APPROVAL) must autofill EMPTY, never one of them. */
    @Test
    void onlyNonLiveStatusQuotations_returnsEmptyList() {
        long ticketId = insertTicket("DEPNOT-D");
        long draftId = insertQuotation(ticketId, "PRICING_REQUEST", "DRAFT");
        insertQuotationItem(draftId, 1, "Draft item", BigDecimal.ONE, "แผ่น",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        long cancelledId = insertQuotation(ticketId, "DEAL_DIRECT", "CANCELLED");
        insertQuotationItem(cancelledId, 1, "Cancelled item", BigDecimal.ONE, "แผ่น",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        long supersededId = insertQuotation(ticketId, "PRICING_REQUEST", "SUPERSEDED");
        insertQuotationItem(supersededId, 1, "Superseded item", BigDecimal.ONE, "แผ่น",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        long pendingApprovalId = insertQuotation(ticketId, "DEAL_DIRECT", "PENDING_APPROVAL");
        insertQuotationItem(pendingApprovalId, 1, "Pending-approval item", BigDecimal.ONE, "แผ่น",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);

        List<DepositNoticeItemRequest> items = itemsForDeposit(ticketId);

        assertThat(items).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** The new-engine branch of {@code DepositNoticeService#itemsFromGoverning}. */
    private List<DepositNoticeItemRequest> itemsForDeposit(long ticketId) {
        return repo.findGoverningQuotation(ticketId)
            .map(governing -> repo.findDealQuotationItems(governing.quotationId()))
            .orElse(List.of());
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'มัดจำ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertTicket(String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status)
            VALUES (:code, 'ทดสอบ deposit autofill', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    'CUSTOMER_CONFIRMED')
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", employeeId), Long.class);
    }

    private long insertQuotation(long ticketId, String origin, String docStatus) {
        // ux_quotation_ticket_recipient_version is unique on; recipient_type defaults to 'UNSPECIFIED' for every row
        // here, so a second quotation on the SAME ticket needs a distinct quotation_version or the insert collides —
        // increment the shared counter rather than defaulting to 1 every time.
        int version = ++quotationSeq;
        String number = "QN-DEPNOT-" + version;
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, quotation_version)
            VALUES (:ticketId, :number, :issuedBy, :origin, :docStatus, :version)
            RETURNING quotation_id
            """, new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("number", number)
                .addValue("issuedBy", employeeId)
                .addValue("origin", origin)
                .addValue("docStatus", docStatus)
                .addValue("version", version),
            Long.class);
    }

    private void insertLine(long quotationId, int seq, String lineType, String description, String qty,
                            String unitPrice, String finalUnitPrice, String discountPct, String sizeText,
                            String thicknessMm) {
        jdbc.update("""
            INSERT INTO sales.quotation_item
                (quotation_id, seq, description, qty, raw_unit, unit_price, sales_discount, final_unit_price, amount,
                 line_type, discount_pct, size, thickness_mm)
            VALUES
                (:q, :seq, :description, :qty, 'แผ่น', :unitPrice, 0, :net, :amount,
                 :lineType, :pct, :size, :thickness)
            """, new MapSqlParameterSource()
                .addValue("q", quotationId).addValue("seq", seq).addValue("description", description)
                .addValue("qty", new BigDecimal(qty)).addValue("unitPrice", new BigDecimal(unitPrice))
                .addValue("net", new BigDecimal(finalUnitPrice))
                .addValue("amount", new BigDecimal(qty).multiply(new BigDecimal(finalUnitPrice)))
                .addValue("lineType", lineType)
                .addValue("pct", discountPct == null ? null : new BigDecimal(discountPct))
                .addValue("size", sizeText)
                .addValue("thickness", thicknessMm == null ? null : new BigDecimal(thicknessMm)));
    }

    private long insertQuotationItem(long quotationId, int seq, String description, BigDecimal qty,
                                     String rawUnit, BigDecimal unitPrice, BigDecimal salesDiscount,
                                     BigDecimal finalUnitPrice) {
        // amount is legacy NOT NULL (V49) — irrelevant to findDealQuotationItems, which
        // reads unit_price/final_unit_price instead, so a harmless qty*unitPrice placeholder.
        BigDecimal amount = qty.multiply(unitPrice);
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation_item
                (quotation_id, seq, description, qty, raw_unit, unit_price, sales_discount,
                 final_unit_price, amount)
            VALUES
                (:quotationId, :seq, :description, :qty, :rawUnit, :unitPrice, :salesDiscount,
                 :finalUnitPrice, :amount)
            RETURNING quotation_item_id
            """, new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("seq", seq)
                .addValue("description", description)
                .addValue("qty", qty)
                .addValue("rawUnit", rawUnit)
                .addValue("unitPrice", unitPrice)
                .addValue("salesDiscount", salesDiscount)
                .addValue("finalUnitPrice", finalUnitPrice)
                .addValue("amount", amount), Long.class);
    }
}
