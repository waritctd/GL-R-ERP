package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /api/tickets movement range: {@code movementFrom}/{@code movementTo} (inclusive Asia/Bangkok days) add
 * {@code stageChangedInRange} (a STAGE_CHANGED ticket_event in range) and {@code activityInRange} (a deal_activity
 * with activity_date in range) to every row, and every row carries {@code lastActivityAt}. Driven over MockMvc
 * so the JSON field names are the contract; before the feature exists each case fails on an assertion.
 *
 * <p>Scoping note: only {@code sales} is scoped in the list query (own deals); sales_manager / ceo see every deal.
 */
class TicketListMovementRangeIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void wireHttp() {
        mvc = MockMvcBuilders.standaloneSetup(new TicketController(ticketService, new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void activityInsideRange_setsActivityInRange_activityOnlyOutside_doesNot() throws Exception {
        long inside = deal(salesActor);
        long outside = deal(salesActor);
        activity(inside, "2026-03-10");
        activity(outside, "2026-03-20");

        JsonNode rows = list(salesActor, "movementFrom=2026-03-08&movementTo=2026-03-12");
        assertThat(flag(rows, inside, "activityInRange")).isTrue();
        assertThat(flag(rows, outside, "activityInRange")).isFalse();
        assertThat(flag(rows, inside, "stageChangedInRange")).isFalse();
    }

    /** The past-range case: a later stage change outside the range must not hide the one inside it. */
    @Test
    void stageChangeInsideRange_followedByALaterOneOutside_isStillInRange() throws Exception {
        long ticket = deal(salesActor);
        long onlyOutside = deal(salesActor);
        stageChange(ticket, "2026-03-10 12:00:00+07");
        stageChange(ticket, "2026-03-25 12:00:00+07");
        stageChange(onlyOutside, "2026-03-25 12:00:00+07");

        JsonNode rows = list(salesActor, "movementFrom=2026-03-08&movementTo=2026-03-12");
        assertThat(flag(rows, ticket, "stageChangedInRange")).isTrue();
        assertThat(flag(rows, onlyOutside, "stageChangedInRange")).isFalse();
    }

    @Test
    void withoutParams_bothFlagsAreFalse_andLastActivityAtIsStillServed() throws Exception {
        long withActivity = deal(salesActor);
        long none = deal(salesActor);
        // TicketService.create logs an OTHER "สร้างดีล" activity dated today; clear it so the dates below are exact
        clearActivity(withActivity);
        clearActivity(none);
        stageChange(withActivity, "2026-03-10 12:00:00+07");
        activity(withActivity, "2026-03-05");
        activity(withActivity, "2026-03-10");

        JsonNode rows = list(salesActor, null);
        assertThat(flag(rows, withActivity, "stageChangedInRange")).isFalse();
        assertThat(flag(rows, withActivity, "activityInRange")).isFalse();
        assertThat(row(rows, withActivity).path("lastActivityAt").asText()).isEqualTo("2026-03-10");
        assertThat(row(rows, none).path("lastActivityAt").isNull()).isTrue();
    }

    @Test
    void movementFromAfterMovementTo_isRejectedWith400() throws Exception {
        MvcResult result = send(salesActor, "movementFrom=2026-03-12&movementTo=2026-03-08");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void onlyOneOfMovementFromAndMovementTo_isRejectedWith400() throws Exception {
        assertThat(send(salesActor, "movementFrom=2026-03-08").getResponse().getStatus()).isEqualTo(400);
        assertThat(send(salesActor, "movementTo=2026-03-08").getResponse().getStatus()).isEqualTo(400);
    }

    /** Bangkok days, inclusive: 00:00 and 23:59 local on the edge days are in; the instant outside either edge is out. */
    @Test
    void bangkokDayBoundaries_areInclusiveOnBothEdges() throws Exception {
        long startEdge = deal(salesActor);
        long endEdge = deal(salesActor);
        long justBefore = deal(salesActor);
        long justAfter = deal(salesActor);
        stageChange(startEdge, "2026-03-10 00:00:00+07");
        stageChange(endEdge, "2026-03-12 23:59:00+07");
        stageChange(justBefore, "2026-03-09 23:59:59+07");
        stageChange(justAfter, "2026-03-13 00:00:00+07");

        JsonNode rows = list(salesActor, "movementFrom=2026-03-10&movementTo=2026-03-12");
        assertThat(flag(rows, startEdge, "stageChangedInRange")).isTrue();
        assertThat(flag(rows, endEdge, "stageChangedInRange")).isTrue();
        assertThat(flag(rows, justBefore, "stageChangedInRange")).isFalse();
        assertThat(flag(rows, justAfter, "stageChangedInRange")).isFalse();
    }

    @Test
    void activityDateOnEitherEdgeDay_isInside() throws Exception {
        long from = deal(salesActor);
        long to = deal(salesActor);
        activity(from, "2026-03-10");
        activity(to, "2026-03-12");

        JsonNode rows = list(salesActor, "movementFrom=2026-03-10&movementTo=2026-03-12");
        assertThat(flag(rows, from, "activityInRange")).isTrue();
        assertThat(flag(rows, to, "activityInRange")).isTrue();
    }

    /** CANNOT REACH: the range params must not widen a sales rep's own-deals scope. */
    @Test
    void salesListWithRangeStillExcludesAnotherRepsDeal() throws Exception {
        long mine = deal(salesActor);
        long theirs = deal(otherSalesActor);
        activity(mine, "2026-03-10");
        activity(theirs, "2026-03-10");

        JsonNode rows = list(salesActor, "movementFrom=2026-03-08&movementTo=2026-03-12");
        assertThat(row(rows, mine)).isNotNull();
        assertThat(flag(rows, mine, "activityInRange")).isTrue();
        assertThat(row(rows, theirs)).isNull();
    }

    /** Characterises the real scoping: sales_manager is NOT narrowed to a team here; they see every rep's deals. */
    @Test
    void salesManagerListWithRange_seesEveryRepsDeals_withTheirFlags() throws Exception {
        long a = deal(salesActor);
        long b = deal(otherSalesActor);
        activity(a, "2026-03-10");

        JsonNode rows = list(salesManagerActor, "movementFrom=2026-03-08&movementTo=2026-03-12");
        assertThat(flag(rows, a, "activityInRange")).isTrue();
        assertThat(flag(rows, b, "activityInRange")).isFalse();
    }

    /**
     * CANNOT REACH: account and import cannot read a deal's activities (GET /api/tickets/{id}/activities is
     * owner/keyer, sales_manager, ceo only), so neither the list nor the detail may leak activity through
     * lastActivityAt or the range flags, even when the range is sent.
     */
    @Test
    void accountAndImportRows_carryNoActivityFacts_evenWithTheRange() throws Exception {
        long ticket = deal(salesActor);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED' WHERE ticket_id = :t", Map.of("t", ticket));
        activity(ticket, "2026-03-10");
        stageChange(ticket, "2026-03-10 12:00:00+07");
        UserPrincipal account = actor(createEmployee("บัญชี S2", "acc-s2@glr.co.th", "ACCOUNT", "ฝ่ายบัญชี", null), "account");
        UserPrincipal importer = actor(createEmployee("นำเข้า S2", "imp-s2@glr.co.th", "IMPORT", "ฝ่ายนำเข้า", null), "import");

        // the sales rep does get them (guards against redacting everyone)
        JsonNode own = row(list(salesActor, "movementFrom=2026-03-08&movementTo=2026-03-12"), ticket);
        assertThat(own.path("lastActivityAt").isNull()).isFalse();
        assertThat(own.path("activityInRange").asBoolean()).isTrue();

        for (UserPrincipal who : new UserPrincipal[] {account, importer}) {
            JsonNode r = row(list(who, "movementFrom=2026-03-08&movementTo=2026-03-12"), ticket);
            assertThat(r).as("%s sees the deal", who.role()).isNotNull();
            assertThat(r.path("lastActivityAt").isNull()).as("%s lastActivityAt", who.role()).isTrue();
            assertThat(r.path("activityInRange").asBoolean()).as("%s activityInRange", who.role()).isFalse();
            assertThat(r.path("stageChangedInRange").asBoolean()).as("%s stageChangedInRange", who.role()).isFalse();
        }
        assertThat(ticketService.get(ticket, salesActor).summary().lastActivityAt()).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void stageChange(long ticketId, String createdAt) {
        jdbc.update("""
            INSERT INTO sales.ticket_event (ticket_id, actor_id, kind, created_at)
            VALUES (:t, :a, 'STAGE_CHANGED', :at::timestamptz)""",
            Map.of("t", ticketId, "a", salesActor.id(), "at", createdAt));
    }

    private void clearActivity(long ticketId) {
        jdbc.update("DELETE FROM sales.deal_activity WHERE ticket_id = :t", Map.of("t", ticketId));
    }

    private void activity(long ticketId, String date) {
        jdbc.update("""
            INSERT INTO sales.deal_activity (ticket_id, activity_date, kind, created_by_id)
            VALUES (:t, :d::date, 'CALL', :a)""",
            Map.of("t", ticketId, "d", date, "a", salesActor.id()));
    }

    private MvcResult send(UserPrincipal actor, String query) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        String url = "/api/tickets?size=200" + (query == null ? "" : "&" + query);
        return mvc.perform(get(url).session(session)).andReturn();
    }

    private JsonNode list(UserPrincipal actor, String query) throws Exception {
        MvcResult result = send(actor, query);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        return json.readTree(body).get("tickets");
    }

    private static JsonNode row(JsonNode rows, long id) {
        for (JsonNode r : rows) {
            if (r.get("id").asLong() == id) return r;
        }
        return null;
    }

    /** Missing field reads as null-node -> asBoolean() false would hide a missing field, so require presence. */
    private static boolean flag(JsonNode rows, long id, String field) {
        JsonNode r = row(rows, id);
        assertThat(r).as("row %s present", id).isNotNull();
        assertThat(r.has(field)).as("row %s carries %s", id, field).isTrue();
        return r.get(field).asBoolean();
    }
}
