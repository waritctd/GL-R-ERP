package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.ticket.QuietRule;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.salesboard.SalesBoardDtos.CashMonth;
import th.co.glr.hr.salesboard.SalesBoardDtos.CashWeek;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * SalesBoardService#cashForMonth through the real repository and Postgres (V212 expected_payment + V53
 * payment_receipt). One fixture month (November 2026) covers every bucket, every exclusion (CANCELLED, other months,
 * a lost deal, another rep) and both Bangkok month edges: 23:30 Bangkok on 30 Nov is November; 00:30 Bangkok on 1 Dec
 * — still 30 Nov in UTC — is NOT. The HTTP half pins scope wrong-way-round: a rep's my-work cash never includes
 * another rep's money. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class CashForMonthIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private final ObjectMapper json = new ObjectMapper();
    private SalesBoardService board;
    private MockMvc mvc;

    @BeforeEach
    void wire() {
        QuietRule quietRule = new QuietRule(jdbc, 14);
        Clock clock = Clock.system(VisitService.BANGKOK);
        VisitService visits = new VisitService(new VisitRepository(jdbc), ticketService, clock);
        board = new SalesBoardService(new SalesBoardRepository(jdbc, quietRule), quietRule,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), visits, new CashRepository(jdbc), clock);
        mvc = MockMvcBuilders
            .standaloneSetup(new SalesBoardController(board, new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void novemberBucketsAndEdges_forOneRep() {
        long delivered = dealAt(salesActor, "DELIVERED");
        long scheduled = dealAt(salesActor, "DELIVERY_SCHEDULING");
        long procurement = dealAt(salesActor, "PROCUREMENT");
        long lead = dealAt(salesActor, "LEAD_APPROACH");
        long lost = dealAt(salesActor, "LEAD_APPROACH");
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST', lost_reason = 'PRICE', lost_at = now() "
            + "WHERE ticket_id = :id", Map.of("id", lost));

        expected(delivered, "BALANCE", "300", "2026-11-09", "OPEN");
        expected(delivered, "ON_DELIVERY", "200", "2026-11-09", "OPEN");
        long depositReceipt = receipt(delivered, "DEPOSIT", "1000", "2026-11-03T03:00:00Z", "RE-1");
        long receivedRow = expected(delivered, "DEPOSIT", "1000", "2026-11-02", "OPEN");
        jdbc.update("UPDATE sales.expected_payment SET status = 'RECEIVED', receipt_id = :r "
            + "WHERE expected_payment_id = :id", Map.of("r", depositReceipt, "id", receivedRow));
        expected(scheduled, "ON_DELIVERY", "50", "2026-11-16", "OPEN");
        expected(procurement, "ON_DELIVERY", "70", "2026-11-23", "OPEN");
        expected(procurement, "BALANCE", "999", "2026-11-23", "OPEN"); // forecast only: not DELIVERED yet
        expected(lead, "DEPOSIT", "400", "2026-11-30", "OPEN");
        expected(lead, "DEPOSIT", "5000", "2026-11-09", "CANCELLED");
        expected(lead, "DEPOSIT", "7000", "2026-12-07", "OPEN");
        expected(lead, "DEPOSIT", "6000", "2026-10-26", "OPEN");
        expected(lost, "DEPOSIT", "8000", "2026-11-09", "OPEN");
        long held = dealAt(salesActor, "LEAD_APPROACH");
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :id", Map.of("id", held));
        expected(held, "DEPOSIT", "9000", "2026-11-09", "OPEN"); // an ON_HOLD deal expects nothing this month

        receipt(delivered, "BALANCE", "250", "2026-11-30T16:30:00Z", "RE-2");   // 23:30 Bangkok, 30 Nov
        receipt(delivered, "BALANCE", "111", "2026-11-30T17:30:00Z", "RE-3");   // 00:30 Bangkok, 1 Dec
        receipt(delivered, "BALANCE", "222", "2026-10-31T16:59:00Z", "RE-4");   // 23:59 Bangkok, 31 Oct
        receipt(delivered, "BALANCE", "33", "2026-10-31T17:00:00Z", "RE-5");    // 00:00 Bangkok, Sun 1 Nov
        receipt(delivered, "ADJUSTMENT", "10", "2026-11-10T05:00:00Z", "RE-6"); // a refund subtracts
        // money the rep recorded as received before the portal is not this month's collection
        jdbc.update("UPDATE sales.payment_receipt SET source = 'PRE_PORTAL' WHERE receipt_id = :id", Map.of("id",
            receipt(delivered, "DEPOSIT", "777", "2026-11-12T05:00:00Z", "RE-PRE")));

        long theirs = dealAt(otherSalesActor, "DELIVERED");
        expected(theirs, "BALANCE", "90000", "2026-11-09", "OPEN");
        receipt(theirs, "BALANCE", "50000", "2026-11-10T05:00:00Z", "RE-7");

        CashMonth mine = board.cashForMonth(salesActor.id(), NOV);

        assertThat(mine.month()).isEqualTo("2026-11");
        // The week of Mon 30 Nov has its Thursday on 3 Dec, so its 400 belongs to December (ISO week rule).
        assertThat(mine.forecast()).isEqualByComparingTo("2619");
        assertThat(mine.received()).isEqualByComparingTo("1273");
        assertThat(mine.deliveredAwaiting()).isEqualByComparingTo("500");
        assertThat(mine.deliveriesScheduled()).isEqualByComparingTo("120");
        assertThat(mine.depositsExpected()).isEqualByComparingTo("0");

        assertThat(mine.weeks()).extracting(CashWeek::weekStart).containsExactly(
            LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-16"),
            LocalDate.parse("2026-11-23"));
        week(mine, 0, "0", "0", "0", "1033"); // Sun 1 Nov folds into the first week
        week(mine, 1, "500", "0", "0", "-10");
        week(mine, 2, "0", "50", "0", "0");
        week(mine, 3, "0", "70", "0", "250"); // Mon 30 Nov's receipt is still November cash: the last week holds it
        BigDecimal weeklyReceived = mine.weeks().stream().map(CashWeek::received)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(weeklyReceived).as("weeks add up to the month").isEqualByComparingTo(mine.received());

        CashMonth team = board.cashForMonth(null, NOV);
        assertThat(team.forecast()).isEqualByComparingTo("92619");
        assertThat(team.received()).isEqualByComparingTo("51273");
        assertThat(team.deliveredAwaiting()).isEqualByComparingTo("90500");
    }

    @Test
    void aPartlyReceivedRowKeepsOnlyItsRemainderInTheBuckets() {
        long lead = dealAt(salesActor, "LEAD_APPROACH");
        long row = expected(lead, "DEPOSIT", "1000", "2026-11-09", "OPEN");
        jdbc.update("UPDATE sales.expected_payment SET received_amount = 400 WHERE expected_payment_id = :id",
            Map.of("id", row));

        CashMonth nov = board.cashForMonth(salesActor.id(), NOV);
        assertThat(nov.forecast()).as("the forecast is the plan").isEqualByComparingTo("1000");
        assertThat(nov.depositsExpected()).as("only what is still to come").isEqualByComparingTo("600");
    }

    // ── scope through HTTP, this month ────────────────────────────────────────

    @Test
    void myWorkCashNeverIncludesAnotherRepsMoney() throws Exception {
        LocalDate monday = LocalDate.now(VisitService.BANGKOK).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // A Monday in this month (the 1st may fall after this week's Monday).
        LocalDate inMonth = monday.getMonth() == LocalDate.now(VisitService.BANGKOK).getMonth()
            ? monday : monday.plusWeeks(1);
        long mine = dealAt(salesActor, "LEAD_APPROACH");
        long theirs = dealAt(otherSalesActor, "LEAD_APPROACH");
        expected(mine, "DEPOSIT", "100", inMonth.toString(), "OPEN");
        expected(theirs, "DEPOSIT", "90000", inMonth.toString(), "OPEN");
        receipt(theirs, "DEPOSIT", "50000", Instant.now().toString(), "RE-THEIRS");

        JsonNode cash = ok(get("/api/sales/my-work"), salesActor).get("cash");
        assertThat(cash.get("forecast").decimalValue()).isEqualByComparingTo("100");
        assertThat(cash.get("received").decimalValue()).isEqualByComparingTo("0");
        assertThat(cash.get("depositsExpected").decimalValue()).isEqualByComparingTo("100");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private long dealAt(UserPrincipal owner, String stage) {
        long id = deal(owner);
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id", Map.of("s", stage, "id", id));
        return id;
    }

    private long expected(long ticketId, String kind, String amount, String week, String status) {
        return jdbc.queryForObject("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, status, created_by)
            VALUES (:t, :k, :a, :w, :s, :by) RETURNING expected_payment_id
            """, Map.of("t", ticketId, "k", kind, "a", new BigDecimal(amount), "w", LocalDate.parse(week),
                "s", "RECEIVED".equals(status) ? "OPEN" : status, "by", salesManagerActor.id()), Long.class);
    }

    private long receipt(long ticketId, String kind, String amount, String receivedAt, String ref) {
        return jdbc.queryForObject("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, received_at, recorded_by, receipt_ref)
            VALUES (:t, :k, :a, :at, :by, :ref) RETURNING receipt_id
            """, Map.of("t", ticketId, "k", kind, "a", new BigDecimal(amount),
                "at", Timestamp.from(Instant.parse(receivedAt)), "by", salesManagerActor.id(), "ref", ref), Long.class);
    }

    private static void week(CashMonth month, int i, String delivered, String scheduled, String deposits,
                             String received) {
        CashWeek w = month.weeks().get(i);
        assertThat(w.deliveredAwaiting()).as("week %s delivered", w.weekStart()).isEqualByComparingTo(delivered);
        assertThat(w.deliveriesScheduled()).as("week %s scheduled", w.weekStart()).isEqualByComparingTo(scheduled);
        assertThat(w.depositsExpected()).as("week %s deposits", w.weekStart()).isEqualByComparingTo(deposits);
        assertThat(w.received()).as("week %s received", w.weekStart()).isEqualByComparingTo(received);
    }

    private static JsonNode rep(JsonNode team, long employeeId) {
        for (JsonNode r : team.get("reps")) {
            if (r.get("employeeId").asLong() == employeeId) {
                return r;
            }
        }
        throw new AssertionError("no rep row for " + employeeId + " in " + team.get("reps"));
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        MvcResult result = perform(request, actor);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        return json.readTree(body);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return mvc.perform(request.session(session)).andReturn();
    }
}
