package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;

/**
 * Slice 5 (spec 2026-11-15 §7.11 item 6, plan task 5.5): a payment account records BY HAND — บันทึกรับชำระ, and the
 * one-tap ยืนยันรับมัดจำ / รับชำระส่วนที่เหลือ that go through the same path — marks the deal's nearest OPEN
 * expected payment of the matching kind RECEIVED and links the receipt, exactly as a matched Express receipt does
 * (ExpectedPaymentRepository#markNearestOpenReceived's ordering). Until this, only the Express matcher ever set
 * RECEIVED, so a deposit taken by cheque left the rep's เงินที่จะเข้า card promising it again.
 *
 * <p>Kind mapping: a DEPOSIT receipt can only be a DEPOSIT expectation; a BALANCE receipt can be ON_DELIVERY or
 * BALANCE; an ADJUSTMENT is not cash the rep expected and marks nothing. Written wrong-way-round: the row of the
 * OTHER kind stays OPEN even when its amount matches exactly. Real Postgres through the real TicketService and
 * TicketRepository. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ManualPaymentExpectedPaymentIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private UserPrincipal account;
    private LocalDate monday;

    @BeforeEach
    void wire() {
        account = actor(createEmployee("บัญชี", "acc-manual-ep@glr.co.th", "ACC", "บัญชี", null), "account");
        monday = LocalDate.now(ZoneId.of("Asia/Bangkok")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    @Test
    void aDepositRecordedByHand_marksTheOpenDepositExpectation_notTheBalanceOneOfTheSameAmount() {
        long d = awaitingDeposit("32100.00");
        long balance = expectedRow(d, "BALANCE", "32100.00", monday);
        long deposit = expectedRow(d, "DEPOSIT", "32100.00", monday.plusWeeks(2));

        ticketService.recordPayment(d, pay("DEPOSIT", "32100.00", "CHQ-1"), account);

        long receipt = receiptId(d, "CHQ-1");
        assertThat(status(deposit)).isEqualTo("RECEIVED");
        assertThat(linkedReceipt(deposit)).isEqualTo(receipt);
        assertThat(status(balance)).isEqualTo("OPEN");
        assertThat(linkedReceipt(balance)).isNull();
    }

    @Test
    void aBalanceRecordedByHand_marksAnOnDeliveryOrBalanceExpectation_neverTheDepositOne() {
        long d = deliveredAwaitingBalance();
        long deposit = expectedRow(d, "DEPOSIT", "20000.00", monday);
        long onDelivery = expectedRow(d, "ON_DELIVERY", "20000.00", monday);

        ticketService.recordPayment(d, pay("BALANCE", "20000.00", "TRF-2"), account);

        assertThat(status(onDelivery)).isEqualTo("RECEIVED");
        assertThat(linkedReceipt(onDelivery)).isEqualTo(receiptId(d, "TRF-2"));
        assertThat(status(deposit)).isEqualTo("OPEN");
    }

    @Test
    void ofTwoOpenExpectationsOfTheKind_theOneWithinToleranceIsMarked_beforeTheNearerWeek() {
        long d = awaitingDeposit("32100.00");
        long nearButWrongAmount = expectedRow(d, "DEPOSIT", "5000.00", monday);
        long farButRightAmount = expectedRow(d, "DEPOSIT", "15005.00", monday.plusWeeks(6));

        ticketService.recordPayment(d, pay("DEPOSIT", "15000.00", "CHQ-3"), account);

        assertThat(status(farButRightAmount)).isEqualTo("RECEIVED");
        assertThat(status(nearButWrongAmount)).isEqualTo("OPEN");
    }

    @Test
    void anAdjustmentMarksNothing() {
        long d = deliveredAwaitingBalance();
        long balance = expectedRow(d, "BALANCE", "1000.00", monday);

        ticketService.recordPayment(d, pay("ADJUSTMENT", "1000.00", "ADJ-4"), account);

        assertThat(status(balance)).isEqualTo("OPEN");
    }

    @Test
    void theOneTapDepositConfirm_isAHandRecordedPaymentToo() {
        long d = awaitingDeposit("32100.00");
        long deposit = expectedRow(d, "DEPOSIT", "32100.00", monday);

        ticketService.confirmDepositPaid(d, account);

        assertThat(status(deposit)).isEqualTo("RECEIVED");
    }

    @Test
    void withNoOpenExpectation_thePaymentIsStillRecorded_andACancelledOrReceivedRowIsNotReused() {
        long d = awaitingDeposit("32100.00");
        long cancelled = expectedRow(d, "DEPOSIT", "32100.00", monday);
        jdbc.update("UPDATE sales.expected_payment SET status = 'CANCELLED' WHERE expected_payment_id = :id",
            Map.of("id", cancelled));

        ticketService.recordPayment(d, pay("DEPOSIT", "32100.00", "CHQ-6"), account);

        assertThat(tickets.sumPaid(d)).isEqualByComparingTo("32100");
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static RecordPaymentRequest pay(String kind, String amount, String ref) {
        return new RecordPaymentRequest(kind, new BigDecimal(amount), Instant.parse("2026-11-12T03:00:00Z"),
            null, null, ref, false);
    }

    /** ORDER_RECEIVED waiting on a REQUIRED deposit, with one ISSUED notice for {@code total} — same as
     * ReceiptImportIntegrationTest#awaitingDeposit. */
    private long awaitingDeposit(String total) {
        long d = deal(salesActor);
        jdbc.update("""
            UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED', deposit_policy = 'REQUIRED',
                   payment_status = 'DEPOSIT_NOTICE_ISSUED' WHERE ticket_id = :t""", Map.of("t", d));
        notice(d, total);
        return d;
    }

    /** DELIVERED, deposit 32,100 paid by hand, balance invoiced (74,900.01) — same as
     * ReceiptImportIntegrationTest#deliveredAwaitingBalance. */
    private long deliveredAwaitingBalance() {
        long d = deal(salesActor);
        long notice = notice(d, "32100.00");
        jdbc.update("""
            INSERT INTO sales.remaining_invoice (ticket_id, deposit_notice_id, status, base_number, doc_number,
                issued_at, grand_total)
            VALUES (:id, :notice, 'ISSUED', 'GLR6900088', 'GLR6900088-1', now(), 74900.01)""",
            new MapSqlParameterSource("id", d).addValue("notice", notice));
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref)
            VALUES (:t, 'DEPOSIT', 32100.00, :by, 'MANUAL-DEP')""", Map.of("t", d, "by", account.id()));
        jdbc.update("""
            UPDATE sales.ticket SET sales_stage = 'DELIVERED', deposit_policy = 'REQUIRED',
                   payment_status = 'AWAITING_FINAL_PAYMENT', fulfillment_status = 'FULLY_DELIVERED'
             WHERE ticket_id = :t""", Map.of("t", d));
        return d;
    }

    private long notice(long ticketId, String total) {
        BigDecimal t = new BigDecimal(total);
        return jdbc.queryForObject("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable, doc_number)
            VALUES (:id, 1, 'ISSUED', 'x', 0.3, :t, :t, 0, 0, :t, :doc)
            RETURNING deposit_notice_id""",
            new MapSqlParameterSource("id", ticketId).addValue("t", t).addValue("doc", "DN69" + ticketId),
            Number.class).longValue();
    }

    private long expectedRow(long ticketId, String kind, String amount, LocalDate week) {
        return jdbc.queryForObject("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, created_by)
            VALUES (:t, :k, :a, :w, :by) RETURNING expected_payment_id
            """, Map.of("t", ticketId, "k", kind, "a", new BigDecimal(amount), "w", week, "by", salesActor.id()),
            Long.class);
    }

    private String status(long expectedPaymentId) {
        return jdbc.queryForObject("SELECT status FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", expectedPaymentId), String.class);
    }

    private Long linkedReceipt(long expectedPaymentId) {
        return jdbc.queryForObject("SELECT receipt_id FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", expectedPaymentId), Long.class);
    }

    private long receiptId(long ticketId, String ref) {
        return jdbc.queryForObject(
            "SELECT receipt_id FROM sales.payment_receipt WHERE ticket_id = :t AND receipt_ref = :r",
            Map.of("t", ticketId, "r", ref), Long.class);
    }
}
