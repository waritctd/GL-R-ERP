package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * /api/tickets/{id}/expected-payments through the real controller, service, repository and Postgres (V212). Written
 * wrong-way-round: another rep must NOT read or write a deal's schedule (by deal id or by guessing a row id), account
 * may read inside its finance scope but NEVER write, import/hr reach nothing, an expected week must be a Monday, and
 * a RECEIVED row can be neither edited nor cancelled. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ExpectedPaymentScopeIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private LocalDate monday;

    @BeforeEach
    void wireHttp() {
        ExpectedPaymentService service = new ExpectedPaymentService(new ExpectedPaymentRepository(jdbc),
            ticketService, tickets);
        mvc = MockMvcBuilders
            .standaloneSetup(new ExpectedPaymentController(service, new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        monday = LocalDate.now(VisitService.BANGKOK).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ── the owner ────────────────────────────────────────────────────────────

    @Test
    void ownerCreatesListsEditsAndCancels_listOrderedByWeekThenId() throws Exception {
        long mine = deal(salesActor);
        long later = create(salesActor, mine, "BALANCE", "456150.00", monday.plusWeeks(3));
        long sooner = create(salesActor, mine, "DEPOSIT", "456150.00", monday);

        JsonNode listed = ok(get(path(mine)), salesActor);
        assertThat(ids(listed)).containsExactly(sooner, later);
        JsonNode first = listed.get(0);
        assertThat(first.get("kind").asText()).isEqualTo("DEPOSIT");
        assertThat(first.get("amount").decimalValue()).isEqualByComparingTo("456150.00");
        assertThat(first.get("expectedWeek").asText()).isEqualTo(monday.toString());
        assertThat(first.get("status").asText()).isEqualTo("OPEN");
        assertThat(first.get("createdById").asLong()).isEqualTo(salesActor.id());

        JsonNode edited = ok(put(path(mine) + "/" + later).contentType(MediaType.APPLICATION_JSON)
            .content(body("ON_DELIVERY", "100000", monday.plusWeeks(1))), salesActor);
        assertThat(edited.get("kind").asText()).isEqualTo("ON_DELIVERY");
        assertThat(edited.get("amount").decimalValue()).isEqualByComparingTo("100000");

        JsonNode cancelled = ok(delete(path(mine) + "/" + later), salesActor);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(ids(ok(get(path(mine)), salesActor))).as("cancelled rows leave the list").containsExactly(sooner);
    }

    @Test
    void managerAndCeoMayWriteAnyRepsDeal() throws Exception {
        long repDeal = deal(salesActor);
        UserPrincipal ceo = actor(createEmployee("ซีอีโอ", "ceo-ep@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");
        for (UserPrincipal writer : List.of(salesManagerActor, ceo)) {
            assertThat(status(createRequest(repDeal, "DEPOSIT", "1000", monday), writer)).as(writer.role())
                .isEqualTo(200);
        }
        assertThat(ids(ok(get(path(repDeal)), salesManagerActor))).hasSize(2);
    }

    // ── another rep ──────────────────────────────────────────────────────────

    @Test
    void otherRepCannotReadOrWriteTheDealsSchedule() throws Exception {
        long theirDeal = deal(otherSalesActor);
        long theirs = create(otherSalesActor, theirDeal, "DEPOSIT", "5000", monday);

        assertThat(status(get(path(theirDeal)), salesActor)).isEqualTo(403);
        assertThat(status(createRequest(theirDeal, "DEPOSIT", "1", monday), salesActor)).isEqualTo(403);
        assertThat(status(put(path(theirDeal) + "/" + theirs).contentType(MediaType.APPLICATION_JSON)
            .content(body("DEPOSIT", "1", monday)), salesActor)).isEqualTo(403);
        assertThat(status(delete(path(theirDeal) + "/" + theirs), salesActor)).isEqualTo(403);

        assertThat(rowCount(theirDeal)).isEqualTo(1);
        assertThat(amountOf(theirs)).isEqualByComparingTo("5000");
        assertThat(statusOf(theirs)).isEqualTo("OPEN");
    }

    @Test
    void guessingAnotherDealsRowIdThroughOwnDealIs404AndChangesNothing() throws Exception {
        long mine = deal(salesActor);
        long theirDeal = deal(otherSalesActor);
        long theirs = create(otherSalesActor, theirDeal, "DEPOSIT", "5000", monday);

        assertThat(status(put(path(mine) + "/" + theirs).contentType(MediaType.APPLICATION_JSON)
            .content(body("DEPOSIT", "1", monday)), salesActor)).isEqualTo(404);
        assertThat(status(delete(path(mine) + "/" + theirs), salesActor)).isEqualTo(404);

        assertThat(amountOf(theirs)).isEqualByComparingTo("5000");
        assertThat(statusOf(theirs)).isEqualTo("OPEN");
    }

    // ── account, import, hr ──────────────────────────────────────────────────

    @Test
    void accountReadsInsideItsScopeButCannotWrite() throws Exception {
        long order = deal(salesActor);
        long row = create(salesActor, order, "DEPOSIT", "5000", monday);
        setStage(order, "ORDER_RECEIVED");
        UserPrincipal account = actor(createEmployee("บัญชี", "acc-ep@glr.co.th", "ACC", "บัญชี", null), "account");

        assertThat(ids(ok(get(path(order)), account))).containsExactly(row);
        assertThat(status(createRequest(order, "DEPOSIT", "1", monday), account)).isEqualTo(403);
        assertThat(status(put(path(order) + "/" + row).contentType(MediaType.APPLICATION_JSON)
            .content(body("DEPOSIT", "1", monday)), account)).isEqualTo(403);
        assertThat(status(delete(path(order) + "/" + row), account)).isEqualTo(403);
        assertThat(rowCount(order)).isEqualTo(1);
        assertThat(amountOf(row)).isEqualByComparingTo("5000");
    }

    @Test
    void accountCannotReadADealOutsideItsScope() throws Exception {
        long lead = deal(salesActor); // LEAD_APPROACH: not in account's finance scope
        create(salesActor, lead, "DEPOSIT", "5000", monday);
        UserPrincipal account = actor(createEmployee("บัญชี", "acc-ep2@glr.co.th", "ACC", "บัญชี", null), "account");

        assertThat(status(get(path(lead)), account)).isEqualTo(403);
    }

    @Test
    void importAndHrReachNothing() throws Exception {
        long order = deal(salesActor);
        long row = create(salesActor, order, "DEPOSIT", "5000", monday);
        setStage(order, "ORDER_RECEIVED");
        for (String role : List.of("import", "hr")) {
            UserPrincipal other = actor(createEmployee("ผู้ใช้ " + role, role + "-ep@glr.co.th", "OPS", "ปฏิบัติการ",
                null), role);
            assertThat(status(get(path(order)), other)).as("%s list", role).isEqualTo(403);
            assertThat(status(createRequest(order, "DEPOSIT", "1", monday), other)).as("%s create", role)
                .isEqualTo(403);
            assertThat(status(delete(path(order) + "/" + row), other)).as("%s cancel", role).isEqualTo(403);
        }
        assertThat(rowCount(order)).isEqualTo(1);
    }

    // ── validation ───────────────────────────────────────────────────────────

    @Test
    void expectedWeekMustBeAMonday() throws Exception {
        long mine = deal(salesActor);
        for (int d = 1; d <= 6; d++) {
            assertThat(status(createRequest(mine, "DEPOSIT", "1000", monday.plusDays(d)), salesActor))
                .as("%s", monday.plusDays(d).getDayOfWeek()).isEqualTo(400);
        }
        assertThat(rowCount(mine)).isZero();
        // The table refuses it too, whoever writes.
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, created_by)
            VALUES (:t, 'DEPOSIT', 1, :w, :by)
            """, Map.of("t", mine, "w", monday.plusDays(2), "by", salesActor.id())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesBadKindAmountAndMissingWeek() throws Exception {
        long mine = deal(salesActor);
        assertThat(status(createRequest(mine, "REFUND", "1000", monday), salesActor)).isEqualTo(400);
        assertThat(status(createRequest(mine, "DEPOSIT", "0", monday), salesActor)).isEqualTo(400);
        assertThat(status(createRequest(mine, "DEPOSIT", "-5", monday), salesActor)).isEqualTo(400);
        assertThat(status(createRequest(mine, "DEPOSIT", "1000", null), salesActor)).isEqualTo(400);
        assertThat(status(createRequest(mine, "DEPOSIT", "1000.005", monday), salesActor)).isEqualTo(400);
        assertThat(rowCount(mine)).isZero();
    }

    @Test
    void receivedRowCannotBeCancelledOrEdited() throws Exception {
        long mine = deal(salesActor);
        long row = create(salesActor, mine, "DEPOSIT", "5000", monday);
        markReceived(mine, row);

        assertThat(status(delete(path(mine) + "/" + row), salesActor)).isEqualTo(409);
        assertThat(status(put(path(mine) + "/" + row).contentType(MediaType.APPLICATION_JSON)
            .content(body("DEPOSIT", "1", monday)), salesActor)).isEqualTo(409);
        assertThat(statusOf(row)).isEqualTo("RECEIVED");
        assertThat(amountOf(row)).isEqualByComparingTo("5000");
        assertThat(ids(ok(get(path(mine)), salesActor))).as("RECEIVED stays listed").containsExactly(row);
    }

    @Test
    void cancelledRowCannotBeEditedOrCancelledAgain() throws Exception {
        long mine = deal(salesActor);
        long row = create(salesActor, mine, "DEPOSIT", "5000", monday);
        ok(delete(path(mine) + "/" + row), salesActor);

        assertThat(status(delete(path(mine) + "/" + row), salesActor)).isEqualTo(409);
        assertThat(status(put(path(mine) + "/" + row).contentType(MediaType.APPLICATION_JSON)
            .content(body("DEPOSIT", "1", monday)), salesActor)).isEqualTo(409);
    }

    @Test
    void dealNotActiveRefusesNewRows() throws Exception {
        long mine = deal(salesActor);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :id", Map.of("id", mine));

        assertThat(status(createRequest(mine, "DEPOSIT", "1000", monday), salesActor)).isEqualTo(409);
    }

    @Test
    void unknownDealIs404() throws Exception {
        assertThat(status(get(path(987654321L)), salesManagerActor)).isEqualTo(404);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String path(long ticketId) {
        return "/api/tickets/" + ticketId + "/expected-payments";
    }

    private long create(UserPrincipal actor, long ticketId, String kind, String amount, LocalDate week)
        throws Exception {
        return ok(createRequest(ticketId, kind, amount, week), actor).get("id").asLong();
    }

    private MockHttpServletRequestBuilder createRequest(long ticketId, String kind, String amount, LocalDate week)
        throws Exception {
        return post(path(ticketId)).contentType(MediaType.APPLICATION_JSON).content(body(kind, amount, week));
    }

    private String body(String kind, String amount, LocalDate week) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", kind);
        b.put("amount", new java.math.BigDecimal(amount));
        b.put("expectedWeek", week == null ? null : week.toString());
        return json.writeValueAsString(b);
    }

    private void setStage(long ticketId, String stage) {
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id", Map.of("s", stage, "id", ticketId));
    }

    private void markReceived(long ticketId, long rowId) {
        Long receipt = jdbc.queryForObject("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref)
            VALUES (:t, 'DEPOSIT', 5000, :by, :ref) RETURNING receipt_id
            """, Map.of("t", ticketId, "by", salesManagerActor.id(), "ref", "RE-TEST-" + rowId), Long.class);
        jdbc.update("UPDATE sales.expected_payment SET status = 'RECEIVED', receipt_id = :r "
            + "WHERE expected_payment_id = :id", Map.of("r", receipt, "id", rowId));
    }

    private int rowCount(long ticketId) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.expected_payment WHERE ticket_id = :id",
            Map.of("id", ticketId), Integer.class);
    }

    private java.math.BigDecimal amountOf(long rowId) {
        return jdbc.queryForObject("SELECT amount FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", rowId), java.math.BigDecimal.class);
    }

    private String statusOf(long rowId) {
        return jdbc.queryForObject("SELECT status FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", rowId), String.class);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        MvcResult result = perform(request, actor);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        return json.readTree(body);
    }

    private int status(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        return perform(request, actor).getResponse().getStatus();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return mvc.perform(request.session(session)).andReturn();
    }

    private static List<Long> ids(JsonNode rows) {
        List<Long> out = new ArrayList<>();
        rows.forEach(v -> out.add(v.get("id").asLong()));
        return out;
    }
}
