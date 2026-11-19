package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import th.co.glr.hr.support.RecordingSalesMailer;
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
    private DepositNoticeService depositNotices;
    private RecordingSalesMailer mailer;
    private long employeeId;
    private UserPrincipal owner;
    private int seq;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        mailer = new RecordingSalesMailer(jdbc);
        CustomerQuotationRepository quotations = new CustomerQuotationRepository(jdbc);
        depositNotices = new DepositNoticeService(new DepositNoticeRepository(jdbc), tickets,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), new DepositNoticePdfRenderer(),
            new CustomerRepository(jdbc), quotations, th.co.glr.hr.support.DocumentBundles.noDownload(jdbc));
        remainingInvoices = new RemainingInvoiceService(new RemainingInvoiceRepository(jdbc), depositNotices, tickets,
            new RemainingInvoicePdfRenderer(), new NotificationRepository(jdbc, mailer));
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

    // PIN: a deal WITH a deposit is unchanged - no issued notice means no remaining invoice (never billed in full).
    @Test
    void aDealWithADeposit_andNoIssuedNotice_stillCannotInvoice() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");

        assertThatThrownBy(() -> remainingInvoices.createDraft(ticket, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static DepositNoticeDraftRequest createBody(List<DepositNoticeItemRequest> items) {
        return new DepositNoticeDraftRequest(null, null, null, null, null, null, null, items);
    }

    // ── "manage all sales documents" grant (Ploy 2026-11-18): hr.employee.can_manage_all_sales_documents ──
    // A holder acts as the OWNER of every deal's deposit notice / remaining invoice. The flag is set with SQL, so
    // before V227 exists these error on the missing column (not on an assertion); the non-holder pin is green today.

    @Test
    void grant_salesHolder_createsAndIssuesARemainingInvoice_onSomeoneElsesDeal() {
        long ticket = deal("NOT_REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");
        UserPrincipal holder = holder("sales");

        var draft = remainingInvoices.createDraft(ticket, null, holder);

        assertThat(remainingInvoices.list(ticket, holder)).hasSize(1);
        assertThat(remainingInvoices.issue(draft.id(), holder).status()).isEqualTo("ISSUED");
        assertThat(createdBy(ticket)).isEqualTo(employeeId);
    }

    /** Pin (green today, must stay green): the grant is per-employee, a plain rep on someone else's deal is refused. */
    @Test
    void grant_nonHolderSales_isStillRefused403_onSomeoneElsesDeal() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");
        UserPrincipal plainRep = employeePrincipal("RI0-PLAIN", "sales", false);

        assertThatThrownBy(() -> depositNotices.createDraft(ticket, createBody(null), plainRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> depositNotices.listByTicket(ticket, plainRep))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private long createdBy(long ticket) {
        return jdbc.queryForObject("SELECT created_by FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticket), Long.class);
    }

    private UserPrincipal holder(String role) {
        return employeePrincipal("RI0-H-" + role.substring(0, 5), role, true);
    }

    private UserPrincipal employeePrincipal(String code, String role, boolean manageAllSalesDocuments) {
        long id = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th)
            VALUES (:c, 'ทดสอบ', 'สิทธิ์') RETURNING employee_id""", Map.of("c", code), Long.class);
        if (manageAllSalesDocuments) {
            jdbc.update("UPDATE hr.employee SET can_manage_all_sales_documents = TRUE WHERE employee_id = :id",
                Map.of("id", id));
        }
        return new UserPrincipal(id, code + "@glr.co.th", code, role, id, true, LocalDate.of(2020, 1, 1), false, null, false);
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
