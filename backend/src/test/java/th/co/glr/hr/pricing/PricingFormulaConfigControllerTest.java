package th.co.glr.hr.pricing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;

/** BRANCH 1 -- config storage + CEO editing UI only. This is a NEW endpoint (not a change to
 * /api/price-calc-configs); its read gate mirrors that endpoint's #388 precedent (ceo/import may read the cost
 * model, everyone else denied), and writes stay CEO-only. */
class PricingFormulaConfigControllerTest {
    private static final List<String> DENIED_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account");

    private final PricingFormulaConfigRepository formulaConfigs = mock(PricingFormulaConfigRepository.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new PricingFormulaConfigController(formulaConfigs, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void getIsForbiddenForNonCostingRoles() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(sampleConfig()));
        for (String role : DENIED_ROLES) {
            mvc.perform(get("/api/pricing-formula-config").session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void getIsAllowedForCostingRoles() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(sampleConfig()));
        for (String role : List.of("ceo", "import")) {
            mvc.perform(get("/api/pricing-formula-config").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void rejectsOverlappingFreightQuantityBandsForTheSameCountryAndThickness() throws Exception {
        String body = """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": 0.2,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 60000},
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 50, "qtyMaxSqm": 200, "amountThb": 60000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3}
             ],
             "clearanceFees": [
               {"qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 8000}
             ]}
            """;
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    // Issue #436 -- add / remove a freight row. Decision-level cases only; the enforcement evidence (that the
    // guard runs BEFORE the SQL, and that the row really does not land) is in
    // PricingFormulaConfigFreightRowIntegrationTest against real Postgres, per CLAUDE.md.

    private PricingFormulaConfigDto sampleConfig() {
        return new PricingFormulaConfigDto(
            1L, 1,
            new BigDecimal("1.15"), new BigDecimal("0.0045"), new BigDecimal("1.07"),
            new BigDecimal("1.07"), new BigDecimal("1.07"), new BigDecimal("0.2"),
            new BigDecimal("10"), true, LocalDate.of(2026, 1, 1), Instant.now(),
            List.of(), List.of(), List.of(), List.of());
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
