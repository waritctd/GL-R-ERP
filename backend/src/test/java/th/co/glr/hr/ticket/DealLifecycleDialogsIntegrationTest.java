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
 * service, repository and Postgres (V220). Written wrong-way-round where a rule says who may do something: the short
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

    /** Wrong-way-round: the estimate is editable by exactly whoever may edit the tracking info today. */
    @Test
    void anotherRepCannotEditTheEstimate() throws Exception {
        long id = created(",\"estimatedValue\":500000");

        assertThat(status(put("/api/tickets/" + id + "/tracking").contentType(MediaType.APPLICATION_JSON)
            .content("{\"estimatedValue\":1}"), otherSalesActor)).isEqualTo(403);
        assertThat((BigDecimal) row(id).get("estimated_value")).isEqualByComparingTo("500000");
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

    @Test
    void aNewCancelReasonIsStoredThroughTheWidenedCheckConstraint() throws Exception {
        long id = deal(salesActor);

        ok(post("/api/tickets/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"DUPLICATE_DEAL\",\"note\":\"ซ้ำกับ PR-2026-0028\"}"), salesActor);

        assertThat(row(id).get("cancel_reason")).isEqualTo("DUPLICATE_DEAL");
        assertThat(row(id).get("lifecycle")).isEqualTo(DealLifecycle.CANCELLED);
    }

    @Test
    void anotherRepStillCannotCancel_withANewReason() throws Exception {
        long id = deal(salesActor);

        assertThat(status(post("/api/tickets/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"NO_REAL_PROJECT\"}"), otherSalesActor)).isEqualTo(403);
        assertThat(row(id).get("cancel_reason")).isNull();
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
