package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
 * Slice 6.5: GET /api/sales/visits/deal/{ticketId}/planned — a deal's PLANNED visits for the deal page's history —
 * through the real controller, VisitService, TicketService, repositories and Postgres.
 *
 * <p>This is an authorization change, so the read is the INTERSECTION of two rules that already exist and exposes
 * nothing new: the caller must be able to open the deal (TicketService#get: the owner/keyer with the sales role, or
 * sales_manager / ceo; account and import refused), AND each visit must be one the caller could already read through
 * /api/sales/visits (own visits for sales) or /api/sales/visits/team (every owner's for sales_manager / ceo). A visit
 * outside that is ABSENT — not redacted — and the list's length is all a caller learns.
 *
 * <p>Written wrong-way-round for every role. Annotated itself: @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class DealPlannedVisitsScopeIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private final LocalDate today = LocalDate.of(2026, 11, 12);

    @BeforeEach
    void wireHttp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), VisitService.BANGKOK);
        VisitService service = new VisitService(new VisitRepository(jdbc), ticketService, clock);
        mvc = MockMvcBuilders
            .standaloneSetup(new VisitController(service, new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // ── who sees which planned visits ────────────────────────────────────────

    @Test
    void theOwnerSeesOnlyTheirOwnPlannedVisitsOnTheDeal_notTheManagersOnTheSameDeal() throws Exception {
        Fixture f = fixture();

        JsonNode seen = ok(planned(f.deal), salesActor);

        assertThat(ids(seen)).containsExactly(f.overdue, f.tomorrowMorning, f.tomorrowUntimed, f.inTwoDays)
            .doesNotContain(f.managersOwn);
    }

    @Test
    void managerAndCeoSeeEveryOwnersPlannedVisitOnTheDeal_inTheVisitListsOrder() throws Exception {
        Fixture f = fixture();
        UserPrincipal ceo = actor(createEmployee("ซีอีโอ 6.5", "ceo-65@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");

        for (UserPrincipal viewer : List.of(salesManagerActor, ceo)) {
            assertThat(ids(ok(planned(f.deal), viewer))).as(viewer.role())
                .containsExactly(f.overdue, f.tomorrowMorning, f.tomorrowUntimed, f.inTwoDays, f.managersOwn);
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /** Deal {@code deal} owned by salesActor, carrying every kind of visit row the read must sort or drop. */
    private record Fixture(long deal, long overdue, long tomorrowMorning, long tomorrowUntimed, long inTwoDays,
                           long managersOwn, long done, long cancelled, long onOtherDeal, long noDeal) {}

    private Fixture fixture() throws Exception {
        long deal = deal(salesActor);
        long otherDeal = deal(salesActor);
        long inTwoDays = plan(salesActor, deal, "บ่ายมะรืน", today.plusDays(2), "14:00");
        long tomorrowUntimed = plan(salesActor, deal, "พรุ่งนี้ ไม่ระบุเวลา", today.plusDays(1), null);
        long tomorrowMorning = plan(salesActor, deal, "พรุ่งนี้เช้า", today.plusDays(1), "09:00");
        // Planned for two days ago and never ticked: still PLANNED, so it is listed (the page marks it overdue).
        long overdue = plan(salesActor, deal, "นัดที่เลยมาแล้ว", today.minusDays(2), null);
        long managersOwn = plan(salesManagerActor, deal, "นัดของผู้จัดการ", today.plusDays(3), null);
        long done = plan(salesActor, deal, "ไปแล้ว", today, null);
        ok(post("/api/sales/visits/" + done + "/done"), salesActor);
        long cancelled = plan(salesActor, deal, "ยกเลิกแล้ว", today.plusDays(1), null);
        ok(post("/api/sales/visits/" + cancelled + "/cancel"), salesActor);
        long onOtherDeal = plan(salesActor, otherDeal, "งานอื่น", today.plusDays(1), null);
        long noDeal = plan(salesActor, null, "ไม่มีงาน", today.plusDays(1), null);
        return new Fixture(deal, overdue, tomorrowMorning, tomorrowUntimed, inTwoDays, managersOwn, done, cancelled,
            onOtherDeal, noDeal);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MockHttpServletRequestBuilder planned(long ticketId) {
        return get("/api/sales/visits/deal/" + ticketId + "/planned");
    }

    private long plan(UserPrincipal owner, Long ticketId, String contact, LocalDate date, String time)
        throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId);
        body.put("contactName", contact);
        body.put("plannedDate", date.toString());
        body.put("plannedTime", time);
        body.put("note", "นำตัวอย่างไปเทียบสี");
        body.put("done", false);
        return ok(post("/api/sales/visits").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)), owner).get("id").asLong();
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

    private static List<Long> ids(JsonNode visits) {
        List<Long> out = new ArrayList<>();
        visits.forEach(v -> out.add(v.get("id").asLong()));
        return out;
    }
}
