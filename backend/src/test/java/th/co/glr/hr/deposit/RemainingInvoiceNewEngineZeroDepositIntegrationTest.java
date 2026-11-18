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
            new CustomerRepository(jdbc), quotations);
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

    // P1 hand-off notification (A5): a VAT-free (EN) invoice must not tell account the total includes VAT.
    @Test
    void zeroPercentEnglishDeal_issuedInvoiceNotice_hasNoVatWording() {
        long accountId = mailer.seedRoleEmployee("account");
        long ticket = deal("NOT_REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        jdbc.update("UPDATE sales.quotation SET document_language = 'EN' WHERE quotation_id = :q", Map.of("q", q));
        line(q, 1, "Tile A", "10", "100.00");

        var issued = remainingInvoices.issue(remainingInvoices.createDraft(ticket, null, owner).id(), owner);

        assertThat(issued.vatAmount()).as("fixture: no VAT").isEqualByComparingTo("0");
        var s = tickets.findById(ticket).orElseThrow().summary();
        mailer.assertRoleNotice("account", "REMAINING_INVOICE_ISSUED", "ออกใบแจ้งหนี้ (ยอดคงเหลือ) แล้ว — รอยืนยันรับชำระ",
            owner.name() + " ออกใบแจ้งหนี้ " + issued.docNumber() + " แล้ว\n"
                + "ดีล " + s.code() + " · ลูกค้า " + s.customerName() + " · ยอดชำระรวม 1,000 บาท"
                + "\nสิ่งที่ต้องทำต่อ: ตรวจรับเงินโอนจากลูกค้า แล้วบันทึกรับชำระในระบบ",
            "/finance/deals/" + ticket, accountId);
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

    // The deposit notice follows the same ruling as the invoice above: it prints the QUOTATION'S OWN lines. Here the
    // goods line is stored as 467,125.00 (list 505 less 7.5%, rounded once) although its printed net price x qty gives
    // 467,130.00, and a -45,000.00 ส่วนลดพิเศษ follows. The notice keeps both stored amounts and the discount as its
    // own line, so its subtotal is the quotation's 422,125.00 and the deposit is a percent of exactly that.
    @Test
    void aDealWithADeposit_theDepositNoticeCopiesTheQuotationsOwnLines_discountIncluded_andTheSubtotalIsTheStoredTotal() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "422125.00");
        line(q, 1, "กระเบื้อง", "1000", "467.13", "467125.00", "TILE");
        line(q, 2, "ส่วนลดพิเศษ 45,000 บาท", "-1", "45000.00", "-45000.00", "ADJUSTMENT");

        var draft = depositNotices.createDraft(ticket,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        assertThat(draft.items()).extracting(i -> i.amount()).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("467125.00"), new BigDecimal("-45000.00"));
        assertThat(draft.items().get(1).description()).isEqualTo("ส่วนลดพิเศษ 45,000 บาท");
        assertThat(draft.items().get(1).qty()).isEqualByComparingTo("-1");
        assertThat(draft.items().get(1).unit()).isEmpty();
        assertThat(draft.subtotal()).isEqualByComparingTo("422125.00");
        assertThat(draft.depositAmount()).isEqualByComparingTo(
            new BigDecimal("422125.00").multiply(draft.depositPercent()).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    // The page PUTs every line back. Echoing the stored amounts keeps the quotation's total; a line the user edited
    // (amount sent null) recomputes from net x qty.
    @Test
    void aDepositNoticeSave_keepsASuppliedAmount_andRecomputesWhenAmountIsNull() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "422125.00");
        line(q, 1, "กระเบื้อง", "1000", "467.13", "467125.00", "TILE");
        line(q, 2, "ส่วนลดพิเศษ 45,000 บาท", "-1", "45000.00", "-45000.00", "ADJUSTMENT");
        var draft = depositNotices.createDraft(ticket,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        var echoed = depositNotices.update(draft.id(), putOf(draft, null), owner);
        assertThat(echoed.subtotal()).isEqualByComparingTo("422125.00");
        assertThat(echoed.items()).extracting(i -> i.amount()).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("467125.00"), new BigDecimal("-45000.00"));

        var edited = depositNotices.update(draft.id(), putOf(draft, new BigDecimal("2")), owner);
        assertThat(edited.items().get(0).amount()).isEqualByComparingTo("934.26");
        assertThat(edited.items().get(1).amount()).isEqualByComparingTo("-45000.00");
        assertThat(edited.subtotal()).isEqualByComparingTo("-44065.74");
    }

    @Test
    void aDepositNoticeSave_ignoresAnAmountThatIsNotTheStoredOne_andRefusesANewNegativeQty() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "422125.00");
        line(q, 1, "กระเบื้อง", "1000", "467.13", "467125.00", "TILE");
        line(q, 2, "ส่วนลดพิเศษ 45,000 บาท", "-1", "45000.00", "-45000.00", "ADJUSTMENT");
        var draft = depositNotices.createDraft(ticket,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        // (c) echoing the stored discount line (qty -1) still saves
        assertThat(depositNotices.update(draft.id(), putOf(draft, null), owner).items().get(1).qty())
            .isEqualByComparingTo("-1");

        // (a) same qty/net as stored, a forged amount: the stored net x qty wins
        var forged = putOf(draft, null);
        var first = forged.items().get(0);
        var items = new java.util.ArrayList<>(forged.items());
        items.set(0, new DepositNoticeItemRequest(first.seq(), first.description(), first.qty(), first.unit(),
            first.unitPrice(), first.discountLabel(), first.netUnitPrice(), new BigDecimal("1.00")));
        var saved = depositNotices.update(draft.id(), withItems(forged, items), owner);
        assertThat(saved.items().get(0).amount()).isEqualByComparingTo("467130.00");

        // (b) a NEW negative-qty line is refused
        items.add(new DepositNoticeItemRequest(3, "แถวใหม่", new BigDecimal("-5"), "แผ่น",
            new BigDecimal("10.00"), null, new BigDecimal("10.00"), null));
        assertThatThrownBy(() -> depositNotices.update(draft.id(), withItems(forged, items), owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // (d) the stored lines echoed back PLUS one extra qty -1 line (the stored discount's qty): a negative qty may
        // be kept, never added
        var withExtraDiscount = new java.util.ArrayList<>(putOf(draft, null).items());
        withExtraDiscount.add(new DepositNoticeItemRequest(3, "ส่วนลดเพิ่ม", new BigDecimal("-1"), "",
            new BigDecimal("400000.00"), null, new BigDecimal("400000.00"), null));
        assertThatThrownBy(() -> depositNotices.update(draft.id(), withItems(forged, withExtraDiscount), owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // The client create route (createDraft with req.items) is client input: a client amount is ignored (the stored
    // amount is net x qty) and a negative qty is refused, whatever the quotation holds.
    @Test
    void aClientCreate_ignoresASuppliedAmount_andRefusesANegativeQty() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");

        var forgedAmount = new DepositNoticeItemRequest(1, "กระเบื้อง A", new BigDecimal("2"), "แผ่น",
            new BigDecimal("100.00"), null, new BigDecimal("100.00"), new BigDecimal("1.00"));
        var negative = new DepositNoticeItemRequest(2, "ส่วนลด", new BigDecimal("-1"), "",
            new BigDecimal("500.00"), null, new BigDecimal("500.00"), null);

        assertThatThrownBy(() -> depositNotices.createDraft(ticket, createBody(List.of(forgedAmount, negative)), owner))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        var draft = depositNotices.createDraft(ticket, createBody(List.of(forgedAmount)), owner);
        assertThat(draft.items().get(0).amount()).isEqualByComparingTo("200.00");
        assertThat(draft.subtotal()).isEqualByComparingTo("200.00");
    }

    // The same two guards at the endpoint (real controller, real service): POST create and PUT update.
    @Test
    void theEndpoints_refuseANewNegativeQty_with400() throws Exception {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new DepositNoticeController(depositNotices, new th.co.glr.hr.auth.SessionContext()))
            .setControllerAdvice(new th.co.glr.hr.common.ApiExceptionHandler()).build();
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(th.co.glr.hr.auth.SessionContext.SESSION_USER_KEY, owner);
        String negativeLine = """
            {"seq": 2, "description": "แถวใหม่", "qty": -5, "unit": "แผ่น", "unitPrice": 10, "netUnitPrice": 10}""";

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/tickets/" + ticket + "/deposit-notice/draft").session(session)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"items\": [" + negativeLine + "]}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                .value("จำนวนต้องไม่ติดลบ"));

        var draft = depositNotices.createDraft(ticket, createBody(null), owner);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/deposit-notices/" + draft.id()).session(session)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"items\": [" + negativeLine + "]}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                .value("จำนวนต้องไม่ติดลบ"));
    }

    // sales.quotation_item.final_unit_price is nullable (hand-written / migrated data): an ADJUSTMENT row without a net
    // price must still become its own discount line (net = |amount| / |qty|), never be dropped or fail the insert.
    @Test
    void aDepositNotice_keepsAnAdjustmentRowWhoseNetPriceIsNull_withItsStoredAmount() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "900.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");
        jdbc.update("""
            INSERT INTO sales.quotation_item (quotation_id, seq, description, qty, raw_unit, unit_price, sales_discount,
                                               final_unit_price, amount, line_type)
            VALUES (:q, 2, 'ส่วนลดพิเศษ', -1, 'แผ่น', 100.00, 0, NULL, -100.00, 'ADJUSTMENT')
            """, Map.of("q", q));

        var draft = depositNotices.createDraft(ticket, createBody(null), owner);

        assertThat(draft.items()).hasSize(2);
        assertThat(draft.items().get(1).qty()).isEqualByComparingTo("-1");
        assertThat(draft.items().get(1).amount()).isEqualByComparingTo("-100.00");
        assertThat(draft.items().get(1).netUnitPrice()).isEqualByComparingTo("100.00");
        assertThat(draft.subtotal()).isEqualByComparingTo("900.00");
    }

    private static DepositNoticeDraftRequest createBody(List<DepositNoticeItemRequest> items) {
        return new DepositNoticeDraftRequest(null, null, null, null, null, null, null, items);
    }

    private static DepositNoticeDraftRequest withItems(DepositNoticeDraftRequest r, List<DepositNoticeItemRequest> items) {
        return new DepositNoticeDraftRequest(r.customerName(), r.customerTaxId(), r.customerAddress(),
            r.projectName(), r.reference(), r.depositPercent(), r.notes(), items, null);
    }

    /** The draft's lines as the page PUTs them; a non-null {@code firstQty} edits line 1 and drops its amount. */
    private static DepositNoticeDraftRequest putOf(DepositNoticeDto draft, BigDecimal firstQty) {
        List<DepositNoticeItemRequest> lines = new java.util.ArrayList<>();
        for (var i : draft.items()) {
            boolean edited = firstQty != null && i.seq() == 1;
            lines.add(new DepositNoticeItemRequest(i.seq(), i.description(), edited ? firstQty : i.qty(), i.unit(),
                i.unitPrice(), i.discountLabel(), i.netUnitPrice(), edited ? null : i.amount()));
        }
        return new DepositNoticeDraftRequest(draft.customerName(), draft.customerTaxId(), draft.customerAddress(),
            draft.projectName(), draft.reference(), draft.depositPercent(), draft.notes(), lines, null);
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

    // ── "manage all sales documents" grant (Ploy 2026-11-18): hr.employee.can_manage_all_sales_documents ──
    // A holder acts as the OWNER of every deal's deposit notice / remaining invoice. The flag is set with SQL, so
    // before V226 exists these error on the missing column (not on an assertion); the non-holder pin is green today.

    @Test
    void grant_salesHolder_createsAndIssuesADepositNotice_onSomeoneElsesDeal() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");
        UserPrincipal holder = holder("sales");

        var draft = depositNotices.createDraft(ticket, createBody(null), holder);
        var issued = depositNotices.issue(draft.id(), holder);

        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(depositNotices.listByTicket(ticket, holder)).hasSize(1);
        assertThat(createdBy(ticket)).as("a holder acts AS owner but never becomes the owner").isEqualTo(employeeId);
    }

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

    /** sales_manager is read-only on these documents today (SALES_ROLES excludes it); a holder writes them. */
    @Test
    void grant_salesManagerHolder_createsAndUpdatesADepositNotice() {
        long ticket = deal("REQUIRED");
        long q = quotation(ticket, "PRICING_REQUEST", "ACCEPTED", "1000.00");
        line(q, 1, "กระเบื้อง A", "10", "100.00");
        UserPrincipal managerHolder = holder("sales_manager");

        var draft = depositNotices.createDraft(ticket, createBody(null), managerHolder);
        var updated = depositNotices.update(draft.id(), putOf(draft, new BigDecimal("2")), managerHolder);

        assertThat(updated.items().get(0).qty()).isEqualByComparingTo("2");
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
