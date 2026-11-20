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
}
