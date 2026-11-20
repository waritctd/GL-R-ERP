package th.co.glr.hr.attendance;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    private final AttendanceMonthlySummaryService monthlySummaryService = mock(AttendanceMonthlySummaryService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new AttendanceController(attendanceService, monthlySummaryService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .setValidator(validator())
        .build();

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
