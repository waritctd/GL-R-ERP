package th.co.glr.hr.catalog;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.importer.ImportEngine;
import th.co.glr.hr.catalog.importer.PriceImportService;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.factory.FactoryConfigController;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.pricing.FxRateController;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigController;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Issue #388 — five read endpoints in the pricing/catalog domain authenticated the caller
 * ({@code sessions.requireUser}) but never checked their ROLE, while the WRITE paths on the same
 * resources were correctly gated. Any authenticated session — {@code employee}, {@code warehouse},
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
 * <p>Final state after the product owner's rulings (see each controller for the reasoning):
 * <ul>
 *   <li>{@code GET /api/catalog} → <b>open</b> to any authenticated user, per #205
 *       (2026-07-16).</li>
 *   <li>{@code GET /api/catalog/prices} → <b>open</b> to any authenticated user, per the owner's
 *       2026-08-01 ruling closing #388: #205's "browsable" covers the supplier purchase price.</li>
 *   <li>{@code GET /api/fx-rates} → {@code ceo}/{@code import}/<b>{@code sales}</b>, per the
 *       owner's 2026-08-02 ruling (see {@code FxRateController.READ_ROLES}): an FX rate only
 *       converts a number already visible via the open catalog-prices read, and reveals nothing
 *       about landed cost or margin on its own — but the ruling was "should only be to sale", so
 *       this is one role wider than #388, NOT open to every authenticated session.</li>
 *   <li>{@code GET /api/price-calc-configs}, {@code GET /api/factory-configs} → ceo/import
 *       ({@code RAW_COSTING_ROLES}), unchanged. The FX widening is deliberately narrower than
 *       #388's catalog ruling: it does not extend to the margin policy or the supplier
 *       directory.</li>
 * </ul>
 */
class CatalogPricingReadAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * The roles {@code DivisionAccessPolicy} hands ordinary staff, plus hr. Used two ways below:
     * as the pin that they DO reach both open catalog reads, and as part of the denial set that
     * they do NOT reach the cost model.
     */
    private static final List<String> ORDINARY_STAFF = List.of("employee", "warehouse", "qc", "hr");

    /** Everyone outside RAW_COSTING_ROLES — ordinary staff plus the whole sales/account side. */
    private static final List<String> NON_COSTING_ROLES =
        List.of("employee", "warehouse", "qc", "hr", "sales", "sales_manager", "account");

    private static final List<String> COSTING_ROLES = List.of("ceo", "import");

    private MockMvc catalogMvc;
    private MockMvc priceConfigMvc;
    private MockMvc fxMvc;
    private MockMvc factoryMvc;

    @BeforeEach
    void wireRealCollaborators() {
        SessionContext sessions = new SessionContext();

        // PriceImportService is reached only from CatalogController's WRITE paths, which this class
        // never exercises — mocking it keeps the whole import engine out of the fixture.
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        catalogMvc = standalone(new CatalogController(
            new CatalogRepository(jdbc),
            new PriceImportService(mock(ImportEngine.class), jdbc, objectMapper),
            sessions), objectMapper);
        priceConfigMvc = standalone(
            new PriceCalcConfigController(new PriceCalcConfigRepository(jdbc), sessions), objectMapper);
        fxMvc = standalone(new FxRateController(new FxRateRepository(jdbc), sessions), objectMapper);
        factoryMvc = standalone(
            new FactoryConfigController(new FactoryConfigRepository(jdbc), sessions), objectMapper);

        seedFixtures();
    }

    // ── what stays deliberately open, pinned against the real SQL ────────────────────────────

    /**
     * PINS ISSUE #205 (product owner, 2026-07-16): "The catalog stays browsable by any logged-in
     * user." Ordinary staff reach real catalog rows through the real repository query.
     * {@code CatalogDto} carries no price field, so nothing commercially sensitive is returned.
     *
     * <p>A failure here means someone gated {@code CatalogController.search} — reopen #205 with the
     * product owner rather than editing this test.
     */
    @Test
    void catalogBrowsingStaysOpenPerIssue205() throws Exception {
        for (String role : ORDINARY_STAFF) {
            catalogMvc.perform(get("/api/catalog")
                    .param("q", "AuthzBrand388")
                    .session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].brand").value("AuthzBrand388"));
        }
    }

    /**
     * PINS THE PRODUCT-OWNER RULING OF 2026-08-01 that closed issue #388: #205's "browsable by any
     * logged-in user" extends to the supplier PURCHASE price.
     *
     * <p>What this asserts, deliberately and against a real row in real Postgres: <strong>an
     * {@code employee}, {@code warehouse}, {@code qc} or {@code hr} session reads
     * {@code factoryName}, {@code price} and {@code currency} out of the live price catalog.</strong>
     * #388 reported that as a HIGH-severity exposure; the owner ruled it an accepted business
     * exposure. Accepted is not the same as safe — an authenticated account is the only control in
     * front of this data — and pinning it is what keeps the acceptance visible instead of looking
     * like a missing gate the next audit re-files.
     *
     * <p>A failure here means someone gated {@code CatalogController.searchPrices}. Reopen the
     * ruling with the product owner. The cost MODEL around it — margin/duty config, FX, supplier
     * directory — is a different question and IS gated, below.
     */
    @Test
    void factoryPurchasePricesStayOpenPerIssue388Ruling() throws Exception {
        for (String role : ORDINARY_STAFF) {
            catalogMvc.perform(get("/api/catalog/prices")
                    .param("q", "AUTHZ-388")
                    .param("limit", "200")
                    .session(sessionFor(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productCode").value("AUTHZ-388-001"))
                .andExpect(jsonPath("$.items[0].factoryName").value("Authz Factory 388"))
                .andExpect(jsonPath("$.items[0].price").exists());
        }
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

    /**
     * PINS THE OWNER'S RULING OF 2026-08-02 (see {@code FxRateController.READ_ROLES}): the FX read
     * widened from {@code RAW_COSTING_ROLES} by exactly one role, to {@code sales}, so the
     * deal-create modal's estimate can convert a catalog price to THB for a plain rep. Asserted
     * against a real row in real Postgres, exactly like the two #388 "stays open" pins above — a
     * failure here means someone re-narrowed the gate; reopen the ruling with the product owner
     * rather than editing this test.
     */
    @Test
    void salesCanReadFxRatesPerOwnerRuling20260802() throws Exception {
        fxMvc.perform(get("/api/fx-rates").session(sessionFor("sales")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fxRates[?(@.currency == 'XTS')].rateToThb").exists());
    }

    /**
     * THE OTHER HALF OF THE SAME RULING, and the half that actually catches a regression. The
     * ruling was "should only be to sale" — it did NOT open the rate to every authenticated
     * session. Without this case the suite stays green with the gate wide open, which is precisely
     * the failure mode a read-gate widening has: you find out in production.
     *
     * <p>{@code sales_manager} is in this list on purpose. It browses the deal pipeline but cannot
     * create a deal ({@code canCreateTickets} is {@code ['sales']}, and {@code TicketService.create}
     * gates on {@code Set.of("sales")}), so it never reaches the modal that needs the rate. Adding
     * it here would be widening beyond what the owner authorised.
     */
    @Test
    void everyRoleOutsideTheRulingIsStillRefusedTheFxRead() throws Exception {
        for (String role : List.of("employee", "warehouse", "qc", "hr", "sales_manager", "account")) {
            fxMvc.perform(get("/api/fx-rates").session(sessionFor(role)))
                .andExpect(status().isForbidden());
        }
    }

    /**
     * The read widening is deliberately narrow: it must not leak into the write. A plain
     * {@code sales} session — now able to GET the same rate — must still be refused the real
     * {@code PUT}, through the real repository.
     */
    @Test
    void salesCannotWriteFxRatesDespiteTheReadWidening() throws Exception {
        fxMvc.perform(put("/api/fx-rates/EUR")
                .session(sessionFor("sales"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {"rateToThb": 38.5, "effectiveDate": "2026-08-02"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void nonCostingRolesCannotReadTheSupplierDirectory() throws Exception {
        for (String role : NON_COSTING_ROLES) {
            factoryMvc.perform(get("/api/factory-configs").session(sessionFor(role)))
                .andExpect(status().isForbidden());
        }
    }

    /** Role-gated is not the same as authenticated: an anonymous caller still gets 401, not 403. */
    @Test
    void anonymousCallerIsRejectedOnEveryReadEndpoint() throws Exception {
        catalogMvc.perform(get("/api/catalog")).andExpect(status().isUnauthorized());
        catalogMvc.perform(get("/api/catalog/prices")).andExpect(status().isUnauthorized());
        priceConfigMvc.perform(get("/api/price-calc-configs")).andExpect(status().isUnauthorized());
        fxMvc.perform(get("/api/fx-rates")).andExpect(status().isUnauthorized());
        factoryMvc.perform(get("/api/factory-configs")).andExpect(status().isUnauthorized());
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

    /**
     * The Jackson converter is wired explicitly with {@code findAndRegisterModules()}: several of
     * these DTOs carry {@code Instant}/{@code LocalDate} fields, and a bare {@code ObjectMapper}
     * fails to serialize them — which surfaces as a bogus 500 on an authz assertion with no stack
     * trace in the surefire report.
     */
    private static MockMvc standalone(Object controller, ObjectMapper objectMapper) {
        return MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
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

    /**
     * Real rows behind all five endpoints. V91 deleted V24's/V25's sample catalog and factory_config
     * seed from the core migrations, so a freshly migrated schema has neither — without these
     * inserts the "permitted role" controls would pass against empty tables and prove nothing.
     * {@code sales.fx_rates} and {@code sales.price_calc_config} are still seeded by V26, but this
     * adds its own distinguishable rows so the assertions do not depend on that seed surviving.
     */
    private void seedFixtures() {
        insertCatalogProduct("Authz Factory 388", "IT", "AUTHZ-388-001",
            new BigDecimal("123.45"), "EUR", "per_sqm");
        jdbc.update("""
            INSERT INTO sales.catalog (brand, collection, color, surface, size, factory, sqm_per_piece)
            VALUES ('AuthzBrand388', 'Authz Collection', 'Ivory', 'Matt', '60x60 cm',
                    'Authz Factory 388', 0.360000)
            """, Map.of());
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Authz Factory 388', 'export@authz388.example', 'EUR', 'sqm', 'Authzland')
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
