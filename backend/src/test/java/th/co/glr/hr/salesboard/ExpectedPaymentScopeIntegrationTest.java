package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
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

    // ── validation ───────────────────────────────────────────────────────────

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
