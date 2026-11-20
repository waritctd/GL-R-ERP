package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.ticket.RecordPaymentRequest;
import th.co.glr.hr.ticket.TicketSummaryDto;

/** OWNER RULINGS 2026-09-30 (money business logic, real Postgres through the real service + repository): A. A
 * deal's PAYABLE is the quotation's VAT-INCLUSIVE grand total (commission stays pre-VAT -- pinned in
 * CommissionAutoCreateIntegrationTest). */
class FinancePayableVatAndDueIntegrationTest extends FinanceDealTestBase {

    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

    // ── A. VAT-inclusive payable ───────────────────────────────────────────────────────────

    @Test
    void payable_isTheVatInclusiveGrandTotal_ofThePayableQuotation() {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT"); // subtotal 100,000.00

        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo("107000.00");
        assertThat(tickets.payableAmountExVat(ticketId)).isEqualByComparingTo("100000.00");
        FinanceDealDto.Money money = service.get(ticketId, accountUser).money();
        assertThat(money.amountPayable()).isEqualByComparingTo("107000.00");
        assertThat(money.amountOutstanding()).isEqualByComparingTo("107000.00");
        assertThat(money.amountVatBasis()).isEqualTo(FinanceDealDto.INCLUDING_VAT);
    }

    @Test
    void vatRounding_isRound2OfSubtotalTimesRate_likeTheQuotationDocument() {
        long ticketId = createTicket("PROCUREMENT");
        insertAcceptedQuotation(ticketId, "QT-RND-" + ticketId, new BigDecimal("33333.33")); // 33333.33 x 7% = 2333.3331

        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo("35666.66");
    }

    @Test
    void payingThePreVatAmount_leavesTheVatBalance_andDoesNotReachFullyPaidOrCloseReady() {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");

        ticketService.recordPayment(ticketId,
            new RecordPaymentRequest("BALANCE", new BigDecimal("100000.00"), null, null, null, "R-PRE", false),
            accountUser);

        TicketSummaryDto s = tickets.findSummaryById(ticketId).orElseThrow();
        assertThat(s.amountOutstanding()).isEqualByComparingTo("7000.00");
        assertThat(paymentStatusOf(ticketId)).isNotEqualTo("FULLY_PAID");
        assertThat(service.get(ticketId, accountUser).money().amountOutstanding()).isEqualByComparingTo("7000.00");
    }

    @Test
    void payingTheFullVatInclusiveAmount_isNotOverpayment_andReachesFullyPaid() {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");

        ticketService.recordPayment(ticketId,
            new RecordPaymentRequest("BALANCE", new BigDecimal("107000.00"), null, null, null, "R-FULL", false),
            accountUser);

        assertThat(paymentStatusOf(ticketId)).isEqualTo("FULLY_PAID");
        assertThat(tickets.findSummaryById(ticketId).orElseThrow().amountOutstanding()).isEqualByComparingTo("0");
    }

