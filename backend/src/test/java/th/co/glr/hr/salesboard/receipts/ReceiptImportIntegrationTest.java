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
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
import th.co.glr.hr.salesboard.ExpectedPaymentRepository;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /api/sales/receipt-imports and the unmatched queue through the real controller, services, repositories and
 * Postgres (V213). Every file is synthetic, built in the test. Written wrong-way-round where it matters: re-uploading
 * writes nothing; a receipt already typed by hand is not counted twice; two deals that both fit a row get NEITHER; an
 * upload never reaches CLOSED_PAID; sales / sales_manager / import cannot upload; a rep cannot attach a row to another
 * rep's deal, nor to their own deal when the row is not theirs to see. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ReceiptImportIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private UserPrincipal account;
    private UserPrincipal ceo;
    private LocalDate monday;

    @BeforeEach
    void wire() {
        ExpectedPaymentRepository expected = new ExpectedPaymentRepository(jdbc);
        ReceiptImportRepository repository = new ReceiptImportRepository(jdbc);
        ReceiptPlacement placement = new ReceiptPlacement(ticketService, expected, repository);
        ReceiptImportService service = new ReceiptImportService(repository, placement, tickets);
        mvc = MockMvcBuilders
            .standaloneSetup(new ReceiptImportController(service, new SessionContext()))
            .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true).build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        account = actor(createEmployee("บัญชี", "acc-rcpt@glr.co.th", "ACC", "บัญชี", null), "account");
        ceo = actor(createEmployee("ซีอีโอ", "ceo-rcpt@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");
        monday = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
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
        assertThat(r.get("customer_name")).isEqualTo("ใครก็ได้");
        assertThat(jdbc.queryForObject("SELECT (received_at AT TIME ZONE 'Asia/Bangkok')::date FROM sales.payment_receipt"
            + " WHERE receipt_ref = 'RE-6911-0001'", Map.of(), LocalDate.class)).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(expectedStatus(ep)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT receipt_id FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", ep), Long.class)).isEqualTo(((Number) r.get("receipt_id")).longValue());
        assertThat(summary(d).paymentStatus()).isEqualTo("DEPOSIT_PAID");
        assertThat(summary(d).salesStage()).isEqualTo("DEPOSIT_RECEIVED");
    }

    @Test
    void quotationNumberWithRevisionSuffixMatches() throws Exception {
        long d = payableDeal(salesActor, "50000.00");
        String number = jdbc.queryForObject("SELECT number FROM sales.quotation WHERE ticket_id = :t LIMIT 1",
            Map.of("t", d), String.class);

        // Numbers are "<base>-<revision>" (QuotationNumbering, e.g. QT-2026-0001-1); the receipt names another revision.
        String otherRevision = number.replaceFirst("-\\d{1,2}$", "").toLowerCase() + "-2";
        JsonNode summary = upload(account, csv(row("RE-Q-1", "2026-11-12", "", "1000", otherRevision)));

        assertThat(summary.get("matched").asInt()).as("quotation number %s, summary %s", number, summary)
            .isEqualTo(1);
        assertThat(receipt("RE-Q-1").get("ticket_id")).isEqualTo(d);
    }

    @Test
    void customerPlusAmountMatchesOnlyWithAnOpenExpectedPaymentWithinTolerance() throws Exception {
        long d = payableDeal(salesActor, "50000.00");
        expectedRow(d, "BALANCE", "10000.00", monday);

        JsonNode summary = upload(account, csv(
            row("RE-C-1", "2026-11-12", "บจก. ผู้รับใบเสนอราคา", "10,050.00", "โอนผ่านธนาคาร"),
            row("RE-C-2", "2026-11-12", "บจก. ผู้รับใบเสนอราคา", "12,000.00", "")));

        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(summary.get("unmatched").asInt()).isEqualTo(1);
        assertThat(receipt("RE-C-1").get("ticket_id")).isEqualTo(d);
        assertThat(receiptCount("RE-C-2")).isZero();
        assertThat(queueReason("RE-C-2")).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void anAmountThatFitsTwoDealsOfTheSameCustomerIsLeftUnmatched_neverGuessed() throws Exception {
        long a = payableDeal(salesActor, "50000.00");
        long b = payableDeal(otherSalesActor, "50000.00");
        expectedRow(a, "DEPOSIT", "15000.00", monday);
        expectedRow(b, "DEPOSIT", "15000.00", monday.plusWeeks(1));

        JsonNode summary = upload(account, csv(row("RE-A-1", "2026-11-12", "ผู้รับใบเสนอราคา", "15000", "")));

        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(summary.get("unmatched").asInt()).isEqualTo(1);
        assertThat(receiptCount("RE-A-1")).isZero();
        assertThat(queueReason("RE-A-1")).isEqualTo("AMBIGUOUS");
        assertThat(paidOn(a)).isEqualByComparingTo("0");
        assertThat(paidOn(b)).isEqualByComparingTo("0");
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
        assertThat(summary(d).paymentStatus()).isEqualTo("FULLY_PAID");
        assertThat(summary(d).salesStage()).isEqualTo("CLOSED_PAID");
    }

    // ── idempotency ─────────────────────────────────────────────────────────

    @Test
    void reUploadingTheSameFileIsAllDuplicatesAndWritesNothing() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900032");
        byte[] file = csv(
            row("RE-R-1", "2026-11-12", "", "32100", "DN6900032"),
            row("RE-R-2", "2026-11-12", "ไม่มีดีลนี้", "777", ""),
            row("RE-R-1", "2026-11-12", "", "32100", "DN6900032")); // the same row twice in one file

        JsonNode first = upload(account, file);
        assertThat(first.get("matched").asInt()).isEqualTo(1);
        assertThat(first.get("unmatched").asInt()).isEqualTo(1);
        assertThat(first.get("duplicates").asInt()).isEqualTo(1);
        int receipts = count("sales.payment_receipt");
        int queued = count("sales.receipt_import_row");
        String status = summary(d).paymentStatus();

        JsonNode second = upload(ceo, file);

        assertThat(second.get("matched").asInt()).isZero();
        assertThat(second.get("unmatched").asInt()).isZero();
        assertThat(second.get("duplicates").asInt()).isEqualTo(3);
        assertThat(second.get("duplicateAmount").decimalValue()).isEqualByComparingTo("64977");
        assertThat(count("sales.payment_receipt")).isEqualTo(receipts);
        assertThat(count("sales.receipt_import_row")).isEqualTo(queued);
        assertThat(summary(d).paymentStatus()).isEqualTo(status);
    }

    @Test
    void aReceiptAlreadyTypedByHandWithTheSameNumberIsCountedOnce_andIsNotTouched() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900033");
        long manual = jdbc.queryForObject("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref, note)
            VALUES (:t, 'DEPOSIT', 32100, :by, 'RE-6911-0100', 'พิมพ์เอง') RETURNING receipt_id
            """, Map.of("t", d, "by", account.id()), Long.class);
        String before = jdbc.queryForObject(
            "SELECT row_to_json(r)::text FROM sales.payment_receipt r WHERE receipt_id = :id", Map.of("id", manual),
            String.class);

        JsonNode summary = upload(account, csv(row(" re-6911-0100 ", "2026-11-12", "", "32100", "DN6900033")));

        assertThat(summary.get("duplicates").asInt()).isEqualTo(1);
        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(paidOn(d)).isEqualByComparingTo("32100");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.payment_receipt WHERE ticket_id = :t",
            Map.of("t", d), Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT row_to_json(r)::text FROM sales.payment_receipt r WHERE receipt_id = :id", Map.of("id", manual),
            String.class)).as("the manual receipt is untouched").isEqualTo(before);
    }

    @Test
    void badRowsAreReportedByLineAndNothingElseIsHeldBack() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900034");
        JsonNode summary = upload(account, csv(
            row("", "2026-11-12", "", "100", ""),
            row("RE-B-2", "2026-11-12", "", "32100", "DN6900034")));

        assertThat(summary.get("invalid").asInt()).isEqualTo(1);
        assertThat(summary.get("errors").get(0).get("line").asInt()).isEqualTo(2);
        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(paidOn(d)).isEqualByComparingTo("32100");
    }

    // ── authz: who may upload ────────────────────────────────────────────────

    @Test
    void onlyAccountAndCeoMayUploadOrDownloadTheTemplate() throws Exception {
        byte[] file = csv(row("RE-Z-1", "2026-11-12", "", "1", ""));
        UserPrincipal importer = actor(createEmployee("นำเข้า", "imp-rcpt@glr.co.th", "IMP", "นำเข้า", null), "import");
        for (UserPrincipal refused : List.of(salesActor, salesManagerActor, importer)) {
            assertThat(status(uploadRequest(file), refused)).as(refused.role()).isEqualTo(403);
            assertThat(status(get("/api/sales/receipt-imports/template"), refused)).as(refused.role()).isEqualTo(403);
            assertThat(status(get("/api/sales/receipt-imports/latest"), refused)).as(refused.role()).isEqualTo(403);
        }
        assertThat(count("sales.receipt_import")).isZero();
        assertThat(count("sales.receipt_import_row")).isZero();

        MvcResult template = perform(get("/api/sales/receipt-imports/template"), account);
        assertThat(template.getResponse().getStatus()).isEqualTo(200);
        assertThat(template.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("เลขที่เอกสาร");
        assertThat(status(uploadRequest(file), ceo)).isEqualTo(200);
    }

    @Test
    void latestSummarisesTheLastUploadAndCountsWhatIsStillWaiting() throws Exception {
        upload(account, csv(row("RE-L-1", "2026-11-12", "ไม่มีใคร", "10", ""), row("RE-L-2", "2026-11-12", "ไม่มี", "20", "")));
        JsonNode latest = ok(get("/api/sales/receipt-imports/latest"), account);
        assertThat(latest.get("latest").get("fileName").asText()).isEqualTo("express.csv");
        assertThat(latest.get("latest").get("unmatched").asInt()).isEqualTo(2);
        assertThat(latest.get("openUnmatched").asInt()).isEqualTo(2);
    }

    // ── the unmatched queue ─────────────────────────────────────────────────

    @Test
    void aRepSeesOnlyRowsTheirOwnDealsCouldBe_andOnlyTheirOwnCandidates() throws Exception {
        long mine = payableDeal(salesActor, "50000.00");
        long theirs = payableDeal(otherSalesActor, "50000.00");
        upload(account, csv(
            row("RE-U-1", "2026-11-12", "ผู้รับใบเสนอราคา", "4321", ""),   // both reps' deals are this customer
            row("RE-U-2", "2026-11-12", "ไม่มีลูกค้านี้", "999", "")));

        JsonNode repView = ok(get("/api/sales/receipt-imports/unmatched"), salesActor);
        assertThat(docNos(repView)).containsExactly("RE-U-1");
        assertThat(candidateIds(repView.get(0))).containsExactly(mine).doesNotContain(theirs);

        JsonNode managerView = ok(get("/api/sales/receipt-imports/unmatched"), salesManagerActor);
        assertThat(docNos(managerView)).containsExactlyInAnyOrder("RE-U-1", "RE-U-2");
        JsonNode both = managerView.get(docNos(managerView).indexOf("RE-U-1"));
        assertThat(candidateIds(both)).containsExactlyInAnyOrder(mine, theirs);
        assertThat(docNos(ok(get("/api/sales/receipt-imports/unmatched"), account))).hasSize(2);
    }

    @Test
    void aRepAttachesARowToTheirOwnDeal_inOneTap() throws Exception {
        long mine = payableDeal(salesActor, "50000.00");
        payableDeal(otherSalesActor, "50000.00");
        upload(account, csv(row("RE-T-1", "2026-11-12", "ผู้รับใบเสนอราคา", "4321", "")));
        long rowId = rowId("RE-T-1");

        JsonNode resolved = ok(resolve(rowId, mine), salesActor);

        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        Map<String, Object> r = receipt("RE-T-1");
        assertThat(r.get("ticket_id")).isEqualTo(mine);
        assertThat(r.get("source")).isEqualTo("EXPRESS");
        assertThat(r.get("recorded_by")).isEqualTo(salesActor.id());
        assertThat(status(resolve(rowId, mine), salesActor)).as("already resolved").isEqualTo(409);
        assertThat(receiptCount("RE-T-1")).isEqualTo(1);
    }

    @Test
    void aRepCannotAttachARowToAnotherRepsDeal() throws Exception {
        payableDeal(salesActor, "50000.00");
        long theirs = payableDeal(otherSalesActor, "50000.00");
        upload(account, csv(row("RE-W-1", "2026-11-12", "ผู้รับใบเสนอราคา", "4321", "")));
        long rowId = rowId("RE-W-1");

        assertThat(status(resolve(rowId, theirs), salesActor)).isEqualTo(403);

        assertThat(receiptCount("RE-W-1")).isZero();
        assertThat(rowStatus(rowId)).isEqualTo("UNMATCHED");
        assertThat(paidOn(theirs)).isEqualByComparingTo("0");
    }

    @Test
    void aRepCannotPullARowThatIsNotTheirsOntoTheirOwnDeal() throws Exception {
        long mine = payableDeal(salesActor, "50000.00");
        jdbc.update("UPDATE sales.ticket SET customer_name = 'ลูกค้าคนละราย' WHERE ticket_id = :t", Map.of("t", mine));
        long theirs = payableDeal(otherSalesActor, "50000.00");
        upload(account, csv(row("RE-X-1", "2026-11-12", "ผู้รับใบเสนอราคา", "4321", "")));
        long rowId = rowId("RE-X-1");

        assertThat(status(resolve(rowId, mine), salesActor)).isEqualTo(403);
        assertThat(rowStatus(rowId)).isEqualTo("UNMATCHED");
        assertThat(receiptCount("RE-X-1")).isZero();

        // A manager may attach it anywhere that fits.
        assertThat(ok(resolve(rowId, theirs), salesManagerActor).get("status").asText()).isEqualTo("RESOLVED");
    }

    /** The service gate under the queue's own: even called directly, a rep cannot put Express money on another rep's deal. */
    @Test
    void recordExpressReceiptRefusesARepOnAnotherRepsDeal_evenCalledDirectly() {
        long theirs = payableDeal(otherSalesActor, "50000.00");
        refusal(() -> ticketService.recordExpressReceipt(theirs, new BigDecimal("100"), java.time.Instant.now(),
            "RE-D-1", null, salesActor), org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(receiptCount("RE-D-1")).isZero();
        UserPrincipal importer = actor(createEmployee("นำเข้า", "imp-d@glr.co.th", "IMP", "นำเข้า", null), "import");
        refusal(() -> ticketService.recordExpressReceipt(theirs, new BigDecimal("100"), java.time.Instant.now(),
            "RE-D-2", null, importer), org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(paidOn(theirs)).isEqualByComparingTo("0");
    }

    @Test
    void ignoreIsForAccountAndManagers_notReps() throws Exception {
        payableDeal(salesActor, "50000.00");
        upload(account, csv(row("RE-I-1", "2026-11-12", "ผู้รับใบเสนอราคา", "4321", "")));
        long rowId = rowId("RE-I-1");

        assertThat(status(post("/api/sales/receipt-imports/unmatched/" + rowId + "/ignore"), salesActor))
            .isEqualTo(403);
        assertThat(rowStatus(rowId)).isEqualTo("UNMATCHED");
        assertThat(ok(post("/api/sales/receipt-imports/unmatched/" + rowId + "/ignore"), account)
            .get("status").asText()).isEqualTo("IGNORED");
        assertThat(docNos(ok(get("/api/sales/receipt-imports/unmatched"), account))).doesNotContain("RE-I-1");
    }

    @Test
    void importAndHrCannotReadTheQueue() throws Exception {
        for (String role : List.of("import", "hr")) {
            UserPrincipal other = actor(createEmployee("ผู้ใช้ " + role, role + "-q@glr.co.th", "OPS", "ปฏิบัติการ", null),
                role);
            assertThat(status(get("/api/sales/receipt-imports/unmatched"), other)).as(role).isEqualTo(403);
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** An ORDER_RECEIVED deal waiting on a REQUIRED deposit: one ISSUED deposit notice (= the payable) numbered docNo. */
    private long awaitingDeposit(UserPrincipal owner, String total, String docNo) {
        long d = deal(owner);
        jdbc.update("""
            UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED', deposit_policy = 'REQUIRED',
                   payment_status = 'DEPOSIT_NOTICE_ISSUED' WHERE ticket_id = :t""", Map.of("t", d));
        notice(d, total, docNo);
        return d;
    }

    /** A deal whose payable is {@code total} through an ACCEPTED quotation (so it has a quotation number). */
    private long payableDeal(UserPrincipal owner, String total) {
        long d = deal(owner);
        long q = approved(d, owner, "OWNER").id();
        setDocStatus(q, "ACCEPTED");
        jdbc.update("UPDATE sales.quotation SET total_amount = :a, document_language = 'EN' WHERE quotation_id = :q",
            Map.of("a", new BigDecimal(total), "q", q));
        return d;
    }

    /** DELIVERED, fully delivered, deposit paid (32,100.00 by hand), balance invoiced as GLR6900077-1. */
    private long deliveredAwaitingBalance(UserPrincipal owner) {
        long d = deal(owner);
        long notice = notice(d, "32100.00", "DN6900077");
        jdbc.update("""
            INSERT INTO sales.remaining_invoice (ticket_id, deposit_notice_id, status, base_number, doc_number,
                issued_at, grand_total)
            VALUES (:id, :notice, 'ISSUED', 'GLR6900077', 'GLR6900077-1', now(), 74900.01)""",
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

    private long notice(long ticketId, String total, String docNo) {
        BigDecimal t = new BigDecimal(total);
        return jdbc.queryForObject("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable, doc_number)
            VALUES (:id, 1, 'ISSUED', 'x', 0.3, :t, :t, 0, 0, :t, :doc)
            RETURNING deposit_notice_id""",
            new MapSqlParameterSource("id", ticketId).addValue("t", t).addValue("doc", docNo), Number.class)
            .longValue();
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
        return ("\uFEFFเลขที่เอกสาร,วันที่,ลูกค้า,จำนวนเงิน,อ้างอิง\n" + String.join("\n", rows) + "\n")
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

    // ── read-backs ──────────────────────────────────────────────────────────

    private Map<String, Object> receipt(String ref) {
        return jdbc.queryForMap("SELECT * FROM sales.payment_receipt WHERE receipt_ref = :r", Map.of("r", ref));
    }

    private int receiptCount(String ref) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.payment_receipt WHERE upper(receipt_ref) = upper(:r)",
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

    private static List<String> docNos(JsonNode rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("docNo").asText()));
        return out;
    }

    private static List<Long> candidateIds(JsonNode row) {
        List<Long> out = new ArrayList<>();
        row.get("candidates").forEach(c -> out.add(c.get("ticketId").asLong()));
        return out;
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
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, who);
        return mvc.perform(request.session(session)).andReturn();
    }
}
