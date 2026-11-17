package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionAccountResolver;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 6.4 on the visit dialogs: ตามต่อ — the optional {@code nextFollowUpAt} on POST /api/sales/visits (a one-step
 * DONE log) and POST /api/sales/visits/{id}/done, through the real controller, VisitService, TicketService,
 * repositories and Postgres.
 *
 * <p>The rule: the deal's follow-up date is written only when the visit is on a deal AND the visit's SITE_VISIT activity
 * lands on that deal by the EXISTING rule (TicketService#recordVisitActivity: the visit's owner is the deal's current
 * owner, {@code created_by}) AND the caller passes the same requireDealOwnership the tracking path uses, AND the deal
 * is ACTIVE. Never looser than PUT /tracking or POST /activities. When a date is sent and the rule refuses it, the
 * whole request is refused and NOTHING is written: no visit row, no tick, no activity, no date.
 *
 * <p>Written wrong-way-round. Annotated itself: @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class VisitFollowUpIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    /** Thursday 12 Nov 2026 in Bangkok (VisitScopeIntegrationTest's fixed day). */
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

    // ── the write itself ─────────────────────────────────────────────────────

    @Test
    void theOwnersOneStepLogOnTheirDeal_writesOnlyTheFollowUpDate() throws Exception {
        long deal = deal(salesActor);
        fillTrackingFields(deal);
        Map<String, Object> before = trackingSnapshot(deal);

        JsonNode logged = ok(log(deal, "Linea Interior", today.plusDays(7)), salesActor);

        assertThat(logged.get("status").asText()).isEqualTo("DONE");
        assertThat(siteVisitRows(deal)).isEqualTo(1);
        assertThat(nextFollowUp(deal)).isEqualTo(today.plusDays(7));
        assertThat(trackingSnapshot(deal)).as("only next_follow_up_at moved").isEqualTo(before);
    }

    @Test
    void theOwnersTickOnAPlannedVisit_writesTheFollowUpDate() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, today);

        ok(done(visit, today.plusDays(3)), salesActor);

        assertThat(visitStatus(visit)).isEqualTo("DONE");
        assertThat(siteVisitRows(deal)).isEqualTo(1);
        assertThat(nextFollowUp(deal)).isEqualTo(today.plusDays(3));
    }

    @Test
    void aManagerTickingTheRepsVisitOnTheRepsDeal_mayAlsoSetIt() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, today);

        ok(done(visit, today.plusDays(14)), salesManagerActor);

        assertThat(siteVisitRows(deal)).isEqualTo(1);
        assertThat(nextFollowUp(deal)).isEqualTo(today.plusDays(14));
    }

    @Test
    void withoutAFollowUp_bothRequestsLeaveTheDealsDateAsItWas() throws Exception {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);
        long visit = plan(salesActor, deal, today);

        ok(post("/api/sales/visits/" + visit + "/done").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"ส่งตัวอย่าง\"}"), salesActor);
        ok(log(deal, "Walk-in", null), salesActor);

        assertThat(siteVisitRows(deal)).isEqualTo(2);
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    // ── wrong-way-round: refused, nothing written ────────────────────────────

    @Test
    void aVisitWithNoDeal_refusesTheFollowUp_400_andIsNeitherCreatedNorTicked() throws Exception {
        assertThat(status(log(null, "Walk-in", today.plusDays(7)), salesActor)).isEqualTo(400);
        assertThat(visitCount()).isZero();

        long visit = plan(salesActor, null, today);
        assertThat(status(done(visit, today.plusDays(7)), salesActor)).isEqualTo(400);
        assertThat(visitStatus(visit)).isEqualTo("PLANNED");
    }

    @Test
    void aPlanThatIsNotYetDone_refusesTheFollowUp_400() throws Exception {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);

        assertThat(status(createRequest(deal, "คุณอรุณ", today.plusDays(2), false, today.plusDays(9)), salesActor))
            .isEqualTo(400);

        assertThat(visitCount()).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    @Test
    void anotherRepAndAQuotationGrantHolder_onSomeoneElsesDeal_are403_andNothingIsWritten() throws Exception {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);

        for (UserPrincipal outsider : List.of(otherSalesActor, grantHolder())) {
            assertThat(status(log(deal, "คุณอรุณ", today.plusDays(7)), outsider)).as(outsider.name()).isEqualTo(403);
        }

        assertThat(visitCount()).isZero();
        assertThat(siteVisitRows(deal)).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    @Test
    void accountImportAndHr_are403_onBothRequests_andNothingIsWritten() throws Exception {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);
        long visit = plan(salesActor, deal, today);

        for (String role : List.of("account", "import", "hr")) {
            UserPrincipal other = actor(createEmployee("ผู้ใช้ 6.4 " + role, role + "-64v@glr.co.th", "ACC", "บัญชี",
                null), role);
            assertThat(status(log(deal, "คุณอรุณ", today.plusDays(7)), other)).as("%s log", role).isEqualTo(403);
            assertThat(status(done(visit, today.plusDays(7)), other)).as("%s done", role).isEqualTo(403);
        }

        assertThat(visitCount()).isEqualTo(1);
        assertThat(visitStatus(visit)).isEqualTo("PLANNED");
        assertThat(siteVisitRows(deal)).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    @Test
    void aKeyerWhoIsNotTheOwner_whoseVisitStaysOffTheDeal_is403_onBothRequests() throws Exception {
        long deal = deal(salesActor);
        jdbc.update("UPDATE sales.ticket SET created_by = :owner, keyed_by = :keyer WHERE ticket_id = :id",
            Map.of("owner", otherSalesActor.id(), "keyer", salesActor.id(), "id", deal));
        LocalDate original = nextFollowUp(deal);
        long visit = plan(salesActor, deal, today);

        assertThat(status(done(visit, today.plusDays(7)), salesActor)).isEqualTo(403);
        assertThat(status(log(deal, "Walk-in", today.plusDays(7)), salesActor)).isEqualTo(403);

        assertThat(visitStatus(visit)).isEqualTo("PLANNED");
        assertThat(visitCount()).isEqualTo(1);
        assertThat(siteVisitRows(deal)).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    @Test
    void anExOwnerAfterTheDealMoved_is403_andTheVisitStaysPlanned() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, today);
        reassign(deal, otherSalesActor);
        LocalDate original = nextFollowUp(deal);

        for (UserPrincipal ticker : List.of(salesActor, salesManagerActor)) {
            assertThat(status(done(visit, today.plusDays(7)), ticker)).as(ticker.role()).isEqualTo(403);
        }

        assertThat(visitStatus(visit)).isEqualTo("PLANNED");
        assertThat(siteVisitRows(deal)).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    @Test
    void aManagersOwnVisitOnARepsDeal_whoseActivityStaysOffTheDeal_is403() throws Exception {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);
        long planned = plan(salesManagerActor, deal, today);

        assertThat(status(log(deal, "คุณอรุณ", today.plusDays(7)), salesManagerActor)).isEqualTo(403);
        assertThat(status(done(planned, today.plusDays(7)), salesManagerActor)).isEqualTo(403);

        assertThat(visitCount()).isEqualTo(1);
        assertThat(visitStatus(planned)).isEqualTo("PLANNED");
        assertThat(siteVisitRows(deal)).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    @Test
    void aTickOnADealThatIsNoLongerActive_refusesTheFollowUp_409() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, today);
        LocalDate original = nextFollowUp(deal);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'ON_HOLD' WHERE ticket_id = :id", Map.of("id", deal));

        assertThat(status(done(visit, today.plusDays(7)), salesActor)).isEqualTo(409);

        assertThat(visitStatus(visit)).isEqualTo("PLANNED");
        assertThat(siteVisitRows(deal)).isZero();
        assertThat(nextFollowUp(deal)).isEqualTo(original);
    }

    @Test
    void aPastDateIs400_onBothRequests_andNothingIsWritten() throws Exception {
        long deal = deal(salesActor);
        LocalDate original = nextFollowUp(deal);
        long visit = plan(salesActor, deal, today);

        assertThat(status(done(visit, today.minusDays(1)), salesActor)).isEqualTo(400);
        assertThat(status(log(deal, "Walk-in", today.minusDays(1)), salesActor)).isEqualTo(400);
        assertThat(status(done(visit, today), salesActor)).as("today is allowed").isEqualTo(200);

        assertThat(visitCount()).isEqualTo(1);
        assertThat(nextFollowUp(deal)).isEqualTo(today);
        assertThat(original).isNotEqualTo(today);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UserPrincipal grantHolder() {
        long id = createEmployee("QC ผู้ได้สิทธิ์ 6.4v", "qc-grant-64v@glr.co.th", "QC", "QC-QC&ISO", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", id));
        UserPrincipal resolved = new SessionAccountResolver(new EmployeeAuthRepository(jdbc)).current(id).orElseThrow();
        assertThat(resolved.role()).isEqualTo("sales");
        return resolved;
    }

    private long plan(UserPrincipal owner, Long ticketId, LocalDate date) throws Exception {
        return ok(createRequest(ticketId, "คุณอรุณ", date, false, null), owner).get("id").asLong();
    }

    private MockHttpServletRequestBuilder log(Long ticketId, String contact, LocalDate nextFollowUpAt) throws Exception {
        return createRequest(ticketId, contact, today, true, nextFollowUpAt);
    }

    private MockHttpServletRequestBuilder createRequest(Long ticketId, String contact, LocalDate date, boolean done,
                                                        LocalDate nextFollowUpAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId);
        body.put("contactName", contact);
        body.put("plannedDate", date.toString());
        body.put("note", "นำตัวอย่างไปเทียบสี");
        body.put("done", done);
        if (nextFollowUpAt != null) {
            body.put("nextFollowUpAt", nextFollowUpAt.toString());
        }
        return post("/api/sales/visits").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder done(long visitId, LocalDate nextFollowUpAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", "ส่งตัวอย่าง");
        body.put("nextFollowUpAt", nextFollowUpAt.toString());
        return post("/api/sales/visits/" + visitId + "/done").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body));
    }

    /** As VisitScopeIntegrationTest#reassign: TicketRepository#moveOwnerToSalesRep, then the recorded move. */
    private void reassign(long ticketId, UserPrincipal newOwner) {
        assertThat(tickets.moveOwnerToSalesRep(ticketId, newOwner.id())).isEqualTo(1);
        tickets.addEvent(ticketId, newOwner.id(), newOwner.name(), TicketEventKind.EDITED, null, null,
            TicketRepository.OWNER_CHANGED_MESSAGE_PREFIX + "เดิม → " + newOwner.name());
    }

    private void fillTrackingFields(long deal) {
        jdbc.update("""
            UPDATE sales.ticket
               SET designer_name = 'สตูดิโอเส้นตรง', owner_name = 'คุณเจ้าของ', buyer_name = 'บริษัท ผู้ซื้อ',
                   win_probability = 60, expected_quarter = '2027-Q1', estimated_value = 250000,
                   last_follow_up_at = DATE '2026-11-01'
             WHERE ticket_id = :id
            """, Map.of("id", deal));
    }

    private Map<String, Object> trackingSnapshot(long deal) {
        return jdbc.queryForMap("""
            SELECT designer_name, owner_name, buyer_name, win_probability, expected_quarter, estimated_value,
                   last_follow_up_at, lifecycle, sales_stage
              FROM sales.ticket WHERE ticket_id = :id
            """, Map.of("id", deal));
    }

    private LocalDate nextFollowUp(long deal) {
        return jdbc.queryForObject("SELECT next_follow_up_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", deal), LocalDate.class);
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
}