    /** A deal whose only quotation is a DEAL_DIRECT one in its terminal live state, APPROVED (subtotal 100,000). */
    private long directApprovedDeal(String origin) {
        long ticketId = unconfirmedDirectApprovedDeal(origin);
        tickets.updatePaymentStatusUnchecked(ticketId, "CUSTOMER_CONFIRMED"); // confirm-order puts the deal on the payment track
        // ...and writes the per-quotation fact the payable rule keys on: the promotion event naming the quotation
        long quotationId = jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId), Long.class);
        tickets.addEventWithDocument(ticketId, salesRepId, "ทดสอบ", "DEAL_PROMOTED_FROM_QUOTATION", null, null,
            "ยืนยันคำสั่งซื้อ", "QUOTATION", quotationId);
        return ticketId;
    }

    private long unconfirmedDirectApprovedDeal(String origin) {
        long ticketId = createTicket("PROCUREMENT");
        long q = insertAcceptedQuotation(ticketId, "QT-DD-" + ticketId, new BigDecimal("100000.00"));
        jdbc.update("UPDATE sales.quotation SET origin = :o, doc_status = 'APPROVED' WHERE quotation_id = :q",
            new MapSqlParameterSource("o", origin).addValue("q", q));
        return ticketId;
    }

    @Test
    void confirmedDirectQuotation_approved_balanceIsNotFullyPaidBeforeTheMoneyArrives() {
        long ticketId = directApprovedDeal("DEAL_DIRECT");
        tickets.updatePaymentStatusUnchecked(ticketId, "DEPOSIT_PAID");
        insertPayment(ticketId, "DEPOSIT", new BigDecimal("53500.00"));

        assertThat(tickets.findSummaryById(ticketId).orElseThrow().amountOutstanding()).isEqualByComparingTo("53500.00");
    }

    @Test
    void acceptedQuotation_outranksAnApprovedDirectOne_inAllThreeQueries() {
        long ticketId = createS15Deal("CUSTOMER_CONFIRMED"); // ACCEPTED, 100,000, (recipient default)
        jdbc.update("UPDATE sales.quotation SET recipient_type = 'OWNER', remainder_mode = 'CREDIT', credit_days = 30 WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        long dd = tickets.createQuotation(ticketId, "QT-DD-RANK-" + ticketId, salesRepId, new BigDecimal("200000.00")).id();
        jdbc.update("UPDATE sales.quotation SET origin = 'DEAL_DIRECT', doc_status = 'APPROVED', recipient_type = 'BUYER' WHERE quotation_id = :q",
            new MapSqlParameterSource("q", dd));
        deliver(ticketId, today().minusDays(10).atTime(12, 0).atZone(BKK).toInstant());

        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo("107000.00");
        assertThat(service.get(ticketId, accountUser).documents().acceptedQuotation().number())
            .isEqualTo("QT-LK-" + ticketId);
        assertThat(summary(ticketId).paymentDueBasis()).isEqualTo("CREDIT_FROM_DELIVERY");
    }

    // ── B. Overdue from the quotation's terms ──────────────────────────────────────────────

    private long creditDeal(int creditDays, LocalDate deliveredOn) {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");
        jdbc.update("UPDATE sales.quotation SET remainder_mode = 'CREDIT', credit_days = :d WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId).addValue("d", creditDays));
        if (deliveredOn != null) deliver(ticketId, deliveredOn.atTime(12, 0).atZone(BKK).toInstant());
        return ticketId;
    }

    private void deliver(long ticketId, Instant at) {
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'FULLY_DELIVERED' WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        jdbc.update("""
            INSERT INTO sales.delivery_record (ticket_id, source, delivered_at, delivered_by)
            VALUES (:t, 'WAREHOUSE', :at, :by)
            """, new MapSqlParameterSource().addValue("t", ticketId)
            .addValue("at", java.sql.Timestamp.from(at)).addValue("by", salesRepId));
    }

    private static LocalDate today() {
        return LocalDate.now(BKK);
    }

    private TicketSummaryDto summary(long ticketId) {
        return tickets.findSummaryById(ticketId).orElseThrow();
    }

    @Test
    void credit30_deliveredThirtyOneDaysAgo_withBalance_isOverdue_dueDateIsDeliveryPlus30() {
        long ticketId = creditDeal(30, today().minusDays(31));

        TicketSummaryDto s = summary(ticketId);
        assertThat(s.paymentDueDate()).isEqualTo(today().minusDays(1));
        assertThat(s.paymentDueBasis()).isEqualTo("CREDIT_FROM_DELIVERY");
        assertThat(s.paymentDueCreditDays()).isEqualTo(30);
        assertThat(s.overdue()).isTrue();
        assertThat(service.get(ticketId, accountUser).money().overdue()).isTrue();
    }

    @Test
    void credit30_deliveredTwentyNineDaysAgo_isNotOverdue_butHasADueDate() {
        long ticketId = creditDeal(30, today().minusDays(29));

        TicketSummaryDto s = summary(ticketId);
        assertThat(s.paymentDueDate()).isEqualTo(today().plusDays(1));
        assertThat(s.overdue()).isFalse();
    }

    @Test
    void zeroDepositCreditTerm_isDueDeliveryPlusCreditDays() {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");
        jdbc.update("UPDATE sales.quotation SET deposit_percent = 0, remainder_mode = NULL, full_payment_term = 'CREDIT', credit_days = 30 WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        deliver(ticketId, today().minusDays(3).atTime(10, 0).atZone(BKK).toInstant());

        TicketSummaryDto s = summary(ticketId);
        assertThat(s.paymentDueBasis()).isEqualTo("CREDIT_FROM_DELIVERY");
        assertThat(s.paymentDueDate()).isEqualTo(today().minusDays(3).plusDays(30));
        assertThat(s.overdue()).isFalse();
    }

    @Test
    void unpaidDeposit_isNeverOverdue_evenWhenDeliveredLongAgo() {
        long ticketId = creditDeal(30, today().minusDays(90));
        tickets.updatePaymentStatusUnchecked(ticketId, "DEPOSIT_NOTICE_ISSUED");

        assertThat(summary(ticketId).overdue()).isFalse();
    }
}
