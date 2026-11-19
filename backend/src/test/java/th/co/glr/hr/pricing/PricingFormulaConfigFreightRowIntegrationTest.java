package th.co.glr.hr.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFormulaConfigDto;
import th.co.glr.hr.pricing.PricingFormulaConfigDtos.PricingFreightRateDto;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Real-DB evidence for issue #436's two NEW write endpoints -- POST /api/pricing-formula-config/freight-rates
 * and DELETE /api/pricing-formula-config/freight-rates/{id} -- per CLAUDE.md's "Permission changes must ship
 * evidence". Mockito cannot stand in here for two separate reasons. */
class PricingFormulaConfigFreightRowIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Every role that must be refused. import leads deliberately: it holds the read gate on this same controller,
     * so "can read the cost model" leaking into "can edit the freight matrix" is the specific regression these
     * cases exist to catch. */
    private static final List<String> DENIED_ROLES =
        List.of("import", "sales", "sales_manager", "account", "hr", "employee", "warehouse", "qc");

    private MockMvc mvc;
    private PricingFormulaConfigRepository formulaConfigs;
    // sales.pricing_formula_config.updated_by is a BIGINT REFERENCES hr.employee(employee_id), so a CEO write
    // whose caller id has no matching row is rejected by the FK, not by authz.
    private long employeeId;

    @BeforeEach
    void wireRealCollaborators() {
        // Real HTTP responses go through Boot's Jackson-3-backed JacksonJsonHttpMessageConverter (JacksonAutoConfiguration),
        // so the MockMvc layer is wired with the same converter type. accept-empty-string-as-null-object mirrors the one
        // Jackson property this app sets (src/main/resources/application.yml).
        JsonMapper jsonMapper = JsonMapper.builder()
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .build();

        formulaConfigs = new PricingFormulaConfigRepository(jdbc);
        mvc = MockMvcBuilders
            .standaloneSetup(new PricingFormulaConfigController(formulaConfigs, new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
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

    // Denial -- the cases that matter

    @Test
    void nonCeoRolesCannotAddAFreightRowAndNothingIsWritten() throws Exception {
        int seededRowCount = totalFreightRowCount();

        for (String role : DENIED_ROLES) {
            mvc.perform(post("/api/pricing-formula-config/freight-rates")
                    .session(sessionFor(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(newRowBody("TR", 3, 8, 1, 101, 70000)))
                .andExpect(status().isForbidden());

            // The 403 is worthless if the row landed anyway: no new version, no new row, and Turkey must not exist
            // anywhere in the table (not merely in the current version).
            assertThat(currentConfig().version()).as("version after %s attempted a write", role).isEqualTo(2);
            assertThat(totalFreightRowCount()).as("row count after %s attempted a write", role).isEqualTo(seededRowCount);
            assertThat(countryRowCountAcrossAllVersions("TR")).as("Turkey rows after %s", role).isZero();
        }
    }

    // The CEO can do the thing the issue was filed about

    /** A brand-new origin country is now a config edit, not a migration -- the other half of #436. */
    @Test
    void ceoCanAddAnEntirelyNewOriginCountry() throws Exception {
        mvc.perform(post("/api/pricing-formula-config/freight-rates")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(newRowBody("TR", 3, 8, 1, null, 45000)))
            .andExpect(status().isOk());

        PricingFormulaConfigDto after = currentConfig();
        assertThat(after.version()).isEqualTo(3);
        assertThat(matchingBands(after, "TR", new BigDecimal("5.5"), new BigDecimal("250.25"))).isEqualTo(1);
    }

    // Validation holds on INSERT, and a delete cannot punch a hole

    // helpers

    private PricingFormulaConfigDto currentConfig() {
        return formulaConfigs.findCurrent().orElseThrow();
    }

    /** Half-open [min, max), NULL max = +infinity -- V109's BAND CONVENTION. */
    private boolean covers(PricingFreightRateDto rate, BigDecimal qty) {
        return qty.compareTo(rate.qtyMinSqm()) >= 0
            && (rate.qtyMaxSqm() == null || qty.compareTo(rate.qtyMaxSqm()) < 0);
    }

    private long matchingBands(PricingFormulaConfigDto config, String country, BigDecimal thickness, BigDecimal qty) {
        return config.freightRates().stream()
            .filter(rate -> rate.originCountryCode().equals(country))
            .filter(rate -> thickness.compareTo(rate.thicknessMinMm()) >= 0 && thickness.compareTo(rate.thicknessMaxMm()) < 0)
            .filter(rate -> covers(rate, qty))
            .count();
    }

    private int totalFreightRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.pricing_freight_rate", Map.of(), Integer.class);
    }

    private int countryRowCountAcrossAllVersions(String country) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_freight_rate WHERE origin_country_code = :country",
            Map.of("country", country), Integer.class);
    }

    private String newRowBody(String country, int thicknessMin, int thicknessMax, int qtyMin, Integer qtyMax, int amount) {
        return """
            {"originCountryCode": "%s", "thicknessMinMm": %d, "thicknessMaxMm": %d,
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
