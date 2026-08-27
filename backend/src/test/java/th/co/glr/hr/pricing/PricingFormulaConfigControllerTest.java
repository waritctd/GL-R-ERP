package th.co.glr.hr.pricing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.CountryDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingClearanceFeeDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingDutyRateDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFreightRateDto;

/**
 * BRANCH 1 -- config storage + CEO editing UI only. This is a NEW endpoint (not a change to
 * {@code /api/price-calc-configs}); its read gate mirrors that endpoint's #388 precedent
 * (ceo/import may read the cost model, everyone else denied), and writes stay CEO-only. Cases are
 * written the wrong way round: denial is asserted for every non-costing role, not just presence
 * for the allowed ones.
 */
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
    void getIsUnauthorizedWithoutASession() throws Exception {
        mvc.perform(get("/api/pricing-formula-config")).andExpect(status().isUnauthorized());
    }

    @Test
    void getReturnsNotFoundWhenNoCurrentConfigExists() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.empty());
        mvc.perform(get("/api/pricing-formula-config").session(session("ceo")))
            .andExpect(status().isNotFound());
    }

    /** The read widening must not leak into the write: creating a new version stays CEO-only. */
    @Test
    void importCannotUpdateTheFormulaConfig() throws Exception {
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    void ceoCanCreateANewVersionWithAValidBody() throws Exception {
        when(formulaConfigs.createNewVersion(any(), anyLong())).thenReturn(sampleConfig());
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void rejectsAMarginOutOfTheZeroToOneRange() throws Exception {
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithMargin("1.5")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsANegativeClearanceFeeAmount() throws Exception {
        String body = """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": 0.2,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 60000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3}
             ],
             "clearanceFees": [
               {"qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": -5000}
             ]}
            """;
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
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

    @Test
    void rejectsOverlappingClearanceFeeQuantityBands() throws Exception {
        String body = """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": 0.2,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 60000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3}
             ],
             "clearanceFees": [
               {"qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 8000},
               {"qtyMinSqm": 90, "qtyMaxSqm": 200, "amountThb": 12000}
             ]}
            """;
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsADuplicateProductTypeInDutyRates() throws Exception {
        String body = """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": 0.2,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 60000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3},
               {"productType": "TILE", "productLabel": "กระเบื้อง 2", "dutyPct": 0.2}
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

    @Test
    void allowsAdjacentNonOverlappingFreightQuantityBands() throws Exception {
        when(formulaConfigs.createNewVersion(any(), anyLong())).thenReturn(sampleConfig());
        String body = """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": 0.2,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 60000},
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 101, "qtyMaxSqm": 450, "amountThb": 60000},
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 451, "qtyMaxSqm": null, "amountThb": 50000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3}
             ],
             "clearanceFees": [
               {"qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 8000},
               {"qtyMinSqm": 101, "qtyMaxSqm": null, "amountThb": 12000}
             ]}
            """;
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is2xxSuccessful());
    }

    /**
     * Regression test for the gap that let two DIFFERENT, overlapping thickness bands for the same
     * country save happily: the old validation grouped by (country, thickness) and only compared
     * quantity bands WITHIN an identical thickness group, so Italy [3,8) and Italy [5,10) -- which
     * never share a group key -- were never compared against each other at all, even though their
     * quantity bands also overlap. That leaves the freight lookup ambiguous for e.g. a 6mm-thick
     * order, which the country-level pairwise check now catches.
     */
    @Test
    void rejectsOverlappingThicknessBandsForTheSameCountry() throws Exception {
        String body = """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": 0.2,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "IT", "thicknessMinMm": 3, "thicknessMaxMm": 8,
                "qtyMinSqm": 1, "qtyMaxSqm": 101, "amountThb": 80000},
               {"originCountryCode": "IT", "thicknessMinMm": 5, "thicknessMaxMm": 10,
                "qtyMinSqm": 1, "qtyMaxSqm": 101, "amountThb": 85000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3}
             ],
             "clearanceFees": [
               {"qtyMinSqm": 1, "qtyMaxSqm": 101, "amountThb": 8000}
             ]}
            """;
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    /**
     * Guards against over-rejecting: adjacent HALF-OPEN thickness bands (e.g. [3,8) then [8,12)) do
     * not overlap even though their quantity bands are identical -- 8mm belongs to the second band
     * only. This mirrors the real V109 seed shape (contiguous thickness bands per country, each with
     * the same contiguous quantity bands), so it also guards against the fix over-rejecting the
     * legitimate seeded configuration.
     */
    @Test
    void allowsAdjacentNonOverlappingThicknessBandsWithTheSameQuantityBands() throws Exception {
        when(formulaConfigs.createNewVersion(any(), anyLong())).thenReturn(sampleConfig());
        String body = """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": 0.2,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "IT", "thicknessMinMm": 3, "thicknessMaxMm": 8,
                "qtyMinSqm": 1, "qtyMaxSqm": 101, "amountThb": 80000},
               {"originCountryCode": "IT", "thicknessMinMm": 3, "thicknessMaxMm": 8,
                "qtyMinSqm": 101, "qtyMaxSqm": 451, "amountThb": 90000},
               {"originCountryCode": "IT", "thicknessMinMm": 8, "thicknessMaxMm": 12,
                "qtyMinSqm": 1, "qtyMaxSqm": 101, "amountThb": 50000},
               {"originCountryCode": "IT", "thicknessMinMm": 8, "thicknessMaxMm": 12,
                "qtyMinSqm": 101, "qtyMaxSqm": 451, "amountThb": 80000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3}
             ],
             "clearanceFees": [
               {"qtyMinSqm": 1, "qtyMaxSqm": 101, "amountThb": 8000},
               {"qtyMinSqm": 101, "qtyMaxSqm": null, "amountThb": 12000}
             ]}
            """;
        mvc.perform(post("/api/pricing-formula-config")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is2xxSuccessful());
    }

    // ============================================================================================
    // Issue #436 -- add / remove a freight row. Decision-level cases only; the enforcement evidence
    // (that the guard runs BEFORE the SQL, and that the row really does not land) is in
    // PricingFormulaConfigFreightRowIntegrationTest against real Postgres, per CLAUDE.md.
    // ============================================================================================

    /** The read gate must not leak into these new writes either -- {ceo} only, import included. */
    @Test
    void nonCeoRolesCannotAddOrDeleteAFreightRow() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(configWithItalyLadder()));
        for (String role : List.of("import", "employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account")) {
            mvc.perform(post("/api/pricing-formula-config/freight-rates")
                    .session(session(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(freightRowBody("TR", 3, 8, 1, "101", 70000)))
                .andExpect(status().isForbidden());
            mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", 11L).session(session(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void addAndDeleteAreUnauthorizedWithoutASession() throws Exception {
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(freightRowBody("TR", 3, 8, 1, "101", 70000)))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", 11L))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void ceoCanAddAFreightRowForANewCountry() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(configWithItalyLadder()));
        when(formulaConfigs.createNewVersion(any(), anyLong())).thenReturn(sampleConfig());
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(freightRowBody("TR", 3, 8, 1, "101", 70000)))
            .andExpect(status().is2xxSuccessful());
    }

    /** Validation holds on INSERT, not just on the whole-config replace. */
    @Test
    void ceoCannotAddAFreightRowThatOverlapsAnExistingBand() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(configWithItalyLadder()));
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(freightRowBody("IT", 3, 8, 50, "200", 90000)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void addFreightRateIsNotFoundWhenThereIsNoCurrentConfig() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.empty());
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(freightRowBody("TR", 3, 8, 1, "101", 70000)))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteIsNotFoundForAnIdOutsideTheCurrentVersion() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(configWithItalyLadder()));
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", 9999L).session(session("ceo")))
            .andExpect(status().isNotFound());
    }

    /** {@code @NotEmpty} on the request record has to hold on this path too, which builds it by hand. */
    @Test
    void deleteRejectsRemovingTheLastRemainingFreightRow() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(configWithFreightRates(
            new PricingFreightRateDto(11L, "IT", "อิตาลี", new BigDecimal("3"), new BigDecimal("8"),
                new BigDecimal("1"), null, new BigDecimal("80000")))));
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", 11L).session(session("ceo")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ceoCanDeleteAnEdgeQuantityBand() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(configWithItalyLadder()));
        when(formulaConfigs.createNewVersion(any(), anyLong())).thenReturn(sampleConfig());
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", 13L).session(session("ceo")))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void ceoCannotDeleteAMiddleQuantityBand() throws Exception {
        when(formulaConfigs.findCurrent()).thenReturn(Optional.of(configWithItalyLadder()));
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", 12L).session(session("ceo")))
            .andExpect(status().isBadRequest());
    }

    /** Three contiguous [1,101)/[101,451)/[451,+inf) bands for Italy [3,8)mm, ids 11/12/13. */
    private PricingFormulaConfigDto configWithItalyLadder() {
        return configWithFreightRates(
            new PricingFreightRateDto(11L, "IT", "อิตาลี", new BigDecimal("3"), new BigDecimal("8"),
                new BigDecimal("1"), new BigDecimal("101"), new BigDecimal("80000")),
            new PricingFreightRateDto(12L, "IT", "อิตาลี", new BigDecimal("3"), new BigDecimal("8"),
                new BigDecimal("101"), new BigDecimal("451"), new BigDecimal("90000")),
            new PricingFreightRateDto(13L, "IT", "อิตาลี", new BigDecimal("3"), new BigDecimal("8"),
                new BigDecimal("451"), null, new BigDecimal("100000")));
    }

    private PricingFormulaConfigDto configWithFreightRates(PricingFreightRateDto... freightRates) {
        return new PricingFormulaConfigDto(
            1L, 1,
            new BigDecimal("1.15"), new BigDecimal("0.0045"), new BigDecimal("1.07"),
            new BigDecimal("1.07"), new BigDecimal("1.07"), new BigDecimal("0.2"),
            new BigDecimal("10"), true, LocalDate.of(2026, 1, 1), Instant.now(),
            List.of(freightRates),
            List.of(new PricingDutyRateDto(1L, "TILE", "กระเบื้อง", new BigDecimal("0.3"))),
            List.of(new PricingClearanceFeeDto(1L, new BigDecimal("1"), null, new BigDecimal("8000"))),
            List.of(new CountryDto("IT", "Italy", "อิตาลี"), new CountryDto("CN", "China", "จีน")));
    }

    private String freightRowBody(String country, int thicknessMin, int thicknessMax, int qtyMin, String qtyMax, int amount) {
        return """
            {"originCountryCode": "%s", "thicknessMinMm": %d, "thicknessMaxMm": %d,
             "qtyMinSqm": %d, "qtyMaxSqm": %s, "amountThb": %d}
            """.formatted(country, thicknessMin, thicknessMax, qtyMin, qtyMax, amount);
    }

    private String validBody() {
        return """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": 0.2,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 60000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3}
             ],
             "clearanceFees": [
               {"qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 8000}
             ]}
            """;
    }

    private String bodyWithMargin(String margin) {
        return """
            {"insuranceValueFactor": 1.15, "insuranceRate": 0.0045, "insuranceBuffer": 1.07,
             "costBuffer": 1.07, "sellingBuffer": 1.07, "defaultMarginPct": %s,
             "sellingPriceRoundUpTo": 10,
             "freightRates": [
               {"originCountryCode": "CN", "thicknessMinMm": 3, "thicknessMaxMm": 7,
                "qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 60000}
             ],
             "dutyRates": [
               {"productType": "TILE", "productLabel": "กระเบื้อง", "dutyPct": 0.3}
             ],
             "clearanceFees": [
               {"qtyMinSqm": 1, "qtyMaxSqm": 100, "amountThb": 8000}
             ]}
            """.formatted(margin);
    }

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
