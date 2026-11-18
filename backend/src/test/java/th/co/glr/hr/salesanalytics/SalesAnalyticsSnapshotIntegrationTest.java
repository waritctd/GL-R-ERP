package th.co.glr.hr.salesanalytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * The sales-insights snapshot endpoints (V218), driven through the REAL controller, service, repository and
 * Postgres — the role gate, the payload validation and the "latest wins" read all have to survive into SQL, which a
 * Mockito repository cannot show. Roles per the agreed design: GET = ceo + sales_manager; PUT = ceo only. Every
 * other role is listed on purpose, so a future widening fails here rather than leaking the company's internal
 * revenue figures. Fixtures are synthetic (Rep A / ลูกค้า ก): the repo is public.
 */
class SalesAnalyticsSnapshotIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String URL = "/api/sales-analytics/snapshot";
    private static final List<String> READ_DENIED = List.of("sales", "hr", "account", "import", "employee");
    private static final List<String> WRITE_DENIED = List.of("sales_manager", "sales", "hr", "account", "import", "employee");

    private MockMvc mvc;
    private long userId;

    @BeforeEach
    void wire() {
        SalesAnalyticsService service =
            new SalesAnalyticsService(new SalesAnalyticsRepository(jdbc), new ObjectMapper(),
                java.time.Clock.fixed(java.time.Instant.parse("2026-11-17T03:00:00Z"), java.time.ZoneId.of("Asia/Bangkok")));
        mvc = MockMvcBuilders.standaloneSetup(new SalesAnalyticsController(service, new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        // uploaded_by references an employee, so the session user must be a real row.
        userId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th, hire_date, is_active)
            VALUES ('CEO-001', 'CEO-001', 'ทดสอบ', 'ซีอีโอ', :d, TRUE) RETURNING employee_id
            """, Map.of("d", LocalDate.of(2020, 1, 1)), Long.class);
    }

    /** A minimal valid snapshot: 2 months, one rep. {@code marker} lets a test tell two snapshots apart. */
    private static String snapshot(String marker) {
        return """
            {"months":["ม.ค. 69","ก.พ. 69"],"monthly_total":[100,200],
             "monthly":[{"total":100,"reps":[{"rep":"Rep A","rev":100}],"topc":[{"name":"ลูกค้า ก","amt":100}]},
                        {"total":200,"reps":[{"rep":"Rep A","rev":200}],"topc":[{"name":"ลูกค้า ข","amt":200}]}],
             "reps":[{"rep":"Rep A","rev":300}],"marker":"%s"}
            """.formatted(marker);
    }

    /** snapshot("x") with the given top-level keys overwritten. */
    private static String mutated(String overrides) throws Exception {
        ObjectMapper om = new ObjectMapper();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(snapshot("x"));
        node.setAll((com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(overrides));
        return om.writeValueAsString(node);
    }

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(userId, role + "@glr.co.th", "Test User", role, userId,
                true, LocalDate.of(2020, 1, 1), false, userId, false));
        return s;
    }

    private void putAs(String role, String body, int expectedStatus) throws Exception {
        mvc.perform(put(URL).session(session(role)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expectedStatus));
    }

    @Test
    void getWithNoSnapshotAnswers200WithANullSnapshotForCeoAndSalesManager() throws Exception {
        // Empty convention (no existing precedent in the codebase): 200 + {"snapshot": null}, so the page renders its
        // empty state without treating a missing upload as an error.
        for (String role : List.of("ceo", "sales_manager")) {
            mvc.perform(get(URL).session(session(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot").value((Object) null));
        }
    }

    @Test
    void ceoPutStoresAndBothReadRolesGetItBack() throws Exception {
        putAs("ceo", snapshot("first"), 200);

        for (String role : List.of("ceo", "sales_manager")) {
            mvc.perform(get(URL).session(session(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.payload.marker").value("first"))
                .andExpect(jsonPath("$.snapshot.payload.months[1]").value("ก.พ. 69"))
                .andExpect(jsonPath("$.snapshot.uploadedAt").isNotEmpty());
        }
    }

    @Test
    void theLatestUploadWins() throws Exception {
        putAs("ceo", snapshot("older"), 200);
        putAs("ceo", snapshot("newer"), 200);

        mvc.perform(get(URL).session(session("ceo")))
            .andExpect(jsonPath("$.snapshot.payload.marker").value("newer"));
        // Older uploads are kept as history, not overwritten.
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM sales.sales_analytics_snapshot", Map.of(), Long.class))
            .isEqualTo(2L);
    }

    @Test
    void everyOtherRoleCannotReadTheSnapshotEvenWhenOneExists() throws Exception {
        putAs("ceo", snapshot("secret"), 200);
        for (String role : READ_DENIED) {
            mvc.perform(get(URL).session(session(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void everyRoleButCeoCannotUpload_andNothingIsStored() throws Exception {
        // sales_manager READS but must not WRITE: the case that catches the read gate leaking into the write gate.
        for (String role : WRITE_DENIED) {
            putAs(role, snapshot("nope"), 403);
        }
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM sales.sales_analytics_snapshot", Map.of(), Long.class))
            .isZero();
    }

    @Test
    void putRejectsAnythingThatIsNotAValidSnapshotObject() throws Exception {
        putAs("ceo", "[1,2,3]", 400);                       // not an object
        putAs("ceo", "\"just a string\"", 400);             // not an object
        putAs("ceo", "{not json", 400);                     // not JSON at all
        for (String missing : List.of("months", "monthly_total", "monthly", "reps")) {
            ObjectMapper om = new ObjectMapper();
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(snapshot("x"));
            node.remove(missing);
            putAs("ceo", om.writeValueAsString(node), 400); // each required key, one at a time
        }
        // months / monthly_total / monthly must be the same length.
        putAs("ceo", snapshot("x").replace("[100,200]", "[100,200,300]"), 400);
        putAs("ceo", snapshot("x").replace("\"months\":[\"ม.ค. 69\",\"ก.พ. 69\"]", "\"months\":[\"ม.ค. 69\"]"), 400);

        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM sales.sales_analytics_snapshot", Map.of(), Long.class))
            .as("a rejected payload must not be stored").isZero();
    }

    @Test
    void putRejectsASnapshotTheDashboardCouldNotRender() throws Exception {
        // zero months
        putAs("ceo", "{\"months\":[],\"monthly_total\":[],\"monthly\":[],\"reps\":[]}", 400);
        // monthly entries that are not objects with a numeric total and an array of reps
        putAs("ceo", mutated("{\"monthly\":[{},{}]}"), 400);
        putAs("ceo", mutated("{\"monthly\":[{\"total\":1,\"reps\":\"none\"},{\"total\":2,\"reps\":[]}]}"), 400);
        putAs("ceo", mutated("{\"monthly\":[{\"total\":\"1\",\"reps\":[]},{\"total\":2,\"reps\":[]}]}"), 400);
        // non-numeric monthly_total, non-string month label
        putAs("ceo", mutated("{\"monthly_total\":[100,\"abc\"]}"), 400);
        putAs("ceo", mutated("{\"months\":[5,\"ก.พ. 69\"]}"), 400);
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM sales.sales_analytics_snapshot", Map.of(), Long.class)).isZero();
    }

    @Test
    void putRejectsRowsThatAreNotObjectsWithAStringRep() throws Exception {
        putAs("ceo", mutated("{\"reps\":5}"), 400);
        putAs("ceo", mutated("{\"reps\":[null]}"), 400);
        putAs("ceo", mutated("{\"reps\":[1,2]}"), 400);
        putAs("ceo", mutated("{\"reps\":[{\"rev\":1}]}"), 400);
        putAs("ceo", mutated("{\"monthly\":[{\"total\":1,\"reps\":[null]},{\"total\":2,\"reps\":[]}]}"), 400);
        putAs("ceo", mutated("{\"monthly\":[{\"total\":1,\"reps\":[{\"rev\":1}]},{\"total\":2,\"reps\":[]}]}"), 400);
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM sales.sales_analytics_snapshot", Map.of(), Long.class)).isZero();
    }

    @Test
    void aCeoSessionWithNoEmployeeRecordGetsA400NotA500() throws Exception {
        // Same convention as AttendanceService: a user not linked to an employee row is refused with a Thai 400.
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(userId, "ceo@glr.co.th", "Test User", "ceo", null, true,
                LocalDate.of(2020, 1, 1), false, null, false));
        mvc.perform(put(URL).session(s).contentType(MediaType.APPLICATION_JSON).content(snapshot("x")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void aMissingAsOfIsStoredAsNullNotAnEmptyString() throws Exception {
        putAs("ceo", snapshot("x"), 200);
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT as_of FROM sales.sales_analytics_snapshot", Map.of(), String.class)).isNull();
    }

    @Test
    void aBodyFarOverTheLimitIsRefusedAndNothingIsStored() throws Exception {
        putAs("ceo", snapshot("x".repeat(6 * 1024 * 1024)), 400);
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM sales.sales_analytics_snapshot", Map.of(), Long.class)).isZero();
    }

    @Test
    void aBodyOfExactlyTheLimitIsAcceptedEvenWithMultiByteText() throws Exception {
        // The cap counts BYTES: pad with Thai (3 bytes/char) plus ASCII so the UTF-8 size is exactly 2 MiB.
        int max = 2 * 1024 * 1024;
        int pad = max - snapshot("").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String body = snapshot("ก".repeat(pad / 3) + "a".repeat(pad % 3));
        org.assertj.core.api.Assertions.assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isEqualTo(max);
        putAs("ceo", body, 200);
    }

    @Test
    void putRejectsAPayloadOverTwoMegabytes() throws Exception {
        String padding = "x".repeat(2 * 1024 * 1024 + 16);
        putAs("ceo", snapshot(padding), 400);
        putAs("ceo", snapshot("small"), 200); // and the limit is not a blanket refusal
    }

    @Test
    void putRejectsJsonThatJacksonAcceptsButPostgresJsonbWouldNot_andNothingIsStored() throws Exception {
        // A \u0000 escape is legal JSON, but jsonb cannot hold NUL: that must be a 400, not a 500.
        putAs("ceo", snapshot("a\\u0000b"), 400);
        // A complete document followed by more text is not one JSON document.
        putAs("ceo", snapshot("x") + " {\"extra\":1}", 400);
        putAs("ceo", snapshot("x") + " garbage", 400);
        org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM sales.sales_analytics_snapshot", Map.of(), Long.class)).isZero();
    }

    // ---- GET /api/sales-analytics/metrics (analytics Phase 2): read-only view of analytics.metric_weekly + run_log ----

    private static final String METRICS_URL = "/api/sales-analytics/metrics";
    private static final LocalDate W1 = LocalDate.of(2026, 11, 2);
    private static final LocalDate W2 = LocalDate.of(2026, 11, 9);

    private void metric(LocalDate week, String key, String dimension, Double value) {
        jdbc.update("INSERT INTO analytics.metric_weekly (week_start, metric_key, dimension, value, computed_at)"
            + " VALUES (:w, :k, :d, :v, TIMESTAMPTZ '2026-11-10 01:00:00+07')",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("w", week).addValue("k", key)
                .addValue("d", dimension).addValue("v", value, java.sql.Types.NUMERIC));
    }

    private void check(LocalDate week, String key, boolean passed, String detail) {
        jdbc.update("INSERT INTO analytics.run_log (week_start, check_key, passed, detail) VALUES (:w, :k, :p, :d)",
            Map.of("w", week, "k", key, "p", passed, "d", detail));
    }

    @Test
    void metricsGateIsCeoAndSalesManagerOnly() throws Exception {
        for (String role : List.of("ceo", "sales_manager")) {
            mvc.perform(get(METRICS_URL).session(session(role))).andExpect(status().isOk());
        }
        for (String role : READ_DENIED) {
            mvc.perform(get(METRICS_URL).session(session(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(METRICS_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void metricsWithNoRowsAnswer200WithNullsAndEmptyLists() throws Exception {
        mvc.perform(get(METRICS_URL).session(session("ceo")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weekStart").value((Object) null))
            .andExpect(jsonPath("$.computedAt").value((Object) null))
            .andExpect(jsonPath("$.metrics").isEmpty())
            .andExpect(jsonPath("$.checks").isEmpty());
    }

    @Test
    void metricsServeTheLatestWeekThatHasThePipelineSnapshot_withPreviousFilledOrNull() throws Exception {
        metric(W1, "pipeline_value_dedup", "", 1000.0);
        metric(W1, "pipeline_value_raw", "", 1500.0);
        metric(W2, "pipeline_value_dedup", "", 1200.0);
        metric(W2, "pipeline_value_raw", "", 1800.0);
        metric(W2, "quoted_value", "brand=Brand X", 700.0);          // no prior-week row -> previous null
        // A later week with only the week-based rep metrics (no pipeline snapshot) must NOT become the headline week.
        metric(LocalDate.of(2026, 11, 16), "rep_activities_week", "rep=999", 3.0);

        mvc.perform(get(METRICS_URL).session(session("sales_manager")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weekStart").value("2026-11-09"))
            .andExpect(jsonPath("$.computedAt").isNotEmpty())
            .andExpect(jsonPath("$.metrics.length()").value(3))
            .andExpect(jsonPath("$.metrics[?(@.key=='pipeline_value_dedup')].value").value(1200.0))
            .andExpect(jsonPath("$.metrics[?(@.key=='pipeline_value_dedup')].previous").value(1000.0))
            .andExpect(jsonPath("$.metrics[?(@.key=='pipeline_value_dedup')].label").value(""))
            .andExpect(jsonPath("$.metrics[?(@.key=='quoted_value')].label").value("Brand X"))
            .andExpect(jsonPath("$.metrics[?(@.key=='quoted_value')].dimension").value("brand=Brand X"))
            .andExpect(jsonPath("$.metrics[?(@.key=='quoted_value')].previous").value((Object) null));
    }

    @Test
    void theWeekInProgressInBangkokIsNeverServed_theLastEndedWeekIs() throws Exception {
        // Clock is Tue 2026-11-17 Bangkok: the week of Mon 11-16 has not ended, even though a hand-run wrote a snapshot for it.
        metric(W2, "pipeline_value_dedup", "", 1200.0);
        metric(LocalDate.of(2026, 11, 16), "pipeline_value_dedup", "", 9999.0);
        mvc.perform(get(METRICS_URL).session(session("ceo")))
            .andExpect(jsonPath("$.weekStart").value("2026-11-09"))
            .andExpect(jsonPath("$.metrics[?(@.key=='pipeline_value_dedup')].value").value(1200.0));
    }

    @Test
    void aRepDimensionIsLabelledWithTheEmployeeNameAndFallsBackToTheDimensionForAnUnknownRep() throws Exception {
        long rep = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th, hire_date, is_active)
            VALUES ('REP-001', 'REP-001', 'สมมติ', 'ตัวอย่าง', :d, TRUE) RETURNING employee_id
            """, Map.of("d", LocalDate.of(2020, 1, 1)), Long.class);
        metric(W2, "pipeline_value_dedup", "", 1200.0);
        metric(W2, "rep_activities_week", "rep=" + rep, 5.0);
        metric(W2, "rep_activities_week", "rep=424242", 2.0);

        mvc.perform(get(METRICS_URL).session(session("ceo")))
            .andExpect(jsonPath("$.metrics[?(@.dimension=='rep=" + rep + "')].label").value("สมมติ ตัวอย่าง"))
            .andExpect(jsonPath("$.metrics[?(@.dimension=='rep=424242')].label").value("rep=424242"));
    }

    @Test
    void checksAreOnlyThoseRecordedForTheServedWeek_latestRunPerCheck() throws Exception {
        metric(W1, "pipeline_value_dedup", "", 1000.0);
        metric(W2, "pipeline_value_dedup", "", 1200.0);
        check(W1, "stage_mapped", false, "unmapped tickets=9");        // other week: must not appear
        check(W2, "stage_mapped", false, "unmapped tickets=2");        // superseded by the re-run below
        check(W2, "stage_mapped", true, "unmapped tickets=0");
        check(W2, "quote_line_brand_present", false, "lines with no brand=4");

        mvc.perform(get(METRICS_URL).session(session("ceo")))
            .andExpect(jsonPath("$.checks.length()").value(2))
            .andExpect(jsonPath("$.checks[?(@.key=='stage_mapped')].passed").value(true))
            .andExpect(jsonPath("$.checks[?(@.key=='stage_mapped')].detail").value("unmapped tickets=0"))
            .andExpect(jsonPath("$.checks[?(@.key=='quote_line_brand_present')].passed").value(false))
            .andExpect(jsonPath("$.checks[?(@.key=='quote_line_brand_present')].detail").value("lines with no brand=4"));
    }
}
