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
 * Real-DB proof for {@link DepositNoticeRepository#findDealQuotationItemsForDeposit(long)} (the
 * 2026-09-29 fix) — the V2 deal-quotation item source {@code DepositNoticeService
 * #buildItemsFromRequest} falls back to once both the legacy {@code ticket_item.approved_price}
 * path and the {@code CustomerQuotationRepository}-scoped {@code pickQuotation} path yield
 * nothing. Mockito cannot exercise this: the method's whole job is the SQL — the status/origin
 * filter and the {@code ORDER BY} preference — so this needs the real query against real
 * Postgres, not a mocked repository. See that method's own Javadoc for the exact selection rule
 * this test pins.
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
        insertQuotationItem(quotationId, 1, "กระเบื้อง A", new BigDecimal("10"), "แผ่น",
            new BigDecimal("4365.29"), new BigDecimal("873.06"), new BigDecimal("3492.23"));

        List<DepositNoticeItemRequest> items = repo.findDealQuotationItemsForDeposit(ticketId);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).seq()).isEqualTo(1);
        assertThat(items.get(0).description()).isEqualTo("กระเบื้อง A");
        assertThat(items.get(0).unit()).isEqualTo("แผ่น");
        assertThat(items.get(0).unitPrice()).isEqualByComparingTo("4365.29");
        assertThat(items.get(0).netUnitPrice()).isEqualByComparingTo("3492.23");
        assertThat(items.get(0).discountLabel()).isEqualTo("ส่วนลด 873.06 ต่อหน่วย");

        assertThat(items.get(1).seq()).isEqualTo(2);
        assertThat(items.get(1).description()).isEqualTo("กระเบื้อง B");
        assertThat(items.get(1).unit()).isEqualTo("ตร.ม.");
        assertThat(items.get(1).unitPrice()).isEqualByComparingTo("500.00");
        assertThat(items.get(1).netUnitPrice()).isEqualByComparingTo("500.00");
        // sales_discount = 0 -> discountLabel must be null, never "ส่วนลด 0 ต่อหน่วย".
        assertThat(items.get(1).discountLabel()).isNull();
    }

    // ── (b) DEAL_DIRECT / APPROVED — the case the old IN ('ACCEPTED','ISSUED') filter MISSED ───

    @Test
    void dealDirectApprovedQuotation_isReturned_theCoreRegressionThisFixCloses() {
        long ticketId = insertTicket("DEPNOT-B");
        long quotationId = insertQuotation(ticketId, "DEAL_DIRECT", "APPROVED");
        insertQuotationItem(quotationId, 1, "DEAL_DIRECT item", new BigDecimal("2"), "แผ่น",
            new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal("1000.00"));

        List<DepositNoticeItemRequest> items = repo.findDealQuotationItemsForDeposit(ticketId);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).description()).isEqualTo("DEAL_DIRECT item");
        assertThat(items.get(0).unitPrice()).isEqualByComparingTo("1000.00");
        assertThat(items.get(0).discountLabel()).isNull();
    }

    // ── (c) ACCEPTED beats ISSUED (both PRICING_REQUEST, same ticket) ───────────────────────────

    @Test
    void acceptedPricingRequestQuotation_winsOverAnIssuedOne_onTheSameTicket() {
        long ticketId = insertTicket("DEPNOT-C");
        long issuedQuotationId = insertQuotation(ticketId, "PRICING_REQUEST", "ISSUED");
        insertQuotationItem(issuedQuotationId, 1, "Issued item (must lose)", BigDecimal.ONE, "แผ่น",
            new BigDecimal("111.00"), BigDecimal.ZERO, new BigDecimal("111.00"));
        long acceptedQuotationId = insertQuotation(ticketId, "PRICING_REQUEST", "ACCEPTED");
        insertQuotationItem(acceptedQuotationId, 1, "Accepted item (must win)", BigDecimal.ONE, "แผ่น",
            new BigDecimal("222.00"), BigDecimal.ZERO, new BigDecimal("222.00"));

        List<DepositNoticeItemRequest> items = repo.findDealQuotationItemsForDeposit(ticketId);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).description()).isEqualTo("Accepted item (must win)");
        assertThat(items.get(0).unitPrice()).isEqualByComparingTo("222.00");
    }

    // ── (d) NEGATIVE, wrong-way-round: nothing in a live status -> empty list ───────────────────

    /**
     * Written wrong-way-round per CLAUDE.md's "Permission changes must ship evidence" guidance,
     * applied here to the status filter: a ticket whose ONLY quotations sit in every non-live
     * status (DRAFT, CANCELLED, SUPERSEDED, PENDING_APPROVAL) must autofill EMPTY, never one of
     * them. This is the test that must go red if someone widens {@code IN
     * ('ACCEPTED','ISSUED','APPROVED')} to include any of these.
     */
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

        List<DepositNoticeItemRequest> items = repo.findDealQuotationItemsForDeposit(ticketId);

        assertThat(items).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

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
        // ux_quotation_ticket_recipient_version is unique on (ticket_id, recipient_type,
        // quotation_version); recipient_type defaults to 'UNSPECIFIED' for every row here, so a
        // second quotation on the SAME ticket needs a distinct quotation_version or the insert
        // collides — increment the shared counter rather than defaulting to 1 every time.
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

    private void insertQuotationItem(long quotationId, int seq, String description, BigDecimal qty,
                                     String rawUnit, BigDecimal unitPrice, BigDecimal salesDiscount,
                                     BigDecimal finalUnitPrice) {
        // amount is legacy NOT NULL (V49) — irrelevant to findDealQuotationItemsForDeposit, which
        // reads unit_price/final_unit_price instead, so a harmless qty*unitPrice placeholder.
        BigDecimal amount = qty.multiply(unitPrice);
        jdbc.update("""
            INSERT INTO sales.quotation_item
                (quotation_id, seq, description, qty, raw_unit, unit_price, sales_discount,
                 final_unit_price, amount)
            VALUES
                (:quotationId, :seq, :description, :qty, :rawUnit, :unitPrice, :salesDiscount,
                 :finalUnitPrice, :amount)
            """, new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("seq", seq)
                .addValue("description", description)
                .addValue("qty", qty)
                .addValue("rawUnit", rawUnit)
                .addValue("unitPrice", unitPrice)
                .addValue("salesDiscount", salesDiscount)
                .addValue("finalUnitPrice", finalUnitPrice)
                .addValue("amount", amount));
    }
}
