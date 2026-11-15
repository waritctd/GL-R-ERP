package th.co.glr.hr.salesboard.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataIntegrityViolationException;
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
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.ticket.RecordPaymentRequest;
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
    void quotationNumberWithAnotherRevisionMatches() throws Exception {
        long d = payableDeal(salesActor, "50000.00");
        String number = jdbc.queryForObject("SELECT number FROM sales.quotation WHERE ticket_id = :t LIMIT 1",
            Map.of("t", d), String.class);
        String otherRevision = number.replaceFirst("-\\d{1,2}$", "").toLowerCase() + "-2";

        JsonNode summary = upload(account, csv(row("RE-Q-1", "2026-11-12", "", "1000", otherRevision)));

        assertThat(summary.get("matched").asInt()).as("quotation number %s", number).isEqualTo(1);
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
        assertThat(queueReason("RE-C-2")).isEqualTo("NO_CANDIDATE");
    }

    /** Review M5: the customer names printed on the deal's issued documents count too, not only the ticket's. */
    @Test
    void theCustomerNameOnTheDealsDepositNoticeAlsoMatches() throws Exception {
        long d = payableDeal(salesActor, "50000.00");
        jdbc.update("UPDATE sales.ticket SET customer_name = 'ชื่อในดีลไม่ตรง' WHERE ticket_id = :t", Map.of("t", d));
        notice(d, "1000.00", null, "ISSUED", "บริษัท ชื่อบนใบแจ้งหนี้ จำกัด");
        expectedRow(d, "DEPOSIT", "2000.00", monday);

        JsonNode summary = upload(account, csv(row("RE-N-1", "2026-11-12", "ชื่อบนใบแจ้งหนี้", "2000", "")));

        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(receipt("RE-N-1").get("ticket_id")).isEqualTo(d);
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
    }

    /** Review M1: candidates reload per row — the second identical row in a file must not ride the first's match. */
    @Test
    void twoRowsForTheSameCustomerInOneFile_theSecondDoesNotReuseTheFirstsExpectedPayment() throws Exception {
        long d = payableDeal(salesActor, "50000.00");
        long ep = expectedRow(d, "DEPOSIT", "15000.00", monday);

        JsonNode summary = upload(account, csv(
            row("RE-2R-1", "2026-11-12", "ผู้รับใบเสนอราคา", "15000", ""),
            row("RE-2R-2", "2026-11-13", "ผู้รับใบเสนอราคา", "15000", "")));

        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(summary.get("unmatched").asInt()).isEqualTo(1);
        assertThat(expectedStatus(ep)).isEqualTo("RECEIVED");
        assertThat(paidOn(d)).isEqualByComparingTo("15000");
    }

    @Test
    void anOverpaymentIsLeftUnmatched() throws Exception {
        long d = awaitingDeposit(salesActor, "1000.00", "DN6900099");

        JsonNode summary = upload(account, csv(row("RE-O-1", "2026-11-12", "", "5000", "DN6900099")));

        assertThat(summary.get("unmatched").asInt()).isEqualTo(1);
        assertThat(queueReason("RE-O-1")).isEqualTo("OVERPAYMENT");
        assertThat(paidOn(d)).isEqualByComparingTo("0");
    }

    /** Review M7: a superseded or cancelled document is not a candidate. */
    @Test
    void aSupersededDepositNoticeNumberIsNotACandidate() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900040");
        notice(d, "32100.00", "DN6900041", "SUPERSEDED", "x");

        JsonNode summary = upload(account, csv(row("RE-S-1", "2026-11-12", "", "32100", "DN6900041")));

        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(queueReason("RE-S-1")).isEqualTo("NO_CANDIDATE");
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

    // ── H1: the same payment typed by hand AND uploaded ─────────────────────

    /**
     * Probe P1, manual first (review N1): the Express row MAY be the payment account already typed. It is queued
     * POSSIBLE_MANUAL_DUPLICATE with nothing written and nothing linked; account's "รายการเดียวกับที่บันทึกเอง" then
     * links it, still writing nothing.
     */
    @Test
    void probeP1_manualFirst_theRowIsQueuedForAccount_andTheSameAsManualChoiceLinksIt() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900050");
        // Account confirms the deposit by hand first — the real flow: the deal moves to DEPOSIT_PAID, so an Express row
        // placed now would be a BALANCE. It is still the same payment.
        ticketService.confirmDepositPaid(d, account);
        long manual = jdbc.queryForObject("SELECT receipt_id FROM sales.payment_receipt WHERE ticket_id = :t",
            Map.of("t", d), Long.class);
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).toString();

        JsonNode summary = upload(account, csv(row("RE-H1-1", today, "", "32100", "DN6900050")));

        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(summary.get("possibleManualDuplicates").asInt()).isEqualTo(1);
        assertThat(queueReason("RE-H1-1")).isEqualTo("POSSIBLE_MANUAL_DUPLICATE");
        assertThat(receiptCount("RE-H1-1")).isZero();
        assertThat(paidOn(d)).isEqualByComparingTo("32100");
        assertThat(jdbc.queryForObject("SELECT express_doc_no FROM sales.payment_receipt WHERE receipt_id = :id",
            Map.of("id", manual), String.class)).as("nothing is linked silently").isNull();
        JsonNode queued = ok(get("/api/sales/receipt-imports/unmatched"), account).get(0);
        assertThat(queued.get("possibleManualReceiptId").asLong()).isEqualTo(manual);

        assertThat(ok(post("/api/sales/receipt-imports/unmatched/" + rowId("RE-H1-1") + "/same-as-manual"), account)
            .get("status").asText()).isEqualTo("RESOLVED");
        assertThat(paidOn(d)).isEqualByComparingTo("32100");
        Map<String, Object> m = jdbc.queryForMap("SELECT * FROM sales.payment_receipt WHERE receipt_id = :id",
            Map.of("id", manual));
        assertThat(m.get("express_doc_no")).isEqualTo("RE-H1-1");
        assertThat(m.get("source")).isEqualTo("MANUAL");
        assertThat(m.get("note")).as("manual fields untouched").isEqualTo("ยืนยันรับมัดจำ");

        JsonNode again = upload(account, csv(row("RE-H1-1", today, "", "32100", "DN6900050")));
        assertThat(again.get("duplicates").asInt()).as("a linked number is known").isEqualTo(1);
    }

    @Test
    void aManualReceiptMoreThanSevenDaysAwayIsNotTheSamePayment() throws Exception {
        long d = payableDeal(salesActor, "100000.00");
        manualReceipt(d, "BALANCE", "5000.00", "2026-10-01T03:00:00Z", "MAN-OLD");
        String number = jdbc.queryForObject("SELECT number FROM sales.quotation WHERE ticket_id = :t LIMIT 1",
            Map.of("t", d), String.class);

        JsonNode summary = upload(account, csv(row("RE-H1-2", "2026-11-12", "", "5000", number)));

        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(summary.get("possibleManualDuplicates").asInt()).isZero();
        assertThat(paidOn(d)).isEqualByComparingTo("10000");
    }

    /**
     * Probe Q3 (review N1): a manual cash DEPOSIT of ฿10,000 and then an Express BALANCE of ฿10,000 — a different
     * payment. It used to be swallowed by the auto-link (paid stuck at ฿10,000). Now it is queued, nothing written,
     * and account's "คนละรายการ" records it: paid ฿20,000.
     */
    @Test
    void probeQ3_aDifferentExpressPaymentOfTheSameAmountIsQueued_andCountsOnceAccountSaysItIsDifferent()
        throws Exception {
        long d = payableDeal(salesActor, "100000.00");
        ticketService.recordPayment(d, new RecordPaymentRequest("DEPOSIT", new BigDecimal("10000"), null, "เงินสด",
            null, null, false), account);
        String number = jdbc.queryForObject("SELECT number FROM sales.quotation WHERE ticket_id = :t LIMIT 1",
            Map.of("t", d), String.class);
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).toString();

        JsonNode summary = upload(account, csv(row("RE-Q3-1", today, "", "10000", number)));

        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(queueReason("RE-Q3-1")).isEqualTo("POSSIBLE_MANUAL_DUPLICATE");
        assertThat(receiptCount("RE-Q3-1")).isZero();
        assertThat(paidOn(d)).isEqualByComparingTo("10000");
        long rowId = rowId("RE-Q3-1");
        for (UserPrincipal refused : List.of(salesActor, salesManagerActor, ceo)) {
            assertThat(status(post("/api/sales/receipt-imports/unmatched/" + rowId + "/same-as-manual"), refused))
                .as(refused.role()).isEqualTo(403);
        }

        assertThat(ok(resolve(rowId, d), account).get("status").asText()).isEqualTo("RESOLVED");
        assertThat(paidOn(d)).isEqualByComparingTo("20000");
        assertThat(receipt("RE-Q3-1").get("source")).isEqualTo("EXPRESS");
        assertThat(status(post("/api/sales/receipt-imports/unmatched/" + rowId + "/same-as-manual"), account))
            .as("already handled").isEqualTo(409);
    }

    @Test
    void sameAsManualIsOnlyForAPossibleManualDuplicateRow() throws Exception {
        upload(account, csv(row("RE-SM-1", "2026-11-12", "ไม่มีใคร", "10", "")));
        assertThat(status(post("/api/sales/receipt-imports/unmatched/" + rowId("RE-SM-1") + "/same-as-manual"), account))
            .isEqualTo(409);
    }

    /** Review N4: a RELEASED billing-note line is not the deal's document any more. */
    @Test
    void aReleasedBillingNoteLineIsNotACandidate() throws Exception {
        long d = payableDeal(salesActor, "50000.00");
        long noteId = jdbc.queryForObject("""
            INSERT INTO sales.billing_note (customer_id, type, base_number, version, doc_number, status, bill_date,
                   total_amount, created_by_id, created_by_name, issued_by_id, issued_by_name, issued_at)
            VALUES (:c, 'GOODS', 'BN-REL-1', 1, 'BN-REL-1', 'ISSUED', CURRENT_DATE, 1000, :by, 'x', :by, 'x', now())
            RETURNING id""", Map.of("c", customer.id(), "by", salesActor.id()), Long.class);
        jdbc.update("""
            INSERT INTO sales.billing_note_line (billing_note_id, seq, source_type, ticket_id, doc_number, doc_date,
                   amount, note_status)
            VALUES (:n, 1, 'MANUAL', :t, 'X-1', CURRENT_DATE, 1000, 'RELEASED')""", Map.of("n", noteId, "t", d));

        JsonNode summary = upload(account, csv(row("RE-BN-1", "2026-11-12", "", "1000", "BN-REL-1")));

        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(queueReason("RE-BN-1")).isEqualTo("NO_CANDIDATE");
    }

    /** Review N4: a concurrent upload queueing the same number between the check and the insert is a duplicate. */
    @Test
    void aConcurrentlyQueuedRowIsADuplicate_notAFailure() throws Exception {
        ReceiptImportRepository racing = new ReceiptImportRepository(jdbc) {
            @Override
            public long insertUnmatched(long importId, ParsedReceipt r, String reason, Long suggestedReceiptId) {
                super.insertUnmatched(importId, r, reason, suggestedReceiptId); // the other upload got there first
                return super.insertUnmatched(importId, r, reason, suggestedReceiptId);
            }
        };
        ReceiptImportService service = new ReceiptImportService(racing, new ReceiptPlacement(ticketService, racing),
            tickets, new ReceiptImportSettings(true, Set.of("RE")));
        MockMvc mvc2 = MockMvcBuilders.standaloneSetup(new ReceiptImportController(service, new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        JsonNode summary = json.readTree(performOn(mvc2, uploadRequest(csv(row("RE-RC-1", "2026-11-12", "ไม่มีใคร",
            "10", ""))), account).getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(summary.get("duplicates").asInt()).isEqualTo(1);
        assertThat(summary.get("failed").asInt()).isZero();
    }

    /** Probe P1, Express first: typing the same payment by hand is refused (409) unless account confirms it. */
    @Test
    void probeP1_expressFirst_aManualReceiptOfTheSameAmountNeedsAnExplicitConfirm() throws Exception {
        long d = payableDeal(salesActor, "100000.00");
        String number = jdbc.queryForObject("SELECT number FROM sales.quotation WHERE ticket_id = :t LIMIT 1",
            Map.of("t", d), String.class);
        upload(account, csv(row("RE-H1-3", LocalDate.now().toString(), "", "5000", number)));
        RecordPaymentRequest same = new RecordPaymentRequest("BALANCE", new BigDecimal("5000"), null, null, null,
            "โอน", false);

        ApiException refused = refusal(() -> ticketService.recordPayment(d, same, account), HttpStatus.CONFLICT);
        assertThat(refused.getDetails()).containsEntry("code", "POSSIBLE_DUPLICATE").containsEntry("expressDocNo",
            "RE-H1-3");
        assertThat(refused.getMessage()).contains("อาจซ้ำกับ RE-H1-3 ที่อัปโหลดแล้ว");
        assertThat(paidOn(d)).isEqualByComparingTo("5000");

        ticketService.recordPayment(d, same, account, true);
        assertThat(paidOn(d)).isEqualByComparingTo("10000");
    }

    @Test
    void probeP1_expressFirst_confirmDepositPaidIsGuardedToo() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900060");
        // An Express receipt of the deposit amount landed on the deal while it still awaits the confirm.
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref, source)
            VALUES (:t, 'BALANCE', 32100.00, :by, 'RE-H1-4', 'EXPRESS')""", Map.of("t", d, "by", account.id()));

        ApiException refused = refusal(() -> ticketService.confirmDepositPaid(d, account), HttpStatus.CONFLICT);
        assertThat(refused.getDetails()).containsEntry("code", "POSSIBLE_DUPLICATE");
        assertThat(paidOn(d)).isEqualByComparingTo("32100");
    }

    // ── idempotency, invalid rows, robustness ───────────────────────────────

    @Test
    void reUploadingTheSameFileIsAllDuplicatesAndWritesNothing() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900032");
        byte[] file = csv(
            row("RE-R-1", "2026-11-12", "", "32100", "DN6900032"),
            row("RE-R-2", "2026-11-12", "ไม่มีดีลนี้", "777", ""),
            row("RE-R-1", "2026-11-12", "", "32100", "DN6900032"));

        JsonNode first = upload(account, file);
        assertThat(first.get("matched").asInt()).isEqualTo(1);
        assertThat(first.get("unmatched").asInt()).isEqualTo(1);
        assertThat(first.get("duplicates").asInt()).isEqualTo(1);
        int receipts = count("sales.payment_receipt");
        int queued = count("sales.receipt_import_row");
        String status = summary(d).paymentStatus();

        JsonNode second = upload(account, file);

        assertThat(second.get("duplicates").asInt()).isEqualTo(3);
        assertThat(second.get("duplicateAmount").decimalValue()).isEqualByComparingTo("64977");
        assertThat(count("sales.payment_receipt")).isEqualTo(receipts);
        assertThat(count("sales.receipt_import_row")).isEqualTo(queued);
        assertThat(summary(d).paymentStatus()).isEqualTo(status);
    }

    @Test
    void aReceiptAlreadyTypedByHandWithTheSameNumberIsCountedOnce_andIsNotTouched() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900033");
        long manual = manualReceipt(d, "DEPOSIT", "32100", "2026-09-01T03:00:00Z", "RE-6911-0100");
        String before = jdbc.queryForObject(
            "SELECT row_to_json(r)::text FROM sales.payment_receipt r WHERE receipt_id = :id", Map.of("id", manual),
            String.class);

        JsonNode summary = upload(account, csv(row(" re-6911-0100 ", "2026-11-12", "", "32100", "DN6900033")));

        assertThat(summary.get("duplicates").asInt()).isEqualTo(1);
        assertThat(paidOn(d)).isEqualByComparingTo("32100");
        assertThat(jdbc.queryForObject(
            "SELECT row_to_json(r)::text FROM sales.payment_receipt r WHERE receipt_id = :id", Map.of("id", manual),
            String.class)).isEqualTo(before);
    }

    @Test
    void badRowsAreReportedByLineAndNothingElseIsHeldBack() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900034");
        JsonNode summary = upload(account, csv(
            row("", "2026-11-12", "", "100", ""),
            row("RE-" + "X".repeat(60), "2026-11-12", "", "100", ""),       // review M2: over the 60-character column
            row("RE-B-2", "2026-11-12", "", "32100", "DN6900034")));

        assertThat(summary.get("invalid").asInt()).isEqualTo(2);
        assertThat(summary.get("errors").get(1).get("line").asInt()).isEqualTo(3);
        assertThat(summary.get("errors").get(1).get("message").asText()).contains("60");
        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(paidOn(d)).isEqualByComparingTo("32100");
    }

    /** Review H4: only cash document types become receipts; the doc-type column wins over the number's prefix. */
    @Test
    void invoiceRowsAreNotCash_andTheDocTypeColumnDecides() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900035");
        byte[] file = ("เลขที่เอกสาร,ประเภทเอกสาร,วันที่,ลูกค้า,จำนวนเงิน,อ้างอิง\n"
            + "IV-6911-0001,,2026-11-12,,32100,DN6900035\n"
            + "X-0002,IV,2026-11-12,,32100,DN6900035\n"
            + "X-0003,re,2026-11-12,,32100,DN6900035\n").getBytes(StandardCharsets.UTF_8);

        JsonNode summary = upload(account, file);

        assertThat(summary.get("nonCash").asInt()).isEqualTo(2);
        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(receipt("X-0003").get("ticket_id")).isEqualTo(d);
        assertThat(receiptCount("IV-6911-0001")).isZero();
        assertThat(receiptCount("X-0002")).isZero();
        assertThat(count("sales.receipt_import_row")).as("non-cash rows are not queued either").isZero();
    }

    /** Review H4: the whole upload is off until the owner has checked a real export. */
    @Test
    void theUploadIsOffByDefault() throws Exception {
        MockMvc off = mvcWith(new ReceiptPlacement(ticketService, repository), new ReceiptImportSettings(false, Set.of("RE")));
        MvcResult refused = performOn(off, uploadRequest(csv(row("RE-OFF-1", "2026-11-12", "", "1", ""))), account);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("sales.receipt_import")).isZero();
        JsonNode latest = json.readTree(performOn(off, get("/api/sales/receipt-imports/latest"), account)
            .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(latest.get("uploadEnabled").asBoolean()).isFalse();
        assertThat(ok(get("/api/sales/receipt-imports/latest"), account).get("uploadEnabled").asBoolean()).isTrue();
        assertThat(new ReceiptImportSettings(false, null).cashDocTypes()).as("default cash types").containsExactly("RE");
    }

    /** Probe P2: a concurrent upload landed the same number (another case) between the check and the write. */
    @Test
    void probeP2_aConcurrentDuplicateIsCountedAsADuplicate_notAnUnmatchedRow() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900070");
        long other = deal(otherSalesActor);
        ReceiptPlacement racing = new ReceiptPlacement(ticketService, repository) {
            @Override
            public long place(long ticketId, ParsedReceipt row, UserPrincipal actor) {
                jdbc.update("""
                    INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref, source)
                    VALUES (:t, 'BALANCE', 1, :by, ' re-p2-1', 'EXPRESS')""", Map.of("t", other, "by", account.id()));
                return super.place(ticketId, row, actor);
            }
        };
        MockMvc mvc2 = mvcWith(racing, new ReceiptImportSettings(true, Set.of("RE")));

        JsonNode summary = json.readTree(performOn(mvc2, uploadRequest(csv(row("RE-P2-1", "2026-11-12", "", "32100",
            "DN6900070"))), account).getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(summary.get("duplicates").asInt()).isEqualTo(1);
        assertThat(summary.get("unmatched").asInt()).isZero();
        assertThat(paidOn(d)).isEqualByComparingTo("0");
    }

    /** Probe P5: any failure on one row is that row's error; the rest land and the stored summary is honest. */
    @Test
    void probeP5_anUnexpectedFailureOnOneRowIsRecorded_andTheSummaryStaysHonest() throws Exception {
        long d = awaitingDeposit(salesActor, "32100.00", "DN6900080");
        long e = awaitingDeposit(otherSalesActor, "1000.00", "DN6900081");
        ReceiptPlacement flaky = new ReceiptPlacement(ticketService, repository) {
            @Override
            public long place(long ticketId, ParsedReceipt row, UserPrincipal actor) {
                if (row.docNo().equals("RE-P5-1")) {
                    throw new IllegalStateException("boom");
                }
                return super.place(ticketId, row, actor);
            }
        };
        MockMvc mvc2 = mvcWith(flaky, new ReceiptImportSettings(true, Set.of("RE")));

        JsonNode summary = json.readTree(performOn(mvc2, uploadRequest(csv(
            row("RE-P5-1", "2026-11-12", "", "32100", "DN6900080"),
            row("RE-P5-2", "2026-11-12", "", "1000", "DN6900081"))), account)
            .getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(summary.get("failed").asInt()).isEqualTo(1);
        assertThat(summary.get("matched").asInt()).isEqualTo(1);
        assertThat(summary.get("errors").get(0).get("line").asInt()).isEqualTo(2);
        assertThat(paidOn(e)).isEqualByComparingTo("1000");
        assertThat(paidOn(d)).isEqualByComparingTo("0");
        Map<String, Object> stored = jdbc.queryForMap("SELECT matched, failed, total_rows FROM sales.receipt_import", Map.of());
        assertThat(stored.get("matched")).isEqualTo(1);
        assertThat(stored.get("failed")).isEqualTo(1);
        assertThat(stored.get("total_rows")).isEqualTo(2);
    }

    /** Review M3: the EXPRESS reference index is case- and space-insensitive (two "upserts" of one number). */
    @Test
    void twoExpressReceiptsWhoseNumbersDifferOnlyByCaseOrSpacesCannotBothExist() {
        long d = deal(salesActor);
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref, source)
            VALUES (:t, 'BALANCE', 1, :by, 'RE-CASE-1', 'EXPRESS')""", Map.of("t", d, "by", account.id()));
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref, source)
            VALUES (:t, 'BALANCE', 1, :by, ' re-case-1 ', 'EXPRESS')""", Map.of("t", d, "by", account.id())))
            .isInstanceOf(DataIntegrityViolationException.class);
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

    @Test
    void theManagersViewIsACountPlusOnlyWhatAccountFlagged() throws Exception {
        upload(account, csv(row("RE-M-1", "2026-11-12", "ไม่มีลูกค้านี้", "111", ""),
            row("RE-M-2", "2026-11-12", "ไม่มีลูกค้านี้เหมือนกัน", "222", "")));
        assertThat(ok(post("/api/sales/receipt-imports/unmatched/" + rowId("RE-M-2") + "/flag-manager"), account)
            .get("managerFlaggedAt").isNull()).isFalse();
        assertThat(status(post("/api/sales/receipt-imports/unmatched/" + rowId("RE-M-1") + "/flag-manager"),
            salesManagerActor)).isEqualTo(403);

        JsonNode view = ok(get("/api/sales/receipt-imports/manager-view"), salesManagerActor);
        assertThat(view.get("openUnmatched").asInt()).isEqualTo(2);
        assertThat(view.get("flagged")).hasSize(1);
        assertThat(view.get("flagged").get(0).get("docNo").asText()).isEqualTo("RE-M-2");
        assertThat(view.get("flagged").get(0).has("candidates")).as("read-only: no deals to pick").isFalse();
    }

    @Test
    void accountAttachesARowToADealInOneTap() throws Exception {
        long mine = payableDeal(salesActor, "50000.00");
        upload(account, csv(row("RE-T-1", "2026-11-12", "ผู้รับใบเสนอราคา", "4321", "")));
        long rowId = rowId("RE-T-1");
        JsonNode queue = ok(get("/api/sales/receipt-imports/unmatched"), account);
        assertThat(candidateIds(queue.get(0))).containsExactly(mine);

        JsonNode resolved = ok(resolve(rowId, mine), account);

        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(receipt("RE-T-1").get("ticket_id")).isEqualTo(mine);
        assertThat(status(resolve(rowId, mine), account)).as("already resolved").isEqualTo(409);
    }

    /** Review M4: a deal outside account's finance scope is never a candidate and cannot be resolved onto. */
    @Test
    void aDealOutsideAccountsScopeIsNeverACandidate_norAResolveTarget() throws Exception {
        long lead = deal(salesActor); // LEAD_APPROACH: outside account's scope
        notice(lead, "1000.00", "DN6900090", "ISSUED", "x");
        expectedRow(lead, "DEPOSIT", "1000.00", monday);

        JsonNode summary = upload(account, csv(row("RE-SC-1", "2026-11-12", "", "1000", "DN6900090")));

        assertThat(summary.get("matched").asInt()).isZero();
        assertThat(candidateIds(ok(get("/api/sales/receipt-imports/unmatched"), account).get(0))).isEmpty();
        assertThat(status(resolve(rowId("RE-SC-1"), lead), account)).isEqualTo(403);
        assertThat(paidOn(lead)).isEqualByComparingTo("0");
        assertThat(ids(ok(get("/api/sales/receipt-imports/deals").param("q", "slice 2"), account))).doesNotContain(lead);
    }

    /** Review M5: account can search its scope for a deal when the matcher found none. */
    @Test
    void theDealPickerSearchesAccountsScopeOnly() throws Exception {
        long order = payableDeal(salesActor, "50000.00");
        long lead = deal(salesActor);

        List<Long> found = ids(ok(get("/api/sales/receipt-imports/deals").param("q", "ผู้รับ"), account));

        assertThat(found).contains(order).doesNotContain(lead);
        assertThat(status(get("/api/sales/receipt-imports/deals").param("q", "ผู้รับ"), salesManagerActor))
            .isEqualTo(403);
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

    @Test
    void ignoreAndUnignoreAreAccounts() throws Exception {
        upload(account, csv(row("RE-I-1", "2026-11-12", "ไม่มีใคร", "4321", "")));
        long rowId = rowId("RE-I-1");

        assertThat(ok(post("/api/sales/receipt-imports/unmatched/" + rowId + "/ignore"), account)
            .get("status").asText()).isEqualTo("IGNORED");
        assertThat(docNos(ok(get("/api/sales/receipt-imports/unmatched"), account))).doesNotContain("RE-I-1");
        assertThat(docNos(ok(get("/api/sales/receipt-imports/ignored"), account))).contains("RE-I-1");
        assertThat(status(post("/api/sales/receipt-imports/unmatched/" + rowId + "/unignore"), salesManagerActor))
            .isEqualTo(403);
        assertThat(ok(post("/api/sales/receipt-imports/unmatched/" + rowId + "/unignore"), account)
            .get("status").asText()).isEqualTo("UNMATCHED");
        assertThat(docNos(ok(get("/api/sales/receipt-imports/unmatched"), account))).contains("RE-I-1");
    }

    @Test
    void latestSummarisesTheLastUploadAndCountsWhatIsStillWaiting() throws Exception {
        upload(account, csv(row("RE-L-1", "2026-11-12", "ไม่มีใคร", "10", ""), row("RE-L-2", "2026-11-12", "ไม่มี", "20", "")));
        JsonNode latest = ok(get("/api/sales/receipt-imports/latest"), account);
        assertThat(latest.get("latest").get("fileName").asText()).isEqualTo("express.csv");
        assertThat(latest.get("openUnmatched").asInt()).isEqualTo(2);
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

    private static List<Long> ids(JsonNode deals) {
        List<Long> out = new ArrayList<>();
        deals.forEach(c -> out.add(c.get("ticketId").asLong()));
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
        return performOn(mvc, request, who);
    }

    private static MvcResult performOn(MockMvc on, AbstractMockHttpServletRequestBuilder<?> request, UserPrincipal who)
        throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, who);
        return on.perform(request.session(session)).andReturn();
    }
}
