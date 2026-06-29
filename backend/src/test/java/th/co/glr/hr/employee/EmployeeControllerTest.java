package th.co.glr.hr.employee;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

class EmployeeControllerTest {
    private final EmployeeService employeeService = mock(EmployeeService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new EmployeeController(employeeService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .setValidator(validator())
        .build();

    @Test
    void requiresAuthenticationForEmployeeDetails() throws Exception {
        mvc.perform(get("/api/employees/10"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Not authenticated"));

        verifyNoInteractions(employeeService);
    }

    @Test
    void forbidsEmployeeRoleFromListingAllEmployees() throws Exception {
        mvc.perform(get("/api/employees").session(sessionFor("employee")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Forbidden"));

        verifyNoInteractions(employeeService);
    }

    @Test
    void validatesEmployeePayloadBeforeCreate() throws Exception {
        mvc.perform(post("/api/employees")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nameTh\":\"ทดสอบ\",\"email\":\"not-an-email\"}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(employeeService);
    }

    @Test
    void mapsActiveQueryParameterForAuthorizedListRequests() throws Exception {
        EmployeeFilter filter = new EmployeeFilter(null, null, null, null, Boolean.TRUE);
        when(employeeService.list(filter)).thenReturn(List.of());

        mvc.perform(get("/api/employees?active=true").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.employees").isArray());

        verify(employeeService).list(filter);
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 10L, true, LocalDate.now(), false));
        return session;
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
