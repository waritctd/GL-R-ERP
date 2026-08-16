package th.co.glr.hr.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
 * The CEO thickness-defaults surface (V152 + its editor).
 *
 * <p>Authorization cases are written wrong-way-round on purpose: each asserts the caller
 * <em>cannot</em> reach what they should not, and that a rejected write left the database
 * untouched. "The CEO can save" is the easy case and proves the least.
 */
class ThicknessDefaultIntegrationTest extends AbstractPostgresIntegrationTest {

    private MockMvc mockMvc;
    // collection_thickness_default.updated_by REFERENCES hr.employee, so a CEO write whose caller
    // id has no matching row is rejected by the FK rather than by authz.
    private long employeeId;

    @BeforeEach
    void wireRealCollaborators() {
        JsonMapper jsonMapper = JsonMapper.builder()
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .build();
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ThicknessDefaultController(
                new ThicknessDefaultRepository(jdbc), new SessionContext()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        long divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES ('CTD', 'ทดสอบความหนา', TRUE) RETURNING division_id
            """, Map.of(), Long.class);
        employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES ('CTD001', 'CTD001', 'ทดสอบ', 'ความหนา', :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, Map.of("divisionId", divisionId, "hireDate", LocalDate.of(2020, 1, 1)), Long.class);
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(employeeId, role + "@glr.co.th", "Test " + role, role, employeeId,
                true, LocalDate.of(2026, 1, 1), false, employeeId, false));
        return session;
    }

    private long seedRowWithoutThickness(String factory, String collection) {
        long priceId = insertCatalogProduct(factory, "ES", "TD-" + collection,
            new BigDecimal("40.00"), "EUR", "per_sqm", "ACTIVE");
        jdbc.update("""
            UPDATE price_catalog.product_prices
               SET collection = :c, thickness_mm = NULL, size_raw = '20x20',
                   width_mm = 200, height_mm = 200
             WHERE price_id = :id
            """, new MapSqlParameterSource().addValue("id", priceId).addValue("c", collection));
        return priceId;
    }

    private long factoryOf(long priceId) {
        return jdbc.queryForObject(
            "SELECT factory_id FROM price_catalog.product_prices WHERE price_id = :id",
            Map.of("id", priceId), Long.class);
    }

    private String statusOf(long priceId) {
        return jdbc.queryForObject(
            "SELECT pricing_status FROM price_catalog.v_priceable_product WHERE price_id = :id",
            Map.of("id", priceId), String.class);
    }

    private int storedDefaults() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM price_catalog.collection_thickness_default", Map.of(), Integer.class);
    }

    private String body(long factoryId, String collection, String thickness) {
        return """
            {"entries":[{"factoryId":%d,"collection":"%s","thicknessMm":%s}]}
            """.formatted(factoryId, collection, thickness);
    }

    // ── authorization, wrong-way-round ───────────────────────────────────────

    @Test
    void anonymousCallerIsRejectedOnBothEndpoints() throws Exception {
        mockMvc.perform(get("/api/catalog/thickness-defaults"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/catalog/thickness-defaults")
                .contentType(MediaType.APPLICATION_JSON).content(body(1L, "X", "9")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void nonCeoRolesCannotReadOrWriteAndNothingIsStored() throws Exception {
        long priceId = seedRowWithoutThickness("TD F1", "ALTEA");
        long factoryId = factoryOf(priceId);

        for (String role : new String[] {"employee", "hr", "sales", "sales_manager", "import"}) {
            mockMvc.perform(get("/api/catalog/thickness-defaults").session(session(role)))
                .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/catalog/thickness-defaults").session(session(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(factoryId, "ALTEA", "9")))
                .andExpect(status().isForbidden());
        }

        assertThat(storedDefaults()).as("a rejected write must leave the table empty").isZero();
        assertThat(statusOf(priceId)).isEqualTo("NO_THICKNESS");
    }

    // ── the behaviour that matters ───────────────────────────────────────────

    @Test
    void savingADefaultFlipsTheRowFromUnpriceableToPriceable() throws Exception {
        long priceId = seedRowWithoutThickness("TD F2", "BARNET");
        long factoryId = factoryOf(priceId);
        assertThat(statusOf(priceId)).isEqualTo("NO_THICKNESS");

        mockMvc.perform(put("/api/catalog/thickness-defaults").session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(factoryId, "BARNET", "8.5")))
            .andExpect(status().isOk());

        assertThat(statusOf(priceId)).isEqualTo("PRICEABLE");
    }

    @Test
    void aNullThicknessClearsTheDefaultRatherThanStoringZero() throws Exception {
        long priceId = seedRowWithoutThickness("TD F3", "MONOCOLOR");
        long factoryId = factoryOf(priceId);

        mockMvc.perform(put("/api/catalog/thickness-defaults").session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(factoryId, "MONOCOLOR", "9")))
            .andExpect(status().isOk());
        assertThat(statusOf(priceId)).isEqualTo("PRICEABLE");

        mockMvc.perform(put("/api/catalog/thickness-defaults").session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(factoryId, "MONOCOLOR", "null")))
            .andExpect(status().isOk());

        // Back to the honest state, NOT a stored 0 -- a zero would silently pick the lowest
        // freight band instead of refusing to price.
        assertThat(storedDefaults()).isZero();
        assertThat(statusOf(priceId)).isEqualTo("NO_THICKNESS");
    }

    @Test
    void aZeroThicknessIsRejected() throws Exception {
        long priceId = seedRowWithoutThickness("TD F4", "PERGOLA");
        long factoryId = factoryOf(priceId);

        mockMvc.perform(put("/api/catalog/thickness-defaults").session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(factoryId, "PERGOLA", "0")))
            .andExpect(status().isBadRequest());

        assertThat(storedDefaults()).isZero();
    }

    /**
     * The subtle one: a size-level override is the escape hatch for a collection whose trim differs
     * from its field tile. A bulk collection-level save must not wipe it.
     */
    @Test
    void aBulkCollectionSaveLeavesSizeLevelOverridesIntact() throws Exception {
        long priceId = seedRowWithoutThickness("TD F5", "ANTHOLOGY");
        long factoryId = factoryOf(priceId);

        jdbc.update("""
            INSERT INTO price_catalog.collection_thickness_default
                (factory_id, collection, size_norm, thickness_mm)
            VALUES (:f, 'ANTHOLOGY', '20X20', 20)
            """, new MapSqlParameterSource().addValue("f", factoryId));

        mockMvc.perform(put("/api/catalog/thickness-defaults").session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(factoryId, "ANTHOLOGY", "9")))
            .andExpect(status().isOk());

        Integer overrides = jdbc.queryForObject("""
            SELECT count(*) FROM price_catalog.collection_thickness_default
             WHERE size_norm = '20X20' AND thickness_mm = 20
            """, Map.of(), Integer.class);
        assertThat(overrides).as("the size-level override must survive a collection-level save")
            .isEqualTo(1);

        // And it still wins, because it is more specific.
        assertThat(jdbc.queryForObject(
            "SELECT thickness_mm FROM price_catalog.v_priceable_product WHERE price_id = :id",
            Map.of("id", priceId), BigDecimal.class)).isEqualByComparingTo("20");
    }

    @Test
    void theGapListIsOrderedByHowManyRowsEachEntryWouldUnblock() throws Exception {
        long small = seedRowWithoutThickness("TD F6", "SMALL");
        long factoryId = factoryOf(small);
        // Give BIG three rows against the same factory so it must sort ahead of SMALL's one.
        for (int i = 0; i < 3; i++) {
            long id = insertCatalogProduct("TD F6", "ES", "BIG-" + i,
                new BigDecimal("40.00"), "EUR", "per_sqm", "ACTIVE");
            jdbc.update("UPDATE price_catalog.product_prices SET collection='BIG', thickness_mm=NULL WHERE price_id=:id",
                Map.of("id", id));
        }

        String json = mockMvc.perform(get("/api/catalog/thickness-defaults").session(session("ceo")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(json.indexOf("\"BIG\"")).as("biggest-impact collection first")
            .isLessThan(json.indexOf("\"SMALL\""));
        assertThat(factoryId).isPositive();
    }
}
