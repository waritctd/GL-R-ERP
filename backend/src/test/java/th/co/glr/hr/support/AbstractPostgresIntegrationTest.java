package th.co.glr.hr.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.payroll.PayrollComponent;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollTaxTreatment;

/** Base for repository integration tests that run the real dynamic SQL against a real PostgreSQL database — the
 * gap Mockito-based unit tests cannot cover (issue #28). They do not skip. The external TEST_DB_URL path keeps
 * clean+migrate so we never drop a database we don't own. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
public abstract class AbstractPostgresIntegrationTest {
    /** A real connection pool, not a DriverManagerDataSource. Each of those connections paid a full PostgreSQL
     * SCRAM-SHA-256 handshake, whose client side is PBKDF2 with 4096 HMAC-SHA-256 iterations. Pooling took that
     * class from 83.8s to 11.7s (-86%). */
    private static HikariDataSource dataSource;

    protected NamedParameterJdbcTemplate jdbc;

    /** A real PlatformTransactionManager/TransactionTemplate pair on the same DataSource #jdbc uses. */
    protected PlatformTransactionManager transactionManager;
    protected TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetSchema() {
        if (PostgresTestSupport.usesContainer()) {
            // Fast path: clone the pre-migrated golden template. Must run before dataSource() first connects, since it
            // (re)creates the working database this test connects to. A pool that survived the drop would keep handing out
            // sockets to a database that no longer exists.
            closePool();
            PostgresTestSupport.resetToGolden();
        } else {
            // External TEST_DB_URL: replay clean + migrate, so we never drop a DB we don't own.
            Flyway flyway = PostgresTestSupport.externalFlyway(dataSource());
            flyway.clean();
            flyway.migrate();
        }
        jdbc = new NamedParameterJdbcTemplate(dataSource());
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource());
        transactionManager = txManager;
        transactionTemplate = new TransactionTemplate(txManager);
    }

    /** Wraps a hand-wired service in a REAL transactional AOP proxy, so its own @Transactional annotations are
     * honoured exactly as they are in production. */
    @SuppressWarnings("unchecked")
    protected <T> T transactional(T service) {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
            transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** A REAL th.co.glr.hr.factoryquote.FactoryQuoteCarryForward, for the many tests that hand-wire a
     * PricingRequestService and do not otherwise care about factory quotes. Deliberately real rather than a mock,
     * and deliberately a helper rather than an optional constructor argument. */
    protected th.co.glr.hr.factoryquote.FactoryQuoteCarryForward factoryQuoteCarryForward() {
        th.co.glr.hr.pricingrequest.PricingRequestRepository pricingRequests =
            new th.co.glr.hr.pricingrequest.PricingRequestRepository(jdbc);
        th.co.glr.hr.factoryquote.FactoryQuoteRepository factoryQuotes =
            new th.co.glr.hr.factoryquote.FactoryQuoteRepository(jdbc);
        return new th.co.glr.hr.factoryquote.FactoryQuoteCarryForward(factoryQuotes, pricingRequests,
            new th.co.glr.hr.pricingcosting.LandedCostCalculator(factoryQuotes, pricingRequests,
                new th.co.glr.hr.pricing.FxRateRepository(jdbc),
                new th.co.glr.hr.factory.FactoryConfigRepository(jdbc),
                new th.co.glr.hr.catalog.CatalogRepository(jdbc),
                new th.co.glr.hr.pricingcosting.PricingFormulaEngine(
                    new th.co.glr.hr.pricing.PricingFormulaConfigRepository(jdbc))));
    }

    private static DataSource dataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(PostgresTestSupport.workingJdbcUrl());
            config.setUsername(PostgresTestSupport.username());
            config.setPassword(PostgresTestSupport.password());
            config.setDriverClassName("org.postgresql.Driver");
            config.setPoolName("integration-test-pool");
            // See the field Javadoc: 8 against a measured demand of 2, and a short timeout so a leaked connection surfaces
            // as a fast failure rather than a 30s stall per acquisition.
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(10_000);
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    /** Tears the pool down so the working database can be dropped out from under it. Idempotent, and safe to call
     * before the pool has ever been built. */
    private static void closePool() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    /** Financial-integrity review Finding A (commit 3): submit() now requires every pricing request item to have a
     * fully-resolved catalog snapshot (an ACTIVE price_catalog.price_list_versions row backing a
     * price_catalog.product_prices row). */
    protected long insertCatalogProduct(String factoryName, String countryCode2, String productCode,
                                        BigDecimal price, String currency, String priceUnit) {
        return insertCatalogProduct(factoryName, countryCode2, productCode, price, currency, priceUnit, "ACTIVE");
    }

    /** Same as the 6-arg overload, but lets the caller choose the price list version's status — used by the
     * catalog-gate test that a product_id pointing at a non-ACTIVE (e.g. ARCHIVED) version must still fail
     * submit()'s catalog-completeness gate, exactly as an unresolved/free-text item does. */
    protected long insertCatalogProduct(String factoryName, String countryCode2, String productCode,
                                        BigDecimal price, String currency, String priceUnit, String versionStatus) {
        // V152 (V109 engine wiring): every pricing-costing test needs a resolvable thickness_mm —
        // LandedCostCalculator#resolveThicknessMm refuses to cost an item without one (owner ruling: refuse to price
        // rather than guess).
        return insertCatalogProduct(factoryName, countryCode2, productCode, price, currency, priceUnit,
            versionStatus, new BigDecimal("10"));
    }

    /** Full control over thickness_mm — for tests exercising V109's thickness-banded freight lookup itself (band
     * edges, the "missing thickness" refusal), or a scenario that must NOT default to the safe 10mm above. */
    protected long insertCatalogProduct(String factoryName, String countryCode2, String productCode,
                                        BigDecimal price, String currency, String priceUnit, String versionStatus,
                                        BigDecimal thicknessMm) {
        Long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, :country, :currency)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            RETURNING factory_id
            """,
            new MapSqlParameterSource()
                .addValue("name", factoryName)
                .addValue("country", countryCode2)
                .addValue("currency", currency),
            Long.class);
        Long versionId = jdbc.queryForObject("""
            INSERT INTO price_catalog.price_list_versions (factory_id, label, status, effective_from)
            VALUES (:factoryId, 'Test catalog', :status, CURRENT_DATE)
            RETURNING version_id
            """,
            new MapSqlParameterSource()
                .addValue("factoryId", factoryId)
                .addValue("status", versionStatus),
            Long.class);
        Long priceId = jdbc.queryForObject("""
            INSERT INTO price_catalog.product_prices
                (factory_id, version_id, product_code, price, currency, price_unit, thickness_mm)
            VALUES (:factoryId, :versionId, :productCode, :price, :currency, :priceUnit, :thicknessMm)
            RETURNING price_id
            """,
            new MapSqlParameterSource()
                .addValue("factoryId", factoryId)
                .addValue("versionId", versionId)
                .addValue("productCode", productCode)
                .addValue("price", price)
                .addValue("currency", currency)
                .addValue("priceUnit", priceUnit)
                .addValue("thicknessMm", thicknessMm),
            Long.class);
        return priceId;
    }

    /** Task 2 (2026-07-29): th.co.glr.hr.payroll.PayrollCalculator#calculateClassified rejects any non-zero pay
     * component that has no stored withholding-tax classification (handoff section 1,
     * docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md). */
    /** V98 (2026-07-29): findCarryForwardSuggestions pre-fills a slot only where THAT employee has a carry-forward
     * flag set. Do NOT reach for this to make an assertion pass. */
    protected void seedCarryForward(long employeeId, int taxYear, PayrollComponent... components) {
        for (PayrollComponent component : components) {
            jdbc.update("""
                INSERT INTO hr.payroll_component_carry_forward
                    (employee_id, tax_year, component, carry_forward, updated_at)
                VALUES (:employeeId, :taxYear, :component, TRUE, now())
                ON CONFLICT (employee_id, tax_year, component)
                DO UPDATE SET carry_forward = TRUE
                """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                    .addValue("employeeId", employeeId)
                    .addValue("taxYear", taxYear)
                    .addValue("component", component.name()));
        }
    }

    protected void seedRegularTaxTreatment(long employeeId, int taxYear, PayrollComponent... components) {
        List<ComponentTaxTreatmentUpsertRequest> items = Arrays.stream(components)
            .map(component -> new ComponentTaxTreatmentUpsertRequest(employeeId, component, PayrollTaxTreatment.REGULAR_REPROJECT))
            .toList();
        new PayrollRepository(jdbc).upsertComponentTaxTreatment(taxYear, items, employeeId);
    }

    /** Companion to #seedRegularTaxTreatment: SSO inclusion has no application-level default at the calculator
     * layer -- an employee with no stored inclusion row is excluded from the SSO wage base entirely. */
    protected void seedSsoIncluded(long employeeId, int taxYear, PayrollComponent... components) {
        List<ComponentSsoInclusionUpsertRequest> items = Arrays.stream(components)
            .map(component -> new ComponentSsoInclusionUpsertRequest(employeeId, component, true))
            .toList();
        new PayrollRepository(jdbc).upsertComponentSsoInclusion(taxYear, items, employeeId);
    }
}
