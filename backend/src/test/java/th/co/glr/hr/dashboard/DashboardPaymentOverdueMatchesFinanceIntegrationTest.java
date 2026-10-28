package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * The dashboard's {@code paymentOverdue} counts exactly the deals the finance side marks overdue
 * ({@code TicketRepository}'s summary): the same VAT-inclusive payable (owner rulings 2026-09-30 / 2026-10-28),
 * the same due date (the payable quotation's terms + delivery, never the stored {@code due_date}), and no
 * overdue while the deposit is unpaid. Each test asserts the absolute count AND parity with finance.
 */
class DashboardPaymentOverdueMatchesFinanceIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

    private DashboardRepository dashboard;
    private TicketRepository tickets;
    private long repId;

    @BeforeEach
    void seed() {
        dashboard = new DashboardRepository(jdbc);
        tickets = new TicketRepository(jdbc);
        long divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th) VALUES ('SA-OD', 'Sales OD') RETURNING division_id
            """, Map.of(), Number.class).longValue();
        repId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES ('EMP-OD', 'EMP-OD', :divisionId, TRUE) RETURNING employee_id
            """, Map.of("divisionId", divisionId), Number.class).longValue();
    }

    @Test
    void ownerDirectDeal_paidThePreVatAmount_isStillOverdue_forTheVat() {
        long id = creditDealDelivered31DaysAgo("ACCEPTED", "CUSTOMER_CONFIRMED");
        pay(id, "100000.00"); // pre-VAT subtotal; payable is 107,000.00

        assertOverdueCount(1, id);
    }

    @Test
    void ownerDirectDeal_paidTheVatInclusiveAmount_isNotOverdue() {
        long id = creditDealDelivered31DaysAgo("ACCEPTED", "CUSTOMER_CONFIRMED");
        pay(id, "107000.00");

        assertOverdueCount(0, id);
    }

    @Test
    void confirmedDealDirectQuotation_stillApproved_isPayable_andOverdue() {
        long id = creditDealDelivered31DaysAgo("APPROVED", "CUSTOMER_CONFIRMED"); // confirm-order leaves it APPROVED
        jdbc.update("UPDATE sales.quotation SET origin = 'DEAL_DIRECT' WHERE ticket_id = :id", Map.of("id", id));
        long quotationId = jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE ticket_id = :id",
            Map.of("id", id), Long.class);
        tickets.addEventWithDocument(id, repId, "ทดสอบ", "DEAL_PROMOTED_FROM_QUOTATION", null, null,
            "ยืนยันคำสั่งซื้อ", "QUOTATION", quotationId); // the per-quotation fact the payable rule keys on

        assertOverdueCount(1, id);
    }

    @Test
    void issuedRemainingInvoice_payableFollowsTheIssuedDocuments_aSatangShortIsOverdue() {
        long id = creditDealDelivered31DaysAgo("ACCEPTED", "CUSTOMER_CONFIRMED");
        // per-document VAT rounding: 32,100.00 + 74,900.01 = 107,000.01, one satang above the quotation's 107,000.00
        long notice = jdbc.queryForObject("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:id, 1, 'ISSUED', 'x', 0.3, 100000.00, 30000.00, 0.07, 2100.00, 32100.00)
            RETURNING deposit_notice_id""", Map.of("id", id), Number.class).longValue();
        jdbc.update("""
            INSERT INTO sales.remaining_invoice (ticket_id, deposit_notice_id, status, base_number, doc_number,
                issued_at, grand_total)
            VALUES (:id, :notice, 'ISSUED', 'GLR6999999', 'GLR6999999-1', now(), 74900.01)""",
            new MapSqlParameterSource("id", id).addValue("notice", notice));
        pay(id, "107000.00");

        assertOverdueCount(1, id);
    }

    @Test
    void storedDueDateInThePast_withoutQuotationTerms_isNotOverdue() {
        long id = createDeal();
        insertQuotation(id, "ACCEPTED"); // no remainder_mode / credit_days: no derived due date
        setStoredDueDate(id, today().minusDays(10));

        assertOverdueCount(0, id);
    }

    @Test
    void unpaidDeposit_isNeverOverdue_evenLongAfterDelivery() {
        long id = creditDealDelivered31DaysAgo("ACCEPTED", "DEPOSIT_NOTICE_ISSUED");

        assertOverdueCount(0, id);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    /** Quotation subtotal 100,000 (TH, 107,000 incl. VAT), CREDIT 30 days, fully delivered 31 days ago. */
    private long creditDealDelivered31DaysAgo(String quotationStatus, String paymentStatus) {
        long id = createDeal();
        insertQuotation(id, quotationStatus);
        jdbc.update("UPDATE sales.quotation SET remainder_mode = 'CREDIT', credit_days = 30 WHERE ticket_id = :id",
            Map.of("id", id));
        tickets.updatePaymentStatusUnchecked(id, paymentStatus);
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'FULLY_DELIVERED' WHERE ticket_id = :id",
            Map.of("id", id));
        jdbc.update("""
            INSERT INTO sales.delivery_record (ticket_id, source, delivered_at, delivered_by)
            VALUES (:id, 'WAREHOUSE', :at, :by)""", new MapSqlParameterSource("id", id)
            .addValue("at", Timestamp.from(today().minusDays(31).atTime(12, 0).atZone(BKK).toInstant()))
            .addValue("by", repId));
        // the stored billing due_date is past too, so the pre-fix query is only wrong about the AMOUNT
        setStoredDueDate(id, today().minusDays(1));
        return id;
    }

    private long createDeal() {
        long id = tickets.create(
            new CreateTicketRequest("ดีลทดสอบ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), repId, "EMP-OD");
        tickets.updateSalesStage(id, "PROCUREMENT");
        return id;
    }

    private void insertQuotation(long ticketId, String status) {
        long q = tickets.createQuotation(ticketId, "QT-OD-" + ticketId, repId, new BigDecimal("100000.00")).id();
        jdbc.update("UPDATE sales.quotation SET doc_status = :s WHERE quotation_id = :q",
            new MapSqlParameterSource("s", status).addValue("q", q));
    }

    private void setStoredDueDate(long ticketId, LocalDate dueDate) {
        jdbc.update("UPDATE sales.ticket SET due_date = :d WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId).addValue("d", dueDate));
    }

    private void pay(long ticketId, String amount) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref)
            VALUES (:t, 'BALANCE', :a, 'THB', now(), :by, :ref)""", new MapSqlParameterSource()
            .addValue("t", ticketId).addValue("a", new BigDecimal(amount))
            .addValue("by", repId).addValue("ref", "OD-" + ticketId));
    }

    private void assertOverdueCount(long expected, long ticketId) {
        long dashboardCount = dashboard.tickets(DashboardQueryScope.self(repId), today().withDayOfMonth(1),
            OffsetDateTime.now(BKK)).paymentOverdue();
        boolean financeOverdue = tickets.findSummaryById(ticketId).orElseThrow().overdue();

        assertThat(financeOverdue).as("finance summary overdue").isEqualTo(expected == 1);
        assertThat(dashboardCount).as("dashboard paymentOverdue").isEqualTo(expected);
    }

    private static LocalDate today() {
        return LocalDate.now(BKK);
    }
}
