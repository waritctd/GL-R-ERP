package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
import th.co.glr.hr.ticket.QuietRule;
import th.co.glr.hr.ticket.TicketController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owner ruling 2026-11-16: a paused deal whose วันที่จะกลับมาดู has arrived (Asia/Bangkok date) comes back into the
 * rep's ควรตามต่อ and counts in the manager's quiet figures, until it is resumed or re-paused with a later date. This
 * changes WHICH deals those scoped reads include; it must not change WHOSE. Through the real controllers, services,
 * repositories and Postgres, written wrong-way-round. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class PausedDealFollowUpScopeIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void wireHttp() {
        SessionContext sessions = new SessionContext();
        QuietRule quietRule = new QuietRule(jdbc, 14);
        VisitService visits = new VisitService(new VisitRepository(jdbc), ticketService);
        SalesBoardService board = new SalesBoardService(new SalesBoardRepository(jdbc, quietRule), quietRule,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), visits, new CashRepository(jdbc));
        jdbc.update("UPDATE hr.division SET source_code = 'SA' WHERE source_code = 'SALES'", Map.of());
        mvc = MockMvcBuilders
            .standaloneSetup(new SalesBoardController(board, sessions), new TicketController(ticketService, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // ── the rep's ควรตามต่อ ──────────────────────────────────────────────────────────────────────

    @Test
    void myPausedDealWhoseDateIsToday_comesBackIntoMyFollowUp_flaggedAsReturning_butNotOnTheBoard() throws Exception {
        long id = deal(salesActor);
        pause(id, "hold", today(), salesActor);

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);

        JsonNode row = byId(work.get("followUp"), id);
        assertThat(row.get("returning").asBoolean()).isTrue();
        assertThat(row.get("quiet").asBoolean()).isFalse();
        assertThat(row.get("pauseReturnOn").asText()).isEqualTo(today().toString());
        assertThat(ids(work.get("board"))).doesNotContain(id);
    }

    /** THE scope test: rep B's returning paused deal never appears in rep A's list (control: it does in B's). */
    @Test
    void anotherRepsReturningPausedDeal_neverAppearsInMyFollowUp() throws Exception {
        long theirs = deal(otherSalesActor);
        pause(theirs, "hold", today(), otherSalesActor);

        assertThat(ids(ok(get("/api/sales/my-work"), salesActor).get("followUp"))).doesNotContain(theirs);
        assertThat(ids(ok(get("/api/sales/my-work"), otherSalesActor).get("followUp"))).contains(theirs);
    }

    @Test
    void aPausedDealWhoseDateHasNotArrived_staysOut() throws Exception {
        long id = deal(salesActor);
        pause(id, "hold", today().plusDays(1), salesActor);
        backdate(id, 30);

        assertThat(ids(ok(get("/api/sales/my-work"), salesActor).get("followUp"))).doesNotContain(id);
    }

    @Test
    void aPausedDealWithNoDate_staysOutAsToday() throws Exception {
        long id = deal(salesActor);
        pause(id, "hold", null, salesActor);
        backdate(id, 30);

        assertThat(ids(ok(get("/api/sales/my-work"), salesActor).get("followUp"))).doesNotContain(id);
    }

    @Test
    void aDateThatPassedDaysAgo_isStillIn_untilTheRepActs() throws Exception {
        long id = deal(salesActor);
        pause(id, "dormant", today(), salesActor);
        jdbc.update("UPDATE sales.ticket SET pause_return_on = :d WHERE ticket_id = :id",
            Map.of("d", today().minusDays(5), "id", id));

        assertThat(ids(ok(get("/api/sales/my-work"), salesActor).get("followUp"))).contains(id);
    }

    @Test
    void resumingTheDeal_dropsItOut() throws Exception {
        long id = deal(salesActor);
        pause(id, "hold", today(), salesActor);

        ok(post("/api/tickets/" + id + "/resume"), salesActor);

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);
        assertThat(ids(work.get("followUp"))).doesNotContain(id);
        assertThat(ids(work.get("board"))).contains(id);
    }

    @Test
    void rePausingWithALaterDate_dropsItOut() throws Exception {
        long id = deal(salesActor);
        pause(id, "hold", today(), salesActor);

        pause(id, "dormant", today().plusMonths(1), salesActor);

        assertThat(ids(ok(get("/api/sales/my-work"), salesActor).get("followUp"))).doesNotContain(id);
    }

    @Test
    void lostAndCancelledDealsNeverReturn_evenWithAnArrivedDate() throws Exception {
        long id = deal(salesActor);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED', pause_return_on = :d WHERE ticket_id = :id",
            Map.of("d", today().minusDays(1), "id", id));

        assertThat(ids(ok(get("/api/sales/my-work"), salesActor).get("followUp"))).doesNotContain(id);
    }

    // ── the manager's quiet figures ──────────────────────────────────────────────────────────────

    @Test
    void teamOverview_countsAReturningPausedDealAsQuiet_forItsOwnRepOnly_andNotAsOpen() throws Exception {
        long a = deal(salesActor);
        long b = deal(otherSalesActor);
        pause(b, "hold", today(), otherSalesActor);

        JsonNode team = ok(get("/api/sales/team-overview"), salesManagerActor);

        JsonNode repA = rep(team, salesActor.id());
        JsonNode repB = rep(team, otherSalesActor.id());
        assertThat(repA.get("openDeals").asInt()).isEqualTo(1);
        assertThat(repA.get("quietDeals").asInt()).isZero();
        assertThat(repB.get("openDeals").asInt()).isZero();
        assertThat(repB.get("quietDeals").asInt()).isEqualTo(1);
        assertThat(ids(team.get("stale"))).containsExactly(b).doesNotContain(a);
    }

    @Test
    void theTeamBoardStillShowsOnlyActiveDeals() throws Exception {
        long id = deal(salesActor);
        pause(id, "hold", today(), salesActor);

        assertThat(ids(ok(get("/api/sales/team-board"), salesManagerActor).get("board"))).doesNotContain(id);
    }

    @Test
    void aSalesRepIsStillRefusedTheTeamOverview() throws Exception {
        long id = deal(otherSalesActor);
        pause(id, "hold", today(), otherSalesActor);

        assertThat(perform(get("/api/sales/team-overview"), salesActor).getResponse().getStatus()).isEqualTo(403);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static LocalDate today() {
        return LocalDate.now(BANGKOK);
    }

    private void pause(long id, String path, LocalDate on, UserPrincipal actor) throws Exception {
        ok(post("/api/tickets/" + id + "/" + path).contentType(MediaType.APPLICATION_JSON)
            .content(on == null ? "{}" : "{\"pauseReturnOn\":\"" + on + "\"}"), actor);
    }

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

    private MvcResult perform(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return mvc.perform(request.session(session)).andReturn();
    }

    private static List<Long> ids(JsonNode deals) {
        List<Long> out = new ArrayList<>();
        deals.forEach(d -> out.add(d.get("ticketId").asLong()));
        return out;
    }

    private static JsonNode byId(JsonNode deals, long id) {
        for (JsonNode d : deals) {
            if (d.get("ticketId").asLong() == id) {
                return d;
            }
        }
        throw new AssertionError("deal " + id + " not in the list");
    }

    private static JsonNode rep(JsonNode team, long employeeId) {
        for (JsonNode r : team.get("reps")) {
            if (r.get("employeeId").asLong() == employeeId) {
                return r;
            }
        }
        throw new AssertionError("no rep row for " + employeeId);
    }
}
