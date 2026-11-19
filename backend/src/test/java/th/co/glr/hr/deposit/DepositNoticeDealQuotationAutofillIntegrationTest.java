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
}
