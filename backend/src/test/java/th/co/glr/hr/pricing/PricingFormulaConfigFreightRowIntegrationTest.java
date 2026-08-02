package th.co.glr.hr.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFreightRateDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-DB evidence for issue #436's two NEW write endpoints -- {@code POST
 * /api/pricing-formula-config/freight-rates} and {@code DELETE
 * /api/pricing-formula-config/freight-rates/{id}} -- per CLAUDE.md's "Permission changes must ship
 * evidence". This runs the REAL controller over the REAL repository against REAL Postgres, the same
 * shape as {@code AttendanceScopeIntegrationTest} and {@code DealEstimateMarkupAuthzIntegrationTest}.
 *
 * <p>Mockito cannot stand in here for two separate reasons. First, a mocked repository would let a
 * 403 assertion "pass" while the SQL happily wrote the row anyway -- so every denial case below also
 * asserts the STORED state is unchanged (version still 1, target row still present), which is the
 * assertion that actually proves the guard runs before the write. Second, the add/delete paths
 * derive the whole new version from {@code findCurrent()}, so the V109 seed itself -- 39 freight
 * rows, six deliberately-blank cells, half-open contiguous bands -- is the fixture under test.
 *
 * <p>Cases are written wrong way round: the roles asserted at length are the ones that must NOT be
 * able to write, {@code import} first, since it is the one non-CEO role that CAN read this config
 * and is therefore the realistic privilege-escalation target.
 */
class PricingFormulaConfigFreightRowIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * Every role that must be refused. {@code import} leads deliberately: it holds the read gate on
     * this same controller, so "can read the cost model" leaking into "can edit the freight matrix"
     * is the specific regression these cases exist to catch.
     */
    private static final List<String> DENIED_ROLES =
        List.of("import", "sales", "sales_manager", "account", "hr", "employee", "warehouse", "qc");

    private MockMvc mvc;
    private PricingFormulaConfigRepository formulaConfigs;
    // sales.pricing_formula_config.updated_by is a BIGINT REFERENCES hr.employee(employee_id), so a
    // CEO write whose caller id has no matching row is rejected by the FK, not by authz.
    private long employeeId;

    @BeforeEach
    void wireRealCollaborators() {
        ObjectMapper objectMapper = new ObjectMapper();
        // PricingFormulaConfigDto carries an Instant (updatedAt); a bare ObjectMapper cannot
        // serialize it and the failure surfaces as a bogus 500 on an authz assertion with no stack
        // trace in the surefire report (see CLAUDE.md).
        objectMapper.findAndRegisterModules();

        formulaConfigs = new PricingFormulaConfigRepository(jdbc);
        mvc = MockMvcBuilders
            .standaloneSetup(new PricingFormulaConfigController(formulaConfigs, new SessionContext()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        long divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES ('PFC', 'ทดสอบสูตรราคา', TRUE) RETURNING division_id
            """, Map.of(), Long.class);
        employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES ('PFC001', 'PFC001', 'ทดสอบ', 'สูตรราคา', :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, Map.of("divisionId", divisionId, "hireDate", LocalDate.of(2020, 1, 1)), Long.class);
    }

    // ============================================================================================
    // Denial -- the cases that matter
    // ============================================================================================

    @Test
    void nonCeoRolesCannotAddAFreightRowAndNothingIsWritten() throws Exception {
        int seededRowCount = totalFreightRowCount();

        for (String role : DENIED_ROLES) {
            mvc.perform(post("/api/pricing-formula-config/freight-rates")
                    .session(sessionFor(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(newRowBody("Turkey", 3, 8, 1, 101, 70000)))
                .andExpect(status().isForbidden());

            // The 403 is worthless if the row landed anyway: no new version, no new row, and
            // Turkey must not exist anywhere in the table (not merely in the current version).
            assertThat(currentConfig().version()).as("version after %s attempted a write", role).isEqualTo(1);
            assertThat(totalFreightRowCount()).as("row count after %s attempted a write", role).isEqualTo(seededRowCount);
            assertThat(countryRowCountAcrossAllVersions("Turkey")).as("Turkey rows after %s", role).isZero();
        }
    }

    @Test
    void nonCeoRolesCannotDeleteAFreightRowAndTheRowSurvives() throws Exception {
        int seededRowCount = totalFreightRowCount();
        long targetId = topQuantityBandOf("Italy", "12", "17").freightRateId();

        for (String role : DENIED_ROLES) {
            mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", targetId)
                    .session(sessionFor(role)))
                .andExpect(status().isForbidden());

            assertThat(currentConfig().version()).as("version after %s attempted a delete", role).isEqualTo(1);
            assertThat(totalFreightRowCount()).as("row count after %s attempted a delete", role).isEqualTo(seededRowCount);
            assertThat(currentConfig().freightRates())
                .as("target row after %s attempted a delete", role)
                .anyMatch(rate -> rate.freightRateId() == targetId);
        }
    }

    @Test
    void anonymousCallerIsRejectedOnBothWriteEndpoints() throws Exception {
        long targetId = topQuantityBandOf("Italy", "12", "17").freightRateId();

        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(newRowBody("Turkey", 3, 8, 1, 101, 70000)))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", targetId))
            .andExpect(status().isUnauthorized());

        assertThat(currentConfig().version()).isEqualTo(1);
        assertThat(countryRowCountAcrossAllVersions("Turkey")).isZero();
    }

    // ============================================================================================
    // The CEO can do the thing the issue was filed about
    // ============================================================================================

    /**
     * The headline case: Italy [12,17)mm x [801,+inf) sqm is one of V109's deliberately-blank
     * cells, and before #436 there was no way to fill it short of a Flyway migration. A 900 sqm
     * order of 14 mm Italian tile matched ZERO freight bands; after the CEO adds the row it matches
     * EXACTLY ONE -- "exactly one", not "at least one", so the assertion fails on an overlap as
     * loudly as it does on a gap.
     */
    @Test
    void ceoCanFillADeliberatelyBlankCellAndTheOrderBecomesPriceable() throws Exception {
        BigDecimal thickness = new BigDecimal("14");
        BigDecimal qty = new BigDecimal("900");
        assertThat(matchingBands(currentConfig(), "Italy", thickness, qty))
            .as("the blank cell really is blank before the fix")
            .isZero();

        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(newRowBody("Italy", 12, 17, 801, null, 110000)))
            .andExpect(status().isOk());

        PricingFormulaConfigDto after = currentConfig();
        assertThat(after.version()).isEqualTo(2);
        assertThat(after.freightRates()).hasSize(40);
        assertThat(matchingBands(after, "Italy", thickness, qty)).isEqualTo(1);
        assertThat(after.freightRates().stream()
            .filter(rate -> "Italy".equals(rate.originCountry()))
            .filter(rate -> rate.qtyMinSqm().compareTo(new BigDecimal("801")) == 0)
            .filter(rate -> rate.thicknessMinMm().compareTo(new BigDecimal("12")) == 0)
            .findFirst().orElseThrow().amountThb()).isEqualByComparingTo("110000");

        // V109's versioning model is untouched: the previous generation keeps all 39 of its rows.
        assertThat(freightRowCountForVersion(1)).isEqualTo(39);
    }

    /** A brand-new origin country is now a config edit, not a migration -- the other half of #436. */
    @Test
    void ceoCanAddAnEntirelyNewOriginCountry() throws Exception {
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(newRowBody("Turkey", 3, 8, 1, null, 45000)))
            .andExpect(status().isOk());

        PricingFormulaConfigDto after = currentConfig();
        assertThat(after.version()).isEqualTo(2);
        assertThat(matchingBands(after, "Turkey", new BigDecimal("5.5"), new BigDecimal("250.25"))).isEqualTo(1);
    }

    @Test
    void ceoCanDeleteAnEdgeBandAndThePreviousVersionIsRetained() throws Exception {
        PricingFreightRateDto target = topQuantityBandOf("Italy", "12", "17");

        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", target.freightRateId())
                .session(sessionFor("ceo")))
            .andExpect(status().isOk());

        PricingFormulaConfigDto after = currentConfig();
        assertThat(after.version()).isEqualTo(2);
        assertThat(after.freightRates()).hasSize(38);
        assertThat(after.freightRates())
            .noneMatch(rate -> "Italy".equals(rate.originCountry())
                && rate.thicknessMinMm().compareTo(new BigDecimal("12")) == 0
                && rate.qtyMinSqm().compareTo(target.qtyMinSqm()) == 0);
        // Trimming the top of a ladder is allowed, and the row below it still covers its own range
        // exactly once -- the delete narrowed coverage without disturbing what remains.
        assertThat(matchingBands(after, "Italy", new BigDecimal("14"), new BigDecimal("300"))).isEqualTo(1);
        assertThat(freightRowCountForVersion(1)).isEqualTo(39);
    }

    // ============================================================================================
    // Validation holds on INSERT, and a delete cannot punch a hole
    // ============================================================================================

    @Test
    void ceoCannotAddAnOverlappingFreightRow() throws Exception {
        // Italy [12,17)mm already has [451,801); [700,900) overlaps it on BOTH thickness and qty.
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(newRowBody("Italy", 12, 17, 700, 900, 95000)))
            .andExpect(status().isBadRequest());

        assertThat(currentConfig().version()).isEqualTo(1);
        assertThat(totalFreightRowCount()).isEqualTo(39);
    }

    /** An overlapping THICKNESS band is caught on insert too, not just an overlapping qty band. */
    @Test
    void ceoCannotAddARowWhoseThicknessBandOverlapsAnExistingOne() throws Exception {
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(newRowBody("Italy", 5, 10, 1, 101, 85000)))
            .andExpect(status().isBadRequest());

        assertThat(currentConfig().version()).isEqualTo(1);
    }

    @Test
    void ceoCannotAddARowWithAnInvertedThicknessBand() throws Exception {
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(newRowBody("Turkey", 12, 12, 1, 101, 70000)))
            .andExpect(status().isBadRequest());

        assertThat(currentConfig().version()).isEqualTo(1);
    }

    /**
     * The gap rule. Italy [12,17)mm is seeded [1,101) / [101,451) / [451,801); dropping the middle
     * band would leave 101-451 sqm unpriceable while the orders either side still price fine --
     * silent mis-coverage. Deleting an edge is a deliberate trim; deleting a middle band is a hole.
     */
    @Test
    void ceoCannotDeleteAMiddleQuantityBand() throws Exception {
        PricingFreightRateDto middle = currentConfig().freightRates().stream()
            .filter(rate -> "Italy".equals(rate.originCountry()))
            .filter(rate -> rate.thicknessMinMm().compareTo(new BigDecimal("12")) == 0)
            .filter(rate -> rate.qtyMinSqm().compareTo(new BigDecimal("101")) == 0)
            .findFirst().orElseThrow();

        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", middle.freightRateId())
                .session(sessionFor("ceo")))
            .andExpect(status().isBadRequest());

        assertThat(currentConfig().version()).isEqualTo(1);
        assertThat(currentConfig().freightRates()).anyMatch(rate -> rate.freightRateId() == middle.freightRateId());
        // Still exactly one band for a 300 sqm / 14 mm Italian order -- the hole was never opened.
        assertThat(matchingBands(currentConfig(), "Italy", new BigDecimal("14"), new BigDecimal("300"))).isEqualTo(1);
    }

    /**
     * Same rule one level up: emptying a MIDDLE thickness band is a hole in the thickness ladder.
     * Italy [8,12)mm is reduced to a single row first (deleting from its edges inward, which is
     * allowed), and the final row -- the one whose removal would empty the band -- is refused.
     */
    @Test
    void ceoCannotEmptyAMiddleThicknessBand() throws Exception {
        deleteAsCeoExpectingOk(quantityBandOf("Italy", "8", "12", "801").freightRateId());
        deleteAsCeoExpectingOk(quantityBandOf("Italy", "8", "12", "451").freightRateId());
        deleteAsCeoExpectingOk(quantityBandOf("Italy", "8", "12", "101").freightRateId());

        PricingFreightRateDto last = quantityBandOf("Italy", "8", "12", "1");
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", last.freightRateId())
                .session(sessionFor("ceo")))
            .andExpect(status().isBadRequest());

        assertThat(currentConfig().freightRates()).anyMatch(rate -> rate.freightRateId() == last.freightRateId());
    }

    /** A row id from a superseded version is a 404 -- old generations stay immutable audit records. */
    @Test
    void deletingARowIdFromASupersededVersionIsNotFound() throws Exception {
        long staleId = topQuantityBandOf("Italy", "12", "17").freightRateId();
        deleteAsCeoExpectingOk(staleId);
        assertThat(currentConfig().version()).isEqualTo(2);

        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", staleId)
                .session(sessionFor("ceo")))
            .andExpect(status().isNotFound());

        // The superseded version still has all 39 of its rows, including the one just "deleted".
        assertThat(freightRowCountForVersion(1)).isEqualTo(39);
        assertThat(currentConfig().version()).isEqualTo(2);
    }

    @Test
    void deletingAnIdThatNeverExistedIsNotFound() throws Exception {
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", 9_999_999L)
                .session(sessionFor("ceo")))
            .andExpect(status().isNotFound());
        assertThat(currentConfig().version()).isEqualTo(1);
    }

    /**
     * The whole matrix stays coherent after a real add + a real delete: for every distinct
     * (country, thickness-band) group, a quantity that the group covers falls in EXACTLY one band.
     * "Exactly one" catches an overlap the add might have introduced as well as a gap the delete
     * might have opened.
     */
    @Test
    void everyCoveredQuantityStillMatchesExactlyOneBandAfterAnAddAndADelete() throws Exception {
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(newRowBody("Italy", 12, 17, 801, null, 110000)))
            .andExpect(status().isOk());
        deleteAsCeoExpectingOk(quantityBandOf("China", "17", "21", "101").freightRateId());

        PricingFormulaConfigDto after = currentConfig();
        assertThat(after.version()).isEqualTo(3);

        for (BigDecimal qty : List.of(new BigDecimal("1"), new BigDecimal("100.5"), new BigDecimal("450.75"))) {
            for (PricingFreightRateDto rate : after.freightRates()) {
                if (!covers(rate, qty)) {
                    continue;
                }
                long matches = after.freightRates().stream()
                    .filter(other -> other.originCountry().equals(rate.originCountry()))
                    .filter(other -> other.thicknessMinMm().compareTo(rate.thicknessMinMm()) == 0)
                    .filter(other -> other.thicknessMaxMm().compareTo(rate.thicknessMaxMm()) == 0)
                    .filter(other -> covers(other, qty))
                    .count();
                assertThat(matches)
                    .as("bands matching %s sqm in %s [%s,%s)mm", qty, rate.originCountry(),
                        rate.thicknessMinMm(), rate.thicknessMaxMm())
                    .isEqualTo(1);
            }
        }
    }

    // ============================================================================================
    // helpers
    // ============================================================================================

    private void deleteAsCeoExpectingOk(long freightRateId) throws Exception {
        mvc.perform(delete("/api/pricing-formula-config/freight-rates/{id}", freightRateId)
                .session(sessionFor("ceo")))
            .andExpect(status().isOk());
    }

    private PricingFormulaConfigDto currentConfig() {
        return formulaConfigs.findCurrent().orElseThrow();
    }

    private PricingFreightRateDto quantityBandOf(String country, String thicknessMin, String thicknessMax, String qtyMin) {
        return currentConfig().freightRates().stream()
            .filter(rate -> rate.originCountry().equals(country))
            .filter(rate -> rate.thicknessMinMm().compareTo(new BigDecimal(thicknessMin)) == 0)
            .filter(rate -> rate.thicknessMaxMm().compareTo(new BigDecimal(thicknessMax)) == 0)
            .filter(rate -> rate.qtyMinSqm().compareTo(new BigDecimal(qtyMin)) == 0)
            .findFirst().orElseThrow();
    }

    private PricingFreightRateDto topQuantityBandOf(String country, String thicknessMin, String thicknessMax) {
        return currentConfig().freightRates().stream()
            .filter(rate -> rate.originCountry().equals(country))
            .filter(rate -> rate.thicknessMinMm().compareTo(new BigDecimal(thicknessMin)) == 0)
            .filter(rate -> rate.thicknessMaxMm().compareTo(new BigDecimal(thicknessMax)) == 0)
            .max((a, b) -> a.qtyMinSqm().compareTo(b.qtyMinSqm()))
            .orElseThrow();
    }

    /** Half-open [min, max), NULL max = +infinity -- V109's BAND CONVENTION. */
    private boolean covers(PricingFreightRateDto rate, BigDecimal qty) {
        return qty.compareTo(rate.qtyMinSqm()) >= 0
            && (rate.qtyMaxSqm() == null || qty.compareTo(rate.qtyMaxSqm()) < 0);
    }

    private long matchingBands(PricingFormulaConfigDto config, String country, BigDecimal thickness, BigDecimal qty) {
        return config.freightRates().stream()
            .filter(rate -> rate.originCountry().equals(country))
            .filter(rate -> thickness.compareTo(rate.thicknessMinMm()) >= 0 && thickness.compareTo(rate.thicknessMaxMm()) < 0)
            .filter(rate -> covers(rate, qty))
            .count();
    }

    private int totalFreightRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.pricing_freight_rate", Map.of(), Integer.class);
    }

    private int freightRowCountForVersion(int version) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_freight_rate r
              JOIN sales.pricing_formula_config c ON c.formula_config_id = r.formula_config_id
             WHERE c.version = :version
            """, Map.of("version", version), Integer.class);
    }

    private int countryRowCountAcrossAllVersions(String country) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_freight_rate WHERE origin_country = :country",
            Map.of("country", country), Integer.class);
    }

    private String newRowBody(String country, int thicknessMin, int thicknessMax, int qtyMin, Integer qtyMax, int amount) {
        return """
            {"originCountry": "%s", "thicknessMinMm": %d, "thicknessMaxMm": %d,
             "qtyMinSqm": %d, "qtyMaxSqm": %s, "amountThb": %d}
            """.formatted(country, thicknessMin, thicknessMax, qtyMin,
                qtyMax == null ? "null" : String.valueOf(qtyMax), amount);
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(employeeId, role + "@glr.co.th", "Test " + role, role, employeeId,
                true, LocalDate.of(2026, 1, 1), false, employeeId, false));
        return session;
    }
}
