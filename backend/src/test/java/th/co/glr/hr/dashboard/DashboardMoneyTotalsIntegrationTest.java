package th.co.glr.hr.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
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
 * The server totals behind the money tiles and /finance's คงค้างรวม: {@code financeOutstanding} (outstanding
 * on deals in account's list scope that have an account to-do) and {@code forecastValue} (payable of live
 * deals before ORDER_RECEIVED), summed in SQL over EVERY deal -- not over the first page of /api/tickets.
 *
 * <p>The deal shapes come from {@code dashboard/money-cases.json}, the same table
 * {@code moneyCases.test.js} checks the frontend rule (account scope + nextAccountAction) against, so the
 * SQL and the JS cannot drift apart unnoticed. Each case is built, the totals are read before and after,
 * and the delta must be its outstanding / payable or nothing.
 */
class DashboardMoneyTotalsIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

    private DashboardRepository dashboard;
    private TicketRepository tickets;
    private long repId;
    private int seq;

    @BeforeEach
    void seed() {
        dashboard = new DashboardRepository(jdbc);
        tickets = new TicketRepository(jdbc);
        long divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th) VALUES ('SA-MT', 'Sales MT') RETURNING division_id
            """, Map.of(), Number.class).longValue();
        repId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES ('EMP-MT', 'EMP-MT', :divisionId, TRUE) RETURNING employee_id
            """, Map.of("divisionId", divisionId), Number.class).longValue();
    }

    @Test
    void everyCaseInTheSharedTableMovesTheTotalsExactlyAsExpected() throws Exception {
        JsonNode table;
        try (InputStream in = getClass().getResourceAsStream("/dashboard/money-cases.json")) {
            table = new ObjectMapper().readTree(in);
        }
        BigDecimal payable = table.get("payable").decimalValue();

        for (JsonNode c : table.get("cases")) {
            BigDecimal outstandingBefore = dashboard.financeOutstanding();
            BigDecimal forecastBefore = dashboard.forecastValue();

            BigDecimal paid = c.get("paid").decimalValue();
            buildDeal(c, payable);
            BigDecimal expectedOutstanding = c.get("countsInOutstanding").asBoolean()
                ? payable.subtract(paid).max(BigDecimal.ZERO) : BigDecimal.ZERO;
            BigDecimal expectedForecast = c.get("countsInForecast").asBoolean() ? payable : BigDecimal.ZERO;
            assertThat(dashboard.financeOutstanding().subtract(outstandingBefore))
                .as("financeOutstanding delta: %s", c.get("name").asText()).isEqualByComparingTo(expectedOutstanding);
            assertThat(dashboard.forecastValue().subtract(forecastBefore))
                .as("forecastValue delta: %s", c.get("name").asText()).isEqualByComparingTo(expectedForecast);
        }
    }

    /** Guards the builder: each fixture is the shape its case names, as /finance's own row sees it. */
    @Test
    void theBuiltDealsAreTheShapesTheCasesName() throws Exception {
        JsonNode table;
        try (InputStream in = getClass().getResourceAsStream("/dashboard/money-cases.json")) {
            table = new ObjectMapper().readTree(in);
        }
        BigDecimal payable = table.get("payable").decimalValue();
        for (JsonNode c : table.get("cases")) {
            long id = buildDeal(c, payable);
            var row = tickets.findSummaryById(id).orElseThrow();
            assertThat(row.amountPayable()).as("payable: %s", c.get("name").asText()).isEqualByComparingTo(payable);
            assertThat(row.overdue()).as("overdue: %s", c.get("name").asText()).isEqualTo(c.get("overdue").asBoolean());
            assertThat(row.commissionRecorded()).as("commission: %s", c.get("name").asText())
                .isEqualTo(c.get("commission").asBoolean());
        }
    }

    @Test
    void totalsIncludeEveryDeal_notJustTheFirstFiveHundred() {
        int deals = 505; // /api/tickets pages at 500
        BigDecimal payable = new BigDecimal("107000.00");
        for (int i = 0; i < deals; i++) {
            long id = newDeal();
            acceptedQuotation(id);
            // a deposit notice is out, before ORDER_RECEIVED: counted by both totals
            jdbc.update("""
                UPDATE sales.ticket SET status = 'quotation_issued', payment_status = 'DEPOSIT_NOTICE_ISSUED', sales_stage = 'QUOTE_BUYER'
                 WHERE ticket_id = :id""", Map.of("id", id));
        }

        assertThat(dashboard.financeOutstanding()).isEqualByComparingTo(payable.multiply(BigDecimal.valueOf(deals)));
        assertThat(dashboard.forecastValue()).isEqualByComparingTo(payable.multiply(BigDecimal.valueOf(deals)));
    }

    // ── builders ────────────────────────────────────────────────────────────────────────────

    private long newDeal() {
        seq++;
        return tickets.create(
            new CreateTicketRequest("ดีลทดสอบ " + seq, "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), repId, "EMP-MT");
    }

    /** A quotation whose subtotal is 100,000, so the VAT-inclusive payable is 107,000. */
    private void acceptedQuotation(long ticketId) {
        long q = tickets.createQuotation(ticketId, "QT-MT-" + ticketId, repId, new BigDecimal("100000.00")).id();
        jdbc.update("UPDATE sales.quotation SET doc_status = 'ACCEPTED' WHERE quotation_id = :q", Map.of("q", q));
    }

    private long buildDeal(JsonNode c, BigDecimal payable) {
        long id = newDeal();
        acceptedQuotation(id);
        MapSqlParameterSource p = new MapSqlParameterSource("id", id)
            .addValue("lifecycle", c.get("lifecycle").asText())
            .addValue("status", c.get("status").asText())
            .addValue("stage", c.get("salesStage").asText())
            .addValue("payment", c.get("paymentStatus").isNull() ? null : c.get("paymentStatus").asText())
            .addValue("policy", c.get("depositPolicy").asText());
        jdbc.update("""
            UPDATE sales.ticket SET lifecycle = :lifecycle, status = :status, sales_stage = :stage,
                   payment_status = :payment, deposit_policy = :policy
             WHERE ticket_id = :id""", p);
        BigDecimal paid = c.get("paid").decimalValue();
        if (paid.signum() > 0) {
            jdbc.update("""
                INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref)
                VALUES (:id, 'DEPOSIT', :a, 'THB', now(), :by, :ref)""", new MapSqlParameterSource("id", id)
                .addValue("a", paid).addValue("by", repId).addValue("ref", "MT-" + id));
        }
        if (c.get("overdue").asBoolean()) {
            // credit 30 days from a delivery 31 days ago -> the balance is a day late
            jdbc.update("UPDATE sales.quotation SET remainder_mode = 'CREDIT', credit_days = 30 WHERE ticket_id = :id",
                Map.of("id", id));
            jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'FULLY_DELIVERED' WHERE ticket_id = :id",
                Map.of("id", id));
            jdbc.update("""
                INSERT INTO sales.delivery_record (ticket_id, source, delivered_at, delivered_by)
                VALUES (:id, 'WAREHOUSE', :at, :by)""", new MapSqlParameterSource("id", id)
                .addValue("at", Timestamp.from(LocalDate.now(BKK).minusDays(31).atTime(12, 0).atZone(BKK).toInstant()))
                .addValue("by", repId));
        }
        if (c.get("commission").asBoolean()) {
            long invoice = jdbc.queryForObject("""
                INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
                VALUES (:n, CURRENT_DATE, 1000.00, 'PRE_ERP_PAPER_APPROVAL') RETURNING invoice_id""",
                Map.of("n", "MT-INV-" + id), Long.class);
            jdbc.update("""
                INSERT INTO sales.commission_record
                    (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                     payroll_month, actual_received, commissionable_base)
                VALUES (:inv, :id, :rep, :rep, 'SALE', 'SUBMITTED',
                        date_trunc('month', CURRENT_DATE)::date, 1000.00, 1000.00)""",
                new MapSqlParameterSource("inv", invoice).addValue("id", id).addValue("rep", repId));
        }
        return id;
    }
}
