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
 * {@code stageChangedInRange} and {@code activityInRange} to every row, and every row carries
 * {@code lastActivityAt}. Driven over MockMvc so the JSON field names are the contract.
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

    @Test
    void movementFromAfterMovementTo_isRejectedWith400() throws Exception {
        MvcResult result = send(salesActor, "movementFrom=2026-03-12&movementTo=2026-03-08");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
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

    private void stageChange(long ticketId, String createdAt) {
        jdbc.update("""
            INSERT INTO sales.ticket_event (ticket_id, actor_id, kind, created_at)
            VALUES (:t, :a, 'STAGE_CHANGED', :at::timestamptz)""",
            Map.of("t", ticketId, "a", salesActor.id(), "at", createdAt));
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
