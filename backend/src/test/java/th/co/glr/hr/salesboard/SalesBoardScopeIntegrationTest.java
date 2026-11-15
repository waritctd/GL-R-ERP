package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.DealLifecycle;
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
        SalesBoardService board = new SalesBoardService(new SalesBoardRepository(jdbc, quietRule), quietRule,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));
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

    @Test
    void managerMyWorkIsStillOnlyTheirOwnDeals() throws Exception {
        long repDeal = deal(salesActor);

        JsonNode work = ok(get("/api/sales/my-work"), salesManagerActor);

        assertThat(ids(work.get("board"))).doesNotContain(repDeal);
    }

    // ── role gates ───────────────────────────────────────────────────────────

    @Test
    void salesRoleIsRefusedTeamOverview() throws Exception {
        deal(salesActor);

        assertThat(status(get("/api/sales/team-overview"), salesActor)).isEqualTo(403);
    }

    @Test
    void accountAndImportAreRefusedBoth() throws Exception {
        deal(salesActor);
        for (String role : List.of("account", "import", "hr")) {
            UserPrincipal other = actor(createEmployee("ผู้ใช้ " + role, role + "-board@glr.co.th", "ACC", "บัญชี",
                null), role);
            assertThat(status(get("/api/sales/my-work"), other)).as("%s my-work", role).isEqualTo(403);
            assertThat(status(get("/api/sales/team-overview"), other)).as("%s team", role).isEqualTo(403);
        }
    }

    // ── team overview ────────────────────────────────────────────────────────

    @Test
    void managerTeamOverviewHasOneRowPerRepWithCorrectCounts() throws Exception {
        long a1 = deal(salesActor);
        deal(salesActor);
        deal(otherSalesActor);
        backdate(a1, 20);

        JsonNode team = ok(get("/api/sales/team-overview"), salesManagerActor);

        JsonNode a = rep(team, salesActor.id());
        JsonNode b = rep(team, otherSalesActor.id());
        assertThat(a.get("openDeals").asInt()).isEqualTo(2);
        assertThat(a.get("quietDeals").asInt()).isEqualTo(1);
        assertThat(b.get("openDeals").asInt()).isEqualTo(1);
        assertThat(b.get("quietDeals").asInt()).isZero();
        assertThat(team.get("reps")).hasSize(2);
        assertThat(ids(team.get("stale"))).containsExactly(a1);
        assertThat(team.get("quietDays").asInt()).isEqualTo(14);
    }

    @Test
    void ceoGetsTheTeamOverview() throws Exception {
        deal(salesActor);
        UserPrincipal ceo = actor(createEmployee("ซีอีโอ", "ceo-board@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");

        assertThat(ok(get("/api/sales/team-overview"), ceo).get("reps")).hasSize(1);
    }

    // ── what is on the board ─────────────────────────────────────────────────

    @Test
    void myWorkExcludesNonActiveDeals() throws Exception {
        long active = deal(salesActor);
        long lost = deal(salesActor);
        long cancelled = deal(salesActor);
        for (long id : List.of(active, lost, cancelled)) {
            backdate(id, 30);
        }
        setLifecycle(lost, DealLifecycle.CLOSED_LOST);
        setLifecycle(cancelled, DealLifecycle.CANCELLED);

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);

        assertThat(ids(work.get("board"))).containsExactly(active);
        assertThat(ids(work.get("followUp"))).containsExactly(active);
    }

    @Test
    void followUpIsCappedAtFiveAndOrderedByValueTimesDays() throws Exception {
        long valued20 = deal(salesActor);
        approved(valued20, salesActor, "DESIGNER");
        long valued30 = deal(salesActor);
        approved(valued30, salesActor, "DESIGNER");
        backdate(valued20, 20);
        backdate(valued30, 30);
        List<Long> plain = new ArrayList<>();
        for (int days = 15; days <= 19; days++) {
            long id = deal(salesActor);
            backdate(id, days);
            plain.add(id);
        }

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);

        assertThat(ids(work.get("followUp")))
            .containsExactly(valued30, valued20, plain.get(4), plain.get(3), plain.get(2));
        assertThat(work.get("board")).hasSize(7);
    }

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

    @Test
    void expectedQuarterRejectsMalformedValue() throws Exception {
        long id = deal(salesActor);

        assertThat(status(put("/api/tickets/" + id + "/tracking")
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedQuarter\":\"2026Q4\"}"), salesActor))
            .isEqualTo(400);
        assertThat(status(put("/api/tickets/" + id + "/tracking")
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedQuarter\":\"2026-Q4\"}"), salesActor))
            .isEqualTo(200);

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);
        assertThat(byId(work.get("board"), id).get("expectedQuarter").asText()).isEqualTo("2026-Q4");
    }

    /** The existing tracking form does not send expectedQuarter; omitting it must not wipe it. */
    @Test
    void trackingUpdateWithoutExpectedQuarterKeepsIt() throws Exception {
        long id = deal(salesActor);
        status(put("/api/tickets/" + id + "/tracking")
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedQuarter\":\"2027-Q1\"}"), salesActor);

        assertThat(status(put("/api/tickets/" + id + "/tracking")
            .contentType(MediaType.APPLICATION_JSON).content("{\"designerName\":\"คุณเอ\"}"), salesActor))
            .isEqualTo(200);

        JsonNode work = ok(get("/api/sales/my-work"), salesActor);
        assertThat(byId(work.get("board"), id).get("expectedQuarter").asText()).isEqualTo("2027-Q1");
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

    /** Two live quotations (seeded directly: the service's sweep allows only one): the NEWER approval wins. */
    @Test
    void valueIsTheLatestLiveQuotation_byApprovalTime_notTheOlderOne() throws Exception {
        long id = deal(salesActor);
        DealQuotationDto first = approved(id, salesActor, "DESIGNER");
        long second = cloneQuotation(first.id(), "APPROVED", "2", "now() - interval '1 day'");
        jdbc.update("UPDATE sales.quotation SET approved_at = now() - interval '5 days', total_amount = 100"
            + " WHERE quotation_id = :id", Map.of("id", first.id()));
        jdbc.update("UPDATE sales.quotation SET total_amount = 300 WHERE quotation_id = :id", Map.of("id", second));

        JsonNode deal = byId(ok(get("/api/sales/my-work"), salesActor).get("board"), id);

        assertThat(new BigDecimal(deal.get("value").asText())).isEqualByComparingTo("321.00");
        assertThat(deal.get("valueExpired").asBoolean()).isFalse();
    }

    @Test
    void valuePrefersTheHeadlinePartysQuotation_overANewerOne() throws Exception {
        long id = deal(salesActor);
        DealQuotationDto headline = approved(id, salesActor, "DESIGNER");
        long newer = cloneQuotation(headline.id(), "APPROVED", "2", "now()");
        jdbc.update("UPDATE sales.quotation SET approved_at = now() - interval '5 days', total_amount = 100"
            + " WHERE quotation_id = :id", Map.of("id", headline.id()));
        jdbc.update("UPDATE sales.quotation SET total_amount = 300, party_id = NULL WHERE quotation_id = :id",
            Map.of("id", newer));
        jdbc.update("""
            UPDATE sales.deal_party SET is_headline = TRUE
             WHERE party_id = (SELECT party_id FROM sales.quotation WHERE quotation_id = :id)
            """, Map.of("id", headline.id()));
        assertThat(jdbc.queryForObject("SELECT party_id IS NOT NULL FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", headline.id()), Boolean.class)).as("fixture: the approved quotation has a party").isTrue();

        JsonNode deal = byId(ok(get("/api/sales/my-work"), salesActor).get("board"), id);

        assertThat(new BigDecimal(deal.get("value").asText())).isEqualByComparingTo("107.00");
    }

    @Test
    void sentAndAcceptedQuotationsCarryAValue() throws Exception {
        long sent = deal(salesActor);
        DealQuotationDto a = approved(sent, salesActor, "DESIGNER");
        jdbc.update("UPDATE sales.quotation SET doc_status = 'SENT' WHERE quotation_id = :id", Map.of("id", a.id()));
        long accepted = deal(salesActor);
        DealQuotationDto b = approved(accepted, salesActor, "DESIGNER");
        jdbc.update("UPDATE sales.quotation SET doc_status = 'ACCEPTED' WHERE quotation_id = :id", Map.of("id", b.id()));

        JsonNode board = ok(get("/api/sales/my-work"), salesActor).get("board");

        assertThat(new BigDecimal(byId(board, sent).get("value").asText())).isEqualByComparingTo(a.grandTotal());
        assertThat(new BigDecimal(byId(board, accepted).get("value").asText())).isEqualByComparingTo(b.grandTotal());
    }

    @Test
    void expiredQuotationKeepsItsValue_flaggedExpired_whenNothingLiveExists() throws Exception {
        long id = deal(salesActor);
        DealQuotationDto q = approved(id, salesActor, "DESIGNER");
        jdbc.update("UPDATE sales.quotation SET doc_status = 'EXPIRED' WHERE quotation_id = :id", Map.of("id", q.id()));

        JsonNode deal = byId(ok(get("/api/sales/my-work"), salesActor).get("board"), id);

        assertThat(new BigDecimal(deal.get("value").asText())).isEqualByComparingTo(q.grandTotal());
        assertThat(deal.get("valueExpired").asBoolean()).isTrue();
    }

    @Test
    void rejectedSupersededAndCancelledQuotationsCarryNoValue() throws Exception {
        for (String status : List.of("REJECTED", "SUPERSEDED", "CANCELLED")) {
            long id = deal(salesActor);
            DealQuotationDto q = approved(id, salesActor, "DESIGNER");
            jdbc.update("UPDATE sales.quotation SET doc_status = :s WHERE quotation_id = :id",
                Map.of("s", status, "id", q.id()));

            JsonNode deal = byId(ok(get("/api/sales/my-work"), salesActor).get("board"), id);
            assertThat(deal.get("value").isNull()).as(status).isTrue();
        }
    }

    @Test
    void repRowsAreOrderedByThbValueDescending() throws Exception {
        deal(salesActor);
        long other = deal(otherSalesActor);
        approved(other, otherSalesActor, "DESIGNER");

        JsonNode reps = ok(get("/api/sales/team-overview"), salesManagerActor).get("reps");

        assertThat(reps.get(0).get("employeeId").asLong()).isEqualTo(otherSalesActor.id());
        assertThat(reps.get(1).get("employeeId").asLong()).isEqualTo(salesActor.id());
    }

    /** Managers' and the CEO's own deals stay in the team table, labelled with their role. */
    @Test
    void aManagersOwnDealsStayInTheTeamTable_labelledWithTheirRole() throws Exception {
        deal(salesManagerActor);
        deal(salesActor);

        JsonNode team = ok(get("/api/sales/team-overview"), salesManagerActor);

        assertThat(rep(team, salesManagerActor.id()).get("role").asText()).isEqualTo("sales_manager");
        assertThat(rep(team, salesActor.id()).get("role").asText()).isEqualTo("sales");
    }

    /** A copy of {@code sourceId} on the same ticket with the given status, number suffix and approval time. */
    private long cloneQuotation(long sourceId, String status, String revisionSuffix, String approvedAtSql) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, total_amount, currency, doc_status, origin,
                                         recipient_type, created_by, sales_rep_id, approved_at, approved_by,
                                         document_language, party_id, quotation_version)
            SELECT ticket_id, number || '-R' || :rev, issued_by, total_amount, currency, :status, origin,
                   recipient_type, created_by, sales_rep_id, %s, approved_by, document_language, party_id,
                   quotation_version + 1
              FROM sales.quotation WHERE quotation_id = :id
            RETURNING quotation_id
            """.formatted(approvedAtSql), Map.of("id", sourceId, "status", status, "rev", revisionSuffix), Long.class);
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

    private void setLifecycle(long ticketId, String lifecycle) {
        jdbc.update("UPDATE sales.ticket SET lifecycle = :l WHERE ticket_id = :id",
            Map.of("l", lifecycle, "id", ticketId));
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
