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
    void managerMayCompleteARepsVisit_theActivityIsTheRepsWithWhoRecordedIt() throws Exception {
        long repDeal = deal(salesActor);
        long visit = plan(salesActor, repDeal, "คุณอรุณ", today);

        assertThat(ok(post("/api/sales/visits/" + visit + "/done").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"ส่งตัวอย่าง\"}"), salesManagerActor).get("status").asText()).isEqualTo("DONE");
        assertThat(siteVisitRows(repDeal)).isEqualTo(1);
        // M3: the person who went, not the person who ticked.
        Map<String, Object> activity = siteVisit(repDeal);
        assertThat(((Number) activity.get("created_by_id")).longValue()).isEqualTo(salesActor.id());
        assertThat((String) activity.get("note")).isEqualTo("ส่งตัวอย่าง · บันทึกโดย " + salesManagerActor.name());
    }

    @Test
    void theOwnersOwnTickCarriesNoRecordedByNote() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, "คุณอรุณ", today);

        ok(post("/api/sales/visits/" + visit + "/done").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"ส่งตัวอย่าง\"}"), salesActor);

        assertThat((String) siteVisit(deal).get("note")).isEqualTo("ส่งตัวอย่าง");
    }

    // ── reassignment: the visit stays with whoever went (review M1) ──────────

    @Test
    void afterTheDealMovesToAnotherRep_theVisitOwnerCanStillTickAndCancel() throws Exception {
        long deal = deal(salesActor);
        long ticked = plan(salesActor, deal, "คุณอรุณ", today);
        long cancelled = plan(salesActor, deal, "คุณอรุณ", today.plusDays(2));
        reassign(deal, otherSalesActor);

        assertThat(ok(post("/api/sales/visits/" + ticked + "/done"), salesActor).get("status").asText())
            .isEqualTo("DONE");
        assertThat(ok(post("/api/sales/visits/" + cancelled + "/cancel"), salesActor).get("status").asText())
            .isEqualTo("CANCELLED");
    }

    // ── review NEW-2: the ex-owner's tick stays on the visit, never on the new owner's deal ──

    @Test
    void anExOwnersTickWritesNoActivityOntoTheNewOwnersDeal_andSaysWhereTheDealWent() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, "คุณอรุณ", today.minusDays(1));
        backdate(deal, 30);
        reassign(deal, otherSalesActor);
        assertThat(quietRule.isQuiet(deal)).isTrue();

        JsonNode done = ok(post("/api/sales/visits/" + visit + "/done").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"ไปมาแล้ว\"}"), salesActor);

        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("note").asText()).isEqualTo("ไปมาแล้ว");
        assertThat(done.get("dealMovedToName").asText()).isEqualTo(employeeName(otherSalesActor));
        assertThat(siteVisitRows(deal)).isZero();
        assertThat(quietRule.isQuiet(deal)).as("the new owner's quiet state is untouched").isTrue();
    }

    @Test
    void aManagerTickingAnExOwnersVisitAlsoWritesNoActivity() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, "คุณอรุณ", today);
        reassign(deal, otherSalesActor);

        ok(post("/api/sales/visits/" + visit + "/done"), salesManagerActor);

        assertThat(siteVisitRows(deal)).isZero();
    }

    @Test
    void aVisitOnTheOwnersOwnDealCarriesNoMovedFlag() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, "คุณอรุณ", today);

        assertThat(ok(post("/api/sales/visits/" + visit + "/done"), salesActor).get("dealMovedToName").isNull())
            .isTrue();
        assertThat(siteVisitRows(deal)).isEqualTo(1);
    }

    // ── review NEW-1: a plan more than 180 days out is refused ──

    @Test
    void aVisitMoreThan180DaysAheadIs400() throws Exception {
        assertThat(status(createRequest(null, "ปีหน้า", today.plusDays(181), null, false), salesActor))
            .isEqualTo(400);
        assertThat(status(createRequest(null, "2099", LocalDate.of(2099, 1, 1), null, false), salesActor))
            .isEqualTo(400);
        assertThat(visitCount()).isZero();
        assertThat(status(createRequest(null, "ครึ่งปี", today.plusDays(180), null, false), salesActor))
            .as("180 days is the last allowed day").isEqualTo(200);
    }

    @Test
    void afterTheDealMoves_neitherTheNewDealOwnerNorAThirdRepCanTouchTheVisit() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, "คุณอรุณ", today);
        reassign(deal, otherSalesActor);
        UserPrincipal third = actor(createEmployee("พนักงานขาย คนที่สาม", "third-visit@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");

        for (UserPrincipal outsider : List.of(otherSalesActor, third)) {
            assertThat(status(post("/api/sales/visits/" + visit + "/done"), outsider)).as("done").isEqualTo(403);
            assertThat(status(post("/api/sales/visits/" + visit + "/cancel"), outsider)).as("cancel").isEqualTo(403);
            assertThat(ids(ok(range("/api/sales/visits", today, today), outsider))).doesNotContain(visit);
        }
        assertThat(visitStatus(visit)).isEqualTo("PLANNED");
        assertThat(siteVisitRows(deal)).isZero();
    }

    @Test
    void creatingAVisitOnAMovedDealIsStill403ForARepWhoWasNeverOnIt() throws Exception {
        long deal = deal(salesActor);
        reassign(deal, otherSalesActor);
        UserPrincipal third = actor(createEmployee("พนักงานขาย คนที่สี่", "fourth-visit@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");

        // The ex-owner is the deal's keyer now and keeps TicketService's keyer access (deal policy, not visits');
        // a rep who was never on the deal is refused.
        assertThat(status(createRequest(deal, "คุณอรุณ", today, null, false), third)).isEqualTo(403);
    }

    // ── review NEW-A: a keyer who is not the owner neither holds nor writes onto the deal ──

    @Test
    void aKeyerWhoIsNotTheOwner_ticksWithoutWritingOntoTheDeal() throws Exception {
        long deal = deal(salesActor);
        // Keyed in by salesActor for otherSalesActor, never reassigned.
        jdbc.update("UPDATE sales.ticket SET created_by = :owner, keyed_by = :keyer WHERE ticket_id = :id",
            Map.of("owner", otherSalesActor.id(), "keyer", salesActor.id(), "id", deal));
        long visit = plan(salesActor, deal, "คุณอรุณ", today);

        JsonNode done = ok(post("/api/sales/visits/" + visit + "/done"), salesActor);

        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("dealMovedToName").asText()).isEqualTo(employeeName(otherSalesActor));
        assertThat(siteVisitRows(deal)).isZero();
    }

    // ── done only on or before today (review M2) ─────────────────────────────

    @Test
    void aFutureVisitCannotBeTickedDone() throws Exception {
        long deal = deal(salesActor);
        long visit = plan(salesActor, deal, "คุณอรุณ", today.plusDays(1));

        MvcResult result = perform(post("/api/sales/visits/" + visit + "/done"), salesActor);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("ยังไม่ถึงวันนัด");
        assertThat(visitStatus(visit)).isEqualTo("PLANNED");
        assertThat(siteVisitRows(deal)).isZero();
    }

    @Test
    void anOverdueVisitCanStillBeTickedDone() throws Exception {
        long visit = plan(salesActor, null, "คุณอรุณ", today.minusDays(3));

        assertThat(ok(post("/api/sales/visits/" + visit + "/done"), salesActor).get("status").asText())
            .isEqualTo("DONE");
    }

    // ── LOW: non-ACTIVE deals, one-step log idempotency ──────────────────────

    @Test
    void aNewVisitCannotBeLinkedToANonActiveDeal() throws Exception {
        long lost = deal(salesActor);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST' WHERE ticket_id = :id", Map.of("id", lost));

        assertThat(status(createRequest(lost, "คุณอรุณ", today, null, false), salesActor)).isEqualTo(400);
        assertThat(status(createRequest(lost, "คุณอรุณ", today, null, true), salesActor)).isEqualTo(400);
        assertThat(visitCount()).isZero();
    }

    @Test
    void anIdenticalOneStepLogWithin60SecondsIsTheSameVisit() throws Exception {
        long deal = deal(salesActor);

        long first = ok(createRequest(deal, "Walk-in", today, null, true), salesActor).get("id").asLong();
        long second = ok(createRequest(deal, "Walk-in", today, null, true), salesActor).get("id").asLong();

        assertThat(second).isEqualTo(first);
        assertThat(visitCount()).isEqualTo(1);
        assertThat(siteVisitRows(deal)).isEqualTo(1);
        // A different contact, or the same one over a minute later, is a new visit.
        ok(createRequest(deal, "คุณอรุณ", today, null, true), salesActor);
        jdbc.update("UPDATE sales.visit SET created_at = now() - interval '61 seconds'", Map.of());
        ok(createRequest(deal, "Walk-in", today, null, true), salesActor);
        assertThat(visitCount()).isEqualTo(3);
    }

    @Test
    void anotherRepsIdenticalLogIsNotMerged() throws Exception {
        ok(createRequest(null, "Walk-in", today, null, true), salesActor);
        ok(createRequest(null, "Walk-in", today, null, true), otherSalesActor);

        assertThat(visitCount()).isEqualTo(2);
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

    private String employeeName(UserPrincipal who) {
        return jdbc.queryForObject("""
            SELECT NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '') FROM hr.employee
             WHERE employee_id = :id
            """, Map.of("id", who.id()), String.class);
    }

    /** The production reassignment path (TicketRepository#moveOwnerToSalesRep): the ex-owner becomes the keyer. */
    private void reassign(long ticketId, UserPrincipal newOwner) {
        assertThat(tickets.moveOwnerToSalesRep(ticketId, newOwner.id())).isEqualTo(1);
    }

    private Map<String, Object> siteVisit(long ticketId) {
        return jdbc.queryForMap(
            "SELECT created_by_id, note FROM sales.deal_activity WHERE ticket_id = :id AND kind = 'SITE_VISIT'",
            Map.of("id", ticketId));
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
