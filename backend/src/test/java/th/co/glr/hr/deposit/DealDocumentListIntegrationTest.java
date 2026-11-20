package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.deposit.DealDocumentRows.InvoiceRow;
import th.co.glr.hr.deposit.DealDocumentRows.NoticeRow;
import th.co.glr.hr.deposit.DealDocumentRows.QuotationDocuments;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Real-Postgres coverage of the cross-deal notice / remaining-invoice lists and the quotation rows' {@code documents}:
 * who sees which rows, and which quotation a notice or invoice resolves to. Rows are written over JDBC so each case
 * sets exactly the status / reference / confirmed-order it needs.
 */
class DealDocumentListIntegrationTest extends AbstractPostgresIntegrationTest {
    private DealDocumentListService lists;
    private TicketRepository tickets;
    private long repA;
    private long repB;
    private long ticketA;
    private long ticketB;
    private long qA1;
    private long qA2;
    private long qB1;
    private int quotationSeq;

    @BeforeEach
    void seed() {
        tickets = new TicketRepository(jdbc);
        lists = new DealDocumentListService(new DealDocumentListRepository(jdbc), tickets);
        repA = newRep("a");
        repB = newRep("b");
        ticketA = newTicket(repA);
        ticketB = newTicket(repB);
        qA1 = insertQuotation(ticketA, repA, "QA1");
        qA2 = insertQuotation(ticketA, repA, "QA2");
        qB1 = insertQuotation(ticketB, repB, "QB1");

        // deal A: ISSUED notice that names QA2; live invoice -2 for QA1 with its superseded -1; money received
        insertNotice(ticketA, "ISSUED", "GLRD69001", "QA2");
        jdbc.getJdbcTemplate().update("INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, received_at)"
            + " VALUES (" + ticketA + ", 'DEPOSIT', 500, " + repA + ", now())");
        insertInvoice(ticketA, qA1, "SUPERSEDED", "GLR6900001", 1);
        insertInvoice(ticketA, qA1, "ISSUED", "GLR6900001", 2);
        insertInvoice(ticketA, qA1, "DRAFT", null, 1); // as revise() stores it: version 1, no number, beside the issued -2
        // deal A is inside account's scope (deposit notice issued), deal B is not; QA1 is a draft, hidden from account
        jdbc.getJdbcTemplate().update("UPDATE sales.ticket SET payment_status = 'DEPOSIT_NOTICE_ISSUED' WHERE ticket_id = " + ticketA);
        jdbc.getJdbcTemplate().update("UPDATE sales.quotation SET doc_status = 'DRAFT' WHERE quotation_id = " + qA1);
        // deal B: DRAFT notice with no reference, order confirmed from QB1
        insertNotice(ticketB, "DRAFT", null, null);
        jdbc.getJdbcTemplate().update("INSERT INTO sales.ticket_event (ticket_id, actor_id, kind, related_document_type,"
            + " related_document_id) VALUES (" + ticketB + ", " + repB + ", '" + TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION
            + "', 'QUOTATION', " + qB1 + ")");
    }

