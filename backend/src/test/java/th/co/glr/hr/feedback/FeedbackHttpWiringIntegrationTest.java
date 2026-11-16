package th.co.glr.hr.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** The HTTP edge of /api/feedback with the real service and a real DB: a forged body can not set identity or status,
 * a missing session is 401, and the admin-only routes answer 403 to everyone else. */
class FeedbackHttpWiringIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MockMvc mvc;
    private long rep;
    private long other;
    private long admin;

    @BeforeEach
    void wire() {
        NotificationEmailService email = new NotificationEmailService(
            Mockito.mock(Mailer.class), new BrandAssets(), "", "", "https://glr-hub.com");
        FeedbackService service = transactional(new FeedbackService(new FeedbackRepository(jdbc),
            new EmployeeAuthRepository(jdbc),
            new NotificationService(new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), email)));
        mvc = MockMvcBuilders.standaloneSetup(new FeedbackController(service, new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        long sales = division("SA", "ฝ่ายขาย");
        long exec = division("MD", "ผู้บริหาร");
        rep = employee("HW-REP", sales, false);
        other = employee("HW-OTH", exec, false);
        admin = employee("HW-ADM", exec, true);
    }

    @Test
    void forgedIdentityAndStatusInTheBodyAreIgnored() throws Exception {
        String body = """
            {"kind":"BUG","area":"QUOTATIONS","details":"กดพิมพ์ใบเสนอราคาแล้วตารางถูกตัด","impact":"LOW",
             "reporterEmployeeId":%d,"reporterName":"ปลอม","reporterRole":"ceo","reporterDepartment":"ปลอม",
             "role":"ceo","department":"ปลอม","status":"RELEASED","eta":"2026-12-01","decidedBy":%d}
            """.formatted(admin, admin);

        JsonNode saved = JSON.readTree(mvc.perform(post("/api/feedback").session(session(rep, "sales"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(saved.get("reporterEmployeeId").asLong()).isEqualTo(rep);
        assertThat(saved.get("reporterRole").asText()).isEqualTo("sales");
        assertThat(saved.get("reporterName").asText()).isEqualTo("ผู้ทดสอบ " + rep);
        assertThat(saved.get("reporterDepartment").asText()).isEqualTo("ฝ่ายขาย");
        assertThat(saved.get("status").asText()).isEqualTo("PENDING");
        assertThat(saved.get("eta").isNull()).isTrue();
    }

    @Test
    void noSessionIs401OnEveryRoute() throws Exception {
        mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/feedback/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/feedback")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoutesAre403ForEveryoneButTheAdmin_andMineIsOwnOnly() throws Exception {
        long id = submitAs(rep, "sales");
        submitAs(other, "ceo");

        mvc.perform(get("/api/feedback").session(session(rep, "sales"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/feedback").session(session(other, "ceo"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/feedback/" + id + "/decision").session(session(rep, "sales"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPT\",\"eta\":\"2026-12-05\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/feedback/" + id).session(session(other, "ceo"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RELEASED\"}"))
            .andExpect(status().isForbidden());

        JsonNode mine = JSON.readTree(mvc.perform(get("/api/feedback/mine").session(session(rep, "sales")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("id").asLong()).isEqualTo(id);
        JsonNode all = JSON.readTree(mvc.perform(get("/api/feedback").session(session(admin, "employee")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(all).hasSize(2);
    }

    @Test
    void adminDecidesThenUpdatesOverHttp() throws Exception {
        long id = submitAs(rep, "sales");

        mvc.perform(post("/api/feedback/" + id + "/decision").session(session(admin, "employee"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPT\",\"eta\":\"2026-12-05\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/feedback/" + id + "/decision").session(session(admin, "employee"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"DENY\",\"reason\":\"เปลี่ยนใจแล้ว\"}"))
            .andExpect(status().isConflict());
        JsonNode updated = JSON.readTree(mvc.perform(patch("/api/feedback/" + id).session(session(admin, "employee"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"TESTING\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(updated.get("status").asText()).isEqualTo("TESTING");
        assertThat(updated.get("eta").asText()).isEqualTo("2026-12-05");
    }

    private long submitAs(long employeeId, String role) throws Exception {
        String body = "{\"kind\":\"FEATURE\",\"area\":\"CATALOG\",\"details\":\"อยากให้แคตตาล็อกกรองตามโรงงานได้\"}";
        return JSON.readTree(mvc.perform(post("/api/feedback").session(session(employeeId, role))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private static MockHttpSession session(long employeeId, String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, new UserPrincipal(employeeId,
            "e" + employeeId + "@glr.co.th", "ผู้ทดสอบ " + employeeId, role, employeeId, true,
            LocalDate.of(2026, 1, 1), false, null, false));
        return session;
    }

    private long division(String code, String name) {
        return jdbc.queryForObject("INSERT INTO hr.division (source_code, name_th) VALUES (:c, :n) RETURNING division_id",
            Map.of("c", code, "n", name), Long.class);
    }

    private long employee(String code, long divisionId, boolean isAdmin) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, email, division_id, is_active, is_admin)
            VALUES (:code, :code, :email, :d, TRUE, :a) RETURNING employee_id
            """, Map.of("code", code, "email", code.toLowerCase() + "@glr.co.th", "d", divisionId, "a", isAdmin), Long.class);
    }
}
