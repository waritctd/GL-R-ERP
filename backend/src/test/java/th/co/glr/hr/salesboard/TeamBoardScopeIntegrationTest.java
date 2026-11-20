package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
 * GET /api/sales/team-board (the งานทั้งหมด board for sales_manager / ceo) through the real controller, service,
 * repository and Postgres. Written wrong-way-round first: every other role is refused and receives no row, and a
 * sales rep cannot use it to reach another rep's deals. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class TeamBoardScopeIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final String TEAM_BOARD = "/api/sales/team-board";

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void wireHttp() {
        SessionContext sessions = new SessionContext();
        QuietRule quietRule = new QuietRule(jdbc, 14);
        VisitService visits = new VisitService(new VisitRepository(jdbc), ticketService,
            java.time.Clock.systemUTC());
        SalesBoardService board = new SalesBoardService(new SalesBoardRepository(jdbc, quietRule), quietRule,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), visits, new CashRepository(jdbc));
        mvc = MockMvcBuilders
            .standaloneSetup(new SalesBoardController(board, sessions), new TicketController(ticketService, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // ── refused: every role but sales_manager / ceo ──────────────────────────────

    @Test
    void everyNonManagerRoleIsRefusedAndGetsNoRows() throws Exception {
        deal(salesActor);
        deal(otherSalesActor);
        for (String role : List.of("import", "account", "hr", "warehouse", "qc", "employee", "division_manager")) {
            UserPrincipal other = actor(createEmployee("ผู้ใช้ " + role, role + "-team-board@glr.co.th", "ACC",
                "บัญชี", null), role);

            MvcResult result = perform(other);

            assertThat(result.getResponse().getStatus()).as("%s status", role).isEqualTo(403);
            assertThat(body(result)).as("%s body", role).doesNotContain("\"board\"").doesNotContain("ticketId");
        }
    }

    // ── allowed: the whole team's open deals, each with its owner ────────────────

    @Test
    void managerSeesEveryRepsOpenDeals_eachWithItsOwner() throws Exception {
        long a = deal(salesActor);
        long b = deal(otherSalesActor);
        long own = deal(salesManagerActor);

        JsonNode board = ok(salesManagerActor);

        assertThat(ids(board.get("board"))).containsExactlyInAnyOrder(a, b, own);
        assertThat(byId(board.get("board"), a).get("ownerId").asLong()).isEqualTo(salesActor.id());
        assertThat(byId(board.get("board"), b).get("ownerId").asLong()).isEqualTo(otherSalesActor.id());
        assertThat(byId(board.get("board"), b).get("ownerName").isNull()).isFalse();
        assertThat(board.get("quietDays").asInt()).isEqualTo(14);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode ok(UserPrincipal actor) throws Exception {
        return okAt(TEAM_BOARD, actor);
    }

    private JsonNode ok(String url, UserPrincipal actor) throws Exception {
        return okAt(url, actor);
    }

    private JsonNode okAt(String url, UserPrincipal actor) throws Exception {
        MvcResult result = perform(url, actor);
        String body = body(result);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        return json.readTree(body);
    }

    private MvcResult perform(UserPrincipal actor) throws Exception {
        return perform(TEAM_BOARD, actor);
    }

    private MvcResult perform(String url, UserPrincipal actor) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return mvc.perform(get(url).session(session)).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
        throw new AssertionError("deal " + id + " not on the board");
    }
}
