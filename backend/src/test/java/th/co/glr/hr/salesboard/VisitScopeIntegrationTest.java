package th.co.glr.hr.salesboard;

import th.co.glr.hr.ticket.QuietRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
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
 * /api/sales/visits through the real controller, service, repository and Postgres (V211 sales.visit). Written
 * wrong-way-round: a rep must NOT see, complete or cancel another rep's visit, must NOT hang a visit on another rep's
 * deal, and sales/account/import/hr must NOT reach the team view. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class VisitScopeIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private QuietRule quietRule;
    private LocalDate today;

    @BeforeEach
    void wireHttp() {
        SessionContext sessions = new SessionContext();
        quietRule = new QuietRule(jdbc, 14);
        // Fixed clock (review M5): Thursday 12 Nov 2026, 10:00 Bangkok. Every date below is relative to this
        // hard-coded day, not to the machine's clock. (QuietRule still reads the database's now(), so the quiet
        // assertions here only ever use dates that are in the past for both.)
        Clock clock = Clock.fixed(java.time.Instant.parse("2026-11-12T03:00:00Z"), VisitService.BANGKOK);
        today = LocalDate.of(2026, 11, 12);
        VisitService service = new VisitService(new VisitRepository(jdbc), ticketService, clock);
        mvc = MockMvcBuilders
            .standaloneSetup(new VisitController(service, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // ── a rep reaches only their own visits ──────────────────────────────────

    @Test
    void repCannotSeeOrCompleteAnotherRepsVisit() throws Exception {
        long theirDeal = deal(otherSalesActor);
        long theirs = plan(otherSalesActor, theirDeal, "คุณอรุณ · อรุณ อาร์คิเทค", today);
        long mine = plan(salesActor, null, "เทพสตูดิโอ ดีไซน์", today);

        JsonNode listed = ok(range("/api/sales/visits", today, today), salesActor);
        assertThat(ids(listed)).containsExactly(mine);

        assertThat(status(post("/api/sales/visits/" + theirs + "/done").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"แอบติ๊ก\"}"), salesActor)).isEqualTo(403);
        assertThat(status(post("/api/sales/visits/" + theirs + "/cancel"), salesActor)).isEqualTo(403);

        assertThat(visitStatus(theirs)).isEqualTo("PLANNED");
        assertThat(siteVisitRows(theirDeal)).isZero();
    }

    // ── team view ────────────────────────────────────────────────────────────

    @Test
    void managerAndCeoSeeEveryRepsVisits() throws Exception {
        long a = plan(salesActor, null, "ก", today);
        long b = plan(otherSalesActor, null, "ข", today);
        UserPrincipal ceo = actor(createEmployee("ซีอีโอ", "ceo-visit@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");

        for (UserPrincipal viewer : List.of(salesManagerActor, ceo)) {
            JsonNode team = ok(range("/api/sales/visits/team", today, today), viewer);
            assertThat(ids(team)).as(viewer.role()).contains(a, b);
        }
    }

    /** v2.3.0 read-all grant: a flagged qc/employee reads the team's week and a deal's planned visits, and is refused
     * to log, tick or cancel any visit (TEAM_ROLES is not widened); without the flag the team view stays closed. */
    @Test
    void readAllHolderSeesTheTeamButChangesNoVisit() throws Exception {
        long theirDeal = deal(otherSalesActor);
        long theirs = plan(otherSalesActor, theirDeal, "คุณอรุณ", today);
        UserPrincipal qc = actor(createEmployee("คิวซี", "qc-visit@glr.co.th", "QC", "ฝ่าย QC", null), "qc");
        UserPrincipal staff = actor(createEmployee("พนักงาน", "emp-visit@glr.co.th", "OTHER", "ฝ่ายอื่น", null), "employee");

        for (UserPrincipal who : List.of(qc, staff)) {
            assertThat(status(range("/api/sales/visits/team", today, today), who)).as(who.role()).isEqualTo(403);
            assertThat(status(get("/api/sales/visits/deal/" + theirDeal + "/planned"), who)).isEqualTo(403);
        }
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id IN (:a, :b)",
            Map.of("a", qc.id(), "b", staff.id()));
        for (UserPrincipal who : List.of(qc, staff)) {
            assertThat(ids(ok(range("/api/sales/visits/team", today, today), who))).as(who.role()).contains(theirs);
            assertThat(ids(ok(get("/api/sales/visits/deal/" + theirDeal + "/planned"), who))).contains(theirs);
            assertThat(status(post("/api/sales/visits/" + theirs + "/done").contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}"), who)).as(who.role()).isEqualTo(403);
            assertThat(status(post("/api/sales/visits/" + theirs + "/cancel"), who)).isEqualTo(403);
            assertThat(status(createRequest(null, "ลูกค้า", today, null, false), who)).isEqualTo(403);
        }
        assertThat(visitStatus(theirs)).isEqualTo("PLANNED");
    }

    // ── done / cancel lifecycle ──────────────────────────────────────────────

    @Test
    void doneOnLinkedDealResetsQuiet() throws Exception {
        long mine = deal(salesActor);
        backdate(mine, 30);
        // Yesterday's unticked visit no longer holds the deal (QuietRule.upcomingVisitSql), so the tick is what clears it.
        long visit = plan(salesActor, mine, "คุณอรุณ", today.minusDays(1));
        assertThat(quietRule.isQuiet(mine)).as("a past-dated planned visit does not touch the deal").isTrue();

        JsonNode done = ok(post("/api/sales/visits/" + visit + "/done").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"ชอบ Raku สีเทา\"}"), salesActor);

        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("doneAt").isNull()).isFalse();
        assertThat(done.get("note").asText()).isEqualTo("ชอบ Raku สีเทา");
        assertThat(quietRule.isQuiet(mine)).isFalse();
        Map<String, Object> activity = jdbc.queryForMap("""
            SELECT kind, activity_date, note, created_by_id FROM sales.deal_activity
             WHERE ticket_id = :id AND kind = 'SITE_VISIT'
            """, Map.of("id", mine));
        assertThat(activity.get("kind")).isEqualTo("SITE_VISIT");
        assertThat(activity.get("activity_date").toString()).isEqualTo(today.toString());
        assertThat(activity.get("note")).isEqualTo("ชอบ Raku สีเทา");
        assertThat(((Number) activity.get("created_by_id")).longValue()).isEqualTo(salesActor.id());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long plan(UserPrincipal owner, Long ticketId, String contact, LocalDate date) throws Exception {
        return plan(owner, ticketId, contact, date, null);
    }

    private long plan(UserPrincipal owner, Long ticketId, String contact, LocalDate date, String time)
        throws Exception {
        return ok(createRequest(ticketId, contact, date, time, false), owner).get("id").asLong();
    }

    private MockHttpServletRequestBuilder createRequest(Long ticketId, String contact, LocalDate date, String time,
                                                        boolean done) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId);
        body.put("contactName", contact);
        body.put("plannedDate", date == null ? null : date.toString());
        body.put("plannedTime", time);
        body.put("note", "นำตัวอย่างไปเทียบสี");
        body.put("done", done);
        return post("/api/sales/visits").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private static MockHttpServletRequestBuilder range(String path, LocalDate from, LocalDate to) {
        return get(path).param("from", from.toString()).param("to", to.toString());
    }

    private String visitStatus(long visitId) {
        return jdbc.queryForObject("SELECT status FROM sales.visit WHERE visit_id = :id", Map.of("id", visitId),
            String.class);
    }

    private int siteVisitRows(long ticketId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM sales.deal_activity WHERE ticket_id = :id AND kind = 'SITE_VISIT'",
            Map.of("id", ticketId), Integer.class);
    }

    /** Pushes the deal, its events and its activities {@code days} into the past. */
    private void backdate(long ticketId, int days) {
        Map<String, Object> p = Map.of("d", days, "id", ticketId);
        jdbc.update("UPDATE sales.ticket SET created_at = now() - make_interval(days => :d) WHERE ticket_id = :id", p);
        jdbc.update("UPDATE sales.ticket_event SET created_at = now() - make_interval(days => :d) WHERE ticket_id = :id",
            p);
        jdbc.update("UPDATE sales.deal_activity SET created_at = now() - make_interval(days => :d) WHERE ticket_id = :id",
            p);
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

    private static List<Long> ids(JsonNode visits) {
        List<Long> out = new ArrayList<>();
        visits.forEach(v -> out.add(v.get("id").asLong()));
        return out;
    }
}
