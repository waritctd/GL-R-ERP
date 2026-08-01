package th.co.glr.hr.pricing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Issue #388 — {@code GET /api/fx-rates} gated on authentication alone, so any session could read
 * the FX rates that convert a factory's foreign-currency quote into a THB landed cost.
 *
 * <p>This class proves the DECISION (which branch the role check takes).
 * {@code CatalogPricingReadAuthzIntegrationTest} proves the ENFORCEMENT on real Postgres through the
 * real repository — Mockito cannot reach that. Cases are written the wrong way round: the
 * load-bearing assertions are that a caller with no costing business gets 403.
 */
class FxRateControllerTest {
    // Everyone outside PricingCostingService.RAW_COSTING_ROLES. sales/sales_manager/account are in
    // here deliberately: a rep works from the approved selling price, never the cost side.
    private static final List<String> DENIED_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account");

    private final FxRateRepository fxRates = mock(FxRateRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new FxRateController(fxRates, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void listIsForbiddenForNonCostingRoles() throws Exception {
        when(fxRates.findAll()).thenReturn(List.of());
        for (String role : DENIED_ROLES) {
            mvc.perform(get("/api/fx-rates").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void listIsAllowedForCostingRoles() throws Exception {
        when(fxRates.findAll()).thenReturn(List.of());
        for (String role : List.of("ceo", "import")) {
            mvc.perform(get("/api/fx-rates").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void listIsUnauthorizedWithoutASession() throws Exception {
        mvc.perform(get("/api/fx-rates")).andExpect(status().isUnauthorized());
    }

    /** The read widening must not leak into the write: upsert stays CEO-only, unchanged. */
    @Test
    void importCannotUpsertARate() throws Exception {
        mvc.perform(put("/api/fx-rates/EUR")
                .session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"rateToThb": 38.5, "effectiveDate": "2026-08-01"}
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
