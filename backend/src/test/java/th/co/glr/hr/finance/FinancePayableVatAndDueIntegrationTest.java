package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.ticket.RecordPaymentRequest;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * OWNER RULINGS 2026-09-30 (money business logic, real Postgres through the real service + repository):
 * <ol>
 *   <li>A. A deal's PAYABLE is the quotation's VAT-INCLUSIVE grand total (commission stays pre-VAT --
 *       pinned in CommissionAutoCreateIntegrationTest).</li>
 *   <li>B. OVERDUE is derived from the payable quotation's own terms, not the billing due_date column.</li>
 * </ol>
 */
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
    void payable_hasNoVat_forAnEnglishDocument() {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");
        jdbc.update("UPDATE sales.quotation SET document_language = 'EN' WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));

        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo("100000.00");
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

    // ── B. Overdue from the quotation's terms ──────────────────────────────────────────────

    private long creditDeal(int creditDays, LocalDate deliveredOn) {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");
        jdbc.update("UPDATE sales.quotation SET remainder_mode = 'CREDIT', credit_days = :d WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId).addValue("d", creditDays));
        if (deliveredOn != null) deliver(ticketId, deliveredOn.atTime(12, 0).atZone(BKK).toInstant());
        return ticketId;
    }

    private long onDeliveryDeal(LocalDateTime deliveredAtBangkok) {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");
        jdbc.update("UPDATE sales.quotation SET remainder_mode = 'ON_DELIVERY' WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        if (deliveredAtBangkok != null) deliver(ticketId, deliveredAtBangkok.atZone(BKK).toInstant());
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

    private boolean inAccountList(long ticketId) {
        return ticketService.listPage(null, accountUser, PageRequest.resolve(0, 200)).items().stream()
            .anyMatch(t -> t.id() == ticketId);
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
    void credit30_paidInFullInclVat_isNotOverdue() {
        long ticketId = creditDeal(30, today().minusDays(60));
        insertPayment(ticketId, "BALANCE", new BigDecimal("107000.00"));

        assertThat(summary(ticketId).overdue()).isFalse();
    }

    @Test
    void credit30_notDelivered_hasNoDueDate_andIsNeverOverdue() {
        long ticketId = creditDeal(30, null);

        TicketSummaryDto s = summary(ticketId);
        assertThat(s.paymentDueDate()).isNull();
        // the term is known even though the date is not: a UI can say "waiting for delivery"
        assertThat(s.paymentDueBasis()).isEqualTo("CREDIT_FROM_DELIVERY");
        assertThat(s.paymentDueCreditDays()).isEqualTo(30);
        assertThat(s.overdue()).isFalse();
    }

    @Test
    void onDeliveryTerms_deliveredYesterday_withBalance_isOverdue_basisOnDelivery() {
        long ticketId = onDeliveryDeal(today().minusDays(1).atTime(12, 0));

        TicketSummaryDto s = summary(ticketId);
        assertThat(s.paymentDueDate()).isEqualTo(today().minusDays(1));
        assertThat(s.paymentDueBasis()).isEqualTo("ON_DELIVERY");
        assertThat(s.paymentDueCreditDays()).isNull();
        assertThat(s.overdue()).isTrue();
    }

    @Test
    void onDeliveryTerms_deliveredToday_isNotYetOverdue() {
        long ticketId = onDeliveryDeal(today().atTime(9, 0));

        assertThat(summary(ticketId).overdue()).isFalse();
    }

    @Test
    void zeroDepositFullPaymentTerm_isTreatedAsOnDelivery() {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");
        jdbc.update("UPDATE sales.quotation SET deposit_percent = 0, full_payment_term = 'ON_OR_BEFORE_DELIVERY' WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        deliver(ticketId, today().minusDays(3).atTime(10, 0).atZone(BKK).toInstant());

        TicketSummaryDto s = summary(ticketId);
        assertThat(s.paymentDueBasis()).isEqualTo("ON_DELIVERY");
        assertThat(s.overdue()).isTrue();
    }

    @Test
    void unpaidDeposit_isNeverOverdue_evenWhenDeliveredLongAgo() {
        long ticketId = creditDeal(30, today().minusDays(90));
        tickets.updatePaymentStatusUnchecked(ticketId, "DEPOSIT_NOTICE_ISSUED");

        assertThat(summary(ticketId).overdue()).isFalse();
    }

    @Test
    void legacyFreeTextTerms_haveNoStructuredDueDate_andAreNeverOverdue() {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");
        jdbc.update("UPDATE sales.quotation SET remainder_mode = NULL, credit_days = NULL, payment_terms = 'เครดิต 30 วัน' WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        deliver(ticketId, today().minusDays(90).atTime(10, 0).atZone(BKK).toInstant());

        TicketSummaryDto s = summary(ticketId);
        assertThat(s.paymentDueDate()).isNull();
        assertThat(s.paymentDueBasis()).isNull();
        assertThat(s.overdue()).isFalse();
    }

    @Test
    void noQuotationAtAll_hasNoDueDate() {
        long ticketId = createTicket("PROCUREMENT");
        deliver(ticketId, today().minusDays(90).atTime(10, 0).atZone(BKK).toInstant());

        assertThat(summary(ticketId).paymentDueDate()).isNull();
        assertThat(summary(ticketId).overdue()).isFalse();
    }

    @Test
    void deliveryDate_isTheBangkokCalendarDay_notTheUtcDay() {
        // 01:00 Bangkok on D is 18:00Z on D-1: the UTC date is the WRONG day.
        LocalDate day = today().minusDays(5);
        long early = onDeliveryDeal(day.atTime(1, 0));
        assertThat(summary(early).paymentDueDate()).isEqualTo(day);

        // 23:30 Bangkok on D is 16:30Z on D: still D in both, and must not roll to D+1.
        long late = onDeliveryDeal(day.atTime(23, 30));
        assertThat(summary(late).paymentDueDate()).isEqualTo(day);
    }

    @Test
    void theStoredBillingDueDate_nolongerDrivesOverdue() {
        long ticketId = creditDeal(30, today().minusDays(10)); // terms say due in 20 days
        tickets.updateBilling(ticketId, today().minusDays(40), today().minusDays(10), 30, null, null);

        TicketSummaryDto s = summary(ticketId);
        assertThat(s.dueDate()).isEqualTo(today().minusDays(10)); // the stored column is untouched
        assertThat(s.overdue()).isFalse();                         // the quotation terms decide
    }

    @Test
    void accountListScope_agreesWithTheOverdueFlag() {
        // A stage OUTSIDE account's order-stage scope and a non-pending payment status: the ONLY way in is overdue.
        long overdue = creditDeal(30, today().minusDays(45));
        long notOverdue = creditDeal(30, today().minusDays(5));
        for (long id : new long[] {overdue, notOverdue}) {
            tickets.updateSalesStage(id, "NEGOTIATION");
            tickets.updatePaymentStatusUnchecked(id, "CUSTOMER_CONFIRMED");
        }
        // The legacy rule would have pulled the not-overdue deal in via its stored billing due date.
        tickets.updateBilling(notOverdue, today().minusDays(40), today().minusDays(1), 30, null, null);

        assertThat(summary(overdue).overdue()).isTrue();
        assertThat(summary(notOverdue).overdue()).isFalse();
        assertThat(inAccountList(overdue)).isTrue();
        assertThat(inAccountList(notOverdue)).isFalse();
        assertThat(tickets.isInAccountScope(overdue)).isTrue();
        assertThat(tickets.isInAccountScope(notOverdue)).isFalse();
    }
}
