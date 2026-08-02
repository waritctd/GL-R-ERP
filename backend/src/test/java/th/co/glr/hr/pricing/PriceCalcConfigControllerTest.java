package th.co.glr.hr.pricing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * Issue #388 — {@code GET /api/price-calc-configs} gated on authentication alone, so any session
 * could read the margin policy itself ({@code marginPct}, {@code importDutyPct}, and every
 * landed-cost component).
 *
 * <p>This class proves the DECISION; {@code CatalogPricingReadAuthzIntegrationTest} proves the
 * ENFORCEMENT on real Postgres through the real repository. Cases are written the wrong way round.
 */
class PriceCalcConfigControllerTest {
    private static final List<String> DENIED_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account");

    private final PriceCalcConfigRepository priceConfigs = mock(PriceCalcConfigRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new PriceCalcConfigController(priceConfigs, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void listIsForbiddenForNonCostingRoles() throws Exception {
        when(priceConfigs.findCurrentConfigs()).thenReturn(List.of());
        for (String role : DENIED_ROLES) {
            mvc.perform(get("/api/price-calc-configs").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void listIsAllowedForCostingRoles() throws Exception {
        when(priceConfigs.findCurrentConfigs()).thenReturn(List.of());
        for (String role : List.of("ceo", "import")) {
            mvc.perform(get("/api/price-calc-configs").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void listIsUnauthorizedWithoutASession() throws Exception {
        mvc.perform(get("/api/price-calc-configs")).andExpect(status().isUnauthorized());
    }

    /** The read widening must not leak into the write: creating a new version stays CEO-only. */
    @Test
    void importCannotUpdateTheMarginPolicy() throws Exception {
        mvc.perform(post("/api/price-calc-configs")
                .session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"country": "IT", "freightPerSqm": 1, "insurancePerSqm": 1,
                     "inlandFactoryToPortPerSqm": 1, "inlandPortToWarehousePerSqm": 1,
                     "importDutyPct": 5, "marginPct": 30}
                    """))
            .andExpect(status().isForbidden());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
