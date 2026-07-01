package th.co.glr.hr.profile;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

class ProfileRequestControllerTest {
    private final ProfileRequestService profileRequestService = mock(ProfileRequestService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ProfileRequestController(profileRequestService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .setValidator(validator())
        .build();

    @Test
    void requiresAuthenticationForProfileRequestList() throws Exception {
        mvc.perform(get("/api/profile-requests"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Not authenticated"));

        verifyNoInteractions(profileRequestService);
    }

    @Test
    void validatesSupportedProfileFieldsBeforeCreate() throws Exception {
        mvc.perform(post("/api/profile-requests")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fieldKey\":\"salary\",\"fieldLabel\":\"Salary\",\"newValue\":\"100000\"}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(profileRequestService);
    }

    @Test
    void forbidsEmployeesFromReviewingProfileRequests() throws Exception {
        mvc.perform(patch("/api/profile-requests/99")
                .session(sessionFor("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"approved\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Forbidden"));

        verifyNoInteractions(profileRequestService);
    }

    @Test
    void validatesReviewStatusValues() throws Exception {
        mvc.perform(patch("/api/profile-requests/99")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"pending\"}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(profileRequestService);
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
