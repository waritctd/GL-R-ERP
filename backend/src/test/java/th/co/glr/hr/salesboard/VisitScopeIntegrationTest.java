package th.co.glr.hr.salesboard;

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
        Clock clock = Clock.system(VisitService.BANGKOK);
        today = LocalDate.now(clock);
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

    @Test
    void listIsOwnVisitsInRange_orderedByDateThenTime() throws Exception {
        long later = plan(salesActor, null, "บ่าย", today, "14:00");
        long earlier = plan(salesActor, null, "เช้า", today, "09:30");
        long untimed = plan(salesActor, null, "ไม่ระบุเวลา", today, null);
        long yesterday = plan(salesActor, null, "เมื่อวาน", today.minusDays(1), "16:00");
        long outOfRange = plan(salesActor, null, "เดือนหน้า", today.plusDays(40));

        JsonNode listed = ok(range("/api/sales/visits", today.minusDays(1), today), salesActor);

        assertThat(ids(listed)).containsExactly(yesterday, earlier, later, untimed).doesNotContain(outOfRange);
        JsonNode first = listed.get(1);
        assertThat(first.get("ownerId").asLong()).isEqualTo(salesActor.id());
        assertThat(first.get("contactName").asText()).isEqualTo("เช้า");
        assertThat(first.get("status").asText()).isEqualTo("PLANNED");
        assertThat(first.get("plannedDate").asText()).isEqualTo(today.toString());
    }

    @Test
    void managerOwnListIsStillOnlyTheirOwnVisits() throws Exception {
        long repVisit = plan(salesActor, null, "ลูกค้าของเซลส์", today);

        assertThat(ids(ok(range("/api/sales/visits", today, today), salesManagerActor)))
            .doesNotContain(repVisit);
    }

    // ── team view ────────────────────────────────────────────────────────────

    @Test
    void salesRoleRefusedTeamVisits() throws Exception {
        plan(otherSalesActor, null, "ของคนอื่น", today);

        assertThat(status(range("/api/sales/visits/team", today, today), salesActor)).isEqualTo(403);
    }

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

    @Test
    void accountRefusedEverything() throws Exception {
        long repVisit = plan(salesActor, null, "ลูกค้า", today);
        for (String role : List.of("account", "import", "hr")) {
            UserPrincipal other = actor(createEmployee("ผู้ใช้ " + role, role + "-visit@glr.co.th", "ACC", "บัญชี",
                null), role);
            assertThat(status(range("/api/sales/visits", today, today), other)).as("%s list", role)
                .isEqualTo(403);
            assertThat(status(range("/api/sales/visits/team", today, today), other)).as("%s team", role)
                .isEqualTo(403);
            assertThat(status(createRequest(null, "ลูกค้า", today, null, false), other)).as("%s create", role)
                .isEqualTo(403);
            assertThat(status(post("/api/sales/visits/" + repVisit + "/done").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), other)).as("%s done", role).isEqualTo(403);
            assertThat(status(post("/api/sales/visits/" + repVisit + "/cancel"), other)).as("%s cancel", role)
                .isEqualTo(403);
        }
        assertThat(visitStatus(repVisit)).isEqualTo("PLANNED");
        assertThat(visitCount()).isEqualTo(1);
    }

    // ── a visit on a deal: the deal's write check applies ────────────────────

    @Test
    void visitOnSomeoneElsesDealIs403() throws Exception {
        long theirDeal = deal(otherSalesActor);

        assertThat(status(createRequest(theirDeal, "คุณอรุณ", today, null, false), salesActor)).isEqualTo(403);
        assertThat(status(createRequest(theirDeal, "คุณอรุณ", today, null, true), salesActor)).isEqualTo(403);

        assertThat(visitCount()).isZero();
        assertThat(siteVisitRows(theirDeal)).isZero();
    }

    @Test
    void visitOnMissingDealIs404() throws Exception {
        assertThat(status(createRequest(987_654_321L, "คุณอรุณ", today, null, false), salesActor)).isEqualTo(404);
        assertThat(visitCount()).isZero();
    }

    @Test
    void visitOnOwnDealCarriesTheDealTitle() throws Exception {
        long mine = deal(salesActor);

        JsonNode created = ok(createRequest(mine, "คุณอรุณ", today, "10:00", false), salesActor);

        assertThat(created.get("ticketId").asLong()).isEqualTo(mine);
        assertThat(created.get("ticketTitle").asText()).isEqualTo("ดีล slice 2");
        assertThat(created.get("plannedTime").asText()).startsWith("10:00");
        assertThat(created.get("ownerName").asText()).isNotBlank();
    }

    // ── done / cancel lifecycle ──────────────────────────────────────────────

    @Test
    void doneOnLinkedDealResetsQuiet() throws Exception {
        long mine = deal(salesActor);
        backdate(mine, 30);
        long visit = plan(salesActor, mine, "คุณอรุณ", today);
        assertThat(quietRule.isQuiet(mine)).as("planning alone does not touch the deal").isTrue();

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

    @Test
    void doneWithoutADealWritesNoActivity() throws Exception {
        long visit = plan(salesActor, null, "Walk-in · คุณวราภรณ์", today);

        assertThat(ok(post("/api/sales/visits/" + visit + "/done"), salesActor).get("status").asText())
            .isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.deal_activity WHERE kind = 'SITE_VISIT'", Map.of(),
            Integer.class)).isZero();
    }

    @Test
    void doneTwiceIs409() throws Exception {
        long mine = deal(salesActor);
        long visit = plan(salesActor, mine, "คุณอรุณ", today);
        ok(post("/api/sales/visits/" + visit + "/done"), salesActor);

        assertThat(status(post("/api/sales/visits/" + visit + "/done"), salesActor)).isEqualTo(409);
        assertThat(siteVisitRows(mine)).as("the second tick must not log a second visit").isEqualTo(1);
    }

    @Test
    void cancelledVisitCannotBeDone() throws Exception {
        long mine = deal(salesActor);
        long visit = plan(salesActor, mine, "คุณอรุณ", today);
        assertThat(ok(post("/api/sales/visits/" + visit + "/cancel"), salesActor).get("status").asText())
            .isEqualTo("CANCELLED");

        assertThat(status(post("/api/sales/visits/" + visit + "/done"), salesActor)).isEqualTo(409);
        assertThat(status(post("/api/sales/visits/" + visit + "/cancel"), salesActor)).isEqualTo(409);
        assertThat(visitStatus(visit)).isEqualTo("CANCELLED");
        assertThat(siteVisitRows(mine)).isZero();
    }

    @Test
    void doneVisitCannotBeCancelled() throws Exception {
        long visit = plan(salesActor, null, "คุณอรุณ", today);
        ok(post("/api/sales/visits/" + visit + "/done"), salesActor);

        assertThat(status(post("/api/sales/visits/" + visit + "/cancel"), salesActor)).isEqualTo(409);
        assertThat(visitStatus(visit)).isEqualTo("DONE");
    }

    @Test
    void unknownVisitIs404() throws Exception {
        assertThat(status(post("/api/sales/visits/987654321/done"), salesActor)).isEqualTo(404);
    }

    @Test
    void managerMayCompleteARepsVisit() throws Exception {
        long repDeal = deal(salesActor);
        long visit = plan(salesActor, repDeal, "คุณอรุณ", today);

        assertThat(ok(post("/api/sales/visits/" + visit + "/done"), salesManagerActor).get("status").asText())
            .isEqualTo("DONE");
        assertThat(siteVisitRows(repDeal)).isEqualTo(1);
    }

    // ── log a visit that already happened (บันทึกเข้าพบ), in one step ─────────

    @Test
    void createDoneLogsTodaysVisitInOneStep() throws Exception {
        long mine = deal(salesActor);
        backdate(mine, 30);

        JsonNode created = ok(createRequest(mine, "Linea Interior", today, null, true), salesActor);

        assertThat(created.get("status").asText()).isEqualTo("DONE");
        assertThat(created.get("doneAt").isNull()).isFalse();
        assertThat(siteVisitRows(mine)).isEqualTo(1);
        assertThat(quietRule.isQuiet(mine)).isFalse();
    }

    @Test
    void createDoneForAnotherDayIs400() throws Exception {
        assertThat(status(createRequest(null, "ลูกค้า", today.plusDays(1), null, true), salesActor)).isEqualTo(400);
        assertThat(status(createRequest(null, "ลูกค้า", today.minusDays(1), null, true), salesActor)).isEqualTo(400);
        assertThat(visitCount()).isZero();
    }

    @Test
    void blankContactIs400() throws Exception {
        assertThat(status(createRequest(null, "   ", today, null, false), salesActor)).isEqualTo(400);
        assertThat(status(createRequest(null, "ลูกค้า", null, null, false), salesActor)).isEqualTo(400);
        assertThat(visitCount()).isZero();
    }

    // ── range limit ──────────────────────────────────────────────────────────

    @Test
    void rangeOver31DaysIs400() throws Exception {
        LocalDate from = today.minusDays(10);
        assertThat(status(range("/api/sales/visits", from, from.plusDays(30)), salesActor))
            .as("31 days inclusive is allowed").isEqualTo(200);
        assertThat(status(range("/api/sales/visits", from, from.plusDays(31)), salesActor)).isEqualTo(400);
        assertThat(status(range("/api/sales/visits/team", from, from.plusDays(31)), salesManagerActor))
            .isEqualTo(400);
        assertThat(status(range("/api/sales/visits", from, from.minusDays(1)), salesActor))
            .as("to before from").isEqualTo(400);
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

    private int visitCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sales.visit", Map.of(), Integer.class);
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
