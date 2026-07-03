package th.co.glr.hr.attendance;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

class AttendanceControllerTest {
    private final AttendanceService attendanceService = mock(AttendanceService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new AttendanceController(attendanceService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .setValidator(validator())
        .build();

    @Test
    void acceptsAgentPunchPayload() throws Exception {
        when(attendanceService.receivePunch(org.mockito.ArgumentMatchers.any(AttendancePunchRequest.class), eq("secret")))
            .thenReturn(new AttendancePunchResponse(42L, true, "inserted"));

        mvc.perform(post("/api/attendance/punch")
                .header(AttendanceController.AGENT_TOKEN_HEADER, "secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "site_code": "SHOWROOM",
                      "device_code": "SHOWROOM_SC700",
                      "badge_code": "10012",
                      "punch_time": "2026-06-28T08:15:30+07:00",
                      "device_status": 1,
                      "punch_state": 0,
                      "punch_source": "BIOMETRIC",
                      "ingest_method": "LIVE_CAPTURE",
                      "raw_payload": {"user_id": "10012"}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.punch_id").value(42))
            .andExpect(jsonPath("$.inserted").value(true))
            .andExpect(jsonPath("$.status").value("inserted"));

        verify(attendanceService).receivePunch(org.mockito.ArgumentMatchers.any(AttendancePunchRequest.class), eq("secret"));
    }

    @Test
    void rejectsInvalidSiteCodeBeforeServiceCall() throws Exception {
        mvc.perform(post("/api/attendance/punch")
                .header(AttendanceController.AGENT_TOKEN_HEADER, "secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "site_code": "showroom",
                      "device_code": "SHOWROOM_SC700",
                      "badge_code": "10012",
                      "punch_time": "2026-06-28T08:15:30+07:00"
                    }
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(attendanceService);
    }

    @Test
    void allowsHrToImportDatFile() throws Exception {
        when(attendanceService.importDatFile(org.mockito.ArgumentMatchers.any(AttendanceDatImportRequest.class), org.mockito.ArgumentMatchers.any(UserPrincipal.class)))
            .thenReturn(new AttendanceImportResponse(7L, "imported", 2, 2, 0, 0));

        mvc.perform(post("/api/attendance/imports/dat")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "site_code": "SHOWROOM",
                      "device_code": "SHOWROOM_SC700",
                      "file_name": "1_attlog.dat",
                      "content": "10012\\t2020-11-02 10:33:55\\t1\\t0\\t0\\t0\\r\\n"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.import_id").value(7))
            .andExpect(jsonPath("$.status").value("imported"))
            .andExpect(jsonPath("$.row_count").value(2));
    }

    @Test
    void allowsCeoToImportDatFile() throws Exception {
        when(attendanceService.importDatFile(org.mockito.ArgumentMatchers.any(AttendanceDatImportRequest.class), org.mockito.ArgumentMatchers.any(UserPrincipal.class)))
            .thenReturn(new AttendanceImportResponse(8L, "imported", 1, 1, 0, 0));

        mvc.perform(post("/api/attendance/imports/dat")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "site_code": "SHOWROOM",
                      "device_code": "SHOWROOM_SC700",
                      "file_name": "1_attlog.dat",
                      "content": "10012\\t2020-11-02 10:33:55\\t1\\t0\\t0\\t0\\r\\n"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.import_id").value(8));
    }

    @Test
    void forbidsEmployeesFromImportingDatFile() throws Exception {
        mvc.perform(post("/api/attendance/imports/dat")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "site_code": "SHOWROOM",
                      "device_code": "SHOWROOM_SC700",
                      "file_name": "1_attlog.dat",
                      "content": "10012\\t2020-11-02 10:33:55\\t1\\t0\\t0\\t0\\r\\n"
                    }
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void requiresAuthenticationForPunchHistory() throws Exception {
        mvc.perform(get("/api/attendance/punches"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsHrToRotateDeviceAgentToken() throws Exception {
        when(attendanceService.rotateDeviceToken(eq("SHOWROOM_SC700")))
            .thenReturn(new RotateAgentTokenResponse("SHOWROOM_SC700", "a".repeat(64),
                java.time.OffsetDateTime.parse("2026-07-01T10:00:00+07:00")));

        mvc.perform(post("/api/attendance/devices/SHOWROOM_SC700/agent-token")
                .session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.device_code").value("SHOWROOM_SC700"))
            .andExpect(jsonPath("$.agent_token").value("a".repeat(64)));

        verify(attendanceService).rotateDeviceToken(eq("SHOWROOM_SC700"));
    }

    @Test
    void forbidsEmployeesFromRotatingDeviceAgentToken() throws Exception {
        mvc.perform(post("/api/attendance/devices/SHOWROOM_SC700/agent-token")
                .session(sessionFor("employee")))
            .andExpect(status().isForbidden());

        verifyNoInteractions(attendanceService);
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 10L, true, LocalDate.now(), false, null, false));
        return session;
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
