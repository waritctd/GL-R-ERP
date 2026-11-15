package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.ticket.RecordPaymentRequest;

/**
 * Review M1: a receipt — typed by account OR uploaded from Express, through the one shared rule
 * (TicketRepository#applyReceiptToExpectedPayments) — adds to the nearest OPEN expected payment of the matching kind,
 * and the row turns RECEIVED only once the cumulative amount reaches it (within ±1% / ±฿10). A part payment leaves it
 * OPEN with its remainder (probe P3). Real Postgres. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ExpectedPaymentReceiptIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private UserPrincipal account;
    private LocalDate monday;

    @BeforeEach
    void wire() {
        account = actor(createEmployee("บัญชี", "acc-epr@glr.co.th", "ACC", "บัญชี", null), "account");
        monday = LocalDate.now(VisitService.BANGKOK).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    @Test
    void aManualPartPaymentKeepsTheRowOpenWithTheRest_theSecondCompletesIt() {
        long d = orderDeal("200000.00");
        long row = expectedRow(d, "DEPOSIT", "1000.00", monday);

        manual(d, "DEPOSIT", "400");
        assertThat(status(row)).isEqualTo("OPEN");
        assertThat(received(row)).isEqualByComparingTo("400");

        long second = manual(d, "DEPOSIT", "600");
        assertThat(status(row)).isEqualTo("RECEIVED");
        assertThat(received(row)).isEqualByComparingTo("1000");
        assertThat(receiptOf(row)).isEqualTo(second);
    }

    @Test
    void probeP3_halfOfTheExpectedAmountByExpressIsNotReceived() {
        long d = orderDeal("200000.00");
        long row = expectedRow(d, "DEPOSIT", "100000.00", monday);

        ticketService.recordExpressReceipt(d, new BigDecimal("50000.00"), Instant.now(), "RE-P3-1", null, account);

        assertThat(status(row)).isEqualTo("OPEN");
        assertThat(received(row)).isEqualByComparingTo("50000");
    }

    /** Review N2: rows fill in a fixed order — DEPOSIT, then ON_DELIVERY, then BALANCE — whatever the receipt's kind. */
    @Test
    void aManualReceiptFillsDepositThenOnDeliveryThenBalance_andCarriesTheOverflow() {
        long d = orderDeal("200000.00");
        long balance = expectedRow(d, "BALANCE", "300.00", monday);           // earliest week, but BALANCE comes last
        long onDelivery = expectedRow(d, "ON_DELIVERY", "300.00", monday.plusWeeks(1));
        long deposit = expectedRow(d, "DEPOSIT", "300.00", monday.plusWeeks(2));

        manual(d, "BALANCE", "600");

        assertThat(status(deposit)).as("a BALANCE-kind receipt still pays the open deposit first").isEqualTo("RECEIVED");
        assertThat(status(onDelivery)).isEqualTo("RECEIVED");
        assertThat(status(balance)).isEqualTo("OPEN");
        assertThat(received(balance)).isEqualByComparingTo("0");
    }

    /** Probe Q2: a deposit paid in two Express halves completes the DEPOSIT row and touches no balance row. */
    @Test
    void probeQ2_aDepositPaidInTwoExpressHalvesCompletesTheDepositRow() {
        long d = orderDeal("200000.00");
        jdbc.update("UPDATE sales.ticket SET deposit_policy = 'REQUIRED', payment_status = 'DEPOSIT_NOTICE_ISSUED'"
            + " WHERE ticket_id = :t", Map.of("t", d));
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable, doc_number)
            VALUES (:t, 1, 'ISSUED', 'x', 0.3, 32100, 32100, 0, 0, 32100, 'DN-Q2')""", Map.of("t", d));
        long deposit = expectedRow(d, "DEPOSIT", "32100.00", monday);
        long balance = expectedRow(d, "BALANCE", "16050.00", monday.minusWeeks(1));

        ticketService.recordExpressReceipt(d, new BigDecimal("16050.00"), Instant.now(), "RE-Q2-1", null, account);
        assertThat(status(deposit)).isEqualTo("OPEN");
        ticketService.recordExpressReceipt(d, new BigDecimal("16050.00"), Instant.now(), "RE-Q2-2", null, account);

        assertThat(status(deposit)).isEqualTo("RECEIVED");
        assertThat(status(balance)).isEqualTo("OPEN");
        assertThat(received(balance)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForList("SELECT kind FROM sales.payment_receipt WHERE ticket_id = :t ORDER BY receipt_id",
            Map.of("t", d), String.class)).as("both halves are deposit money while the notice is still owed")
            .containsExactly("DEPOSIT", "DEPOSIT");
    }

    @Test
    void aBypassPolicyDealsRepEnteredDepositRowIsClosedByItsReceipt() {
        long d = orderDeal("200000.00");
        jdbc.update("UPDATE sales.ticket SET deposit_policy = 'NOT_REQUIRED' WHERE ticket_id = :t", Map.of("t", d));
        long deposit = expectedRow(d, "DEPOSIT", "5000.00", monday);

        ticketService.recordExpressReceipt(d, new BigDecimal("5000.00"), Instant.now(), "RE-BP-1", null, account);

        assertThat(status(deposit)).isEqualTo("RECEIVED");
    }

    @Test
    void withinToleranceCountsAsReceived() {
        long d = orderDeal("200000.00");
        long row = expectedRow(d, "BALANCE", "10000.00", monday);
        manual(d, "BALANCE", "9905"); // 0.95% short
        assertThat(status(row)).isEqualTo("RECEIVED");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** In account's finance scope (ORDER_RECEIVED) with an ACCEPTED EN quotation of {@code total} as its payable. */
    private long orderDeal(String total) {
        long d = deal(salesActor);
        long q = approved(d, salesActor, "OWNER").id();
        setDocStatus(q, "ACCEPTED");
        jdbc.update("UPDATE sales.quotation SET total_amount = :a, document_language = 'EN' WHERE quotation_id = :q",
            Map.of("a", new BigDecimal(total), "q", q));
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED' WHERE ticket_id = :t", Map.of("t", d));
        return d;
    }

    private long manual(long ticketId, String kind, String amount) {
        ticketService.recordPayment(ticketId,
            new RecordPaymentRequest(kind, new BigDecimal(amount), null, null, null, null, false), account);
        return jdbc.queryForObject("SELECT max(receipt_id) FROM sales.payment_receipt WHERE ticket_id = :t",
            Map.of("t", ticketId), Long.class);
    }

    private long expectedRow(long ticketId, String kind, String amount, LocalDate week) {
        return jdbc.queryForObject("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, created_by)
            VALUES (:t, :k, :a, :w, :by) RETURNING expected_payment_id
            """, Map.of("t", ticketId, "k", kind, "a", new BigDecimal(amount), "w", week, "by", salesActor.id()),
            Long.class);
    }

    private String status(long row) {
        return jdbc.queryForObject("SELECT status FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", row), String.class);
    }

    private BigDecimal received(long row) {
        return jdbc.queryForObject("SELECT received_amount FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", row), BigDecimal.class);
    }

    private Long receiptOf(long row) {
        return jdbc.queryForObject("SELECT receipt_id FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", row), Long.class);
    }
}
