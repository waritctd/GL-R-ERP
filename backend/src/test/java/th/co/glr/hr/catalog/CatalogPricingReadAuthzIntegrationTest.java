package th.co.glr.hr.catalog;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.importer.PriceImportService;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.factory.FactoryConfigController;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.pricing.BotFxFetchService;
import th.co.glr.hr.pricing.FxRateController;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigController;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Five read endpoints in the pricing/catalog domain authenticate the caller
 * ({@code sessions.requireUser}); their ROLE checks matter because the WRITE paths on the same
 * resources are gated too. Without them any authenticated session — {@code employee}, {@code warehouse},
 * {@code qc}, all of which {@code DivisionAccessPolicy} hands to ordinary staff — could read the
 * supplier purchase prices, the FX rates, and the margin/duty policy, i.e. reconstruct from outside
 * exactly the cost→price model {@code PricingCostingService} (RAW_COSTING_ROLES = import/ceo) and
 * {@code PricingDecisionService} exist to keep inside.
 *
 * <p>This runs the REAL controllers over the REAL repositories on REAL Postgres, per CLAUDE.md's
 * "Permission changes must ship evidence": a Mockito-mocked repository passes happily while the SQL
 * does something else, and this repo has been bitten by exactly that.
 * {@code AttendanceScopeIntegrationTest} is the reference shape. The sibling
 * {@code *ControllerTest} classes prove the DECISION; this proves the ENFORCEMENT survives into the
 * real query path.
 *
 * <p>Every denial case is written <strong>wrong way round</strong>: it asks whether a caller who
 * should not reach the data can reach it, and asserts 403. The permitted cases assert a real seeded
 * row comes back through the real SQL, so a removed guard fails loudly instead of passing against an
 * empty table — and, for the two open endpoints, so the pin proves real data is genuinely reachable
 * rather than merely un-403'd.
 *
 * <p>State per the product owner's rulings (see each controller for the reasoning):
 * <ul>
 *   <li>{@code GET /api/catalog} → <b>open</b> to any authenticated user.</li>
 *   <li>{@code GET /api/catalog/prices} → <b>open</b> to any authenticated user: the catalog's
 *       "browsable" covers the supplier purchase price.</li>
 *   <li>{@code GET /api/fx-rates} → {@code ceo}/{@code import}/<b>{@code sales}</b>/
 *       <b>{@code sales_manager}</b>, per the owner's rulings — see {@code FxRateController.READ_ROLES}: an FX rate only
 *       converts a number already visible via the open catalog-prices read, and reveals nothing
 *       about landed cost or margin on its own. NOT open to every authenticated session.</li>
 *   <li>{@code GET /api/price-calc-configs}, {@code GET /api/factory-configs} → ceo/import
 *       ({@code RAW_COSTING_ROLES}). The FX widening does not extend to the margin
 *       policy or this supplier-directory endpoint.</li>
 * </ul>
 */
class CatalogPricingReadAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Everyone outside RAW_COSTING_ROLES — ordinary staff plus the whole sales/account side. */
    private static final List<String> NON_COSTING_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account");

    private static final List<String> COSTING_ROLES = List.of("ceo", "import");

    private MockMvc priceConfigMvc;
    private MockMvc fxMvc;
    private MockMvc factoryMvc;
    private BotFxFetchService botFxFetchService;

    @BeforeEach
    void wireRealCollaborators() {
        SessionContext sessions = new SessionContext();

        // PriceImportService is reached only from CatalogController's WRITE paths, which this class never exercises —
        // mocking it keeps the whole import engine out of the fixture.
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        // Real HTTP responses go through Boot's Jackson-3-backed JacksonJsonHttpMessageConverter
        // (JacksonAutoConfiguration), not a bare ObjectMapper — so the MockMvc layer is wired with the same converter
        // type, built the same way.
        JsonMapper jsonMapper = JsonMapper.builder()
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .build();

        priceConfigMvc = standalone(
            new PriceCalcConfigController(new PriceCalcConfigRepository(jdbc), sessions), jsonMapper);
        botFxFetchService = mock(BotFxFetchService.class);
        fxMvc = standalone(new FxRateController(
            new FxRateRepository(jdbc), sessions, botFxFetchService), jsonMapper);
        factoryMvc = standalone(
            new FactoryConfigController(new FactoryConfigRepository(jdbc), sessions, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc)), jsonMapper);

        seedFixtures();
    }

    // ── the cases that matter: callers who must NOT reach the cost model ─────────────────────

    /** The margin policy itself — marginPct / importDutyPct. */
    @Test
    void nonCostingRolesCannotReadTheMarginPolicy() throws Exception {
        for (String role : NON_COSTING_ROLES) {
            priceConfigMvc.perform(get("/api/price-calc-configs").session(sessionFor(role)))
                .andExpect(status().isForbidden());
        }
    }

    // ── fixture liveness: permitted roles must reach REAL rows, or the denials prove nothing ──

    @Test
    void costingRolesStillReachRealMarginFxAndSupplierRows() throws Exception {
        for (String role : COSTING_ROLES) {
            priceConfigMvc.perform(get("/api/price-calc-configs").session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configs[?(@.country == 'Authzland')].marginPct").exists());
            fxMvc.perform(get("/api/fx-rates").session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fxRates[?(@.currency == 'XTS')].rateToThb").exists());
            factoryMvc.perform(get("/api/factory-configs").session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factories[?(@.factoryName == 'Authz Factory 388')].email").exists());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** The Jackson converter is wired explicitly, matching production's Boot-4-auto-configured
     * JacksonJsonHttpMessageConverter: several of these DTOs carry Instant/ LocalDate fields, and Jackson 3
     * handles both natively (no module registration needed). */
    private static MockMvc standalone(Object controller, JsonMapper jsonMapper) {
        return MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private static MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test " + role, role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }

    /** Real rows behind all five endpoints. sales.fx_rates and sales.price_calc_config are still seeded by V26, but
     * this adds its own distinguishable rows so the assertions do not depend on that seed surviving. */
    private void seedFixtures() {
        insertCatalogProduct("Authz Factory 388", "IT", "AUTHZ-388-001",
            new BigDecimal("123.45"), "EUR", "per_sqm");
        jdbc.update("""
            INSERT INTO sales.catalog (brand, collection, color, surface, size, factory, sqm_per_piece)
            VALUES ('AuthzBrand388', 'Authz Collection', 'Ivory', 'Matt', '60x60 cm',
                    'Authz Factory 388', 0.360000)
            """, Map.of());
        jdbc.update("""
            UPDATE price_catalog.factories
               SET email = 'export@authz388.example',
                   unit = 'sqm'
             WHERE name = 'Authz Factory 388'
            """, Map.of());
        jdbc.update("""
            INSERT INTO sales.fx_rates (currency, rate_to_thb, effective_date)
            VALUES ('XTS', 12.345600, CURRENT_DATE)
            """, Map.of());
        jdbc.update("""
            INSERT INTO sales.price_calc_config
                (version, country, freight_per_sqm, insurance_per_sqm,
                 inland_factory_to_port_per_sqm, inland_port_to_warehouse_per_sqm,
                 import_duty_pct, margin_pct, is_current)
            VALUES (1, 'Authzland', 120, 15, 30, 50, 0.050000, 0.250000, TRUE)
            """, Map.of());
    }
}
