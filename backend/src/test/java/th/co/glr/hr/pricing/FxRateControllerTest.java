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
 * Owner ruling 2026-08-02 — {@code GET /api/fx-rates} widened from issue #388's
 * {@code ceo}/{@code import} gate by exactly one role, to {@code sales}, so the deal-create modal's
 * "ราคาตั้ง (ประมาณการ)" estimate can convert a catalog price to THB for a plain {@code sales}
 * session. It was NOT opened to every authenticated session — the owner's words were "should only
 * be to sale". See {@link FxRateController#READ_ROLES}'s javadoc for the full reasoning, including
 * why this does NOT relax {@code PriceCalcConfigController}.
 *
 * <p>This class proves the DECISION (which branch the role check takes).
 * {@code CatalogPricingReadAuthzIntegrationTest} proves the ENFORCEMENT on real Postgres through the
 * real repository — Mockito cannot reach that. Cases are written the wrong way round where it
 * matters: the write gate must NOT have widened along with the read, and the read must still DENY
 * everyone outside the ruling. That deny case is the one that catches a future re-widening; a
 * suite that only asserts "sales can read" stays green with the gate wide open.
 */
class FxRateControllerTest {
    /** The whole of the 2026-08-02 ruling. Anything added here is a new ruling, not a refactor. */
    private static final List<String> READ_ALLOWED = List.of("ceo", "import", "sales");

    /**
     * Everyone else. {@code sales_manager} belongs here deliberately: it browses the deal pipeline
     * but cannot create a deal ({@code canCreateTickets} is {@code ['sales']} and
     * {@code TicketService.create} gates on {@code Set.of("sales")}), so it never reaches the modal
     * that needs the rate.
     */
    private static final List<String> READ_DENIED =
        List.of("employee", "warehouse", "qc", "hr", "sales_manager", "account");

    private final FxRateRepository fxRates = mock(FxRateRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new FxRateController(fxRates, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void listIsAllowedForTheRuledReadRoles() throws Exception {
        when(fxRates.findAll()).thenReturn(List.of());
        for (String role : READ_ALLOWED) {
            mvc.perform(get("/api/fx-rates").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    /** The case that catches a re-widening. Wrong-way-round on purpose. */
    @Test
    void listIsForbiddenForEveryRoleOutsideTheRuling() throws Exception {
        when(fxRates.findAll()).thenReturn(List.of());
        for (String role : READ_DENIED) {
            mvc.perform(get("/api/fx-rates").session(session(role)))
                .andExpect(status().isForbidden());
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

    /** Same guard, from the role the read widening was specifically for — a plain sales rep. */
    @Test
    void salesCannotUpsertARate() throws Exception {
        mvc.perform(put("/api/fx-rates/EUR")
                .session(session("sales"))
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