    @Test
    void noticeList_isScopedByRole_andDraftsAreHiddenFromCeoAndAccount() {
        assertThat(ids(notices(null, actor(repA, "sales")))).containsExactly("GLRD69001");
        assertThat(notices(null, actor(repB, "sales"))).extracting(n -> n.status()).containsExactly("DRAFT");
        assertThat(notices(null, actor(9001, "sales_manager"))).hasSize(2);
        assertThat(notices("DRAFT", actor(9001, "sales_manager"))).hasSize(1);
        assertThat(notices(null, actor(9002, "ceo"))).extracting(n -> n.status()).containsExactly("ISSUED");
        assertThat(ids(notices(null, actor(9003, "account")))).containsExactly("GLRD69001"); // deal A only, no draft
        assertThat(lists.noticeCounts(actor(9001, "sales_manager"))).isEqualTo(new DealDocumentRows.Counts(2, 1, 1));
        assertThat(lists.noticeCounts(actor(9002, "ceo"))).isEqualTo(new DealDocumentRows.Counts(1, 0, 1));
        // the per-deal read refuses import, so the list does too
        assertThatThrownBy(() -> notices(null, actor(9004, "import"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> invoices(null, actor(9004, "import"))).isInstanceOf(ApiException.class);
    }

    @Test
    void noticeRow_resolvesItsQuotation_andFlagsWriteAndMoney() {
        NoticeRow byReference = notices(null, actor(repA, "sales")).get(0);
        assertThat(byReference.origin().quotationId()).isEqualTo(qA2);
        assertThat(byReference.origin().quotationNumber()).isEqualTo("QA2-" + ticketA);
        assertThat(byReference.origin().ownerId()).isEqualTo(repA);
        assertThat(byReference.moneyReceived()).isTrue();
        assertThat(byReference.viewerMayWrite()).isTrue();
        assertThat(notices(null, actor(9001, "sales_manager")).stream()
            .filter(n -> n.id() == byReference.id()).findFirst().orElseThrow().viewerMayWrite()).isFalse();

        NoticeRow byConfirmedOrder = notices(null, actor(repB, "sales")).get(0);
        assertThat(byConfirmedOrder.origin().quotationId()).isEqualTo(qB1);
        assertThat(byConfirmedOrder.moneyReceived()).isFalse();
    }

    @Test
    void invoiceList_showsTheLiveDocumentWithOlderVersionsNested_andItsQuotation() {
        List<InvoiceRow> rows = invoices(null, actor(9002, "ceo"));
        assertThat(rows).hasSize(1);
        InvoiceRow live = rows.get(0);
        assertThat(live.docNumber()).isEqualTo("GLR6900001-2");
        assertThat(live.origin().quotationId()).isEqualTo(qA1);
        assertThat(live.olderVersions()).extracting(o -> o.docNumber()).containsExactly("GLR6900001-1");
        assertThat(live.viewerMayWrite()).isFalse();
        assertThat(invoices(null, actor(repB, "sales"))).isEmpty();

        // the draft revision folds into the ISSUED row for whoever may see drafts; counted once, as issued
        InvoiceRow withPending = invoices(null, actor(9001, "sales_manager")).get(0);
        assertThat(invoices(null, actor(9001, "sales_manager"))).hasSize(1);
        assertThat(withPending.status()).isEqualTo("ISSUED");
        assertThat(withPending.pendingRevision().nextVersion()).isEqualTo(3);
        assertThat(live.pendingRevision()).isNull();
        assertThat(lists.invoiceCounts(actor(9001, "sales_manager"))).isEqualTo(new DealDocumentRows.Counts(1, 0, 1));
        assertThat(invoices("DRAFT", actor(9001, "sales_manager"))).isEmpty();

        // account sees QA1's number but no id: QA1 is a draft, not in account's quotation list
        InvoiceRow forAccount = invoices(null, actor(9003, "account")).get(0);
        assertThat(forAccount.origin().quotationId()).isNull();
        assertThat(forAccount.origin().quotationNumber()).isEqualTo("QA1-" + ticketA);
        assertThat(notices(null, actor(9003, "account")).get(0).origin().quotationId()).isEqualTo(qA2);
    }

    @Test
    void quotationRowsCarryTheirDocuments() {
        Map<Long, QuotationDocuments> docs = lists.summarize(
            Map.of(qA1, ticketA, qA2, ticketA, qB1, ticketB), actor(repA, "sales"));
        assertThat(docs.get(qA2).depositNotice().forThisQuotation()).isTrue();
        assertThat(docs.get(qA1).depositNotice().forThisQuotation()).isFalse();
        assertThat(docs.get(qA1).depositNotice().docNumber()).isEqualTo("GLRD69001");
        assertThat(docs.get(qA1).remainingInvoices()).extracting(i -> i.version()).containsExactlyInAnyOrder(2, 3);
        assertThat(docs.get(qA1).remainingInvoices()).filteredOn(i -> "DRAFT".equals(i.status()))
            .extracting(i -> i.version()).containsExactly(3); // the revision's coming version, not its stored 1
        assertThat(docs.get(qA2).remainingInvoices()).isEmpty();
        assertThat(docs).doesNotContainKey(qB1); // deal B's draft is not rep A's to read
        assertThat(docs.get(qA1).viewerMayWrite()).isTrue(); // rep A owns deal A
        assertThat(docs.get(qA2).viewerMayWrite()).isTrue(); // the flag is per deal, also on the notice-only row
        // a sales_manager reads every deal but writes none of them without the grant
        assertThat(lists.summarize(Map.of(qA1, ticketA), actor(9001, "sales_manager")).get(qA1).viewerMayWrite()).isFalse();
    }

    @Test
    void aDealThatIsNotActiveIsReadOnly_pagingCountsAllRows_andAnInvoiceCarriesItsOwnQuotation() {
        // the owner's rows turn read-only (the server refuses their writes with 409) on a completed deal
        jdbc.getJdbcTemplate().update("UPDATE sales.ticket SET lifecycle = 'COMPLETED' WHERE ticket_id = " + ticketA);
        NoticeRow notice = notices(null, actor(repA, "sales")).get(0);
        assertThat(notice.viewerMayWrite()).isFalse();
        assertThat(notice.origin().lifecycle()).isEqualTo("COMPLETED");
        assertThat(invoices(null, actor(repA, "sales")).get(0).viewerMayWrite()).isFalse();
        assertThat(lists.summarize(Map.of(qA1, ticketA), actor(repA, "sales")).get(qA1).viewerMayWrite()).isFalse();
        String status = jdbc.getJdbcTemplate().queryForObject("SELECT status FROM sales.ticket WHERE ticket_id = " + ticketA, String.class);
        jdbc.getJdbcTemplate().update("UPDATE sales.ticket SET lifecycle = 'ACTIVE', status = 'cancelled' WHERE ticket_id = " + ticketA);
        assertThat(notices(null, actor(repA, "sales")).get(0).origin().lifecycle()).isEqualTo("CANCELLED");
        jdbc.getJdbcTemplate().update("UPDATE sales.ticket SET status = '" + status + "' WHERE ticket_id = " + ticketA);

        // limit / offset page the rows; total is the whole match
        var all = lists.listNotices(null, null, null, actor(9001, "sales_manager"));
        assertThat(all.total()).isEqualTo(2);
        var first = lists.listNotices(null, 1, 0, actor(9001, "sales_manager"));
        var second = lists.listNotices(null, 1, 1, actor(9001, "sales_manager"));
        assertThat(first.rows()).hasSize(1);
        assertThat(first.total()).isEqualTo(2);
        assertThat(second.rows()).hasSize(1);
        assertThat(second.rows().get(0).id()).isNotEqualTo(first.rows().get(0).id());
        assertThat(lists.listInvoices(null, 1, 5, actor(9001, "sales_manager")).rows()).isEmpty();
        assertThat(lists.listInvoices(null, 1, 5, actor(9001, "sales_manager")).total()).isEqualTo(1);

        // the invoice row names its OWN quotation even where account is not allowed the link
        InvoiceRow forAccount = invoices(null, actor(9003, "account")).get(0);
        assertThat(forAccount.origin().quotationId()).isNull();
        assertThat(forAccount.customerQuotationId()).isEqualTo(qA1);
    }

    // ── fixtures ──

    private List<NoticeRow> notices(String status, UserPrincipal actor) {
        return lists.listNotices(status, null, null, actor).rows();
    }

    private List<InvoiceRow> invoices(String status, UserPrincipal actor) {
        return lists.listInvoices(status, null, null, actor).rows();
    }

    private static List<String> ids(List<NoticeRow> rows) {
        return rows.stream().map(NoticeRow::docNumber).toList();
    }

    private UserPrincipal actor(long id, String role) {
        return new UserPrincipal(id, id + "@glr.co.th", "Actor " + id, role, id, true, LocalDate.now(), false, null, false);
    }

    private long newRep(String tag) {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        return employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย " + tag, null, null, null, null, null, null, null,
            "dd-" + tag + UUID.randomUUID().toString().substring(0, 8) + "@glr.co.th", null,
            "SALES", "แผนกขาย", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private long newTicket(long ownerId) {
        CustomerDto customer = new CustomerRepository(jdbc).create(
            "บริษัท เอกสารขาย " + UUID.randomUUID() + " จำกัด", "0100000000199", "1 ถนนทดสอบ", "สนญ", "02-000-0199");
        ProjectDto project = new ProjectRepository(jdbc).create(customer.id(), "โครงการ เอกสารขาย");
        return tickets.create(new CreateTicketRequest("ดีลเอกสารขาย", "NORMAL", customer.name(), customer.id(), project.id(),
            null, null, null, List.of(new TicketItemRequest("SCG", "Tile", "White", "Matte", "60x60", "Factory",
                new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB"))),
            tickets.nextTicketCode(), ownerId, "Sales");
    }

    private long insertQuotation(long ticketId, long issuedBy, String number) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version, origin)
            VALUES (:t, :n, :by, 'APPROVED', :v, 'DEAL_DIRECT') RETURNING quotation_id
            """, new MapSqlParameterSource("t", ticketId).addValue("n", number + "-" + ticketId).addValue("by", issuedBy)
            .addValue("v", ++quotationSeq), Long.class);
    }

    private void insertNotice(long ticketId, String status, String docNumber, String reference) {
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, status, doc_number, reference, issue_date, customer_name,
                project_name, deposit_percent, deposit_amount, vat_amount, total_payable)
            VALUES (:t, :s, :dn, :ref, CURRENT_DATE, 'ลูกค้า', 'โครงการ', 0.5, 1000, 70, 1070)
            """, new MapSqlParameterSource("t", ticketId).addValue("s", status).addValue("dn", docNumber)
            .addValue("ref", reference == null ? null : reference + "-" + ticketId));
    }

    private void insertInvoice(long ticketId, long quotationId, String status, String base, int version) {
        jdbc.update("""
            INSERT INTO sales.remaining_invoice (ticket_id, customer_quotation_id, status, base_number, doc_number,
                version, doc_date, customer_name, project_name, items_total, deposit_deduction, net_amount, vat_amount,
                grand_total, issued_at)
            VALUES (:t, :q, :s, :b, :dn, :v, CURRENT_DATE, 'ลูกค้า', 'โครงการ', 2000, 1000, 1000, 70, 1070,
                    CASE WHEN :s = 'DRAFT' THEN NULL ELSE now() END)
            """, new MapSqlParameterSource("t", ticketId).addValue("q", quotationId).addValue("s", status)
            .addValue("b", base).addValue("dn", base == null ? null : base + "-" + version).addValue("v", version));
    }
}
