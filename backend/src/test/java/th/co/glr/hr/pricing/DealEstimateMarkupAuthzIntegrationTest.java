package th.co.glr.hr.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-DB authz evidence for {@code /api/deal-estimate-markup} (V112), per CLAUDE.md's "Permission
 * changes must ship evidence": this runs the REAL controller over the REAL repository on REAL
 * Postgres, the same shape as {@code AttendanceScopeIntegrationTest} and
 * {@code CatalogPricingReadAuthzIntegrationTest} (the FX-rate sibling of this exact ruling).
 *
 * <p>Every case is written wrong way round where it matters: the read is pinned OPEN (a caller who
 * should not see the margin policy must still see this bare multiplier), and the write is pinned
 * CLOSED for the same caller, so a future change cannot widen one without the tests noticing.
 */
class DealEstimateMarkupAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private MockMvc mvc;
    // The real hr.employee row the "ceo" session's UserPrincipal.id() points at -- V112's
    // updated_by BIGINT REFERENCES hr.employee(employee_id) FK (same convention as fx_rates /
    // price_calc_config's own updated_by) rejects a write whose caller id has no matching row.
    private long employeeId;

    @BeforeEach
    void wireRealCollaborators() {
        // Real HTTP responses go through Boot's Jackson-3-backed JacksonJsonHttpMessageConverter
        // (JacksonAutoConfiguration), so the MockMvc layer is wired with the same converter type.
        // DealEstimateMarkupDto carries an Instant (updatedAt); Jackson 3 handles it natively (no
        // module registration needed, unlike the old Jackson 2 ObjectMapper this replaced).
        // accept-empty-string-as-null-object mirrors the one Jackson property this app sets
        // (src/main/resources/application.yml).
        JsonMapper jsonMapper = JsonMapper.builder()
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .build();

        mvc = MockMvcBuilders
            .standaloneSetup(new DealEstimateMarkupController(new DealEstimateMarkupRepository(jdbc), new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        long divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES ('MKP', 'ทดสอบตัวคูณ', TRUE) RETURNING division_id
            """, Map.of(), Long.class);
        employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES ('MKP001', 'MKP001', 'ทดสอบ', 'ตัวคูณ', :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, Map.of("divisionId", divisionId, "hireDate", LocalDate.of(2020, 1, 1)), Long.class);
    }

    /**
     * PINS THE OWNER'S RULING (see {@code DealEstimateMarkupController}'s javadoc, same reasoning
     * as the FX-rate widening): a plain sales rep — who cannot read the real margin policy at
     * {@code /api/price-calc-configs} — CAN read this bare multiplier, through the real repository
     * against the real seeded row (V112 seeds 2.000).
     */
    @Test
    void salesCanReadTheMultiplier() throws Exception {
        for (String role : List.of("sales", "employee")) {
            mvc.perform(get("/api/deal-estimate-markup").session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealEstimateMarkup.multiplier").value(2.0));
        }
    }

    /** The read being open must not leak into the write: sales still gets 403 on PUT. */
    @Test
    void salesCannotWriteTheMultiplier() throws Exception {
        mvc.perform(put("/api/deal-estimate-markup")
                .session(sessionFor("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"multiplier": 3.5}
                    """))
            .andExpect(status().isForbidden());
    }

    /** CEO can write, and the change survives into the real row through the real repository. */
    @Test
    void ceoCanWriteTheMultiplierAndItPersists() throws Exception {
        mvc.perform(put("/api/deal-estimate-markup")
                .session(sessionFor("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"multiplier": 3.5}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dealEstimateMarkup.multiplier").value(3.5));

        assertThat(new DealEstimateMarkupRepository(jdbc).findCurrent().orElseThrow().multiplier())
            .isEqualByComparingTo(new BigDecimal("3.500"));
    }

    @Test
    void anonymousCallerIsRejectedOnBothEndpoints() throws Exception {
        mvc.perform(get("/api/deal-estimate-markup")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/deal-estimate-markup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"multiplier": 3.5}
                    """))
            .andExpect(status().isUnauthorized());
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(employeeId, role + "@glr.co.th", "Test " + role, role, employeeId,
                true, LocalDate.of(2026, 1, 1), false, employeeId, false));
        return session;
    }
}
