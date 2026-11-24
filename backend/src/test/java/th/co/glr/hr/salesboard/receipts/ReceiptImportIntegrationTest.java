package th.co.glr.hr.salesboard.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /api/sales/receipt-imports and the unmatched queue through the real controller, services, repositories and
 * Postgres (V213 + V214). Every file is synthetic, built in the test. Written wrong-way-round where it matters:
 * re-uploading writes nothing; a receipt account already typed by hand is linked, not doubled — in either order; two
 * deals that both fit a row get NEITHER; an upload never reaches CLOSED_PAID; only ACCOUNT uploads and works the queue
 * (not the CEO, not sales, not sales_manager); IV rows are not cash; the whole upload is off until the owner turns it
 * on; a deal outside account's finance scope is never a candidate. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ReceiptImportIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private ReceiptImportRepository repository;
    private UserPrincipal account;
    private UserPrincipal ceo;
    private LocalDate monday;

    @BeforeEach
    void wire() {
        repository = new ReceiptImportRepository(jdbc);
        mvc = mvcWith(new ReceiptPlacement(ticketService, repository), new ReceiptImportSettings(true, Set.of("RE")));
        account = actor(createEmployee("บัญชี", "acc-rcpt@glr.co.th", "ACC", "บัญชี", null), "account");
        ceo = actor(createEmployee("ซีอีโอ", "ceo-rcpt@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");
        monday = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private MockMvc mvcWith(ReceiptPlacement placement, ReceiptImportSettings settings) {
        ReceiptImportService service = new ReceiptImportService(repository, placement, tickets, settings);
        return MockMvcBuilders
            .standaloneSetup(new ReceiptImportController(service, new SessionContext()))
            .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true).build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // ── matching ────────────────────────────────────────────────────────────

    @Test
    void depositNoticeNumberMatches_writesAnExpressDeposit_marksTheExpectedPaymentReceived_andAdvancesTheDeposit()
        throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900031");
        long ep = expectedRow(d, "DEPOSIT", "32100.00", monday);

        JsonNode summary = upload(account, csv(row("RE-6911-0001", "2026-11-12", "ใครก็ได้", "32,100.00", "dn6900031")));

        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(summary.get("matchedAmount").decimalValue()).isEqualByComparingTo("32100");
        Map<String, Object> r = receipt("RE-6911-0001");
        assertThat(r.get("ticket_id")).isEqualTo(d);
        assertThat(r.get("source")).isEqualTo("EXPRESS");
        assertThat(r.get("kind")).isEqualTo("DEPOSIT");
        assertThat(r.get("recorded_by")).isEqualTo(account.id());
        assertThat(jdbc.queryForObject("SELECT (received_at AT TIME ZONE 'Asia/Bangkok')::date FROM sales.payment_receipt"
            + " WHERE receipt_ref = 'RE-6911-0001'", Map.of(), LocalDate.class)).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(expectedStatus(ep)).isEqualTo("RECEIVED");
        assertThat(summary(d).paymentStatus()).isEqualTo("DEPOSIT_PAID");
        assertThat(summary(d).salesStage()).isEqualTo("DEPOSIT_RECEIVED");
    }

    @Test
    void anAmountThatFitsTwoDealsOfTheSameCustomerIsLeftUnmatched_neverGuessed() throws Exception {
        long a = payableDeal(salesActor, "50000.00");
        long b = payableDeal(otherSalesActor, "50000.00");
        expectedRow(a, "DEPOSIT", "15000.00", monday);
        expectedRow(b, "DEPOSIT", "15000.00", monday.plusWeeks(1));

        JsonNode summary = upload(account, csv(row("RE-A-1", "2026-11-12", "ผู้รับใบเสนอราคา", "15000", "")));

        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(queueReason("RE-A-1")).isEqualTo("AMBIGUOUS");
        assertThat(paidOn(a)).isEqualByComparingTo("0");
        assertThat(paidOn(b)).isEqualByComparingTo("0");

        // ออกในนาม (feedback #74): a receipt in the name a's notice is issued in matches a alone
        long n = notice(a, "15000.00", "DN6900074", "ISSUED", "ผู้รับใบเสนอราคา");
        jdbc.update("UPDATE sales.deposit_notice SET payer_name = 'ผู้จ่ายแทน ทดสอบ' WHERE deposit_notice_id = :n",
            Map.of("n", n));
        JsonNode byPayer = upload(account, csv(row("RE-A-2", "2026-11-12", "ผู้จ่ายแทน ทดสอบ", "15000", "")));
        assertThat(byPayer.get("matched").asInt()).isEqualTo(1);
        assertThat(receipt("RE-A-2").get("ticket_id")).isEqualTo(a);
    }

    @Test
    void anOverpaymentIsLeftUnmatched() throws Exception {
        long d = awaitingDeposit(salesActor, "1000.00", "DN6900099");

        JsonNode summary = upload(account, csv(row("RE-O-1", "2026-11-12", "", "5000", "DN6900099")));

        assertThat(summary.get("unmatched").asInt()).isEqualTo(1);
        assertThat(queueReason("RE-O-1")).isEqualTo("OVERPAYMENT");
        assertThat(paidOn(d)).isEqualByComparingTo("0");
    }

    @Test
    void anUploadNeverReachesClosedPaid_theHumanConfirmStillDoes() throws Exception {
        long d = deliveredAwaitingBalance(salesActor);

        JsonNode summary = upload(account, csv(row("RE-F-1", "2026-11-20", "", "74,900.01", "GLR6900077")));

        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(paidOn(d)).isEqualByComparingTo("107000.01");
        assertThat(summary(d).paymentStatus()).isEqualTo("AWAITING_FINAL_PAYMENT");
        assertThat(summary(d).salesStage()).isEqualTo("DELIVERED");

        ticketService.confirmFinalPayment(d, account);
        assertThat(summary(d).salesStage()).isEqualTo("CLOSED_PAID");
    }


    /** A round tax invoice already recorded the money: the Express row for it is queued as a possible duplicate, not placed twice. */
    @Test
    void aTaxInvoiceReceiptCountsAsATwin_soTheExpressRowIsQueuedNotPlacedTwice() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900051");
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref, received_at, source)
            VALUES (:t, 'BALANCE', 32100, :by, 'IV2611-0077', now(), 'TAX_INVOICE')
            """, Map.of("t", d, "by", account.id()));
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).toString();
        JsonNode summary = upload(account, csv(row("RE-TW-1", today, "", "32100", "DN6900051")));
        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(queueReason("RE-TW-1")).isEqualTo("POSSIBLE_MANUAL_DUPLICATE");
        assertThat(receiptCount("RE-TW-1")).isZero();

        // The queue shows it as the TAX INVOICE (number, source), and "same money" settles the row writing no receipt.
        JsonNode twin = ok(get("/api/sales/receipt-imports/unmatched"), account).get(0).get("possibleManual");
        assertThat(twin.get("source").asText()).isEqualTo("TAX_INVOICE");
        assertThat(twin.get("invoiceNumber").asText()).isEqualTo("IV2611-0077");
        upload(account, csv(row("RE-TW-2", today, "", "32100", "DN6900051")));
        assertThat(ok(post("/api/sales/receipt-imports/unmatched/" + rowId("RE-TW-1") + "/same-as-manual"), account)
            .get("status").asText()).isEqualTo("RESOLVED");
        assertThat(receiptCount("RE-TW-1")).isZero();
        assertThat(paidOn(d)).isEqualByComparingTo("32100");

        // One invoice's money settles at most ONE Express row: the second is refused, and no longer offered as its twin.
        assertThat(status(post("/api/sales/receipt-imports/unmatched/" + rowId("RE-TW-2") + "/same-as-manual"), account)).isEqualTo(409);
        assertThat(repository.findManualTwin(d, new BigDecimal("32100"), LocalDate.parse(today))).isEmpty();
    }

    /** A pre-portal deposit carries the date the rep gave, so it twins an Express row by the normal window around that date. */
    @Test
    void aPrePortalReceiptIsATwinWithinTheDateWindow_andSettlesAtMostOneExpressRow() throws Exception {
        long d = awaitingDeposit(salesActor, "30000.00", "DN6900052");
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, source, received_at)
            VALUES (:t, 'DEPOSIT', 30000, :by, 'PRE_PORTAL', now() - interval '40 days')
            """, Map.of("t", d, "by", salesActor.id()));
        String lastMonth = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).minusDays(40).toString();
        assertThat(upload(account, csv(row("RE-PP-1", lastMonth, "", "30000", "DN6900052"))).get("matched").asInt()).isZero();
        assertThat(queueReason("RE-PP-1")).isEqualTo("POSSIBLE_MANUAL_DUPLICATE");
        assertThat(receiptCount("RE-PP-1")).isZero();
        assertThat(ok(post("/api/sales/receipt-imports/unmatched/" + rowId("RE-PP-1") + "/same-as-manual"), account)
            .get("status").asText()).isEqualTo("RESOLVED");
        assertThat(paidOn(d)).isEqualByComparingTo("30000");
        assertThat(repository.findManualTwin(d, new BigDecimal("30000"), LocalDate.parse(lastMonth))).isEmpty();
    }

    @Test
    void aPrePortalReceiptIsNotTheTwinOfAnExpressRowOutsideTheDateWindow() throws Exception {
        long d = awaitingDeposit(salesActor, "30000.00", "DN6900053");
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, source, received_at)
            VALUES (:t, 'DEPOSIT', 30000, :by, 'PRE_PORTAL', now())
            """, Map.of("t", d, "by", salesActor.id()));
        String later = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).plusDays(20).toString();
        assertThat(repository.findManualTwin(d, new BigDecimal("30000"), LocalDate.parse(later))).isEmpty();
    }
    // ── authz: account only ─────────────────────────────────────────────────

    @Test
    void onlyAccountMayUploadOrDownloadTheTemplate_notTheCeo() throws Exception {
        byte[] file = csv(row("RE-Z-1", "2026-11-12", "", "1", ""));
        UserPrincipal importer = actor(createEmployee("นำเข้า", "imp-rcpt@glr.co.th", "IMP", "นำเข้า", null), "import");
        for (UserPrincipal refused : List.of(ceo, salesActor, salesManagerActor, importer)) {
            assertThat(status(uploadRequest(file), refused)).as(refused.role()).isEqualTo(403);
            assertThat(status(get("/api/sales/receipt-imports/template"), refused)).as(refused.role()).isEqualTo(403);
            assertThat(status(get("/api/sales/receipt-imports/latest"), refused)).as(refused.role()).isEqualTo(403);
        }
        assertThat(count("sales.receipt_import")).isZero();
        refusal(() -> ticketService.recordExpressReceipt(deal(salesActor), BigDecimal.ONE, Instant.now(), "RE-CEO-1",
            null, ceo), HttpStatus.FORBIDDEN);
        assertThat(status(uploadRequest(file), account)).isEqualTo(200);
    }

    /** Review H3: the queue is account's. A rep sees none of it; a sales_manager cannot work it. */
    @Test
    void repsAndManagersCannotReadOrResolveTheQueue() throws Exception {
        long mine = payableDeal(salesActor, "50000.00");
        upload(account, csv(row("RE-U-1", "2026-11-12", "ผู้รับใบเสนอราคา", "4321", "")));
        long rowId = rowId("RE-U-1");

        for (UserPrincipal refused : List.of(salesActor, salesManagerActor, ceo)) {
            assertThat(status(get("/api/sales/receipt-imports/unmatched"), refused)).as(refused.role()).isEqualTo(403);
            assertThat(status(resolve(rowId, mine), refused)).as(refused.role()).isEqualTo(403);
            assertThat(status(post("/api/sales/receipt-imports/unmatched/" + rowId + "/ignore"), refused))
                .as(refused.role()).isEqualTo(403);
        }
        assertThat(status(get("/api/sales/receipt-imports/manager-view"), salesActor)).isEqualTo(403);
        assertThat(rowStatus(rowId)).isEqualTo("UNMATCHED");
        assertThat(receiptCount("RE-U-1")).isZero();
        refusal(() -> ticketService.recordExpressReceipt(mine, BigDecimal.ONE, Instant.now(), "RE-REP-1", null,
            salesActor), HttpStatus.FORBIDDEN);
    }

    /** Review M7: one billing-note receipt split across the deals it covers; the lines must sum to the row. */
    @Test
    void aRowCanBeSplitAcrossDeals_onlyWhenTheLinesSumToTheRow() throws Exception {
        long a = payableDeal(salesActor, "50000.00");
        long b = payableDeal(otherSalesActor, "50000.00");
        upload(account, csv(row("RE-SP-1", "2026-11-12", "ไม่มีลูกค้านี้", "3000", "")));
        long rowId = rowId("RE-SP-1");

        assertThat(status(split(rowId, a, "1000", b, "1999.99"), account)).isEqualTo(400);
        assertThat(status(split(rowId, a, "1000", b, "2000"), salesManagerActor)).isEqualTo(403);
        assertThat(rowStatus(rowId)).isEqualTo("UNMATCHED");

        assertThat(ok(split(rowId, a, "1000", b, "2000"), account).get("status").asText()).isEqualTo("RESOLVED");
        assertThat(paidOn(a)).isEqualByComparingTo("1000");
        assertThat(paidOn(b)).isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM sales.receipt_import_allocation WHERE row_id = :r",
            Map.of("r", rowId), BigDecimal.class)).isEqualByComparingTo("3000");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** An ORDER_RECEIVED deal waiting on a REQUIRED deposit: one ISSUED deposit notice (= the payable) numbered docNo. */
    private long awaitingDeposit(UserPrincipal owner, String total, String docNo) {
        long d = deal(owner);
        jdbc.update("""
            UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED', deposit_policy = 'REQUIRED',
                   payment_status = 'DEPOSIT_NOTICE_ISSUED' WHERE ticket_id = :t""", Map.of("t", d));
        notice(d, total, docNo, "ISSUED", "x");
        return d;
    }

    /** Inside account's scope (ORDER_RECEIVED), payable {@code total} through an ACCEPTED EN quotation. */
    private long payableDeal(UserPrincipal owner, String total) {
        long d = deal(owner);
        long q = approved(d, owner, "OWNER").id();
        setDocStatus(q, "ACCEPTED");
        jdbc.update("UPDATE sales.quotation SET total_amount = :a, document_language = 'EN' WHERE quotation_id = :q",
            Map.of("a", new BigDecimal(total), "q", q));
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED' WHERE ticket_id = :t", Map.of("t", d));
        return d;
    }

    /** DELIVERED, fully delivered, deposit paid (32,100.00 by hand), balance invoiced as GLR6900077-1. */
    private long deliveredAwaitingBalance(UserPrincipal owner) {
        long d = deal(owner);
        long notice = notice(d, "32100.00", "DN6900077", "ISSUED", "x");
        jdbc.update("""
            INSERT INTO sales.remaining_invoice (ticket_id, deposit_notice_id, status, base_number, doc_number,
                issued_at, grand_total)
            VALUES (:id, :notice, 'ISSUED', 'GLR6900077', 'GLR6900077-1', now(), 74900.01)""",
            new MapSqlParameterSource("id", d).addValue("notice", notice));
        manualReceipt(d, "DEPOSIT", "32100.00", "2026-09-01T03:00:00Z", "MANUAL-DEP");
        jdbc.update("""
            UPDATE sales.ticket SET sales_stage = 'DELIVERED', deposit_policy = 'REQUIRED',
                   payment_status = 'AWAITING_FINAL_PAYMENT', fulfillment_status = 'FULLY_DELIVERED'
             WHERE ticket_id = :t""", Map.of("t", d));
        return d;
    }

    private long notice(long ticketId, String total, String docNo, String status, String customer) {
        BigDecimal t = new BigDecimal(total);
        return jdbc.queryForObject("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable, doc_number)
            VALUES (:id, (SELECT COALESCE(max(version), 0) + 1 FROM sales.deposit_notice WHERE ticket_id = :id),
                    :status, :cust, 0.3, :t, :t, 0, 0, :t, :doc)
            RETURNING deposit_notice_id""",
            new MapSqlParameterSource("id", ticketId).addValue("t", t).addValue("doc", docNo)
                .addValue("status", status).addValue("cust", customer), Number.class).longValue();
    }

    private long manualReceipt(long ticketId, String kind, String amount, String at, String ref) {
        return jdbc.queryForObject("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref, received_at)
            VALUES (:t, :k, :a, :by, :ref, CAST(:at AS timestamptz)) RETURNING receipt_id
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("k", kind)
                .addValue("a", new BigDecimal(amount)).addValue("by", account.id()).addValue("ref", ref)
                .addValue("at", at), Long.class);
    }

    private long expectedRow(long ticketId, String kind, String amount, LocalDate week) {
        return jdbc.queryForObject("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, created_by)
            VALUES (:t, :k, :a, :w, :by) RETURNING expected_payment_id
            """, Map.of("t", ticketId, "k", kind, "a", new BigDecimal(amount), "w", week, "by", salesActor.id()),
            Long.class);
    }

    private static String row(String docNo, String date, String customer, String amount, String ref) {
        return String.join(",", q(docNo), q(date), q(customer), q(amount), q(ref));
    }

    private static String q(String v) {
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static byte[] csv(String... rows) {
        return ("เลขที่เอกสาร,วันที่,ลูกค้า,จำนวนเงิน,อ้างอิง\n" + String.join("\n", rows) + "\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static AbstractMockHttpServletRequestBuilder<?> uploadRequest(byte[] file) {
        return multipart("/api/sales/receipt-imports")
            .file(new MockMultipartFile("file", "express.csv", "text/csv", file));
    }

    private JsonNode upload(UserPrincipal who, byte[] file) throws Exception {
        return ok(uploadRequest(file), who);
    }

    private AbstractMockHttpServletRequestBuilder<?> resolve(long rowId, long ticketId) {
        return post("/api/sales/receipt-imports/unmatched/" + rowId + "/resolve")
            .contentType(MediaType.APPLICATION_JSON).content("{\"ticketId\":" + ticketId + "}");
    }

    private AbstractMockHttpServletRequestBuilder<?> split(long rowId, long a, String amountA, long b, String amountB) {
        return post("/api/sales/receipt-imports/unmatched/" + rowId + "/resolve")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"allocations\":[{\"ticketId\":" + a + ",\"amount\":" + amountA + "},{\"ticketId\":" + b
                + ",\"amount\":" + amountB + "}]}");
    }

    // ── read-backs ──────────────────────────────────────────────────────────

    private Map<String, Object> receipt(String ref) {
        return jdbc.queryForMap("SELECT * FROM sales.payment_receipt WHERE receipt_ref = :r", Map.of("r", ref));
    }

    private int receiptCount(String ref) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM sales.payment_receipt WHERE upper(btrim(receipt_ref)) = upper(btrim(:r))",
            Map.of("r", ref), Integer.class);
    }

    private BigDecimal paidOn(long ticketId) {
        return tickets.sumPaid(ticketId);
    }

    private String expectedStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", id), String.class);
    }

    private String queueReason(String docNo) {
        return jdbc.queryForObject("SELECT reason FROM sales.receipt_import_row WHERE doc_no = :d", Map.of("d", docNo),
            String.class);
    }

    private long rowId(String docNo) {
        return jdbc.queryForObject("SELECT row_id FROM sales.receipt_import_row WHERE doc_no = :d", Map.of("d", docNo),
            Long.class);
    }

    private String rowStatus(long rowId) {
        return jdbc.queryForObject("SELECT status FROM sales.receipt_import_row WHERE row_id = :id",
            Map.of("id", rowId), String.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Map.of(), Integer.class);
    }

    private JsonNode ok(AbstractMockHttpServletRequestBuilder<?> request, UserPrincipal who) throws Exception {
        MvcResult result = perform(request, who);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        return json.readTree(body);
    }

    private int status(AbstractMockHttpServletRequestBuilder<?> request, UserPrincipal who) throws Exception {
        return perform(request, who).getResponse().getStatus();
    }

    private MvcResult perform(AbstractMockHttpServletRequestBuilder<?> request, UserPrincipal who) throws Exception {
        return performOn(mvc, request, who);
    }

    private static MvcResult performOn(MockMvc on, AbstractMockHttpServletRequestBuilder<?> request, UserPrincipal who)
        throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, who);
        return on.perform(request.session(session)).andReturn();
    }
}
