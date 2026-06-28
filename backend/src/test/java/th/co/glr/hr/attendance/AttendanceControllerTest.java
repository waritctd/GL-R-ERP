package th.co.glr.hr.attendance;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import th.co.glr.hr.common.ApiExceptionHandler;

class AttendanceControllerTest {
    private final AttendanceService attendanceService = mock(AttendanceService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new AttendanceController(attendanceService))
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

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
