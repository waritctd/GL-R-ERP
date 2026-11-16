package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

    @Test
    void shortForm_withNoChannel_createsAnS1DealStoringTheDrawnFields() throws Exception {
        JsonNode created = ok(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content("""
            {"title":"โครงการบ้านสวน","customerId":%d,"customerName":"%s","projectId":%d,
             "designerName":"เทพสตูดิโอ ดีไซน์","estimatedValue":1200000}
            """.formatted(customer.id(), customer.name(), projectId)), salesActor);

        long id = created.at("/ticket/summary/id").asLong();
        Map<String, Object> row = row(id);
        assertThat(row.get("entry_channel")).isEqualTo(EntryChannel.UNSPECIFIED);
        assertThat(row.get("sales_stage")).isEqualTo(DealStage.LEAD_APPROACH);
        assertThat(row.get("designer_name")).isEqualTo("เทพสตูดิโอ ดีไซน์");
        assertThat((BigDecimal) row.get("imported_value")).isEqualByComparingTo("1200000");
        assertThat(eventCount(id, TicketEventKind.STAGE_CHANGED)).isZero();
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
            projectId, null, null, null, List.of(), null, null, null, null, null, DealStage.SPEC_APPROVED);

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

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static LocalDate today() {
        return LocalDate.now(BANGKOK);
    }

    private String body(String extra) {
        return "{\"title\":\"โครงการสั้น\",\"customerId\":%d,\"customerName\":\"%s\",\"projectId\":%d%s}"
            .formatted(customer.id(), customer.name(), projectId, extra);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("""
            SELECT entry_channel, sales_stage, designer_name, imported_value, cancel_reason, lifecycle
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
