package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend half of the five rebuilt deal dialogs (สร้างงานใหม่ / ยกเลิกงาน / พักงาน), through the real controller,
 * service, repository and Postgres (V219). Written wrong-way-round where a rule says who may do something: the short
 * create form must not let anyone start a deal at S2 who could not move one there by hand, and a return date is
 * writable by exactly whoever may pause the deal. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class DealLifecycleDialogsIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void wireHttp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders
            .standaloneSetup(new TicketController(ticketService, new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .setValidator(validator)
            .build();
    }

    // ── สร้างงานใหม่: the drawn short form ─────────────────────────────────────────────────────────

    // Fix round 1, ruling 1: the channel stays REQUIRED at creation (owner rule 2026-09-30). Ruling 3: the estimate
    // has its own column, estimated_value; imported_value stays the pipeline import's alone.
    @Test
    void shortForm_createsAnS1DealStoringTheDrawnFields_andTheEstimateInItsOwnColumn() throws Exception {
        JsonNode created = ok(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content("""
            {"title":"โครงการบ้านสวน","customerId":%d,"customerName":"%s","projectId":%d,"entryChannel":"OWNER_DIRECT",
             "designerName":"เทพสตูดิโอ ดีไซน์","estimatedValue":1200000}
            """.formatted(customer.id(), customer.name(), projectId)), salesActor);

        long id = created.at("/ticket/summary/id").asLong();
        Map<String, Object> row = row(id);
        assertThat(row.get("entry_channel")).isEqualTo(EntryChannel.OWNER_DIRECT);
        assertThat(row.get("sales_stage")).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(row.get("designer_name")).isEqualTo("เทพสตูดิโอ ดีไซน์");
        assertThat((BigDecimal) row.get("estimated_value")).isEqualByComparingTo("1200000");
        assertThat(row.get("imported_value")).isNull();
        assertThat(created.at("/ticket/summary/estimatedValue").decimalValue()).isEqualByComparingTo("1200000");
        assertThat(eventCount(id, TicketEventKind.STAGE_CHANGED)).isZero();
    }

    @Test
    void aDealWithNoChannelIsStillRefused_andNothingIsWritten() throws Exception {
        int before = dealCount();
        assertThat(status(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content("""
            {"title":"ไม่มีช่องทาง","customerId":%d,"projectId":%d}
            """.formatted(customer.id(), projectId)), salesActor)).isEqualTo(400);
        assertThat(dealCount()).isEqualTo(before);
    }

    /** Ruling 2: a sales_manager cannot edit items afterwards (TicketService.editItems is sales-only), so the create form
     * sends them; they persist exactly as the old form's did. */
    @Test
    void aManagerCreatedDealWithItemsPersistsThem() throws Exception {
        JsonNode created = ok(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(
            ",\"items\":[{\"brand\":\"Padana\",\"model\":\"Raku\",\"color\":\"ขาว\",\"size\":\"20x20\","
                + "\"factory\":\"Padana\",\"unitBasis\":\"PIECE\",\"qty\":12,\"sourcedFromStock\":false}]")),
            salesManagerActor);

        long id = created.at("/ticket/summary/id").asLong();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_item WHERE ticket_id = :id AND model = 'Raku'",
            Map.of("id", id), Integer.class)).isEqualTo(1);
        assertThat(created.at("/ticket/summary/itemCount").asInt()).isEqualTo(1);
    }

    // ── ruling 3: the estimate is edited through the existing tracking update ─────────────────────

    @Test
    void theTrackingUpdateSetsAndClearsTheEstimate_andLeavesItWhenNotSent() throws Exception {
        long id = created(",\"estimatedValue\":500000");

        // A tracking update that does not mention the estimate keeps it (the guided advance sends such updates).
        ok(put("/api/tickets/" + id + "/tracking").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nextFollowUpAt\":\"" + today().plusDays(7) + "\"}"), salesActor);
        assertThat((BigDecimal) row(id).get("estimated_value")).isEqualByComparingTo("500000");

        ok(put("/api/tickets/" + id + "/tracking").contentType(MediaType.APPLICATION_JSON)
            .content("{\"estimatedValue\":750000}"), salesActor);
        assertThat((BigDecimal) row(id).get("estimated_value")).isEqualByComparingTo("750000");

        ok(put("/api/tickets/" + id + "/tracking").contentType(MediaType.APPLICATION_JSON)
            .content("{\"clearEstimatedValue\":true}"), salesActor);
        assertThat(row(id).get("estimated_value")).isNull();
    }

    /** Wrong-way-round: the estimate is editable by exactly whoever may edit the tracking info today. */
    @Test
    void anotherRepCannotEditTheEstimate() throws Exception {
        long id = created(",\"estimatedValue\":500000");

        assertThat(status(put("/api/tickets/" + id + "/tracking").contentType(MediaType.APPLICATION_JSON)
            .content("{\"estimatedValue\":1}"), otherSalesActor)).isEqualTo(403);
        assertThat((BigDecimal) row(id).get("estimated_value")).isEqualByComparingTo("500000");
    }

    @Test
    void aNonPositiveEstimateIsRefusedByTheTrackingUpdate() throws Exception {
        long id = created("");
        assertThat(status(put("/api/tickets/" + id + "/tracking").contentType(MediaType.APPLICATION_JSON)
            .content("{\"estimatedValue\":0}"), salesActor)).isEqualTo(400);
    }

    @Test
    void startAtS2_landsOnSpecApproved_withAStageChangedEvent() throws Exception {
        JsonNode created = ok(post("/api/tickets").contentType(MediaType.APPLICATION_JSON)
            .content(body(",\"startStage\":\"SPEC_APPROVED\"")), salesActor);

        long id = created.at("/ticket/summary/id").asLong();
        assertThat(created.at("/ticket/summary/salesStage").asText()).isEqualTo(DealStage.SPEC_APPROVED);
        assertThat(eventCount(id, TicketEventKind.STAGE_CHANGED)).isEqualTo(1);
    }

    @Test
    void startAtAnythingButS1OrS2_isRefused_andNoDealIsWritten() throws Exception {
        int before = dealCount();
        for (String stage : List.of(DealStage.PRESENTATION, DealStage.QUOTE_BUYER, DealStage.CLOSED_PAID, "NOPE")) {
            assertThat(status(post("/api/tickets").contentType(MediaType.APPLICATION_JSON)
                .content(body(",\"startStage\":\"" + stage + "\"")), salesActor)).as(stage).isEqualTo(400);
        }
        assertThat(dealCount()).isEqualTo(before);
    }

    /** Wrong-way-round: a deal-entry grant holder outside the sales roles may create a deal but may NOT move one to
     * S2 by hand (TicketService#requireStageWriteAccess), so the short form must not let them START one there.
     *
     * <p>The refusal comes after the insert, from the same write gate a hand move runs, so the create must roll back.
     * In the app that is TicketService.create's own {@code @Transactional} (a Spring proxy). This harness builds the
     * service with {@code new}, which has no proxy, so the call is wrapped in a real transaction on the same
     * DataSource to stand in for it — exactly the boundary the proxy draws. */
    @Test
    void grantHolderWhoCannotMoveADealToS2_cannotStartOneThere() throws Exception {
        long qcId = createEmployee("QC ผู้คีย์", "qc-short-create@glr.co.th", "QC", "ฝ่าย QC", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", qcId));
        UserPrincipal qc = actor(qcId, "qc");
        int before = dealCount();
        org.springframework.transaction.support.TransactionTemplate tx =
            new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    jdbc.getJdbcTemplate().getDataSource()));
        CreateTicketRequest atS2 = new CreateTicketRequest("โครงการสั้น", null, customer.name(), customer.id(),
            projectId, null, null, EntryChannel.DESIGNER_LED, List.of(), null, null, null, null, null,
            DealStage.SPEC_APPROVED);

        refusal(() -> tx.executeWithoutResult(status -> ticketService.create(atS2, qc)),
            org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(dealCount()).as("the refused create must roll back").isEqualTo(before);

        // Through HTTP the same caller is told 403 too.
        assertThat(status(post("/api/tickets").contentType(MediaType.APPLICATION_JSON)
            .content(body(",\"startStage\":\"SPEC_APPROVED\"")), qc)).isEqualTo(403);
        // Control: the same person may still create at S1, exactly as before.
        assertThat(status(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body("")), qc))
            .isEqualTo(200);
    }

    @Test
    void anExplicitUnspecifiedChannelIsStillRefused() throws Exception {
        assertThat(status(post("/api/tickets").contentType(MediaType.APPLICATION_JSON)
            .content(body(",\"entryChannel\":\"UNSPECIFIED\"")), salesActor)).isEqualTo(400);
    }

    // ── ยกเลิกงาน: the drawn reasons, the old ones still valid ──────────────────────────────────────

    @Test
    void aNewCancelReasonIsStoredThroughTheWidenedCheckConstraint() throws Exception {
        long id = deal(salesActor);

        ok(post("/api/tickets/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"DUPLICATE_DEAL\",\"note\":\"ซ้ำกับ PR-2026-0028\"}"), salesActor);

        assertThat(row(id).get("cancel_reason")).isEqualTo("DUPLICATE_DEAL");
        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.CANCELLED);
    }

    @Test
    void aRetiredCancelReasonIsStillAccepted() throws Exception {
        long id = deal(salesActor);

        ok(post("/api/tickets/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"OWNER_CANCELLED\"}"), salesActor);

        assertThat(row(id).get("cancel_reason")).isEqualTo("OWNER_CANCELLED");
    }

    @Test
    void anotherRepStillCannotCancel_withANewReason() throws Exception {
        long id = deal(salesActor);

        assertThat(status(post("/api/tickets/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"NO_REAL_PROJECT\"}"), otherSalesActor)).isEqualTo(403);
        assertThat(row(id).get("cancel_reason")).isNull();
    }

    // ── พักงาน: วันที่จะกลับมาดู ─────────────────────────────────────────────────────────────────

    @Test
    void holdWithAReturnDate_storesIt_andTheDealPageReadsIt() throws Exception {
        long id = deal(salesActor);
        LocalDate on = today().plusMonths(2);

        ok(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"รอใบอนุญาต\",\"pauseReturnOn\":\"" + on + "\"}"), salesActor);

        JsonNode detail = ok(get("/api/tickets/" + id), salesActor);
        assertThat(detail.at("/ticket/summary/lifecycle").asText()).isEqualTo(DealLifecycle.ON_HOLD);
        assertThat(detail.at("/ticket/summary/pauseReturnOn").asText()).isEqualTo(on.toString());
    }

    @Test
    void dormantWithAReturnDate_storesIt() throws Exception {
        long id = deal(salesActor);
        LocalDate on = today().plusWeeks(2);

        ok(post("/api/tickets/" + id + "/dormant").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + on + "\"}"), salesActor);

        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.DORMANT);
        assertThat(summary(id).pauseReturnOn()).isEqualTo(on);
    }

    @Test
    void holdWithoutADate_behavesAsBefore() throws Exception {
        long id = deal(salesActor);

        ok(post("/api/tickets/" + id + "/hold"), salesActor);

        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.ON_HOLD);
        assertThat(summary(id).pauseReturnOn()).isNull();
    }

    @Test
    void resume_clearsTheReturnDate() throws Exception {
        long id = deal(salesActor);
        ok(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + today().plusDays(30) + "\"}"), salesActor);

        ok(post("/api/tickets/" + id + "/resume"), salesActor);

        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.ACTIVE);
        assertThat(summary(id).pauseReturnOn()).isNull();
    }

    @Test
    void aReturnDateInThePast_isRefused_andTheDealStaysActive() throws Exception {
        long id = deal(salesActor);

        assertThat(status(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + today().minusDays(1) + "\"}"), salesActor)).isEqualTo(400);

        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.ACTIVE);
        assertThat(summary(id).pauseReturnOn()).isNull();
    }

    /** Wrong-way-round: the return date is writable by exactly whoever may pause the deal today — another rep may
     * not, and nothing is written. */
    @Test
    void anotherRep_cannotPauseWithAReturnDate() throws Exception {
        long id = deal(salesActor);

        for (String path : List.of("hold", "dormant")) {
            assertThat(status(post("/api/tickets/" + id + "/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseReturnOn\":\"" + today().plusDays(14) + "\"}"), otherSalesActor))
                .as(path).isEqualTo(403);
        }
        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.ACTIVE);
        assertThat(summary(id).pauseReturnOn()).isNull();
    }

    /** Fix round 1 (B7): the permission check comes BEFORE the date validation, so a caller who may not pause the
     * deal is told 403 — never a 400 about the date. */
    @Test
    void anotherRepWithAPastDate_isToldForbidden_notABadDate() throws Exception {
        long id = deal(salesActor);

        for (String path : List.of("hold", "dormant")) {
            assertThat(status(post("/api/tickets/" + id + "/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseReturnOn\":\"" + today().minusDays(3) + "\"}"), otherSalesActor))
                .as(path).isEqualTo(403);
        }
    }

    // ── ruling 5: changing the return date of an already-paused deal ─────────────────────────────

    @Test
    void holdOnAnOnHoldDeal_changesOnlyTheDate_withNoNotificationAndNoSecondPauseEvent() throws Exception {
        long id = deal(salesActor);
        ok(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + today().plusDays(7) + "\"}"), salesActor);
        int notices = noticeCount(id);
        int holds = eventCount(id, TicketEventKind.ON_HOLD);

        ok(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"เลื่อนอีกเดือน\",\"pauseReturnOn\":\"" + today().plusMonths(1) + "\"}"), salesActor);

        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.ON_HOLD);
        assertThat(summary(id).pauseReturnOn()).isEqualTo(today().plusMonths(1));
        assertThat(noticeCount(id)).as("no second pause notice").isEqualTo(notices);
        assertThat(eventCount(id, TicketEventKind.ON_HOLD)).as("no second ON_HOLD event").isEqualTo(holds);
    }

    @Test
    void dormantOnADormantDeal_changesOrClearsOnlyTheDate_withNoNotification() throws Exception {
        long id = deal(salesActor);
        ok(post("/api/tickets/" + id + "/dormant").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + today().plusDays(7) + "\"}"), salesActor);
        int notices = noticeCount(id);
        int dormants = eventCount(id, TicketEventKind.DORMANT);

        ok(post("/api/tickets/" + id + "/dormant").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + today().plusWeeks(2) + "\"}"), salesActor);
        assertThat(summary(id).pauseReturnOn()).isEqualTo(today().plusWeeks(2));
        ok(post("/api/tickets/" + id + "/dormant").contentType(MediaType.APPLICATION_JSON).content("{}"), salesActor);

        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.DORMANT);
        assertThat(summary(id).pauseReturnOn()).isNull();
        assertThat(noticeCount(id)).isEqualTo(notices);
        assertThat(eventCount(id, TicketEventKind.DORMANT)).isEqualTo(dormants);
    }

    @Test
    void switchingFromOnHoldToDormant_behavesAsBefore_withItsNotification() throws Exception {
        long id = deal(salesActor);
        ok(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON).content("{}"), salesActor);
        int notices = noticeCount(id);

        ok(post("/api/tickets/" + id + "/dormant").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + today().plusDays(5) + "\"}"), salesActor);

        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.DORMANT);
        assertThat(noticeCount(id)).isGreaterThan(notices);
        assertThat(eventCount(id, TicketEventKind.DORMANT)).isEqualTo(1);
    }

    /** DORMANT → ON_HOLD stays refused, as before ruling 5 (a state switch behaves as today). */
    @Test
    void holdOnADormantDeal_isStillRefused() throws Exception {
        long id = deal(salesActor);
        ok(post("/api/tickets/" + id + "/dormant").contentType(MediaType.APPLICATION_JSON).content("{}"), salesActor);

        assertThat(status(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + today().plusDays(5) + "\"}"), salesActor)).isEqualTo(409);
    }

    /** Wrong-way-round: changing the date needs the same permission as pausing. */
    @Test
    void anotherRepCannotChangeTheReturnDate() throws Exception {
        long id = deal(salesActor);
        LocalDate on = today().plusDays(7);
        ok(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + on + "\"}"), salesActor);

        assertThat(status(post("/api/tickets/" + id + "/hold").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pauseReturnOn\":\"" + today().plusMonths(3) + "\"}"), otherSalesActor)).isEqualTo(403);
        assertThat(summary(id).pauseReturnOn()).isEqualTo(on);
    }

    /** Review round 2, M-2 (BM7): the in-place date change is logged as POLICY_CHANGED and touches nothing the quiet rule
     * or the movement reports read: no STAGE_CHANGED event, the same stage_updated_at, the same last-touched instant. */
    @Test
    void changingTheDateInPlace_isAPolicyChange_notAStageMove_andLeavesTheQuietClockAlone() throws Exception {
        for (String path : List.of("hold", "dormant")) {
            long id = deal(salesActor);
            ok(post("/api/tickets/" + id + "/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseReturnOn\":\"" + today().plusDays(7) + "\"}"), salesActor);
            int stageEvents = eventCount(id, TicketEventKind.STAGE_CHANGED);
            int policyEvents = eventCount(id, TicketEventKind.POLICY_CHANGED);
            Object stageUpdatedAt = stageUpdatedAt(id);
            Object lastTouched = lastTouched(id);

            ok(post("/api/tickets/" + id + "/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseReturnOn\":\"" + today().plusMonths(1) + "\"}"), salesActor);

            assertThat(eventCount(id, TicketEventKind.POLICY_CHANGED)).as(path).isEqualTo(policyEvents + 1);
            assertThat(eventCount(id, TicketEventKind.STAGE_CHANGED)).as(path).isEqualTo(stageEvents);
            assertThat(stageUpdatedAt(id)).as(path).isEqualTo(stageUpdatedAt);
            assertThat(lastTouched(id)).as(path).isEqualTo(lastTouched);
        }
    }

    /** Review round 2, M-2 (BM8): the OWNER re-dating an already-paused deal to a past Bangkok date is refused 400 (the
     * permission check passes first), and the stored date is unchanged — in both pause states. */
    @Test
    void theOwnerCannotReDateAPausedDealIntoThePast() throws Exception {
        for (String path : List.of("hold", "dormant")) {
            long id = deal(salesActor);
            LocalDate on = today().plusDays(7);
            ok(post("/api/tickets/" + id + "/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseReturnOn\":\"" + on + "\"}"), salesActor);

            assertThat(status(post("/api/tickets/" + id + "/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pauseReturnOn\":\"" + today().minusDays(1) + "\"}"), salesActor)).as(path).isEqualTo(400);
            assertThat(summary(id).pauseReturnOn()).as(path).isEqualTo(on);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static LocalDate today() {
        return LocalDate.now(BANGKOK);
    }

    /** A short-form create body WITH a channel (ruling 1), unless the caller states one itself. */
    private String body(String extra) {
        String channel = extra.contains("\"entryChannel\"") ? "" : ",\"entryChannel\":\"DESIGNER_LED\"";
        return "{\"title\":\"โครงการสั้น\",\"customerId\":%d,\"customerName\":\"%s\",\"projectId\":%d%s%s}"
            .formatted(customer.id(), customer.name(), projectId, channel, extra);
    }

    private long created(String extra) throws Exception {
        return ok(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body(extra)), salesActor)
            .at("/ticket/summary/id").asLong();
    }

    private Object stageUpdatedAt(long id) {
        return jdbc.queryForObject("SELECT stage_updated_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", id), java.sql.Timestamp.class);
    }

    /** The instant the shared quiet rule (QuietRule#lastTouchedSql) reads as this deal's last touch. */
    private Object lastTouched(long id) {
        return jdbc.queryForObject("SELECT " + QuietRule.lastTouchedSql("t") + " FROM sales.ticket t WHERE t.ticket_id = :id",
            Map.of("id", id), java.sql.Timestamp.class);
    }

    /** Notifications about this deal (every lifecycle notice links to /tickets/{id}). */
    private int noticeCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE link = :link",
            Map.of("link", "/tickets/" + id), Integer.class);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
            SELECT entry_channel, sales_stage, designer_name, imported_value, estimated_value, cancel_reason, lifecycle
              FROM sales.ticket WHERE ticket_id = :id
            """, Map.of("id", id));
    }

    private int dealCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket", Map.of(), Integer.class);
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
