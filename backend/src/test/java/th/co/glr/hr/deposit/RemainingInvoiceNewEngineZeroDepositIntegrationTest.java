package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * W5-01 (walk 5): a deal quoted with no deposit (0% / WAIVED / CREDIT_CUSTOMER) on the NEW quotation engine
 * (PRICING_REQUEST- or DEAL_DIRECT-origin rows in sales.quotation) has no legacy CustomerQuotation and no
 * deposit notice, so the remaining-invoice resolver found nothing to bill and answered 409 for every such deal.
 * The "answered quotation" is the same one payable reads (TicketRepository#ANSWERED_QUOTATION_SQL); the invoice
 * is for the full payable with no deposit deduction, VAT = round2(net x 7%) on its own document.
 */
class RemainingInvoiceNewEngineZeroDepositIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private RemainingInvoiceService remainingInvoices;
    private long employeeId;
    private UserPrincipal owner;
    private int seq;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        CustomerQuotationRepository quotations = new CustomerQuotationRepository(jdbc);
        DepositNoticeService depositNotices = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), new DepositNoticePdfRenderer(),
            new CustomerRepository(jdbc), quotations);
        remainingInvoices = new RemainingInvoiceService(new RemainingInvoiceRepository(jdbc), depositNotices, tickets,
            new RemainingInvoicePdfRenderer());
        employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th)
            VALUES ('RI0-OWN', 'ทดสอบ', 'ใบแจ้งหนี้') RETURNING employee_id
            """, Map.of(), Long.class);
        owner = new UserPrincipal(employeeId, "ri0@glr.co.th", "owner", "sales", employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    @Test
    void zeroPercentPcrDeal_issuesTheInvoiceForTheFullPayable() {
        long ticket = deal("NOT_REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "60.00");
        line(q, 2, "กระเบื้อง B", "5", "80.00");

        var draft = remainingInvoices.createDraft(ticket, null, owner);

        assertThat(draft.itemsTotal()).isEqualByComparingTo("1000.00");
        assertThat(draft.depositDeduction()).isEqualByComparingTo("0");
        assertThat(draft.netAmount()).isEqualByComparingTo("1000.00");
        assertThat(draft.vatAmount()).isEqualByComparingTo("70.00");
        assertThat(draft.grandTotal()).as("= the deal's payable").isEqualByComparingTo(tickets.payableAmount(ticket));
        assertThat(draft.items()).hasSize(2);
        assertThat(remainingInvoices.issue(draft.id(), owner).status()).isEqualTo("ISSUED");
    }

    @Test
    void zeroPercentDirectDeal_issuesTheInvoiceForTheFullPayable() {
        long ticket = deal("WAIVED");
        long q = quotation(ticket, "DEAL_DIRECT", "APPROVED", "1234.50");
        line(q, 1, "รายการตรง", "10", "123.45");
        // confirm-order leaves a DEAL_DIRECT quotation APPROVED; the promotion event is what makes it "answered".
        jdbc.update("""
            INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, related_document_type, related_document_id)
            VALUES (:t, :by, 'x', 'DEAL_PROMOTED_FROM_QUOTATION', 'QUOTATION', :q)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("by", employeeId).addValue("q", q));

        var draft = remainingInvoices.createDraft(ticket, null, owner);

        assertThat(draft.itemsTotal()).isEqualByComparingTo("1234.50");
        assertThat(draft.vatAmount()).isEqualByComparingTo("86.42"); // round2(1234.50 x 7%) = 86.415 -> 86.42
        assertThat(draft.grandTotal()).isEqualByComparingTo(tickets.payableAmount(ticket));
        assertThat(remainingInvoices.issue(draft.id(), owner).status()).isEqualTo("ISSUED");
    }

    @Test
    void allStockZeroPercentDeal_issuesTheInvoiceForTheFullPayable() {
        long ticket = deal("CREDIT_CUSTOMER");
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'FULLY_DELIVERED', sales_stage = 'DELIVERED' WHERE ticket_id = :t",
            Map.of("t", ticket));
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, qty_from_stock, qty_delivered, unit, sort_order)
            VALUES (:t, 'SCG', 'Stock', '60x60', 10, 10, 10, 'pcs', 0)
            """, Map.of("t", ticket));
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "600.00");
        line(q, 1, "สต็อก", "10", "60.00");

        var draft = remainingInvoices.createDraft(ticket, null, owner);

        assertThat(draft.depositDeduction()).isEqualByComparingTo("0");
        assertThat(draft.grandTotal()).isEqualByComparingTo(tickets.payableAmount(ticket));
        assertThat(remainingInvoices.issue(draft.id(), owner).status()).isEqualTo("ISSUED");
    }

    // An EN quotation carries no VAT (payable reads it as 0%): the invoice must not add 7%, before or after issue
    // (once issued, payable follows the document - a wrong 7% here would bill the customer extra).
    @Test
    void zeroPercentEnglishDeal_hasNoVat_andTheInvoiceEqualsPayableBeforeAndAfterIssue() {
        long ticket = deal("NOT_REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        jdbc.update("UPDATE sales.quotation SET document_language = 'EN' WHERE quotation_id = :q", Map.of("q", q));
        line(q, 1, "Tile A", "10", "100.00");
        BigDecimal payable = tickets.payableAmount(ticket);
        assertThat(payable).as("fixture: an EN quotation has no VAT").isEqualByComparingTo("1000.00");

        var draft = remainingInvoices.createDraft(ticket, null, owner);

        assertThat(draft.vatAmount()).isEqualByComparingTo("0");
        assertThat(draft.grandTotal()).isEqualByComparingTo(payable);
        var issued = remainingInvoices.issue(draft.id(), owner);
        assertThat(issued.vatAmount()).isEqualByComparingTo("0");
        assertThat(tickets.payableAmount(ticket)).as("payable follows the issued document").isEqualByComparingTo("1000.00");
    }

    // Owner ruling (Ploy): the invoice prints the QUOTATION'S OWN lines - each line's stored amount, the ส่วนลดพิเศษ
    // (ADJUSTMENT) as its own negative line - and they add up to the stored total. Here 3 x 33.3333 is stored as 99.99
    // (not the 100.00 a recompute gives) and a -10.00 discount follows: 89.99 net, 6.30 VAT, 96.29 = payable.
    @Test
    void zeroPercentDeal_printsTheQuotationsOwnLines_discountIncluded_andTheTotalIsTheStoredOne() {
        long ticket = deal("NOT_REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "89.99");
        line(q, 1, "กระเบื้อง", "3", "33.3333", "99.99", "TILE");
        line(q, 2, "ส่วนลดพิเศษ 10 บาท", "-1", "10.00", "-10.00", "ADJUSTMENT");
        BigDecimal payable = tickets.payableAmount(ticket);
        assertThat(payable).isEqualByComparingTo("96.29");

        var draft = remainingInvoices.createDraft(ticket, null, owner);

        assertThat(draft.items()).extracting(i -> i.amount()).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("99.99"), new BigDecimal("-10.00"));
        assertThat(draft.items().get(1).description()).isEqualTo("ส่วนลดพิเศษ 10 บาท");
        assertThat(draft.items().stream().map(i -> i.amount()).reduce(BigDecimal.ZERO, BigDecimal::add))
            .as("the lines add up to the stored total").isEqualByComparingTo("89.99");
        assertThat(draft.itemsTotal()).isEqualByComparingTo("89.99");
        assertThat(draft.netAmount()).isEqualByComparingTo("89.99");
        assertThat(draft.vatAmount()).isEqualByComparingTo("6.30");
        assertThat(draft.grandTotal()).isEqualByComparingTo(payable);
    }

    // Payable skips an answered row whose stored total is NULL, so the invoice must refuse it rather than quietly
    // billing the line sum (the two would then disagree).
    @Test
    void aNewEngineQuotationWithNoStoredTotal_isRefused409_neverBilledFromTheLineSum() {
        long ticket = deal("NOT_REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", null);
        line(q, 1, "กระเบื้อง A", "10", "100.00");

        assertThatThrownBy(() -> remainingInvoices.createDraft(ticket, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // Payable and the invoice must pick the SAME quotation. Here payable's pick is a ticket-level legacy row
    // (BUYER outranks OWNER) that the invoice resolver cannot bill, so the invoice refuses rather than silently
    // billing the OWNER's new-engine quotation that payable ignores.
    @Test
    void whenPayablePicksANonNewEngineQuotation_theInvoiceDoesNotBillADifferentOne() {
        long ticket = deal("NOT_REQUIRED");
        long legacy = quotation(ticket, null, "ACCEPTED", "500.00");
        jdbc.update("UPDATE sales.quotation SET recipient_type = 'BUYER' WHERE quotation_id = :q", Map.of("q", legacy));
        long newEngine = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        jdbc.update("UPDATE sales.quotation SET recipient_type = 'OWNER' WHERE quotation_id = :q", Map.of("q", newEngine));
        line(newEngine, 1, "กระเบื้อง A", "10", "100.00");
        assertThat(tickets.payableAmount(ticket)).as("fixture: payable picks the BUYER legacy row").isEqualByComparingTo("535.00");

        assertThatThrownBy(() -> remainingInvoices.createDraft(ticket, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // PIN: a deal WITH a deposit is unchanged - no issued notice means no remaining invoice (never billed in full).
    @Test
    void aDealWithADeposit_andNoIssuedNotice_stillCannotInvoice() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");

        assertThatThrownBy(() -> remainingInvoices.createDraft(ticket, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // PIN: no answered quotation at all (a quotation only ISSUED) -> still nothing to bill.
    @Test
    void aZeroDepositDeal_whoseQuotationIsOnlyIssued_cannotInvoice() {
        long ticket = deal("NOT_REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ISSUED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");

        assertThatThrownBy(() -> remainingInvoices.createDraft(ticket, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    private long deal(String depositPolicy) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status, deposit_policy, sales_stage)
            VALUES (:code, 'ri0', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued', :ps, :policy, 'ORDER_RECEIVED')
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", "RI0-" + (++seq)).addValue("by", employeeId)
                .addValue("ps", "REQUIRED".equals(depositPolicy) ? "CUSTOMER_CONFIRMED" : "CUSTOMER_CONFIRMED")
                .addValue("policy", depositPolicy), Long.class);
    }

    private long quotation(long ticket, String origin, String docStatus, String totalAmount) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, quotation_version, total_amount)
            VALUES (:t, :n, :by, :o, :s, :v, :total) RETURNING quotation_id
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("n", "QT-RI0-" + (++seq))
                .addValue("by", employeeId).addValue("o", origin).addValue("s", docStatus).addValue("v", seq)
                .addValue("total", totalAmount == null ? null : new BigDecimal(totalAmount)), Long.class);
    }

    private void line(long quotation, int seq, String description, String qty, String unitPrice) {
        BigDecimal amount = new BigDecimal(qty).multiply(new BigDecimal(unitPrice));
        line(quotation, seq, description, qty, unitPrice, amount.toPlainString(), "TILE");
    }

    /** {@code amount} is the STORED line amount (it may differ from qty x price rounded). */
    private void line(long quotation, int seq, String description, String qty, String unitPrice, String amount,
                      String lineType) {
        BigDecimal price = new BigDecimal(unitPrice);
        jdbc.update("""
            INSERT INTO sales.quotation_item (quotation_id, seq, description, qty, raw_unit, unit_price, sales_discount,
                                               final_unit_price, amount, line_type)
            VALUES (:q, :seq, :d, :qty, 'แผ่น', :p, 0, :p, :amt, :lt)
            """, new MapSqlParameterSource().addValue("q", quotation).addValue("seq", seq).addValue("d", description)
                .addValue("qty", new BigDecimal(qty)).addValue("p", price).addValue("amt", new BigDecimal(amount))
                .addValue("lt", lineType));
    }
}
