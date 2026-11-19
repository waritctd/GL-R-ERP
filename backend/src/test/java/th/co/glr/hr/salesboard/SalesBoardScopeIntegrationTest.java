package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuietRule;
import th.co.glr.hr.ticket.TicketController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /api/sales/my-work and GET /api/sales/team-overview through the real controller, service, repository and
 * Postgres. Written wrong-way-round: a rep must NOT reach another rep's deals; sales/account/import must NOT reach the
 * team overview. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class SalesBoardScopeIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void wireHttp() {
        SessionContext sessions = new SessionContext();
        QuietRule quietRule = new QuietRule(jdbc, 14);
        // Fixed clock (review M5): Thursday 12 Nov 2026, 10:00 Bangkok. The week is hard-coded below
        // (Mon 9 – Sun 15 Nov), never derived from VisitService.weekStart, so a wrong week computation fails here.
        VisitService visits = new VisitService(new VisitRepository(jdbc), ticketService,
            java.time.Clock.fixed(java.time.Instant.parse("2026-11-12T03:00:00Z"), VisitService.BANGKOK));
        SalesBoardService board = new SalesBoardService(new SalesBoardRepository(jdbc, quietRule), quietRule,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), visits, new CashRepository(jdbc));
        // The base fixtures label the sales division "SALES"; the real role derivation reads "SA".
        jdbc.update("UPDATE hr.division SET source_code = 'SA' WHERE source_code = 'SALES'", Map.of());
        mvc = MockMvcBuilders
            .standaloneSetup(new SalesBoardController(board, sessions), new TicketController(ticketService, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // ── scope: a rep sees only their own deals ───────────────────────────────

    @Test
    void repCannotSeeAnotherRepsDeals() throws Exception {
        long mine = deal(salesActor);
        long theirs = deal(otherSalesActor);
        backdate(mine, 30);
        backdate(theirs, 30); // quiet too, so it would be in followUp as well as on the board

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);

        assertThat(ids(work.get("board"))).contains(mine).doesNotContain(theirs);
        assertThat(ids(work.get("followUp"))).contains(mine).doesNotContain(theirs);
    }

    // ── role gates ───────────────────────────────────────────────────────────

    @Test
    void salesRoleIsRefusedTeamOverview() throws Exception {
        deal(salesActor);

        assertThat(status(get("/api/sales/team-overview"), salesActor)).isEqualTo(403);
    }

    // ── team overview ────────────────────────────────────────────────────────

    @Test
    void ceoGetsTheTeamOverview() throws Exception {
        deal(salesActor);
        UserPrincipal ceo = actor(createEmployee("ซีอีโอ", "ceo-board@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");

        assertThat(ok(get("/api/sales/team-overview"), ceo).get("reps")).hasSize(1);
    }

    // ── days quiet reads ONE clock (review round 2, L-1) ─────────────────────

    @Test
    void valueIsLatestApprovedQuotationTotal_nullWhenNone() throws Exception {
        long quoted = deal(salesActor);
        DealQuotationDto approved = approved(quoted, salesActor, "DESIGNER");
        long bare = deal(salesActor);

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);

        JsonNode q = byId(work.get("board"), quoted);
        assertThat(new BigDecimal(q.get("value").asText())).isEqualByComparingTo(approved.grandTotal());
        assertThat(q.get("salesStage").asText()).isEqualTo(DealStage.QUOTE_DESIGN_SIDE);
        assertThat(q.get("repCode").asText()).isEqualTo("S3");
        assertThat(q.get("boardColumn").asInt()).isEqualTo(3);
        assertThat(q.get("quiet").asBoolean()).isFalse();
        assertThat(byId(work.get("board"), bare).get("value").isNull()).isTrue();
    }

    // ── review fixes: currency (M1), CLOSED_PAID (M2), value source (M3), role label ──────────

    @Test
    void usdValueKeepsItsCurrency_andStaysOutOfTheThbTotal() throws Exception {
        long thb = deal(salesActor);
        DealQuotationDto thbQuote = approved(thb, salesActor, "DESIGNER");
        long usd = deal(salesActor);
        DealQuotationDto usdQuote = approved(usd, salesActor, "DESIGNER");
        jdbc.update("UPDATE sales.quotation SET currency = 'USD' WHERE quotation_id = :id", Map.of("id", usdQuote.id()));

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);
        assertThat(byId(work.get("board"), usd).get("currency").asText()).isEqualTo("USD");
        assertThat(byId(work.get("board"), thb).get("currency").asText()).isEqualTo("THB");

        JsonNode rep = rep(ok(get("/api/sales/team-overview"), salesManagerActor), salesActor.id());
        assertThat(new BigDecimal(rep.get("openValue").asText())).isEqualByComparingTo(thbQuote.grandTotal());
        assertThat(new BigDecimal(rep.get("otherCurrencyValues").get("USD").asText()))
            .isEqualByComparingTo(usdQuote.grandTotal());
    }

    @Test
    void closedPaidDealsAreNotOpen_butDeliveredOnesAre() throws Exception {
        long paid = deal(salesActor);
        long delivered = deal(salesActor);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'CLOSED_PAID' WHERE ticket_id = :id", Map.of("id", paid));
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERED' WHERE ticket_id = :id", Map.of("id", delivered));

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);
        assertThat(ids(work.get("board"))).containsExactly(delivered);
        assertThat(byId(work.get("board"), delivered).get("boardColumn").asInt()).isEqualTo(5);
        assertThat(rep(ok(get("/api/sales/team-overview"), salesManagerActor), salesActor.id())
            .get("openDeals").asInt()).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private static JsonNode rep(JsonNode team, long employeeId) {
        for (JsonNode r : team.get("reps")) {
            if (r.get("employeeId").asLong() == employeeId) {
                return r;
            }
        }
        throw new AssertionError("no rep row for " + employeeId);
    }
}
