package th.co.glr.hr.factory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * Issue #388 — {@code GET /api/factory-configs} gated on authentication alone, so any session could
 * read the supplier directory (who the company buys from, the quoting email, the billing currency).
 *
 * <p>This class proves the DECISION; {@code CatalogPricingReadAuthzIntegrationTest} proves the
 * ENFORCEMENT on real Postgres through the real repository. Cases are written the wrong way round.
 */
class FactoryConfigControllerTest {
    private static final List<String> DENIED_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account");

    private final FactoryConfigRepository repo = mock(FactoryConfigRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new FactoryConfigController(repo, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void listIsForbiddenForNonProcurementRoles() throws Exception {
        when(repo.findAll()).thenReturn(List.of());
        for (String role : DENIED_ROLES) {
            mvc.perform(get("/api/factory-configs").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void listIsAllowedForProcurementRoles() throws Exception {
        when(repo.findAll()).thenReturn(List.of());
        for (String role : List.of("ceo", "import")) {
            mvc.perform(get("/api/factory-configs").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void listIsUnauthorizedWithoutASession() throws Exception {
        mvc.perform(get("/api/factory-configs")).andExpect(status().isUnauthorized());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
